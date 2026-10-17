#Requires -Version 5.1
<#
.SYNOPSIS
  N-043 Day 3 ten-minute check on an ENROLLED phone (Aron SR as device owner): spoofing apps are refused, check-in
  suspends the chosen apps, check-out releases them.

.DESCRIPTION
  Run on the owner's laptop in a PowerShell window, from the repository folder, the enrolled SR phone on USB:

      powershell -ExecutionPolicy Bypass -File .\tools\checks\day3-check.ps1 -SkipCheckout        # morning
      powershell -ExecutionPolicy Bypass -File .\tools\checks\day3-check.ps1 -CheckoutOnly        # after 17:00
      ... -SpoofApks C:\apks\fakegps.apk,C:\apks\parallel.apk    # also install and judge these spoofing apps

  Checks (PASS or FAIL; the exit code is the number of failures):
   1. phone listed; Aron SR is the device owner
   2. sideloading blocked     user 0's EFFECTIVE restrictions hold no_install_unknown_sources (and, in production
                              lockdown, no_debugging_features; dev lockdown keeps adb on and says so)
   3. spoofing apps refused   every spoofing app on the phone (the well-known list plus each -SpoofApks package, read
                              with aapt2) is judged: PASS when it is suspended for user 0, or when YOU switched it on
                              and the SR app refused the visit with the fake-location message. With no spoofing app
                              on the phone this check FAILS ("not tested"): N-043 needs at least one.
                              In production lockdown an -SpoofApks install must be refused; in dev lockdown adb may
                              install it (adb is a debugging channel there) and the app is judged instead.
   4. not suspended before    the chosen apps (-BlockedApps) are installed and not suspended while checked out
   5. suspended at check-in   after you check in (hold to confirm), every chosen app is suspended (polled up to 30 s)
   6. released at check-out   (-CheckoutOnly, or a full run) the chosen apps are suspended before, and none is
                              suspended within 30 s after you check out
  Nothing is uninstalled. Spoofing apps installed by this script stay on the phone for N-039's report; remove them
  with adb uninstall afterwards.

.PARAMETER BlockedApps
  Packages the policy suspends during the working day (default: Facebook, TikTok Lite, YouTube, as in D-04).
.PARAMETER SpoofApks
  APK files of mock-location apps and app-cloning tools to install and judge (optional).
.PARAMETER SkipCheckout
  Stop after the check-in part.
.PARAMETER CheckoutOnly
  Only the check-out part (run after 17:00, while checked in).
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

# Polls user 0's suspension of every chosen app until it equals $Want (or 30 s pass); returns the apps that differ.
function Wait-Suspension([string]$Serial, [bool]$Want) {
  $deadline = (Get-Date).AddSeconds(30)
  do {
    $wrong = @($BlockedApps | Where-Object { $s = Get-PackageState $Serial $_; -not $s.Installed -or $s.Suspended -ne $Want })
    if ($wrong.Count -eq 0) { return $wrong }
    Start-Sleep -Seconds 3
  } while ((Get-Date) -lt $deadline)
  return $wrong
}
function Describe([string]$Serial, [string[]]$Apps) {
  return ($Apps | ForEach-Object { $s = Get-PackageState $Serial $_; if (-not $s.Installed) { "$_ (not installed)" } else { "$_ (suspended=$($s.Suspended))" } }) -join ', '
}

$serial = Get-PhoneSerial
if (-not $serial) { exit (Complete-Check) }
$owners = (Invoke-Adb @('-s', $serial, 'shell', 'dpm', 'list-owners')).Out
$isOwner = $owners -match [regex]::Escape($Package) -and $owners -match 'DeviceOwner'
Write-CheckResult 'device owner' $isOwner $(if ($isOwner) { "$Package is the device owner" } else { "not enrolled as device owner: $($owners -replace '\s+', ' ')" })

