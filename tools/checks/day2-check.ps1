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
   3. airplane mode on  the script switches it on (adb) and the phone reports no active network
   4. offline sale      you check in, sell one memo and print it on the phone; the phone was still in airplane mode
                        when you answered, and the server did not get the memo meanwhile
   5. printed memo      your answer: one memo came out of the printer
   6. network on        the script switches airplane mode off
   7. on the server     within 5 minutes exactly one new ACTIVE sr1001 memo of today appears
   8. still once        a minute later (sync retries done) it is still exactly one
   9. print on server   that memo's print_count is at least 1
  Run it in a PowerShell window: powershell -ExecutionPolicy Bypass -File .\tools\checks\day2-check.ps1
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

# One business date for the whole run (a run must not cross Dhaka midnight).
$day = Get-DhakaDate

# The SR's ACTIVE memos of $day as the supervisor sees them (every page, at most 50), keyed by memo number; $null when
# the server cannot be read (after retries and a fresh login: the web token lives 15 minutes).
function Get-SrMemos {
  $all = @{}
  $cursor = $null
  for ($n = 0; $n -lt 50; $n++) {
    $q = "/v1/memos?from=$day&to=$day&limit=200" + $(if ($cursor) { "&cursor=$([uri]::EscapeDataString($cursor))" } else { '' })
    $page = Get-Aron $q
    if ($null -eq $page) { return $null }
    foreach ($m in @($page.items)) { if ($m.memo_no -like "$Sr-*" -and $m.status -eq 'active') { $all[$m.memo_no] = $m } }
    $cursor = $page.next_cursor
    if (-not $cursor) { return $all }
  }
  return $all
}

function Get-Airplane([string]$Serial) { return "$((Invoke-Adb @('-s', $Serial, 'shell', 'settings', 'get', 'global', 'airplane_mode_on')).Out)".Trim() -eq '1' }

$dhakaNow = (Get-Date).ToUniversalTime().AddHours(6)
if ($dhakaNow.Hour -eq 23 -and $dhakaNow.Minute -ge 30) {
  Write-CheckResult 'time of day' $false 'less than 30 minutes before Dhaka midnight: run it after 00:05'
  exit (Complete-Check)
}

$serial = Get-PhoneSerial
if ($serial) {
  $installed = Test-PackageInstalled $serial $Package
  Write-CheckResult 'SR app installed' $installed $(if ($installed) { $Package } else { "$Package is not on the phone (run day1-check.ps1 -Apk ...)" })
}
$token = Get-WebToken $ApiHost $Supervisor 'web login'
$before = $null
if ($token) {
  $before = Get-SrMemos
  if ($null -eq $before) { Write-CheckResult 'memo count before' $false "GET /v1/memos failed: $script:GetError" }
  else { Write-Host "  $Sr has $($before.Count) active memos of $day on the server before the sale" }
}

$airplaneSet = $false
try {
  if ($serial -and $null -ne $before) {
    [void](Invoke-Adb @('-s', $serial, 'shell', 'cmd', 'connectivity', 'airplane-mode', 'enable'))
    $airplaneSet = $true
    Start-Sleep -Seconds 5
    $on = Get-Airplane $serial
    $offline = -not (Test-PhoneOnline $serial)
    Write-CheckResult 'airplane mode on' ($on -and $offline) $(if ($on -and $offline) { 'airplane mode on, no active network' } elseif ($on) { 'airplane mode is on but the phone still has a network (Wi-Fi?): switch Wi-Fi off' } else { 'could not switch it on: switch it on by hand, then rerun' })
    if ($on -and $offline) {
      Write-Host "  Now, on the phone: check in (Attendance), open an outlet, sell ONE memo and Print it." -ForegroundColor Cyan
      Write-Host "  If the printer needs Bluetooth, switch ONLY Bluetooth back on (not Wi-Fi, not mobile data)." -ForegroundColor Cyan
      $printed = Read-YesNo 'Did exactly one memo come out of the printer?'
      $stillOffline = -not (Test-PhoneOnline $serial)
      $meanwhile = Get-SrMemos
      $leaked = if ($null -eq $meanwhile) { $null } else { $meanwhile.Count - $before.Count }
      Write-CheckResult 'offline sale' ($stillOffline -and $leaked -eq 0) $(if (-not $stillOffline) { 'the phone went online during the sale' } elseif ($null -eq $leaked) { "the server could not be read: $script:GetError" } elseif ($leaked -ne 0) { "the server got $leaked memo(s) while the phone was offline?" } else { 'sold offline; nothing reached the server' })
      Write-CheckResult 'printed memo' $printed $(if ($printed) { 'owner: one memo printed' } else { 'owner: no memo, or more than one, printed' })

      [void](Invoke-Adb @('-s', $serial, 'shell', 'cmd', 'connectivity', 'airplane-mode', 'disable'))
      $airplaneSet = $false
      Start-Sleep -Seconds 5
      $back = -not (Get-Airplane $serial)
      Write-CheckResult 'network on' $back $(if ($back) { 'airplane mode off' } else { 'could not switch it off: switch it off by hand' })

      $deadline = (Get-Date).AddSeconds($WaitSeconds)
      $now = $before
      while ((Get-Date) -lt $deadline) {
        $read = Get-SrMemos
        if ($null -ne $read) { $now = $read; if ($now.Count -ne $before.Count) { break } }
        Start-Sleep -Seconds 10
      }
      $new = @($now.Keys | Where-Object { -not $before.ContainsKey($_) })
      Write-CheckResult 'on the server' ($new.Count -eq 1 -and $now.Count -eq $before.Count + 1) $(if ($new.Count -eq 1) { "one new $Sr memo: $($new[0])" } elseif ($new.Count -eq 0) { "no new memo after $WaitSeconds s: open the app, check the sync screen ($script:GetError)" } else { "$($new.Count) new memos for one sale: $($new -join ', ')" })
      if ($new.Count -eq 1) {
        Start-Sleep -Seconds 60
        $later = Get-SrMemos
        $again = if ($null -eq $later) { $null } else { @($later.Keys | Where-Object { -not $before.ContainsKey($_) }) }
        Write-CheckResult 'still once' ($null -ne $again -and $again.Count -eq 1) $(if ($null -eq $again) { "the server could not be read: $script:GetError" } else { "$($again.Count) new memo(s) a minute later" })
        $m = if ($later) { $later[$new[0]] } else { $null }
        $pc = if ($m) { [int]$m.print_count } else { 0 }
        Write-CheckResult 'print on server' ($pc -ge 1) "memo $($new[0]) print_count $pc"
      }
    }
  }
} finally {
  # Never leave the phone offline, whatever happened above.
  if ($airplaneSet -and $serial) { [void](Invoke-Adb @('-s', $serial, 'shell', 'cmd', 'connectivity', 'airplane-mode', 'disable')) }
}
exit (Complete-Check)
