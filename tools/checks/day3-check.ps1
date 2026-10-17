#Requires -Version 5.1
<#
.SYNOPSIS
  N-043 Day 3 ten-minute check on an ENROLLED phone (Aron SR as device owner): spoofing apps are refused, check-in
  suspends the chosen apps, check-out releases them.

.DESCRIPTION
  Run on the owner's laptop, from the repository folder, the enrolled SR phone on USB:

      .\tools\checks\day3-check.ps1                                   # checks only
      .\tools\checks\day3-check.ps1 -SpoofApks C:\apks\fakegps1.apk,C:\apks\clone.apk   # also try to install them

  Checks (PASS or FAIL; the exit code is the number of failures):
   1. phone listed; Aron SR is the device owner (dpm list-owners)
   2. sideloading blocked     the device-owner policy holds no_install_unknown_sources (and, in production lockdown,
                              no_debugging_features; dev lockdown keeps adb on and says so)
   3. spoofing apps refused   for each -SpoofApks file: adb install fails, or the app lands suspended; for each
                              well-known spoofing package already on the phone: it is suspended or its mock-location
                              permission (app-op) is not allowed, and you confirm the visit is refused and flagged
   4. not suspended before    the chosen apps (-BlockedApps) are not suspended while checked out
   5. suspended at check-in   after you check in (hold to confirm), every chosen app is suspended
   6. released at check-out   after you check out (from 17:00, or as the config allows), none is suspended
  Nothing is uninstalled; a spoofing app installed by this script stays for N-039's report (remove it afterwards).

.PARAMETER BlockedApps
  Packages the policy suspends during the working day (default: Facebook, TikTok Lite, YouTube, as in D-04).
.PARAMETER SpoofApks
  APK files of mock-location apps and an app-cloning tool to try to install (optional).
.PARAMETER SkipCheckout
  Stop after the check-in part (run again after 17:00 for the check-out part: it then starts from check 6).
#>
param(
  [string[]]$BlockedApps = @('com.facebook.katana', 'com.ss.android.ugc.trill', 'com.google.android.youtube'),
  [string[]]$SpoofApks = @(),
  [switch]$SkipCheckout,
  [switch]$CheckoutOnly
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'AronCheck.psm1') -Force
$Package = 'com.aktcl.aron.sr'
# Well-known mock-location apps and app-cloning tools (N-039's list).
$SpoofPackages = @(
  'com.lexa.fakegps', 'com.incorporateapps.fakegps.fre', 'com.blogspot.newapphorizons.fakegps', 'com.theappninjas.fakegpsjoystick',
  'com.rosteam.gpsemulator', 'com.lbe.parallel.intl', 'com.excelliance.dualaid'
)
Write-Host "Aron Day 3 check  $(Get-Date -Format 'yyyy-MM-dd HH:mm')" -ForegroundColor Cyan

function Get-Suspended([string]$Serial, [string]$Pkg) {
  $d = (Invoke-Adb @('-s', $Serial, 'shell', 'dumpsys', 'package', $Pkg)).Out
  return $d -match 'suspended=true'
}

$serial = Get-PhoneSerial
if (-not $serial) { exit (Complete-Check) }
$owners = (Invoke-Adb @('-s', $serial, 'shell', 'dpm', 'list-owners')).Out
$isOwner = $owners -match [regex]::Escape($Package) -and $owners -match 'DeviceOwner'
Write-CheckResult 'device owner' $isOwner $(if ($isOwner) { "$Package is the device owner" } else { "not enrolled as device owner: $($owners -replace '\s+', ' ')" })

if (-not $CheckoutOnly) {
  $policy = (Invoke-Adb @('-s', $serial, 'shell', 'dumpsys', 'device_policy')).Out
  $noSideload = $policy -match 'no_install_unknown_sources'
  $noDebug = $policy -match 'no_debugging_features'
  Write-CheckResult 'sideloading blocked' $noSideload $(if ($noSideload) { 'no_install_unknown_sources set' + $(if ($noDebug) { '; no_debugging_features set (production lockdown)' } else { '; adb stays on (dev lockdown)' }) } else { 'no_install_unknown_sources is not set' })

  foreach ($apk in $SpoofApks) {
    if (-not (Test-Path $apk)) { Write-CheckResult 'spoof install refused' $false "no file $apk"; continue }
    $r = Invoke-Adb @('-s', $serial, 'install', '-r', $apk)
    $refused = $r.Out -notmatch 'Success'
    Write-CheckResult 'spoof install refused' $refused $(if ($refused) { "$(Split-Path $apk -Leaf): $($r.Out -replace '\s+', ' ')" } else { "$(Split-Path $apk -Leaf) installed (adb is a debugging channel in dev lockdown; judged below)" })
  }
  $installed = (Invoke-Adb @('-s', $serial, 'shell', 'pm', 'list', 'packages')).Out -split "`r?`n" | ForEach-Object { $_ -replace '^package:', '' }
  $present = @($SpoofPackages | Where-Object { $installed -contains $_ })
  if ($present.Count -eq 0) { Write-Host '  no well-known spoofing app is on the phone' }
  foreach ($p in $present) {
    $op = (Invoke-Adb @('-s', $serial, 'shell', 'appops', 'get', $p, 'android:mock_location')).Out
    $suspended = Get-Suspended $serial $p
    $mockAllowed = $op -match 'allow'
    Write-Host "  $p is installed: suspended=$suspended, mock_location app-op: $($op -replace '\s+', ' ')" -ForegroundColor Cyan
    $refusedVisit = Read-YesNo "Switch $p on (fake a location next to an outlet) and open that outlet in the SR app: is the visit refused with the fake-location message?"
    Write-CheckResult 'spoofing app refused' ($suspended -or -not $mockAllowed -or $refusedVisit) ("{0}: suspended={1}, mock op allowed={2}, owner saw the visit refused={3}" -f $p, $suspended, $mockAllowed, $refusedVisit)
  }

  $wrongly = @($BlockedApps | Where-Object { Get-Suspended $serial $_ })
  Write-CheckResult 'not suspended before' ($wrongly.Count -eq 0) $(if ($wrongly.Count -eq 0) { 'checked out: no chosen app suspended' } else { "already suspended (check out first?): $($wrongly -join ', ')" })
  Write-Host '  Now check in on the phone (Attendance, press and hold).' -ForegroundColor Cyan
  [void](Read-Host '  Press Enter when the check-in is confirmed')
  Start-Sleep -Seconds 5
  $notYet = @($BlockedApps | Where-Object { -not (Get-Suspended $serial $_) })
  Write-CheckResult 'suspended at check-in' ($notYet.Count -eq 0) $(if ($notYet.Count -eq 0) { "suspended: $($BlockedApps -join ', ')" } else { "not suspended: $($notYet -join ', ')" })
  if ($SkipCheckout) { exit (Complete-Check) }
}
Write-Host '  Now check out on the phone (Attendance, Check out, press and hold; from 17:00 unless the config allows earlier).' -ForegroundColor Cyan
[void](Read-Host '  Press Enter when the check-out is confirmed')
Start-Sleep -Seconds 6
$still = @($BlockedApps | Where-Object { Get-Suspended $serial $_ })
Write-CheckResult 'released at check-out' ($still.Count -eq 0) $(if ($still.Count -eq 0) { 'no chosen app is suspended' } else { "still suspended: $($still -join ', ')" })
exit (Complete-Check)
