#Requires -Version 5.1
<#
.SYNOPSIS
  N-042 developer-verification check: a release-signed SR APK installs and upgrades on an ENROLLED phone without
  Google Play, and the result is logged against Google's September 2026 developer-verification rule.

.DESCRIPTION
  Run on the laptop in a PowerShell window, the enrolled phone on USB, two release-signed SR APKs from CI (an older and
  a newer build):

      powershell -ExecutionPolicy Bypass -File .\tools\checks\release-install-check.ps1 -OldApk .\aron-sr-0.1.700.apk -NewApk .\aron-sr-0.1.724.apk
      ... -NewApk <apk> -ViaInAppUpdate     # the field path: the release is published, the rep taps Update in the app

  Checks (PASS or FAIL; the exit code is the number of failures):
   1. signed for release   each APK verifies with apksigner, with exactly one signer, the same certificate in both,
                           not a debug key, and (with -ExpectedCertSha256) the expected release certificate
   2. newer version        the new APK's versionCode is higher than the old one's
   3. phone listed; Aron SR is the device owner
   4. old build installed  the phone runs the old build (installed now with adb if it runs nothing or something older)
   5. upgraded             adb install -r of the new APK succeeds; with -ViaInAppUpdate the phone reaches the new
                           versionCode within -WaitMinutes while you open the SR app and tap the update (the app checks
                           at login and on return to the foreground). The in-app path also needs the release to be
                           published on the server: there is no publish route on the backend at this commit
                           (web/src/app/admin/releases calls /v1/admin/releases, which the API does not serve yet).
   6. same app, data kept  only after an upgrade: firstInstallTime unchanged (an upgrade, not a reinstall), still the
                           device owner
   7. install logged       one line in -Log: phone, Android version and patch, path, the install output. Google's
                           developer-verification rule (from September 2026, first on certified devices in Brazil,
                           Indonesia, Singapore and Thailand; adb installs are meant to stay possible for developers) is
                           NOT exercised by an adb install on a Bangladesh phone; the line says so instead of claiming a
                           result. A failure code is logged as it came (Play Protect or the package verifier also give
                           INSTALL_FAILED_VERIFICATION_FAILURE).

.PARAMETER OldApk
  The older release APK (optional with -ViaInAppUpdate when the phone already runs an older build).
.PARAMETER NewApk
  The newer release APK (its versionCode is the target).
.PARAMETER ExpectedCertSha256
  The release certificate's SHA-256 (64 hex digits) both APKs must carry (optional).
#>
param(
  [string]$OldApk = '',
  [Parameter(Mandatory = $true)][string]$NewApk,
  [switch]$ViaInAppUpdate,
  [string]$ExpectedCertSha256 = '',
  [int]$WaitMinutes = 20,
  [string]$Log = '.\release-install-check.log'
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'AronCheck.psm1') -Force
$Package = 'com.aktcl.aron.sr'
Write-Host "Aron release install check  $(Get-Date -Format 'yyyy-MM-dd HH:mm')" -ForegroundColor Cyan

