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
  # ARON_CHECK_PASSWORD_<USER> (e.g. ARON_CHECK_PASSWORD_TSO1001) for scripts that sign in as several users, else
  # ARON_CHECK_PASSWORD, else asked; whichever was used is cleared at once (not left in the window's environment).
  $perUser = "ARON_CHECK_PASSWORD_$($User.ToUpper())"
  $plain = [Environment]::GetEnvironmentVariable($perUser)
  if ($plain) { Remove-Item "Env:$perUser" -ErrorAction SilentlyContinue }
  elseif ($env:ARON_CHECK_PASSWORD) { $plain = $env:ARON_CHECK_PASSWORD; Remove-Item Env:ARON_CHECK_PASSWORD -ErrorAction SilentlyContinue }
  $secure = if ($plain) { ConvertTo-SecureString $plain -AsPlainText -Force } else { Read-Host "Password for $User (not shown)" -AsSecureString }
  $plain = $null
  $script:Login = @{ ApiHost = $ApiHost; User = $User; Secure = $secure }
  $token = New-WebToken
  $script:Token = $token
  $script:Sessions[$User] = @{ Login = $script:Login; Token = $token }
  if ($token) { Write-CheckResult $CheckName $true "$User answered ok (token not shown)" }
  else { Write-CheckResult $CheckName $false "$User failed: $script:LoginError" }
  return $token
}

# Signed-in users of this run, so a script that acts as several people asks each password once.
$script:Sessions = @{}

# Makes [User] (signed in earlier with Get-WebToken) the one Get-Aron and Send-Aron act as; false when not signed in.
function Use-AronUser([string]$User) {
  if ($script:Login -and $script:Sessions.ContainsKey($script:Login.User)) { $script:Sessions[$script:Login.User].Token = $script:Token }
  $s = $script:Sessions[$User]
  if (-not $s) { return $false }
  $script:Login = $s.Login; $script:Token = $s.Token
  return $true
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
    try { $script:GetStatus = 200; return Invoke-AronGet $l.ApiHost $script:Token $PathAndQuery }
    catch {
      $status = $null
      try { $status = [int]$_.Exception.Response.StatusCode } catch { }
      $script:GetError = "$status $($_.Exception.Message)"
      $script:GetStatus = $status
      if ($status -eq 401) { $script:Token = New-WebToken }
      elseif ($status -ge 400 -and $status -lt 500) { return $null }
      Start-Sleep -Seconds (2 * $i)
    }
  }
  return $null
}

# POST (or another method) with a JSON body; never throws: logs in again on 401, retries a transport failure three
# times; returns the parsed body, or $null with Get-AronStatus / Get-AronError set (a 4xx is returned at once).
function Send-Aron([string]$PathAndQuery, $Body, [string]$Method = 'Post') {
  $l = $script:Login
  if (-not $l) { $script:GetError = 'not logged in'; return $null }
  if (-not $script:Token) { $script:Token = New-WebToken }
  $json = $Body | ConvertTo-Json -Compress -Depth 8
  for ($i = 1; $i -le 4; $i++) {
    try {
      $script:GetStatus = 200
      return Invoke-RestMethod -Method $Method -Uri "$($l.ApiHost)$PathAndQuery" -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec 30 -Headers @{ Authorization = "Bearer $script:Token"; 'X-App-Version' = '1.0.0+1' }
    } catch {
      $status = $null
      try { $status = [int]$_.Exception.Response.StatusCode } catch { }
      $code = $null
      try { $code = (($_.ErrorDetails.Message | ConvertFrom-Json).code) } catch { }
      $script:GetError = "$status $(if ($code) { $code } else { $_.Exception.Message })"
      $script:GetStatus = $status
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

# A build-tools program of the Android SDK (newest stable folder first; release candidates and odd names sort last).
function Get-BuildTool([string]$Name) {
  $local = if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA 'Android\Sdk' } else { $null }
  $sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, $local) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
  if (-not $sdk) { return $null }
  $dirs = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue | ForEach-Object {
    $v = $null
    try { $v = [version]($_.Name -replace '-.*$', '') } catch { }
    if ($v) { [pscustomobject]@{ Dir = $_.FullName; Version = $v; Stable = $_.Name -notmatch '-' } }
  } | Sort-Object Stable, Version -Descending
  foreach ($d in $dirs) {
    $hit = @("$($d.Dir)\$Name.bat", "$($d.Dir)\$Name.exe", "$($d.Dir)/$Name") | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($hit) { return $hit }
  }
  return $null
}

# User 0's block of `dumpsys package <pkg>`: @{ Installed; Suspended } (a package missing for user 0 is not installed).
function Get-PackageState([string]$Serial, [string]$Pkg) {
  $d = (Invoke-Adb @('-s', $Serial, 'shell', 'dumpsys', 'package', $Pkg)).Out
  $line = ($d -split "`r?`n" | Where-Object { $_ -match '^\s*User 0:' } | Select-Object -First 1)
  if (-not $line) { return @{ Installed = $false; Suspended = $false } }
  return @{ Installed = "$line" -match 'installed=true'; Suspended = "$line" -match 'suspended=true' }
}

# The user restrictions in effect for user 0 (from `dumpsys user`, the "Effective restrictions" of UserInfo{0:...}).
function Get-EffectiveRestrictions([string]$Serial) {
  $d = (Invoke-Adb @('-s', $Serial, 'shell', 'dumpsys', 'user')).Out
  $m = [regex]::Match($d, '(?s)UserInfo\{0:.*?Effective restrictions:(.*?)(?:\n\s*\S[^\n]*:\s*\n|UserInfo\{|$)')
  if (-not $m.Success) { return $null }
  # The comma keeps an empty list an (empty) array; a bare @() reaches the caller as $null ("could not read").
  return ,@([regex]::Matches($m.Groups[1].Value, '\b(no_[a-z_]+)\b') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique)
}

# The HTTP status of the last Get-Aron (200, 4xx, 5xx, or $null for a transport failure).
function Get-AronStatus { return $script:GetStatus }

# The message of the last failed Get-Aron, or of the last failed login.
function Get-AronError { return "$script:GetError$(if (-not $script:GetError) { $script:LoginError })" }

# Whether the phone has an active network (dumpsys connectivity "Active default network: none" when offline).
function Test-PhoneOnline([string]$Serial) {
  $c = (Invoke-Adb @('-s', $Serial, 'shell', 'dumpsys', 'connectivity')).Out
  $m = [regex]::Match($c, 'Active default network:\s*(\S+)')
  if ($m.Success) { return $m.Groups[1].Value -ne 'none' }
  return $c -match 'NetworkAgentInfo\{.*state: CONNECTED'
}

Export-ModuleMember -Function Get-AronError, Get-AronStatus, Get-BuildTool, Get-PackageState, Get-EffectiveRestrictions, Write-CheckResult, Test-AdbPresent, Invoke-Adb, Get-PhoneSerial, Test-PackageInstalled, Get-WebToken, New-WebToken, Invoke-AronGet, Get-Aron, Send-Aron, Use-AronUser, Get-DhakaDate, Read-YesNo, Test-PhoneOnline, Complete-Check
