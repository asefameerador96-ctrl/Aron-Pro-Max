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
   3. radius before        cfg.geo.radius_m resolved AT THE OUTLET (outlet overrides, then route, zone, ...: what
                           the phone gets in its bundle) is the old value (default 100)
   4. radius changed       after you change it in the portal and it is approved, the outlet resolves to the new value
                           (polled up to 10 minutes: config changes are maker-checker)
   5. phone synced         you tap Sync on the phone; the phone has a network
   6. judged by new radius at about 70 m from the outlet the visit screen reads "limit 50 m" (সীমা ৫০ মি) with a distance
                           above 50 m, and the sale does not open (Refresh, then Force Sale). An accuracy, mock or
                           no-fix refusal does not count: the screen must show the limit and the distance.
   7. stamped new radius   the visit reaches the server stamped radius_m_used = 50. Needs a visit read that shows
                           radius_m_used (docs/requests/qa-visit-radius-read.md); until then this check FAILS with
                           that reason, and the laptop session reads the stamp from the dev database instead.
   8. radius restored      after you set it back in the portal, the outlet resolves to the old value again
  The supervisor reads config through GET /v1/admin/config/resolve (TSO is a config reader). If that is refused
  (403), checks 3, 4 and 8 fall back to your answer from the portal screen; any other failure is a FAIL.

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

# The radius resolved at the outlet (what its bundle row carries); $null when config reads are refused (403), and
# 'error' when the server could not be read at all.
function Get-OutletRadius([long]$OutletId) {
  $r = Get-Aron "/v1/admin/config/resolve?key=cfg.geo.radius_m&node_type=outlet&node_id=$OutletId"
  if ($null -ne $r) { return [int]$r.value }
  if ((Get-AronStatus) -eq 403) { return $null }
  return 'error'
}

function Report-Radius([string]$Name, $Value, [int]$Want, [string]$Ask) {
  if ($Value -eq 'error') { Write-CheckResult $Name $false "config could not be read: $(Get-AronError)" }
  elseif ($null -ne $Value) { Write-CheckResult $Name ($Value -eq $Want) "outlet $Outlet resolves $Value m (expected $Want m)" }
  else { Write-CheckResult $Name (Read-YesNo $Ask) "$Supervisor may not read config (403): owner read the portal" }
}

$serial = Get-PhoneSerial
if ($serial) {
  $installed = Test-PackageInstalled $serial $Package
  Write-CheckResult 'SR app installed' $installed $(if ($installed) { $Package } else { "$Package is not on the phone" })
}
$token = Get-WebToken $ApiHost $Supervisor 'web login'
if (-not $token) { exit (Complete-Check) }

$outletId = $null
$cursor = $null
for ($n = 0; $n -lt 20 -and -not $outletId; $n++) {
  $o = Get-Aron ("/v1/admin/outlets?q=$([uri]::EscapeDataString($Outlet))&limit=200" + $(if ($cursor) { "&cursor=$([uri]::EscapeDataString($cursor))" } else { '' }))
  if (-not $o) { break }
  $outletId = (@($o.items) | Where-Object { $_.code -eq $Outlet } | Select-Object -First 1).id
  $cursor = $o.next_cursor
  if (-not $cursor) { break }
}
if (-not $outletId) { Write-CheckResult 'outlet found' $false "$Supervisor cannot see outlet $Outlet (wrong code, outside the supervisor's reach, or $(Get-AronError))"; exit (Complete-Check) }

Report-Radius 'radius before' (Get-OutletRadius $outletId) $OldRadius "The portal shows $OldRadius m for $Outlet now?"

Write-Host "  Now, in the web portal: Settings > Geofence, set $NewRadius m for $Outlet's zone (or the outlet) and approve it (a second admin may be needed)." -ForegroundColor Cyan
[void](Read-Host '  Press Enter when the change is approved')
$now = Get-OutletRadius $outletId
if ($null -ne $now -and $now -ne 'error') {
  $deadline = (Get-Date).AddMinutes(10)
  while ($now -ne $NewRadius -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 15; $now = Get-OutletRadius $outletId; if ($now -eq 'error') { break } }
}
Report-Radius 'radius changed' $now $NewRadius "The portal shows $NewRadius m for $Outlet, approved?"

if ($serial) {
  Write-Host "  On the phone (online): open the SR app and tap Sync (or pull down on Home); wait until it says synced." -ForegroundColor Cyan
  $synced = Read-YesNo 'Did the phone finish syncing?'
  $online = Test-PhoneOnline $serial
  Write-CheckResult 'phone synced' ($synced -and $online) $(if (-not $online) { 'the phone is in airplane mode' } elseif ($synced) { 'owner: synced' } else { 'owner: not synced' })
  Write-Host "  Walk to about 70 m from $Outlet (more than $NewRadius m, less than $OldRadius m) and open the outlet for a sale." -ForegroundColor Cyan
  $limit = Read-YesNo "Does the visit screen read 'limit $NewRadius m' (সীমা $NewRadius মি) next to the distance?"
  $distance = Read-Host '  The distance it shows, in metres (number only)'
  $refused = Read-YesNo 'Did the sale stay closed (Refresh, then Force Sale offered), not open?'
  $metres = [regex]::Match("$distance", '\d+').Value -as [int]  # "70", "70 m" and "70.4" all read 70
  $far = $null -ne $metres -and $metres -gt $NewRadius -and $metres -lt $OldRadius
  Write-CheckResult 'judged by new radius' ($limit -and $refused -and $far) ("screen limit {0} m: {1}; distance {2} m (between {0} and {3}: {4}); sale refused: {5}" -f $NewRadius, $limit, $distance, $OldRadius, $far, $refused)
  Write-Host "  Use Force Sale (photo) or close the visit, so the visit record syncs." -ForegroundColor Cyan
}
Write-CheckResult 'stamped new radius' $false "no API shows a visit's radius_m_used yet (docs/requests/qa-visit-radius-read.md): the laptop session reads app.visit.radius_m_used for $Outlet today on dev"

Write-Host "  Now set the radius back to $OldRadius m in the portal (the same place) and approve it: the zone's other SRs use it too." -ForegroundColor Cyan
[void](Read-Host '  Press Enter when the change is approved')
$back = Get-OutletRadius $outletId
if ($null -ne $back -and $back -ne 'error') {
  $deadline = (Get-Date).AddMinutes(10)
  while ($back -ne $OldRadius -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 15; $back = Get-OutletRadius $outletId; if ($back -eq 'error') { break } }
}
Report-Radius 'radius restored' $back $OldRadius "The portal shows $OldRadius m for $Outlet again?"
exit (Complete-Check)
