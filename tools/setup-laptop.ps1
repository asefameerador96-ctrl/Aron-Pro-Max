#Requires -Version 5.1
<#
.SYNOPSIS
  Installs the tools Aron needs on a Windows laptop. Run ONCE in an
  administrator PowerShell (right-click PowerShell > Run as administrator).

  Android Studio is installed first because it brings ADB (the Android Debug
  Bridge, in its "platform-tools" folder). This script then checks that ADB is
  there and puts it on your PATH.
#>
$ErrorActionPreference = 'Stop'

function Install-Pkg([string]$id) {
  Write-Host "`n== $id" -ForegroundColor Cyan
  winget install --id $id --exact --silent --accept-package-agreements --accept-source-agreements
  if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne -1978335189) { Write-Warning "winget exit code $LASTEXITCODE for $id (continuing)" }
}

# -1978335189 = "already installed / no upgrade available"
$packages = @(
  'Git.Git',                 # source control
  'GitHub.cli',              # gh: log in to GitHub, set repository secrets
  'Microsoft.AzureCLI',      # az: log in to Azure
  'Microsoft.OpenJDK.17',    # Java, needed to build Android
  'OpenJS.NodeJS.LTS',       # Node, for the web portal
  'Google.AndroidStudio'     # Android Studio (includes the SDK manager and ADB)
)
foreach ($p in $packages) { Install-Pkg $p }

# ADB lives in the Android SDK's platform-tools folder. Android Studio creates it
# after its first start (it downloads the SDK). Open Android Studio once, accept the
# defaults, then run this script again if it says ADB is missing.
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$pt  = Join-Path $sdk 'platform-tools'
$adb = Join-Path $pt 'adb.exe'

if (-not (Test-Path $adb)) {
  Write-Warning "ADB not found yet at $adb"
  Write-Host "Open Android Studio once and finish its setup wizard (it downloads the SDK)."
  Write-Host "Then in Android Studio: More Actions > SDK Manager > SDK Tools > tick 'Android SDK Platform-Tools' > Apply."
  Write-Host "Then run this script again."
  exit 0
}

$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if ($userPath -notlike "*$pt*") {
  [Environment]::SetEnvironmentVariable('Path', "$userPath;$pt", 'User')
  Write-Host "Added $pt to your PATH (open a NEW PowerShell window to use 'adb')."
}
[Environment]::SetEnvironmentVariable('ANDROID_HOME', $sdk, 'User')

Write-Host "`n== Versions" -ForegroundColor Cyan
& $adb version
git --version
gh --version | Select-Object -First 1
az version --query '"azure-cli"' -o tsv
node --version
java -version 2>&1 | Select-Object -First 1

Write-Host @"

Next, in a NEW PowerShell window:
  gh auth login          (choose GitHub.com, HTTPS, log in with the browser)
  az login               (log in with the browser)
Then tell me, and run:   .\infra\bootstrap-azure.ps1
"@
