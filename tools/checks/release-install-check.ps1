#Requires -Version 5.1
<#
.SYNOPSIS
  N-042 developer-verification check: a release-signed SR APK installs and upgrades on an ENROLLED phone without
  Google Play, and the result is logged against Google's September 2026 developer-verification rule.

.DESCRIPTION
  Run on the laptop, the enrolled phone on USB, two release-signed SR APKs from CI (an older and a newer build):

      powershell -ExecutionPolicy Bypass -File .\tools\checks\release-install-check.ps1 -OldApk .\aron-sr-0.1.700.apk -NewApk .\aron-sr-0.1.724.apk
      ... -NewApk <apk> -ViaManagedUpdate      # production path: publish the release in the portal, the DPC updates

  Checks (PASS or FAIL; the exit code is the number of failures):
   1. signed for release   both APKs verify with apksigner, by the same certificate, which is not a debug key
   2. newer version        the new APK's versionCode is higher than the old one's
   3. phone listed; Aron SR is the device owner
   4. old build installed  the phone runs the old build (installed now with adb if it runs nothing or something older)
   5. upgraded             adb install -r of the new APK succeeds (or, with -ViaManagedUpdate, the phone reaches the new
                           versionCode within -WaitMinutes after you publish it in the portal)
   6. same app, data kept  firstInstallTime unchanged (an upgrade, not a reinstall), still the device owner
   7. verification logged  the install output and the phone's build are written to -Log, with any Google
                           developer-verification refusal (INSTALL_FAILED_VERIFICATION_FAILURE, "unverified developer")
  The log line goes into docs/status/device-checks.md (D-QA-5) by the laptop session.

.PARAMETER OldApk
  The older release APK (optional with -ViaManagedUpdate when the phone already runs an older build).
.PARAMETER NewApk
  The newer release APK (its versionCode is the target).
#>
param(
  [string]$OldApk = '',
  [Parameter(Mandatory = $true)][string]$NewApk,
  [switch]$ViaManagedUpdate,
  [int]$WaitMinutes = 20,
  [string]$Log = '.\release-install-check.log'
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'AronCheck.psm1') -Force
$Package = 'com.aktcl.aron.sr'
Write-Host "Aron release install check  $(Get-Date -Format 'yyyy-MM-dd HH:mm')" -ForegroundColor Cyan

# The newest build-tools of the Android SDK (apksigner and aapt2 live there).
function Get-BuildTool([string]$Name) {
  $local = if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA 'Android\Sdk' } else { $null }
  $sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, $local) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
  if (-not $sdk) { return $null }
  $dir = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue | Sort-Object { [version]($_.Name -replace '[^0-9.]', '') } -Descending | Select-Object -First 1
  if (-not $dir) { return $null }
  return @("$($dir.FullName)\$Name.bat", "$($dir.FullName)\$Name.exe", "$($dir.FullName)\$Name") | Where-Object { Test-Path $_ } | Select-Object -First 1
}
function Invoke-Tool([string]$Tool, [string[]]$Arguments) {
  $ErrorActionPreference = 'Continue'
  return (& $Tool @Arguments 2>&1 | ForEach-Object { "$_" } | Out-String)
}
function Get-ApkVersion([string]$Aapt, [string]$Apk) {
  $m = [regex]::Match((Invoke-Tool $Aapt @('dump', 'badging', $Apk)), "versionCode='(\d+)'")
  if ($m.Success) { return [long]$m.Groups[1].Value } else { return $null }
}
function Get-ApkSigner([string]$ApkSigner, [string]$Apk) {
  $out = Invoke-Tool $ApkSigner @('verify', '--print-certs', $Apk)
  $sha = [regex]::Match($out, 'certificate SHA-256 digest:\s*([0-9a-f]{64})').Groups[1].Value
  $dn = [regex]::Match($out, 'certificate DN:\s*(.+)').Groups[1].Value.Trim()
  return @{ Ok = ($LASTEXITCODE -eq 0 -and $sha); Sha = $sha; Dn = $dn }
}
function Get-Installed([string]$Serial) {
  $d = (Invoke-Adb @('-s', $Serial, 'shell', 'dumpsys', 'package', $Package)).Out
  $vc = [regex]::Match($d, 'versionCode=(\d+)')
  return @{ Version = $(if ($vc.Success) { [long]$vc.Groups[1].Value } else { $null }); First = [regex]::Match($d, 'firstInstallTime=([^\r\n]+)').Groups[1].Value.Trim() }
}

