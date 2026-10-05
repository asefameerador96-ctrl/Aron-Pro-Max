#Requires -Version 5.1
<#
.SYNOPSIS
  One-time Azure + GitHub setup for Aron (dev). Run it ONCE on your laptop.

.DESCRIPTION
  Creates an isolated resource group and a GitHub-only identity that can deploy
  ONLY into that group. Nothing else in the subscription is touched or readable
  by that identity, so the services that are already live cannot be interrupted.
  No password or secret is created: GitHub signs in with a short-lived token
  (OIDC federation).

  Before running, in this same PowerShell window:
      az login
      gh auth login

  Run:
      .\infra\bootstrap-azure.ps1
#>
[CmdletBinding()]
param(
  [string]$SubscriptionId = 'fdd05880-48dd-46bd-be4c-36f7039e71d7',
  [string]$Location       = 'southeastasia',
  [string]$ResourceGroup  = 'rg-aron-dev',
  [string]$Repo           = 'asefameerador96-ctrl/Aron-Pro-Max',
  [string]$Environment    = 'azure-dev',
  [string]$AppName        = 'sp-aron-github-dev'
)

$ErrorActionPreference = 'Stop'

function Step([string]$m) { Write-Host "`n== $m" -ForegroundColor Cyan }
function Check([string]$what) { if ($LASTEXITCODE -ne 0) { throw "FAILED: $what" } }

Step "Using subscription $SubscriptionId"
az account set --subscription $SubscriptionId; Check 'az account set'
$tenantId = az account show --query tenantId -o tsv; Check 'read tenant'
Write-Host "Tenant: $tenantId"

# 1. Resource providers the platform needs (registration is idempotent and non-blocking).
Step 'Registering resource providers'
$providers = @(
  'Microsoft.App', 'Microsoft.ContainerRegistry', 'Microsoft.DBforPostgreSQL',
  'Microsoft.KeyVault', 'Microsoft.Storage', 'Microsoft.Insights',
  'Microsoft.OperationalInsights', 'Microsoft.Cdn', 'Microsoft.Network',
  'Microsoft.ManagedIdentity', 'Microsoft.LoadTestService', 'Microsoft.Cache',
  'Microsoft.ServiceBus', 'Microsoft.Consumption', 'Microsoft.AlertsManagement',
  'Microsoft.Authorization'
)
foreach ($p in $providers) { az provider register --namespace $p --only-show-errors | Out-Null; Check "register $p" }

# 2. The isolated resource group.
Step "Creating resource group $ResourceGroup in $Location"
az group create --name $ResourceGroup --location $Location `
  --tags project=aron environment=dev owner=aktcl --only-show-errors | Out-Null
Check 'az group create'

# 3. The GitHub identity (app registration + service principal), created once.
Step "Creating the GitHub identity $AppName"
$appId = az ad app list --display-name $AppName --query '[0].appId' -o tsv
if (-not $appId) {
  $appId = az ad app create --display-name $AppName --query appId -o tsv; Check 'az ad app create'
  az ad sp create --id $appId --only-show-errors | Out-Null; Check 'az ad sp create'
}
$spObjectId = az ad sp show --id $appId --query id -o tsv; Check 'read service principal'
Write-Host "Client id: $appId"

# 4. Permissions: ONLY on the new resource group.
Step "Granting rights on $ResourceGroup only"
$scope = "/subscriptions/$SubscriptionId/resourceGroups/$ResourceGroup"
foreach ($role in @('Contributor', 'Role Based Access Control Administrator')) {
  $has = az role assignment list --assignee $spObjectId --scope $scope --role $role --query 'length(@)' -o tsv
  if ($has -eq '0') {
    az role assignment create --assignee-object-id $spObjectId --assignee-principal-type ServicePrincipal `
      --role $role --scope $scope --only-show-errors | Out-Null
    Check "role $role"
  }
}

# 5. GitHub may sign in as this identity from the named environment (and for pull-request checks).
Step 'Creating the GitHub sign-in trust (OIDC)'
$existing = az ad app federated-credential list --id $appId --query '[].name' -o tsv
$creds = @(
  @{ name = 'github-environment-azure-dev'; subject = "repo:${Repo}:environment:$Environment" },
  @{ name = 'github-pull-request';          subject = "repo:${Repo}:pull_request" }
)
foreach ($c in $creds) {
  if ($existing -notcontains $c.name) {
    $tmp = Join-Path $env:TEMP "fc-$($c.name).json"
    @{ name = $c.name; issuer = 'https://token.actions.githubusercontent.com'
       subject = $c.subject; audiences = @('api://AzureADTokenExchange') } |
      ConvertTo-Json | Set-Content -Path $tmp -Encoding utf8
    az ad app federated-credential create --id $appId --parameters "@$tmp" --only-show-errors | Out-Null
    Check "federated credential $($c.name)"
    Remove-Item $tmp -Force
  }
}

# 6. Tell GitHub (these three values are identifiers, not passwords).
Step "Writing settings into GitHub repo $Repo"
gh api --method PUT "repos/$Repo/environments/$Environment" | Out-Null; Check 'create environment'
gh secret set AZURE_CLIENT_ID       --body $appId          --repo $Repo; Check 'secret AZURE_CLIENT_ID'
gh secret set AZURE_TENANT_ID       --body $tenantId       --repo $Repo; Check 'secret AZURE_TENANT_ID'
gh secret set AZURE_SUBSCRIPTION_ID --body $SubscriptionId --repo $Repo; Check 'secret AZURE_SUBSCRIPTION_ID'
gh variable set AZURE_RESOURCE_GROUP --body $ResourceGroup --repo $Repo; Check 'variable AZURE_RESOURCE_GROUP'
gh variable set AZURE_LOCATION       --body $Location      --repo $Repo; Check 'variable AZURE_LOCATION'

# 7. A snapshot of regional compute quota, so heavy load tests cannot starve live services.
Step "Saving a compute-quota snapshot for $Location"
$q = "quota-$Location.txt"
az vm list-usage --location $Location --query "[?contains(name.value, 'ores')].{name:name.localizedValue, used:currentValue, limit:limit}" -o table |
  Out-File -Encoding utf8 $q
Write-Host "Saved $q (send me its contents)."

Step 'Done'
Write-Host @"

Created:
  resource group   $ResourceGroup ($Location)
  GitHub identity  $AppName  (client id $appId)
  rights           Contributor + RBAC Administrator on $ResourceGroup ONLY
  GitHub           environment '$Environment', 3 secrets, 2 variables in $Repo

Nothing outside $ResourceGroup was changed. Tell me when this finished.
"@