if (-not $CheckoutOnly) {
  $production = $false
  $restrictions = Get-EffectiveRestrictions $serial
  if ($null -eq $restrictions) {
    Write-CheckResult 'sideloading blocked' $false 'could not read user 0''s effective restrictions (dumpsys user)'
  } else {
    $noSideload = $restrictions -contains 'no_install_unknown_sources' -or $restrictions -contains 'no_install_unknown_sources_globally'
    $production = $restrictions -contains 'no_debugging_features'
    Write-CheckResult 'sideloading blocked' $noSideload ("user 0: " + $(if ($noSideload) { ($restrictions | Where-Object { $_ -like 'no_install_unknown*' }) -join ', ' } else { 'no_install_unknown_sources NOT in effect' }) + $(if ($production) { '; no_debugging_features (production lockdown)' } else { '; adb stays on (dev lockdown)' }))
  }

  # Spoofing apps: install the given APKs (their package names read with aapt2), then judge every one on the phone.
  $judge = [System.Collections.Generic.List[string]]::new()
  $aapt = Get-BuildTool 'aapt2'
  foreach ($apk in $SpoofApks) {
    if (-not (Test-Path $apk)) { Write-CheckResult 'spoofing app refused' $false "no file $apk"; continue }
    $pkg = $null
    if ($aapt) {
      $ErrorActionPreference = 'Continue'
      $badging = (& $aapt dump badging $apk 2>&1 | ForEach-Object { "$_" } | Out-String)
      $ErrorActionPreference = 'Stop'
      $pkg = [regex]::Match($badging, "package: name='([^']+)'").Groups[1].Value
    }
    $r = Invoke-Adb @('-s', $serial, 'install', '-r', $apk)
    $installed = $r.Out -match 'Success'
    if ($installed -and $production) { Write-CheckResult 'spoofing app refused' $false "$(Split-Path $apk -Leaf) installed although production lockdown forbids debugging installs" }
    elseif (-not $installed) { Write-CheckResult 'spoofing app refused' $true "$(Split-Path $apk -Leaf): install refused ($($r.Out -replace '\s+', ' '))" }
    else { Write-Host "  $(Split-Path $apk -Leaf) installed over adb (dev lockdown); judged below" }
    if ($installed -and $pkg) { $judge.Add($pkg) } elseif ($installed) { Write-CheckResult 'spoofing app refused' $false "$(Split-Path $apk -Leaf): installed, but its package name is unknown (build-tools aapt2 missing)" }
  }
  $onPhone = (Invoke-Adb @('-s', $serial, 'shell', 'pm', 'list', 'packages')).Out -split "`r?`n" | ForEach-Object { ($_ -replace '^package:', '').Trim() }
  foreach ($p in $SpoofPackages) { if ($onPhone -contains $p -and -not $judge.Contains($p)) { $judge.Add($p) } }
  if ($judge.Count -eq 0 -and $SpoofApks.Count -eq 0) {
    Write-CheckResult 'spoofing app refused' $false 'not tested: no spoofing app on the phone (pass -SpoofApks, or install one of the N-039 list)'
  }
  foreach ($p in $judge) {
    $state = Get-PackageState $serial $p
    if ($state.Suspended) { Write-CheckResult 'spoofing app refused' $true "${p}: suspended for user 0 (cannot be opened)"; continue }
    Write-Host "  $p can be opened. Open it, set a fake location next to an outlet (or clone the SR app in it), then open that outlet in the SR app." -ForegroundColor Cyan
    $refused = Read-YesNo "Did the SR app refuse the visit with the fake-location message (নকল লোকেশন ধরা পড়েছে)?"
    $op = ((Invoke-Adb @('-s', $serial, 'shell', 'appops', 'get', $p, 'android:mock_location')).Out -replace '\s+', ' ').Trim()
    Write-CheckResult 'spoofing app refused' $refused ("${p}: owner saw the visit {0}; mock_location op now: {1}" -f $(if ($refused) { 'refused' } else { 'ACCEPTED' }), $(if ($op) { $op } else { 'none' }))
  }

  $wrongly = Wait-Suspension $serial $false
  Write-CheckResult 'not suspended before' ($wrongly.Count -eq 0) $(if ($wrongly.Count -eq 0) { 'checked out: chosen apps installed, none suspended' } else { "not ready (check out first, install the apps): $(Describe $serial $wrongly)" })
  Write-Host '  Now check in on the phone (Attendance, press and hold).' -ForegroundColor Cyan
  [void](Read-Host '  Press Enter when the check-in is confirmed')
  $notYet = Wait-Suspension $serial $true
  Write-CheckResult 'suspended at check-in' ($notYet.Count -eq 0) $(if ($notYet.Count -eq 0) { "suspended for user 0: $($BlockedApps -join ', ')" } else { "after 30 s: $(Describe $serial $notYet)" })
  if ($SkipCheckout) { exit (Complete-Check) }
} else {
  $notSuspended = @($BlockedApps | Where-Object { $s = Get-PackageState $serial $_; -not ($s.Installed -and $s.Suspended) })
  Write-CheckResult 'suspended before check-out' ($notSuspended.Count -eq 0) $(if ($notSuspended.Count -eq 0) { 'checked in: every chosen app is suspended' } else { "not suspended (not checked in, past 20:00, or rebooted?): $(Describe $serial $notSuspended)" })
  if ($notSuspended.Count -gt 0) { exit (Complete-Check) }
}
Write-Host '  Now check out on the phone (Attendance, Check out, press and hold; from 17:00 unless the config allows earlier).' -ForegroundColor Cyan
[void](Read-Host '  Press Enter when the check-out is confirmed')
$still = Wait-Suspension $serial $false
Write-CheckResult 'released at check-out' ($still.Count -eq 0) $(if ($still.Count -eq 0) { 'no chosen app is suspended' } else { "after 30 s: $(Describe $serial $still)" })
exit (Complete-Check)
