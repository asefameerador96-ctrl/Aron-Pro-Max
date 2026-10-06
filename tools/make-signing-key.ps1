#Requires -Version 5.1
<#
.SYNOPSIS
  Creates the ONE Android signing key for the Aron apps and stores it in GitHub
  secrets. Run it ONCE on your laptop, after `gh auth login` and after the Java
  install from tools/setup-laptop.ps1.

.DESCRIPTION
  Why: every Aron app must be signed with the same key every time, because the
  phones' enrolment QR code and the Google Maps key restriction are tied to this
  key's fingerprint. A different key on each build would break both.

  What it does:
    1. creates a keystore (a file holding the key) in a protected folder on your laptop;
    2. generates strong random passwords;
    3. stores the keystore and the passwords in GitHub secrets (never in the repository);
    4. prints the key's SHA-1 and SHA-256 fingerprints (these are public, not secrets).

  KEEP THE BACKUP: the keystore file and the passwords file are written to
  %USERPROFILE%\AronSigning. Copy that folder somewhere safe (a USB stick or a
  password manager). If both the GitHub secrets and this folder are lost, every
  enrolled phone must be reset again.
#>
[CmdletBinding()]
param(
  [string]$Repo  = 'asefameerador96-ctrl/Aron-Pro-Max',
  [string]$Alias = 'aron-release',
  [string]$Dir   = (Join-Path $env:USERPROFILE 'AronSigning')
)
$ErrorActionPreference = 'Stop'

function RandomPassword([int]$len = 28) {
  $chars = (48..57) + (65..90) + (97..122)
  -join ((1..$len) | ForEach-Object { [char]($chars | Get-Random) })
}

$keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source
if (-not $keytool) {
  $jdk = Get-ChildItem 'C:\Program Files\Microsoft' -Filter 'jdk-17*' -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($jdk) { $keytool = Join-Path $jdk.FullName 'bin\keytool.exe' }
}
if (-not $keytool -or -not (Test-Path $keytool)) { throw 'keytool (Java) not found. Run tools\setup-laptop.ps1 first, then open a NEW PowerShell window.' }

$jks = Join-Path $Dir 'aron-release.jks'
if (Test-Path $jks) { throw "A keystore already exists at $jks. Not overwriting it. Move it away only if you are sure." }

New-Item -ItemType Directory -Force -Path $Dir | Out-Null
$storePass = RandomPassword
$keyPass   = $storePass   # one password for both keeps PKCS12 simple and valid

& $keytool -genkeypair -v -keystore $jks -storetype PKCS12 -alias $Alias `
  -keyalg RSA -keysize 4096 -validity 9125 `
  -storepass $storePass -keypass $keyPass `
  -dname 'CN=Aron, OU=AKTCL, O=Abul Khair Group, L=Dhaka, C=BD'
if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }

# A protected copy of the passwords, for your backup only.
@"
Aron Android signing key. KEEP SECRET. Back this folder up.
alias:    $Alias
password: $storePass
"@ | Set-Content -Path (Join-Path $Dir 'passwords.txt') -Encoding utf8

# Lock the folder to your Windows account.
icacls $Dir /inheritance:r /grant:r "$($env:USERNAME):(OI)(CI)F" | Out-Null

$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($jks))
gh secret set ANDROID_SIGNING_KEYSTORE_BASE64   --body $b64       --repo $Repo; if ($LASTEXITCODE -ne 0) { throw 'gh secret failed' }
gh secret set ANDROID_SIGNING_KEYSTORE_PASSWORD --body $storePass --repo $Repo; if ($LASTEXITCODE -ne 0) { throw 'gh secret failed' }
gh secret set ANDROID_SIGNING_KEY_ALIAS         --body $Alias     --repo $Repo; if ($LASTEXITCODE -ne 0) { throw 'gh secret failed' }
gh secret set ANDROID_SIGNING_KEY_PASSWORD      --body $keyPass   --repo $Repo; if ($LASTEXITCODE -ne 0) { throw 'gh secret failed' }

Write-Host "`nFingerprints (public; send me the SHA-1 and SHA-256 lines):" -ForegroundColor Cyan
& $keytool -list -v -keystore $jks -alias $Alias -storepass $storePass |
  Select-String -Pattern 'SHA1:|SHA256:'

Write-Host @"

Done.
  keystore + passwords backed up in: $Dir   (copy this folder somewhere safe)
  GitHub secrets set in ${Repo}: ANDROID_SIGNING_KEYSTORE_BASE64, _PASSWORD, _KEY_ALIAS, _KEY_PASSWORD
"@
