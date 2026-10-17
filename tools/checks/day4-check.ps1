#Requires -Version 5.1
<#
.SYNOPSIS
  N-050 Day 4 ten-minute check: the geofence radius changed in the portal (100 m to 50 m) is obeyed by the phone
  after its next sync.

.DESCRIPTION
  Run on the owner's laptop, from the repository folder, with the SR phone on USB (SR app logged in as sr1001). You
  need one outlet you can stand about 70 m away from (between the old 100 m and the new 50 m).

      .\tools\checks\day4-check.ps1 -Outlet MIR-D-001

  Checks (PASS or FAIL each; the exit code is the number of failures):
   1. phone listed, SR app installed
   2. web login            the supervisor (default tso1001) logs in; the password is never printed or stored
   3. radius before        the outlet's zone resolves cfg.geo.radius_m to the old value (default 100)
   4. radius changed       after you change it in the portal (Settings > Geofence > zone, 50 m, approve), the zone
                           resolves to the new value (the script polls up to 10 minutes: maker-checker)
   5. phone synced         you tap Sync on the phone; the phone stays online
   6. judged by 50 m       at about 70 m from the outlet the phone refuses the in-range sale (Force Sale shown)
   7. stamped 50 m         the visit reaches the server stamped radius_m_used = 50. Needs a visit read that shows
                           radius_m_used (docs/requests/qa-visit-radius-read.md); until then this check FAILS with
                           that reason, and the stamp is read by the laptop session from the dev database instead.
  Reading the config needs the supervisor to hold config read (admin-config); with a TSO that is refused, and checks
  3 and 4 fall back to your own answer from the portal screen.

.PARAMETER Outlet
  The outlet code you will stand next to (default MIR-D-001, the first seeded outlet of the daily route).
.PARAMETER OldRadius
  Expected radius before the change (default 100).
.PARAMETER NewRadius
  The radius you set in the portal (default 50).
#>
param(
  [string]$ApiHost = 'https://fde-aron-dev-7i7g53-gpgaa3fpgrhmdgaz.z03.azurefd.net',
  [string]$Supervisor = 'tso1001',
  [string]$Outlet = 'MIR-D-001',
  [int]$OldRadius = 100,
  [int]$NewRadius = 50
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'AronCheck.psm1') -Force
$Package = 'com.aktcl.aron.sr'
Write-Host "Aron Day 4 check  $(Get-Date -Format 'yyyy-MM-dd HH:mm')  API $ApiHost  outlet $Outlet" -ForegroundColor Cyan

# The resolved radius at the outlet's zone, or $null when the caller may not read config.
function Get-ZoneRadius([string]$Token, [long]$ZoneId) {
  try { return [int](Invoke-AronGet $ApiHost $Token "/v1/admin/config/resolve?key=cfg.geo.radius_m&node_type=zone&node_id=$ZoneId").value }
  catch { return $null }
}

$serial = Get-PhoneSerial
if ($serial) {
  $installed = Test-PackageInstalled $serial $Package
  Write-CheckResult 'SR app installed' $installed $(if ($installed) { $Package } else { "$Package is not on the phone" })
}
$token = Get-WebToken $ApiHost $Supervisor 'web login'
if (-not $token) { exit (Complete-Check) }

$zone = $null
try {
  $o = Invoke-AronGet $ApiHost $token "/v1/admin/outlets?q=$([uri]::EscapeDataString($Outlet))&limit=5"
  $zone = ($o.items | Where-Object { $_.code -eq $Outlet } | Select-Object -First 1).zone_id
} catch { }
if (-not $zone) { Write-CheckResult 'outlet found' $false "$Supervisor cannot see outlet $Outlet (wrong code, or outside the supervisor's reach)"; exit (Complete-Check) }

$before = Get-ZoneRadius $token $zone
if ($null -ne $before) { Write-CheckResult 'radius before' ($before -eq $OldRadius) "zone $zone resolves $before m (expected $OldRadius m)" }
else { Write-CheckResult 'radius before' (Read-YesNo "The portal shows $OldRadius m for the zone of $Outlet now?") "$Supervisor may not read config: owner read the portal" }

Write-Host "  Now, in the web portal: Settings > Geofence, set the zone of $Outlet to $NewRadius m and approve it (a second admin may be needed)." -ForegroundColor Cyan
[void](Read-Host '  Press Enter when the change is approved')
if ($null -ne $before) {
  $deadline = (Get-Date).AddMinutes(10)
  $now = $before
  while ((Get-Date) -lt $deadline) { $now = Get-ZoneRadius $token $zone; if ($now -eq $NewRadius) { break }; Start-Sleep -Seconds 15 }
  Write-CheckResult 'radius changed' ($now -eq $NewRadius) "zone $zone resolves $now m (expected $NewRadius m)"
} else {
  Write-CheckResult 'radius changed' (Read-YesNo "The portal shows $NewRadius m for the zone of $Outlet, approved?") 'owner read the portal'
}

if ($serial) {
  Write-Host "  On the phone (online): open the SR app and tap Sync (or pull down on Home); wait until it says synced." -ForegroundColor Cyan
  $synced = Read-YesNo 'Did the phone finish syncing?'
  $online = (Invoke-Adb @('-s', $serial, 'shell', 'settings', 'get', 'global', 'airplane_mode_on')).Out.Trim() -ne '1'
  Write-CheckResult 'phone synced' ($synced -and $online) $(if (-not $online) { 'the phone is in airplane mode' } elseif ($synced) { 'owner: synced' } else { 'owner: not synced' })
  Write-Host "  Walk to about 70 m from $Outlet (more than $NewRadius m, less than $OldRadius m) and open the outlet for a sale." -ForegroundColor Cyan
  $judged = Read-YesNo "Did the phone say you are too far away (Force Sale offered) instead of opening the sale?"
  Write-CheckResult 'judged by new radius' $judged $(if ($judged) { "owner: refused at about 70 m (judged by $NewRadius m)" } else { "owner: the sale opened, so the phone still uses the old radius" })
  Write-Host "  Use Force Sale (photo) or close the visit, so the visit record syncs." -ForegroundColor Cyan
}
Write-CheckResult 'stamped new radius' $false "no API shows a visit's radius_m_used yet (docs/requests/qa-visit-radius-read.md): the laptop session reads app.visit.radius_m_used for $Outlet today on dev"
exit (Complete-Check)
