#Requires -Version 5.1
<#
.SYNOPSIS
  N-055 Day 5 ten-minute check: an SR raises a new-outlet request, an AMO verifies it, the web approves it, and the
  TSO closes the zone-day with Final Submit.

.DESCRIPTION
  Run on the owner's laptop in a PowerShell window, from the repository folder, at the END of a test day (Final
  Submit closes the zone-day: later sales of that day are accepted but flagged late):

      powershell -ExecutionPolicy Bypass -File .\tools\checks\day5-check.ps1
      ... -WebVerify -SubChannelId 3     # no AMO phone: the AMO verifies on the web instead (AMO login asked)

  Checks (PASS or FAIL; the exit code is the number of failures):
   1. approver login       the approver (default dmo1001) logs in on the web; passwords are asked, never printed or
                           stored
   2. SR request arrived   you raise a NEW-outlet request in the SR app (Outlet, New shop) and sync; a pending "new"
                           request that was not in the list read before your step reaches the server (polled up to
                           5 minutes; no clock is compared, so a slow phone clock or midnight does not matter)
   3. AMO verified         you verify it in the AMO app (Outlet, Verify) and sync, or, with -WebVerify, the AMO's web
                           login verifies it; the request turns "verified" (polled up to 5 minutes)
   4. web approved         the approver approves it (POST approve, a different person from the requester and the
                           verifier); the request turns "approved" and names the new outlet
   5. TSO login            the TSO (default tso1001) logs in on the web
   6. preview read         GET /v1/day/final-submit/preview for the zone and today answers, not yet submitted, with
                           routes, the request's route among them
   7. Final Submit         after you confirm, POST /v1/day/final-submit closes the zone-day; the same client_uuid
                           sent again replays the first answer (same submitted_at), and a new client_uuid gets 409
                           ERR_DAY_ALREADY_FINAL_SUBMITTED (once per zone and day)
  The TSO app's own Final Submit screen is F-TSO-010; until it ships, step 7 uses the same API from the laptop.

.PARAMETER Zone
  The zone code of the SR's route (default Z-MIR).
#>
param(
  [string]$ApiHost = 'https://fde-aron-dev-7i7g53-gpgaa3fpgrhmdgaz.z03.azurefd.net',
  [string]$Zone = 'Z-MIR',
  [string]$Approver = 'dmo1001',
  [string]$Tso = 'tso1001',
  [string]$Amo = 'amo1001',
  [switch]$WebVerify,
  [long]$SubChannelId = 0,
  [int]$WaitMinutes = 5
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'AronCheck.psm1') -Force
$today = Get-DhakaDate
Write-Host "Aron Day 5 check  $(Get-Date -Format 'yyyy-MM-dd HH:mm')  business date $today  API $ApiHost" -ForegroundColor Cyan
if ($WebVerify -and $SubChannelId -le 0) { Write-Host '  -WebVerify needs -SubChannelId (the sub-channel the AMO picks).' -ForegroundColor Yellow; exit 1 }

# Every item of a paged list (follows next_cursor); $null when a page cannot be read.
function Get-AllItems([string]$Path) {
  $items = @(); $cursor = $null
  do {
    $sep = if ($Path.Contains('?')) { '&' } else { '?' }
    $page = Get-Aron ($Path + $(if ($cursor) { "${sep}cursor=$([uri]::EscapeDataString($cursor))" } else { '' }))
    if (-not $page) { return $null }
    $items += @($page.items); $cursor = $page.next_cursor
  } while ($cursor)
  return ,$items
}

# Polls GET <path> until $Test is true on the answer or the wait ends; returns the last answer.
function Wait-Aron([string]$Path, [scriptblock]$Test) {
  $deadline = (Get-Date).AddMinutes($WaitMinutes)
  do {
    $r = Get-Aron $Path
    if ($r -and (& $Test $r)) { return $r }
    Start-Sleep -Seconds 10
  } while ((Get-Date) -lt $deadline)
  return $r
}

# 1, 2: the approver's login, then the SR's request
if (-not (Get-WebToken $ApiHost $Approver 'approver login')) { exit (Complete-Check) }
# The pending new-outlet requests that exist BEFORE the SR's step: only a request not among them counts (no clocks).
$listPath = '/v1/outlet-requests?status=pending&request_type=new&limit=100'
$before = Get-AllItems $listPath
if ($null -eq $before) { Write-CheckResult 'SR request arrived' $false "the pending list cannot be read: $(Get-AronError)"; exit (Complete-Check) }
$known = @($before | ForEach-Object { $_.request_uuid })
Write-Host "  $($known.Count) pending new-outlet requests already exist in reach; only a new one counts." -ForegroundColor DarkGray
Write-Host '  On the SR phone: Outlet, New shop, fill it in, Save, then Sync.' -ForegroundColor Cyan
[void](Read-Host '  Press Enter when the request is saved and synced')
$req = $null
$deadline = (Get-Date).AddMinutes($WaitMinutes)
do {
  $now = Get-AllItems $listPath
  $new = @($now | Where-Object { $known -notcontains $_.request_uuid })
  if ($new.Count -gt 0) { $req = $new[0]; break }
  Start-Sleep -Seconds 10
} while ((Get-Date) -lt $deadline)
$detailText = if (-not $req) { "no pending new-outlet request appeared within $WaitMinutes min$(if ($null -eq $now) { ": $(Get-AronError)" })" } elseif ($new.Count -gt 1) { "$($new.Count) new requests appeared; checking $($req.request_uuid) by user $($req.requested_by_user_id), $($req.outlet_name)" } else { "request $($req.request_uuid) by user $($req.requested_by_user_id), $($req.outlet_name), route $($req.route_id)" }
Write-CheckResult 'SR request arrived' ([bool]$req) $detailText
if (-not $req) { exit (Complete-Check) }
$uuid = $req.request_uuid

