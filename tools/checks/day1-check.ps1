#Requires -Version 5.1
<#
.SYNOPSIS
  N-015 Day 1 ten-minute check: phone listed, SR app installed, login works, the API answers from Azure.

.DESCRIPTION
  Run on the owner's laptop in a normal PowerShell window, from the repository folder:

      .\tools\checks\day1-check.ps1                       # checks only
      .\tools\checks\day1-check.ps1 -Apk C:\path\sr.apk   # installs (or updates) the SR app first

  It prints one line per check, PASS or FAIL, and a summary; the exit code is the number of failed checks.
  1. phone listed       adb sees exactly one phone in the "device" state (not "unauthorized" or "offline")
  2. APK installed      the package com.aktcl.aron.sr is on that phone (after installing -Apk, if given)
  3. login succeeded    POST /v1/auth/login answers status "ok" for the user you name (default tso1001, a web
                        login without MFA). The password is asked for (or read from the environment variable
                        ARON_CHECK_PASSWORD, for the laptop operator session) and never printed, logged or stored.
                        Then you log in on the phone yourself and answer y if the app opened its home screen.
  4. /health from Azure GET /v1/health on the dev address answers 200, status ok, and the build that runs.

  Nothing is changed on the phone except the optional install. No secret is written anywhere.

.PARAMETER ApiHost
  The API origin (default: the dev Front Door address the device checks use).
.PARAMETER User
  The account for the API login (default tso1001; an SR account needs an enrolled phone and is checked on the phone).
.PARAMETER Apk
  Optional path to the SR APK to install with adb install -r before the checks.
.PARAMETER SkipPhoneLogin
  Do not ask about the login on the phone (for a run without the phone in hand).
#>
param(
  [string]$ApiHost = 'https://fde-aron-dev-7i7g53-gpgaa3fpgrhmdgaz.z03.azurefd.net',
  [string]$User = 'tso1001',
  [string]$Apk = '',
  [switch]$SkipPhoneLogin
)
$ErrorActionPreference = 'Stop'
$Package = 'com.aktcl.aron.sr'
$results = [System.Collections.Generic.List[object]]::new()

function Report([string]$name, [bool]$ok, [string]$detail) {
  $results.Add([pscustomobject]@{ Check = $name; Result = $(if ($ok) { 'PASS' } else { 'FAIL' }); Detail = $detail })
  $colour = if ($ok) { 'Green' } else { 'Red' }
  Write-Host ("{0,-4}  {1,-18} {2}" -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $name, $detail) -ForegroundColor $colour
}