$apksigner = Get-BuildTool 'apksigner'
$aapt = Get-BuildTool 'aapt2'
$apks = @($NewApk) + @($OldApk | Where-Object { $_ })
if (-not $apksigner -or -not $aapt) {
  Write-CheckResult 'signed for release' $false 'Android SDK build-tools (apksigner, aapt2) not found: install them with the SDK Manager'
} else {
  $signers = @($apks | ForEach-Object { if (Test-Path $_) { Get-ApkSigner $apksigner $_ } else { @{ Ok = $false; Sha = ''; Dn = "no file $_" } } })
  $okAll = @($signers | Where-Object { -not $_.Ok }).Count -eq 0
  $same = @($signers.Sha | Select-Object -Unique).Count -eq 1
  $debug = @($signers | Where-Object { $_.Dn -match 'CN=Android Debug' }).Count -gt 0
  Write-CheckResult 'signed for release' ($okAll -and $same -and -not $debug) $(if (-not $okAll) { "apksigner refused: $($signers.Dn -join '; ')" } elseif (-not $same) { 'the APKs are signed by different certificates' } elseif ($debug) { 'signed with the Android debug key' } else { "one release certificate $($signers[0].Sha.Substring(0, 8))…$($signers[0].Sha.Substring(56))" })
}
$newVersion = if ($aapt -and (Test-Path $NewApk)) { Get-ApkVersion $aapt $NewApk } else { $null }
$oldVersion = if ($aapt -and $OldApk -and (Test-Path $OldApk)) { Get-ApkVersion $aapt $OldApk } else { $null }
if ($OldApk) { Write-CheckResult 'newer version' ($null -ne $newVersion -and $null -ne $oldVersion -and $newVersion -gt $oldVersion) "old $oldVersion, new $newVersion" }

$serial = Get-PhoneSerial
$installOut = ''
if ($serial) {
  $owners = (Invoke-Adb @('-s', $serial, 'shell', 'dpm', 'list-owners')).Out
  Write-CheckResult 'device owner' ($owners -match [regex]::Escape($Package) -and $owners -match 'DeviceOwner') ($owners -replace '\s+', ' ')
  $start = Get-Installed $serial
  if ($OldApk -and ($null -eq $start.Version -or $start.Version -lt $oldVersion)) {
    $r = Invoke-Adb @('-s', $serial, 'install', '-r', $OldApk); $installOut += "old: $($r.Out)`n"
    $start = Get-Installed $serial
  }
  $baseOk = $null -ne $start.Version -and ($null -eq $newVersion -or $start.Version -lt $newVersion)
  Write-CheckResult 'old build installed' $baseOk "versionCode $($start.Version), first installed $($start.First)"
  if ($baseOk) {
    if ($ViaManagedUpdate) {
      Write-Host "  Publish the new release (versionCode $newVersion) in the portal (Devices > Releases); the phone updates itself." -ForegroundColor Cyan
      $deadline = (Get-Date).AddMinutes($WaitMinutes)
      $now = $start
      while ((Get-Date) -lt $deadline) { Start-Sleep -Seconds 20; $now = Get-Installed $serial; if ($now.Version -ge $newVersion) { break } }
      $installOut += "managed update: versionCode $($start.Version) -> $($now.Version)`n"
    } else {
      $r = Invoke-Adb @('-s', $serial, 'install', '-r', $NewApk); $installOut += "new: $($r.Out)`n"
      $now = Get-Installed $serial
    }
    Write-CheckResult 'upgraded' ($now.Version -eq $newVersion) "versionCode $($start.Version) -> $($now.Version) (target $newVersion)"
    $stillOwner = (Invoke-Adb @('-s', $serial, 'shell', 'dpm', 'list-owners')).Out -match [regex]::Escape($Package)
    Write-CheckResult 'same app, data kept' ($now.First -eq $start.First -and $stillOwner) "firstInstallTime $($now.First) (was $($start.First)), device owner kept: $stillOwner"
  }
  $build = "{0} {1}, Android {2} (API {3}), patch {4}" -f (Invoke-Adb @('-s', $serial, 'shell', 'getprop', 'ro.product.manufacturer')).Out, (Invoke-Adb @('-s', $serial, 'shell', 'getprop', 'ro.product.model')).Out, (Invoke-Adb @('-s', $serial, 'shell', 'getprop', 'ro.build.version.release')).Out, (Invoke-Adb @('-s', $serial, 'shell', 'getprop', 'ro.build.version.sdk')).Out, (Invoke-Adb @('-s', $serial, 'shell', 'getprop', 'ro.build.version.security_patch')).Out
  $refusal = $installOut -match 'VERIFICATION|unverified developer|developer verification'
  $line = "{0} | {1} | {2} | {3} | {4}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm'), $build, $(if ($ViaManagedUpdate) { 'managed update' } else { 'adb install' }), $(if ($refusal) { 'REFUSED by developer verification' } else { 'no developer-verification refusal' }), ($installOut -replace '\s+', ' ').Trim()
  Add-Content -Path $Log -Value $line
  Write-CheckResult 'verification logged' (-not $refusal) "$line  (written to $Log)"
}
exit (Complete-Check)