function Invoke-Tool([string]$Tool, [string[]]$Arguments) {
  $ErrorActionPreference = 'Continue'
  return (& $Tool @Arguments 2>&1 | ForEach-Object { "$_" } | Out-String)
}
function Get-ApkVersion([string]$Aapt, [string]$Apk) {
  $m = [regex]::Match((Invoke-Tool $Aapt @('dump', 'badging', $Apk)), "versionCode='(\d+)'")
  if ($m.Success) { return [long]$m.Groups[1].Value } else { return $null }
}
# Every signer of an APK: @{ Ok; Shas; Dns; Out } (Ok when apksigner verifies it).
function Get-ApkSigner([string]$ApkSigner, [string]$Apk) {
  $out = Invoke-Tool $ApkSigner @('verify', '--print-certs', $Apk)
  $shas = @([regex]::Matches($out, 'certificate SHA-256 digest:\s*([0-9a-f]{64})') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique)
  $dns = @([regex]::Matches($out, 'certificate DN:\s*([^\r\n]+)') | ForEach-Object { $_.Groups[1].Value.Trim() })
  return @{ Ok = ($shas.Count -gt 0 -and $out -notmatch '(?m)^ERROR'); Shas = $shas; Dns = $dns; Out = ($out -replace '\s+', ' ').Trim() }
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
  $signers = @($apks | ForEach-Object { if (Test-Path $_) { Get-ApkSigner $apksigner $_ } else { @{ Ok = $false; Shas = @(); Dns = @(); Out = "no file $_" } } })
  $failed = @($signers | Where-Object { -not $_.Ok })
  $multi = @($signers | Where-Object { $_.Shas.Count -ne 1 })
  $allShas = @($signers | ForEach-Object { $_.Shas } | Select-Object -Unique)
  $debug = @($signers | Where-Object { ($_.Dns -join ' ') -match 'CN=Android Debug' }).Count -gt 0
  $expectedOk = -not $ExpectedCertSha256 -or ($allShas.Count -eq 1 -and $allShas[0] -eq $ExpectedCertSha256.ToLower())
  $detail = if ($failed.Count) { "apksigner refused: $($failed[0].Out)" } elseif ($multi.Count) { "an APK has $($multi[0].Shas.Count) signers (one release signer expected)" } elseif ($allShas.Count -ne 1) { 'the APKs are signed by different certificates' } elseif ($debug) { 'signed with the Android debug key' } elseif (-not $expectedOk) { "certificate $($allShas[0]) is not the expected $ExpectedCertSha256" } else { "one release certificate $($allShas[0].Substring(0, 8))...$($allShas[0].Substring(56))" }
  Write-CheckResult 'signed for release' ($failed.Count -eq 0 -and $multi.Count -eq 0 -and $allShas.Count -eq 1 -and -not $debug -and $expectedOk) $detail
}
$newVersion = if ($aapt -and (Test-Path $NewApk)) { Get-ApkVersion $aapt $NewApk } else { $null }
$oldVersion = if ($aapt -and $OldApk -and (Test-Path $OldApk)) { Get-ApkVersion $aapt $OldApk } else { $null }
if ($OldApk) { Write-CheckResult 'newer version' ($null -ne $newVersion -and $null -ne $oldVersion -and $newVersion -gt $oldVersion) "old $oldVersion, new $newVersion" }

$serial = Get-PhoneSerial
$installOut = ''
$upgraded = $false
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
    if ($ViaInAppUpdate) {
      Write-Host "  With release $newVersion published: open the SR app on the phone (or log out and in), accept the update and tap Install." -ForegroundColor Cyan
      $deadline = (Get-Date).AddMinutes($WaitMinutes)
      $now = $start
      while ((Get-Date) -lt $deadline) { Start-Sleep -Seconds 20; $now = Get-Installed $serial; if ($now.Version -ge $newVersion) { break } }
      $installOut += "in-app update: versionCode $($start.Version) -> $($now.Version)`n"
    } else {
      $r = Invoke-Adb @('-s', $serial, 'install', '-r', $NewApk); $installOut += "new: $($r.Out)`n"
      $now = Get-Installed $serial
    }
    $upgraded = $now.Version -eq $newVersion
    Write-CheckResult 'upgraded' $upgraded "versionCode $($start.Version) -> $($now.Version) (target $newVersion)"
    if ($upgraded) {
      $stillOwner = (Invoke-Adb @('-s', $serial, 'shell', 'dpm', 'list-owners')).Out -match [regex]::Escape($Package)
      Write-CheckResult 'same app, data kept' ($now.First -eq $start.First -and $stillOwner) "firstInstallTime $($now.First) (was $($start.First)), device owner kept: $stillOwner"
    } else {
      Write-CheckResult 'same app, data kept' $false 'not reached: the upgrade did not happen'
    }
  } else { $upgraded = $false }
  $build = "{0} {1}, Android {2} (API {3}), patch {4}" -f (Invoke-Adb @('-s', $serial, 'shell', 'getprop', 'ro.product.manufacturer')).Out, (Invoke-Adb @('-s', $serial, 'shell', 'getprop', 'ro.product.model')).Out, (Invoke-Adb @('-s', $serial, 'shell', 'getprop', 'ro.build.version.release')).Out, (Invoke-Adb @('-s', $serial, 'shell', 'getprop', 'ro.build.version.sdk')).Out, (Invoke-Adb @('-s', $serial, 'shell', 'getprop', 'ro.build.version.security_patch')).Out
  $code = [regex]::Match($installOut, 'INSTALL_FAILED_[A-Z_]+').Value
  $rule = if ($installOut -match '(?i)developer verification|unverified developer') { 'install refused citing developer verification' } else { 'developer-verification rule not exercised (adb or in-app install on a phone outside the first rollout countries)' }
  $line = "{0} | {1} | {2} | {3} | {4} | {5}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm'), $build, $(if ($ViaInAppUpdate) { 'in-app update' } else { 'adb install' }), $(if ($upgraded) { 'upgraded' } else { "not upgraded $code" }), $rule, ($installOut -replace '\s+', ' ').Trim()
  Add-Content -Path $Log -Value $line
  Write-CheckResult 'install logged' $upgraded "$line  (written to $Log)"
}
exit (Complete-Check)
