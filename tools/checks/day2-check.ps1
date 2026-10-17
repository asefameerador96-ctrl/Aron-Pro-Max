#Requires -Version 5.1
<#
.SYNOPSIS
  N-022 Day 2 ten-minute check: an offline sale with a printed memo reaches the server exactly once.

.DESCRIPTION
  Run on the owner's laptop, from the repository folder, with the SR phone on USB (SR app installed, logged in as
  sr1001, printer paired):

      .\tools\checks\day2-check.ps1

  It prints one line per check, PASS or FAIL, and a summary; the exit code is the number of failed checks.
   1. phone listed, SR app installed
   2. web login         tso1001 (no MFA) reads sr1001's memos of today: the count before the sale
   3. airplane mode on  the script switches it on (adb) and reads it back
   4. offline sale      you check in, sell one memo and print it on the phone; the phone was still in airplane mode
                        when you answered, and the server did not get the memo meanwhile
   5. printed memo      your answer: one memo came out of the printer
   6. network on        the script switches airplane mode off
   7. on the server     within 5 minutes exactly one new sr1001 memo of today appears
   8. still once        a minute later (sync retries done) it is still exactly one
  The password is asked for (or read from ARON_CHECK_PASSWORD in this window) and never printed or stored.

.PARAMETER ApiHost
  The API origin (default: the dev Front Door address the device checks use).
.PARAMETER Supervisor
  The web user that reads the memos (default tso1001, whose territory holds sr1001's routes).
.PARAMETER Sr
  The SR whose memo is counted (default sr1001).
.PARAMETER WaitSeconds
  How long to wait for the memo after the network is back (default 300).
#>
param(
  [string]$ApiHost = 'https://fde-aron-dev-7i7g53-gpgaa3fpgrhmdgaz.z03.azurefd.net',
  [string]$Supervisor = 'tso1001',
  [string]$Sr = 'sr1001',
  [int]$WaitSeconds = 300
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'AronCheck.psm1') -Force
$Package = 'com.aktcl.aron.sr'
Write-Host "Aron Day 2 check  $(Get-Date -Format 'yyyy-MM-dd HH:mm')  API $ApiHost" -ForegroundColor Cyan

# sr1001's memos of today, as the supervisor sees them (every page).
function Get-SrMemos([string]$Token) {
  $day = Get-DhakaDate
  $all = @()
  $cursor = $null
  do {
    $q = "/v1/memos?from=$day&to=$day&limit=200" + $(if ($cursor) { "&cursor=$([uri]::EscapeDataString($cursor))" } else { '' })
    $page = Invoke-AronGet $ApiHost $Token $q
    $all += @($page.items | Where-Object { $_.memo_no -like "$Sr-*" })
    $cursor = $page.next_cursor
  } while ($cursor)
  return , $all
}

function Get-Airplane([string]$Serial) { return (Invoke-Adb @('-s', $Serial, 'shell', 'settings', 'get', 'global', 'airplane_mode_on')).Out.Trim() -eq '1' }

$serial = Get-PhoneSerial
if ($serial) {
  $installed = Test-PackageInstalled $serial $Package
  Write-CheckResult 'SR app installed' $installed $(if ($installed) { $Package } else { "$Package is not on the phone (run day1-check.ps1 -Apk ...)" })
}
$token = Get-WebToken $ApiHost $Supervisor 'web login'
$before = $null
if ($token) {
  try { $before = (Get-SrMemos $token).Count; Write-Host "  $Sr has $before memos of today on the server before the sale" }
  catch { Write-CheckResult 'memo count before' $false "GET /v1/memos failed: $($_.Exception.Message)" }
}

if ($serial -and $null -ne $before) {
  [void](Invoke-Adb @('-s', $serial, 'shell', 'cmd', 'connectivity', 'airplane-mode', 'enable'))
  Start-Sleep -Seconds 3
  $on = Get-Airplane $serial
  Write-CheckResult 'airplane mode on' $on $(if ($on) { 'airplane_mode_on=1' } else { 'could not switch it on: switch it on by hand, then rerun' })
  if ($on) {
    Write-Host "  Now, on the phone, in airplane mode: check in (Attendance), open an outlet, sell ONE memo and Print it." -ForegroundColor Cyan
    $printed = Read-YesNo 'Did exactly one memo come out of the printer?'
    $stillOff = Get-Airplane $serial
    $meanwhile = (Get-SrMemos $token).Count
    Write-CheckResult 'offline sale' ($stillOff -and $meanwhile -eq $before) $(if (-not $stillOff) { 'airplane mode was switched off during the sale' } elseif ($meanwhile -ne $before) { "the server got $($meanwhile - $before) memo(s) while the phone was offline?" } else { 'sold in airplane mode; nothing reached the server' })
    Write-CheckResult 'printed memo' $printed $(if ($printed) { 'owner: one memo printed' } else { 'owner: no memo, or more than one, printed' })

    [void](Invoke-Adb @('-s', $serial, 'shell', 'cmd', 'connectivity', 'airplane-mode', 'disable'))
    Start-Sleep -Seconds 3
    $back = -not (Get-Airplane $serial)
    Write-CheckResult 'network on' $back $(if ($back) { 'airplane mode off' } else { 'could not switch it off: switch it off by hand' })

    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    $now = $before
    while ((Get-Date) -lt $deadline) {
      $now = (Get-SrMemos $token).Count
      if ($now -ne $before) { break }
      Start-Sleep -Seconds 10
    }
    Write-CheckResult 'on the server' ($now -eq $before + 1) $(if ($now -eq $before + 1) { "one new $Sr memo of today (was $before, now $now)" } elseif ($now -eq $before) { "no new memo after $WaitSeconds s: open the app, check the sync screen" } else { "$($now - $before) new memos for one sale" })
    if ($now -eq $before + 1) {
      Start-Sleep -Seconds 60
      $later = Get-SrMemos $token
      $newest = $later | Sort-Object { $_.memo_no } | Select-Object -Last 1
      Write-CheckResult 'still once' ($later.Count -eq $before + 1) ("{0} memos a minute later; newest {1}, print_count {2}" -f $later.Count, $newest.memo_no, $newest.print_count)
    }
  }
}
exit (Complete-Check)
