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
                           request made after the script started reaches the server (polled up to 5 minutes)
   3. AMO verified         you verify it in the AMO app (Outlet, Verify) and sync, or, with -WebVerify, the AMO's web
                           login verifies it; the request turns "verified" (polled up to 5 minutes)
   4. web approved         the approver approves it (POST approve, a different person from the requester and the
                           verifier); the request turns "approved" and names the new outlet
   5. TSO login            the TSO (default tso1001) logs in on the web
   6. preview read         GET /v1/day/final-submit/preview for the zone and today answers, with the zone's routes
   7. Final Submit         after you confirm, POST /v1/day/final-submit closes the zone-day; the same client_uuid
                           sent again replays the first success, and a new client_uuid gets 409
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
$start = (Get-Date).ToUniversalTime().AddMinutes(-1)
$today = Get-DhakaDate
Write-Host "Aron Day 5 check  $(Get-Date -Format 'yyyy-MM-dd HH:mm')  business date $today  API $ApiHost" -ForegroundColor Cyan
if ($WebVerify -and $SubChannelId -le 0) { Write-Host '  -WebVerify needs -SubChannelId (the sub-channel the AMO picks).' -ForegroundColor Yellow; exit 1 }

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
Write-Host '  On the SR phone: Outlet, New shop, fill it in, Save, then Sync.' -ForegroundColor Cyan
[void](Read-Host '  Press Enter when the request is saved and synced')
$fresh = { param($page) @($page.items | Where-Object { [DateTime]::Parse($_.requested_at).ToUniversalTime() -ge $start }).Count -gt 0 }
$page = Wait-Aron "/v1/outlet-requests?status=pending&request_type=new&from=$today&to=$today&limit=100" $fresh
$req = @($page.items | Where-Object { [DateTime]::Parse($_.requested_at).ToUniversalTime() -ge $start } | Sort-Object { [DateTime]::Parse($_.requested_at) } -Descending) | Select-Object -First 1
Write-CheckResult 'SR request arrived' ([bool]$req) $(if ($req) { "request $($req.request_uuid) by user $($req.requested_by_user_id), $($req.outlet_name)" } else { "no new pending request after $($start.ToString('HH:mm')) UTC within $WaitMinutes min: $(Get-AronError)" })
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
$zones = Get-Aron "/v1/admin/geo/zone?search=$([uri]::EscapeDataString($Zone))&limit=10"
$zoneId = @($zones.items | Where-Object { $_.code -eq $Zone } | ForEach-Object { $_.id }) | Select-Object -First 1
if (-not $zoneId) { Write-CheckResult 'preview read' $false "zone $Zone not found for $Tso ($(Get-AronError))"; exit (Complete-Check) }
$preview = Get-Aron "/v1/day/final-submit/preview?zone_id=$zoneId&business_date=$today"
$previewOk = $preview -and -not $preview.already_submitted
Write-CheckResult 'preview read' ([bool]$previewOk) $(if (-not $preview) { "refused: $(Get-AronError)" } elseif ($preview.already_submitted) { "zone $Zone is already Final Submitted for $today (at $($preview.submitted_at)): run on a day not yet closed" } else { "zone $Zone ($zoneId): $(@($preview.routes).Count) routes, $(@($preview.warnings).Count) warnings" })
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
$ok = $first -and $replay -and $replayStatus -eq 200 -and -not $second -and $secondStatus -eq 409 -and $secondError -match 'ERR_DAY_ALREADY_FINAL_SUBMITTED'
Write-CheckResult 'Final Submit' $ok ("first: {0}; same client_uuid again: {1}; a new client_uuid: {2}" -f $(if ($first) { 'ok' } else { $firstError }), $(if ($replay) { 'replayed ok' } else { "status $replayStatus" }), $(if ($second) { 'ACCEPTED (a second Final Submit)' } else { $secondError }))
exit (Complete-Check)