# The adb executable itself (a function named like it would shadow it: PowerShell names are case-insensitive).
$AdbExe = (Get-Command adb -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source
function Invoke-Adb([string[]]$a) {
  $out = & $AdbExe @a 2>&1 | Out-String
  return @{ Code = $LASTEXITCODE; Out = $out.Trim() }
}

Write-Host "Aron Day 1 check  $(Get-Date -Format 'yyyy-MM-dd HH:mm')  API $ApiHost" -ForegroundColor Cyan

# 1. phone listed --------------------------------------------------------------------------------------------------------
$serial = $null
if (-not $AdbExe) {
  Report 'phone listed' $false 'adb is not on PATH (run tools\setup-laptop.ps1 once, then open a new window)'
} else {
  $lines = (Invoke-Adb @('devices')).Out -split "`r?`n" | Select-Object -Skip 1 | Where-Object { $_ -match '\S' }
  $ready = @($lines | Where-Object { $_ -match "`tdevice$" })
  $other = @($lines | Where-Object { $_ -notmatch "`tdevice$" })
  if ($ready.Count -eq 1) {
    $serial = ($ready[0] -split "`t")[0]
    $model = (Invoke-Adb @('-s', $serial, 'shell', 'getprop', 'ro.product.model')).Out
    Report 'phone listed' $true "$model ($serial)"
  } elseif ($ready.Count -gt 1) {
    Report 'phone listed' $false "$($ready.Count) phones are connected; unplug all but one"
  } elseif ($other.Count -gt 0) {
    Report 'phone listed' $false ("the phone is listed as '" + (($other[0] -split "`t")[1]) + "': unlock it and accept the USB debugging prompt")
  } else {
    Report 'phone listed' $false 'no phone: connect the USB cable, enable USB debugging, accept the prompt on the phone'
  }
}

# 2. APK installed -------------------------------------------------------------------------------------------------------
if ($serial) {
  if ($Apk) {
    if (-not (Test-Path $Apk)) { Report 'APK installed' $false "no file at $Apk" ; $Apk = '' }
    else {
      $inst = Invoke-Adb @('-s', $serial, 'install', '-r', $Apk)
      if ($inst.Out -notmatch 'Success') { Write-Host "  adb install said: $($inst.Out)" -ForegroundColor Yellow }
    }
  }
  $pkg = (Invoke-Adb @('-s', $serial, 'shell', 'pm', 'list', 'packages', $Package)).Out
  if ($pkg -match "package:$([regex]::Escape($Package))(\s|$)") {
    $ver = ((Invoke-Adb @('-s', $serial, 'shell', 'dumpsys', 'package', $Package)).Out -split "`r?`n" | Where-Object { $_ -match 'versionName=' } | Select-Object -First 1).Trim()
    Report 'APK installed' $true "$Package $ver"
  } else {
    Report 'APK installed' $false "$Package is not on the phone (pass -Apk <path to the SR apk>)"
  }
} else {
  Report 'APK installed' $false 'skipped: no phone'
}

# 3. login succeeded -----------------------------------------------------------------------------------------------------
$secure = if ($env:ARON_CHECK_PASSWORD) { ConvertTo-SecureString $env:ARON_CHECK_PASSWORD -AsPlainText -Force } else { Read-Host "Password for $User (not shown)" -AsSecureString }
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
  $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
  $body = @{ username = $User; password = $plain; client = 'web' } | ConvertTo-Json -Compress
  $plain = $null
  try {
    $r = Invoke-RestMethod -Method Post -Uri "$ApiHost/v1/auth/login" -ContentType 'application/json' -Body $body -TimeoutSec 30 -Headers @{ 'X-App-Version' = '1.0.0+1' }
    $body = $null
    if ($r.status -eq 'ok') { Report 'login succeeded' $true "API login of $User answered ok (token not shown)" }
    else { Report 'login succeeded' $false "API login of $User answered status '$($r.status)'" }
  } catch {
    $body = $null
    $code = $null
    try { $code = (($_.ErrorDetails.Message | ConvertFrom-Json).code) } catch { }
    Report 'login succeeded' $false ("API login of $User failed: " + $(if ($code) { $code } else { $_.Exception.Message }))
  }
} finally {
  [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
}
if (-not $SkipPhoneLogin -and $serial) {
  Write-Host "  Now open the Aron SR app on the phone and log in as the SR (sr1001)." -ForegroundColor Cyan
  $answer = Read-Host '  Did the app open its home screen after login? (y/n)'
  $focus = ((Invoke-Adb @('-s', $serial, 'shell', 'dumpsys', 'window')).Out -split "`r?`n" | Where-Object { $_ -match 'mCurrentFocus' } | Select-Object -First 1)
  $inApp = $focus -match [regex]::Escape($Package)
  Report 'login on phone' ($answer -match '^[yY]' -and $inApp) $(if ($inApp) { "owner answered '$answer'; the app is in front" } else { "the app is not in front ($($focus.Trim()))" })
}

# 4. /health from Azure --------------------------------------------------------------------------------------------------
try {
  $resp = Invoke-WebRequest -Method Get -Uri "$ApiHost/v1/health" -UseBasicParsing -TimeoutSec 30
  $h = $resp.Content | ConvertFrom-Json
  $ok = $resp.StatusCode -eq 200 -and $h.status -eq 'ok' -and $h.api -eq '/v1'
  $via = if ($resp.Headers['X-Azure-Ref']) { 'via Azure Front Door' } else { 'no X-Azure-Ref header: not through Azure Front Door?' }
  Report '/health from Azure' $ok "status $($h.status), build $($h.build), $via"
} catch {
  Report '/health from Azure' $false "GET $ApiHost/v1/health failed: $($_.Exception.Message)"
}

$failed = @($results | Where-Object { $_.Result -eq 'FAIL' }).Count
Write-Host ''
if ($failed -eq 0) { Write-Host "ALL PASS ($($results.Count) checks)" -ForegroundColor Green }
else { Write-Host "$failed of $($results.Count) checks FAILED: copy this window into the lead's chat" -ForegroundColor Red }
exit $failed
