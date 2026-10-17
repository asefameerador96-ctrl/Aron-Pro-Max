# Shared helpers of the ten-minute check scripts (qa; tools/checks/day*-check.ps1). Each check prints one PASS or FAIL
# line; Complete-Check prints the summary and returns the number of failures (the script's exit code).
# Passwords are read without echo (or from ARON_CHECK_PASSWORD in that window) and never printed or stored.

$script:Results = [System.Collections.Generic.List[object]]::new()
# Windows PowerShell 5.1 on an older .NET may default to TLS 1.0; Front Door needs 1.2.
try { [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12 } catch { }
# The web login, kept to log in again when the 15-minute web token expires (never printed).
$script:Login = $null
# The adb executable itself: a function named like it would shadow it (PowerShell names are case-insensitive).
$script:AdbExe = (Get-Command adb -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source

function Write-CheckResult([string]$Name, [bool]$Ok, [string]$Detail) {
  $script:Results.Add([pscustomobject]@{ Check = $Name; Result = $(if ($Ok) { 'PASS' } else { 'FAIL' }); Detail = $Detail })
  Write-Host ("{0,-4}  {1,-22} {2}" -f $(if ($Ok) { 'PASS' } else { 'FAIL' }), $Name, $Detail) -ForegroundColor $(if ($Ok) { 'Green' } else { 'Red' })
}

function Test-AdbPresent { return [bool]$script:AdbExe }

function Invoke-Adb([string[]]$Arguments) {
  # adb writes progress and failures to stderr; in Windows PowerShell 5.1 that would be a terminating error under Stop.
  $ErrorActionPreference = 'Continue'
  $out = & $script:AdbExe @Arguments 2>&1 | ForEach-Object { "$_" } | Out-String
  return @{ Code = $LASTEXITCODE; Out = $out.Trim() }
}

# The serial of the one connected phone in the "device" state, or $null after reporting why not.
function Get-PhoneSerial {
  if (-not $script:AdbExe) { Write-CheckResult 'phone listed' $false 'adb is not on PATH (run tools\setup-laptop.ps1 once, then open a new window)'; return $null }
  # Device lines are "<serial><TAB><state>"; daemon start-up messages and the header have no tab.
  $lines = (Invoke-Adb @('devices')).Out -split "`r?`n" | Where-Object { $_ -match "`t" }
  $ready = @($lines | Where-Object { $_ -match "`tdevice\s*$" })
  $other = @($lines | Where-Object { $_ -match "`t(unauthorized|offline|no permissions|recovery|sideload)" })
  if ($ready.Count -eq 1) {
    $serial = ($ready[0] -split "`t")[0]
    Write-CheckResult 'phone listed' $true ("{0} ({1})" -f (Invoke-Adb @('-s', $serial, 'shell', 'getprop', 'ro.product.model')).Out, $serial)
    return $serial
  }
  if ($ready.Count -gt 1) { Write-CheckResult 'phone listed' $false "$($ready.Count) phones are connected; unplug all but one" }
  elseif ($other.Count -gt 0) { Write-CheckResult 'phone listed' $false ("the phone is listed as '" + (($other[0] -split "`t")[1]) + "': unlock it and accept the USB debugging prompt") }
  else { Write-CheckResult 'phone listed' $false 'no phone: connect the USB cable, enable USB debugging, accept the prompt on the phone' }
  return $null
}

function Test-PackageInstalled([string]$Serial, [string]$Package) {
  $pkg = (Invoke-Adb @('-s', $Serial, 'shell', 'pm', 'list', 'packages', $Package)).Out
  return $pkg -match "package:$([regex]::Escape($Package))(\s|$)"
}

# POST /v1/auth/login for a web user; returns the access token or $null (after reporting). The password is never shown.
function Get-WebToken([string]$ApiHost, [string]$User, [string]$CheckName = 'web login') {
  $secure = if ($env:ARON_CHECK_PASSWORD) { ConvertTo-SecureString $env:ARON_CHECK_PASSWORD -AsPlainText -Force } else { Read-Host "Password for $User (not shown)" -AsSecureString }
  Remove-Item Env:ARON_CHECK_PASSWORD -ErrorAction SilentlyContinue  # not left in the window's environment
  $script:Login = @{ ApiHost = $ApiHost; User = $User; Secure = $secure }
  $token = New-WebToken
  $script:Token = $token
  if ($token) { Write-CheckResult $CheckName $true "$User answered ok (token not shown)" }
  else { Write-CheckResult $CheckName $false "$User failed: $script:LoginError" }
  return $token
}

# Logs in again with the kept credentials; the token, or $null with $script:LoginError set.
function New-WebToken {
  $l = $script:Login
  if (-not $l) { return $null }
  $secure = $l.Secure; $ApiHost = $l.ApiHost; $User = $l.User
  $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
  try {
    $body = @{ username = $User; password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr); client = 'web' } | ConvertTo-Json -Compress
    try {
      $r = Invoke-RestMethod -Method Post -Uri "$ApiHost/v1/auth/login" -ContentType 'application/json' -Body $body -TimeoutSec 30 -Headers @{ 'X-App-Version' = '1.0.0+1' }
      $body = $null
      if ($r.status -eq 'ok' -and $r.access_token) { return $r.access_token }
      $script:LoginError = "status '$($r.status)'"
    } catch {
      $body = $null
      $code = $null
      try { $code = (($_.ErrorDetails.Message | ConvertFrom-Json).code) } catch { }
      $script:LoginError = $(if ($code) { $code } else { $_.Exception.Message })
    }
  } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
  return $null
}

# GET with a bearer token; returns the parsed body or throws.
function Invoke-AronGet([string]$ApiHost, [string]$Token, [string]$PathAndQuery) {
  return Invoke-RestMethod -Method Get -Uri "$ApiHost$PathAndQuery" -Headers @{ Authorization = "Bearer $Token"; 'X-App-Version' = '1.0.0+1' } -TimeoutSec 30
}

# GET that never throws: retries a failure three times (a laptop network blip), logs in again on 401 (the web token
# lives 15 minutes); returns the parsed body, or $null with $script:GetError set.
function Get-Aron([string]$PathAndQuery) {
  $l = $script:Login
  if (-not $l) { $script:GetError = 'not logged in'; return $null }
  if (-not $script:Token) { $script:Token = New-WebToken }
  for ($i = 1; $i -le 4; $i++) {
    try { return Invoke-AronGet $l.ApiHost $script:Token $PathAndQuery }
    catch {
      $status = $null
      try { $status = [int]$_.Exception.Response.StatusCode } catch { }
      $script:GetError = "$status $($_.Exception.Message)"
      if ($status -eq 401) { $script:Token = New-WebToken }
      elseif ($status -ge 400 -and $status -lt 500) { return $null }
      Start-Sleep -Seconds (2 * $i)
    }
  }
  return $null
}

# Today's business date in Asia/Dhaka (UTC+6, no daylight saving).
function Get-DhakaDate { return (Get-Date).ToUniversalTime().AddHours(6).ToString('yyyy-MM-dd') }

function Read-YesNo([string]$Question) { return (Read-Host "  $Question (y/n)") -match '^[yY]' }

function Complete-Check {
  $failed = @($script:Results | Where-Object { $_.Result -eq 'FAIL' }).Count
  Write-Host ''
  if ($failed -eq 0) { Write-Host "ALL PASS ($($script:Results.Count) checks)" -ForegroundColor Green }
  else { Write-Host "$failed of $($script:Results.Count) checks FAILED: copy this window into the lead's chat" -ForegroundColor Red }
  return $failed
}

# Whether the phone has an active network (dumpsys connectivity "Active default network: none" when offline).
function Test-PhoneOnline([string]$Serial) {
  $c = (Invoke-Adb @('-s', $Serial, 'shell', 'dumpsys', 'connectivity')).Out
  $m = [regex]::Match($c, 'Active default network:\s*(\S+)')
  if ($m.Success) { return $m.Groups[1].Value -ne 'none' }
  return $c -match 'NetworkAgentInfo\{.*state: CONNECTED'
}

Export-ModuleMember -Function Write-CheckResult, Test-AdbPresent, Invoke-Adb, Get-PhoneSerial, Test-PackageInstalled, Get-WebToken, New-WebToken, Invoke-AronGet, Get-Aron, Get-DhakaDate, Read-YesNo, Test-PhoneOnline, Complete-Check