# 3: AMO verification (the app, or the web with -WebVerify)
if ($WebVerify) {
  if (Get-WebToken $ApiHost $Amo 'AMO web login') {
    $v = Send-Aron "/v1/outlet-requests/$uuid/verify" @{ sub_channel_id = $SubChannelId; note = 'Day 5 check' }
    if (-not $v) { Write-Host "  web verify refused: $(Get-AronError)" -ForegroundColor Yellow }
  }
  [void](Use-AronUser $Approver)
} else {
  Write-Host '  On the AMO phone: Outlet, Verify, open the new request, set the sub-channel, Save, then Sync.' -ForegroundColor Cyan
  [void](Read-Host '  Press Enter when the verification is saved and synced')
}
$detail = Wait-Aron "/v1/outlet-requests/$uuid" { param($d) $d.status -eq 'verified' }
Write-CheckResult 'AMO verified' ($detail.status -eq 'verified') "status $($detail.status), verified by $($detail.verified_by_user_id) via $($detail.verified_via)"
if ($detail.status -ne 'verified') { exit (Complete-Check) }

# 4: web approval by a third person
$a = Send-Aron "/v1/outlet-requests/$uuid/approve" @{ change_reason = 'Day 5 ten-minute check (N-055)' }
$after = Get-Aron "/v1/outlet-requests/$uuid"
$approved = $after.status -eq 'approved' -and $after.resulting_outlet_id
Write-CheckResult 'web approved' ([bool]$approved) $(if ($approved) { "approved by $($after.approved_by_user_id); new outlet $($after.resulting_outlet_id)" } else { "status $($after.status); approve answered: $(if ($a) { 'ok' } else { Get-AronError })" })

# 5, 6: the TSO, the zone and the preview
if (-not (Get-WebToken $ApiHost $Tso 'TSO login')) { exit (Complete-Check) }
$zones = Get-AllItems "/v1/admin/geo/zone?q=$([uri]::EscapeDataString($Zone))&limit=50"
$zoneId = @($zones | Where-Object { $_.code -eq $Zone } | ForEach-Object { $_.id }) | Select-Object -First 1
if (-not $zoneId) { Write-CheckResult 'preview read' $false "zone $Zone not found for $Tso among $(@($zones).Count) zones read$(if ($null -eq $zones) { ": $(Get-AronError)" })"; exit (Complete-Check) }
$preview = Get-Aron "/v1/day/final-submit/preview?zone_id=$zoneId&business_date=$today"
$routeIds = @($preview.routes | ForEach-Object { $_.route_id })
$routeInZone = (-not $req.route_id) -or ($routeIds -contains $req.route_id)
$previewOk = $preview -and -not $preview.already_submitted -and $routeIds.Count -gt 0 -and $routeInZone
Write-CheckResult 'preview read' ([bool]$previewOk) $(if (-not $preview) { "refused: $(Get-AronError)" } elseif ($preview.already_submitted) { "zone $Zone is already Final Submitted for $today (at $($preview.submitted_at)): run on a day not yet closed" } elseif ($routeIds.Count -eq 0) { "zone $Zone ($zoneId) lists no route for $today" } elseif (-not $routeInZone) { "the request's route $($req.route_id) is not in $Zone (routes $($routeIds -join ', ')): pass -Zone with the SR's zone" } else { "zone $Zone ($zoneId): $($routeIds.Count) routes including the request's route, $(@($preview.warnings).Count) warnings" })
if (-not $previewOk) { exit (Complete-Check) }

# 7: Final Submit, its replay and a second attempt
if (-not (Read-YesNo "Final Submit closes $Zone for $today (it cannot be undone today except by an admin reopen). Submit now?")) {
  Write-CheckResult 'Final Submit' $false 'not run: you answered no'
  exit (Complete-Check)
}
$cu = [guid]::NewGuid().ToString()
$first = Send-Aron '/v1/day/final-submit' @{ client_uuid = $cu; zone_id = $zoneId; business_date = $today }
$firstStatus = Get-AronStatus; $firstError = Get-AronError
$replay = Send-Aron '/v1/day/final-submit' @{ client_uuid = $cu; zone_id = $zoneId; business_date = $today }
$replayStatus = Get-AronStatus
$second = Send-Aron '/v1/day/final-submit' @{ client_uuid = [guid]::NewGuid().ToString(); zone_id = $zoneId; business_date = $today }
$secondStatus = Get-AronStatus; $secondError = Get-AronError
$sameAnswer = $first -and $replay -and "$($replay.submitted_at)" -eq "$($first.submitted_at)" -and "$($replay.zone_id)" -eq "$zoneId"
$ok = $sameAnswer -and $replayStatus -eq 200 -and -not $second -and $secondStatus -eq 409 -and $secondError -match 'ERR_DAY_ALREADY_FINAL_SUBMITTED'
Write-CheckResult 'Final Submit' $ok ("first: {0}; same client_uuid again: {1}; a new client_uuid: {2}" -f $(if ($first) { 'ok' } else { $firstError }), $(if (-not $replay) { "status $replayStatus" } elseif ($sameAnswer) { 'replayed the first answer' } else { "answered, but not the first answer (submitted_at $($replay.submitted_at) vs $($first.submitted_at))" }), $(if ($second) { 'ACCEPTED (a second Final Submit)' } else { $secondError }))
exit (Complete-Check)
