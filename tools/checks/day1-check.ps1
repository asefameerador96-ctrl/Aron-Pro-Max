#Requires -Version 5.1
<#
.SYNOPSIS
  N-015 Day 1 ten-minute check: phone listed, SR app installed, login works, the API answers from Azure.

.DESCRIPTION
  Run on the owner's laptop in a PowerShell window (not by double-click, so the result stays on screen), from the
  repository folder:

      powershell -ExecutionPolicy Bypass -File .\tools\checks\day1-check.ps1
      powershell -ExecutionPolicy Bypass -File .\tools\checks\day1-check.ps1 -Apk C:\path\sr.apk   # installs it first

  It prints one line per check, PASS or FAIL, and a summary; the exit code is the number of failed checks.
  1. phone listed       adb sees exactly one phone in the "device" state (not "unauthorized" or "offline")
  2. APK installed      the package com.aktcl.aron.sr is on that phone (after installing -Apk, if given)
  3. login succeeded    POST /v1/auth/login answers status "ok" with a token for the user you name (default tso1001,
                        a web login without MFA). The password is asked for (or read once from ARON_CHECK_PASSWORD,
                        which is then cleared) and never printed, logged or stored.
  4. login on phone     you log in on the phone yourself and answer y; the app must be in front
  5. /health from Azure GET /v1/health on the dev address answers 200, status ok, and the build that runs
  Nothing is changed on the phone except the optional install.
#>
param(
  [string]$ApiHost = 'https://fde-aron-dev-7i7g53-gpgaa3fpgrhmdgaz.z03.azurefd.net',
  [string]$User = 'tso1001',
  [string]$Apk = '',
  [switch]$SkipPhoneLogin
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'AronCheck.psm1') -Force
$Package = 'com.aktcl.aron.sr'
Write-Host "Aron Day 1 check  $(Get-Date -Format 'yyyy-MM-dd HH:mm')  API $ApiHost" -ForegroundColor Cyan

# 1, 2: phone and app (one result line each, also when the install fails)
$serial = Get-PhoneSerial
if ($serial) {
  $installNote = ''
  if ($Apk) {
    if (-not (Test-Path $Apk)) { $installNote = "no file at $Apk; " }
    else {
      $inst = Invoke-Adb @('-s', $serial, 'install', '-r', $Apk)
      if ($inst.Out -notmatch 'Success') { $installNote = "adb install failed ($($inst.Out -replace '\s+', ' ')); " }
    }
  }
  if (Test-PackageInstalled $serial $Package) {
    $verLine = (Invoke-Adb @('-s', $serial, 'shell', 'dumpsys', 'package', $Package)).Out -split "`r?`n" | Where-Object { $_ -match 'versionName=' } | Select-Object -First 1
    Write-CheckResult 'APK installed' (-not $installNote) ($installNote + "$Package $("$verLine".Trim())")
  } else {
    Write-CheckResult 'APK installed' $false ($installNote + "$Package is not on the phone (pass -Apk <path to the SR apk>)")
  }
} else {
  Write-CheckResult 'APK installed' $false 'skipped: no phone'
}

# 3: API login
[void](Get-WebToken $ApiHost $User 'login succeeded')

# 4: login on the phone
if (-not $SkipPhoneLogin -and $serial) {
  Write-Host "  Now open the Aron SR app on the phone and log in as the SR (sr1001)." -ForegroundColor Cyan
  $answer = Read-YesNo 'Did the app open its home screen after login?'
  $focus = (Invoke-Adb @('-s', $serial, 'shell', 'dumpsys', 'window')).Out -split "`r?`n" | Where-Object { $_ -match 'mCurrentFocus=Window' } | Select-Object -First 1
  $inApp = "$focus" -match [regex]::Escape($Package)
  Write-CheckResult 'login on phone' ($answer -and $inApp) $(if ($inApp) { "owner answered $(if ($answer) { 'yes' } else { 'no' }); the app is in front" } else { "the app is not in front ($("$focus".Trim()))" })
}

# 5: /health from Azure
try {
  $resp = Invoke-WebRequest -Method Get -Uri "$ApiHost/v1/health" -UseBasicParsing -TimeoutSec 30
  $h = $resp.Content | ConvertFrom-Json
  $ok = $resp.StatusCode -eq 200 -and $h.status -eq 'ok' -and $h.api -eq '/v1'
  $via = if ($resp.Headers['X-Azure-Ref']) { 'via Azure Front Door' } else { 'no X-Azure-Ref header: not through Azure Front Door?' }
  Write-CheckResult '/health from Azure' $ok "status $($h.status), build $($h.build), $via"
} catch {
  Write-CheckResult '/health from Azure' $false "GET $ApiHost/v1/health failed: $($_.Exception.Message)"
}
exit (Complete-Check)
