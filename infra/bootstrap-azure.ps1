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
  # The only branch the environment (and so the Azure identity) accepts deployments from.
  [string]$Branch         = 'claude/wonderful-thompson-k6ejnf',
  [string]$AppName        = 'sp-aron-github-dev',
  # Who receives the budget and platform alerts (comma-separated). Stored as the GitHub variable ARON_ALERT_EMAILS.
  [string]$AlertEmails    = ''
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
  'Microsoft.Authorization', 'Microsoft.EventGrid'
)
foreach ($p in $providers) { az provider register --namespace $p --only-show-errors | Out-Null; Check "register $p" }

# 2. The isolated resource group, plus an EMPTY probe group the GitHub identity gets no rights on: every deploy proves
#    the scope by attempting a test deployment into it and expecting AuthorizationFailed (infra/scripts/scope-check.sh).
Step "Creating resource group $ResourceGroup in $Location"
az group create --name $ResourceGroup --location $Location `
  --tags project=aron environment=dev owner=aktcl --only-show-errors | Out-Null
Check 'az group create'
az group create --name 'rg-aron-scope-probe' --location $Location `
  --tags project=aron purpose=scope-probe --only-show-errors | Out-Null
Check 'az group create (scope probe)'

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
#    Contributor, plus Role Based Access Control Administrator LIMITED BY A CONDITION: the identity may create or delete
#    role assignments only for the data-plane roles the templates grant (infra/lib/naming.bicep), never Owner,
#    Contributor or User Access Administrator. An older unconditional assignment is replaced.
Step "Granting rights on $ResourceGroup only"
$scope = "/subscriptions/$SubscriptionId/resourceGroups/$ResourceGroup"
$has = az role assignment list --assignee $spObjectId --scope $scope --role 'Contributor' --query 'length(@)' -o tsv
Check 'list Contributor assignments (re-run in a minute if the new identity has not replicated yet)'
if ($has -eq '0') {
  az role assignment create --assignee-object-id $spObjectId --assignee-principal-type ServicePrincipal `
    --role 'Contributor' --scope $scope --only-show-errors | Out-Null
  Check 'role Contributor'
}
$rbacAdmin = 'f58310d9-a9f6-439a-9e8d-f62e7b41a168'
$allowed = @(
  '7f951dda-4ed3-4680-a7ca-43fe172d538d', # AcrPull
  '8311e382-0749-4cb8-b61a-304f252e45ec', # AcrPush
  '4633458b-17de-408a-b874-0445c86b69e6', # Key Vault Secrets User
  'b86a8fe4-44ce-4948-aee5-eccb2c155cd7', # Key Vault Secrets Officer
  'ba92f5b4-2d11-453d-a403-e96b0029c9fe', # Storage Blob Data Contributor
  'db58b8e5-c6ad-4a2a-8342-4190687cbf4a', # Storage Blob Delegator
  '8a0f0c08-91a1-4084-bc3d-661d67233fed', # Storage Queue Data Message Processor
  'c6a89b2d-59bc-44d0-9896-0f6e12d7b80a'  # Storage Queue Data Message Sender
) -join ', '
$condition = "((!(ActionMatches{'Microsoft.Authorization/roleAssignments/write'})) OR (@Request[Microsoft.Authorization/roleAssignments:RoleDefinitionId] ForAnyOfAnyValues:GuidEquals {$allowed})) AND ((!(ActionMatches{'Microsoft.Authorization/roleAssignments/delete'})) OR (@Resource[Microsoft.Authorization/roleAssignments:RoleDefinitionId] ForAnyOfAnyValues:GuidEquals {$allowed}))"
$assignments = az role assignment list --assignee $spObjectId --scope $scope --role $rbacAdmin --query '[].{id:id, condition:condition}' -o json | ConvertFrom-Json
Check 'list RBAC Administrator assignments'
function Normalize([string]$c) { if ($c) { ($c -replace '\s+', '') } else { '' } }
$good = $assignments | Where-Object { (Normalize $_.condition) -eq (Normalize $condition) }
$stale = $assignments | Where-Object { (Normalize $_.condition) -ne (Normalize $condition) }
if (-not $good) {
  $body = Join-Path $env:TEMP 'ra-rbac-admin.json'
  @{ properties = @{
       roleDefinitionId = "/subscriptions/$SubscriptionId/providers/Microsoft.Authorization/roleDefinitions/$rbacAdmin"
       principalId = $spObjectId; principalType = 'ServicePrincipal'
       condition = $condition; conditionVersion = '2.0' } } |
    ConvertTo-Json -Depth 5 | Set-Content -Path $body -Encoding utf8
  $raName = [guid]::NewGuid().ToString()
  az rest --method put --url "https://management.azure.com$scope/providers/Microsoft.Authorization/roleAssignments/${raName}?api-version=2022-04-01" `
    --body "@$body" --only-show-errors | Out-Null
  Check 'role RBAC Administrator (conditional)'
  Remove-Item $body -Force
}
# Only after the conditional assignment exists, remove any older one without the condition (no gap in rights).
foreach ($a in $stale) {
  Write-Host "Removing an RBAC Administrator assignment without the role condition: $($a.id)"
  az role assignment delete --ids $a.id --only-show-errors; Check 'delete unconditional RBAC Administrator'
}

# 5. GitHub may sign in as this identity ONLY from the named environment, which accepts deployments only from
#    $Branch (step 6). Pull requests get no Azure identity: a credential for them would let any PR act as the deployer.
Step 'Creating the GitHub sign-in trust (OIDC)'
$existing = az ad app federated-credential list --id $appId --query '[].name' -o tsv
if ($existing -contains 'github-pull-request') {
  az ad app federated-credential delete --id $appId --federated-credential-id 'github-pull-request' --only-show-errors
  Check 'remove the pull-request credential'
}
$creds = @(
  @{ name = 'github-environment-azure-dev'; subject = "repo:${Repo}:environment:$Environment" }
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
$envBody = Join-Path $env:TEMP 'gh-environment.json'
'{"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}' |
  Set-Content -Path $envBody -Encoding ascii
gh api --method PUT "repos/$Repo/environments/$Environment" --input $envBody | Out-Null; Check 'create environment'
Remove-Item $envBody -Force
$policies = gh api "repos/$Repo/environments/$Environment/deployment-branch-policies" --jq '.branch_policies[].name'
if ($policies -notcontains $Branch) {
  gh api --method POST "repos/$Repo/environments/$Environment/deployment-branch-policies" -f "name=$Branch" -f type=branch | Out-Null
  Check "environment branch policy $Branch"
}
gh secret set AZURE_CLIENT_ID       --body $appId          --repo $Repo; Check 'secret AZURE_CLIENT_ID'
gh secret set AZURE_TENANT_ID       --body $tenantId       --repo $Repo; Check 'secret AZURE_TENANT_ID'
gh secret set AZURE_SUBSCRIPTION_ID --body $SubscriptionId --repo $Repo; Check 'secret AZURE_SUBSCRIPTION_ID'
gh variable set AZURE_RESOURCE_GROUP --body $ResourceGroup --repo $Repo; Check 'variable AZURE_RESOURCE_GROUP'
gh variable set AZURE_LOCATION       --body $Location      --repo $Repo; Check 'variable AZURE_LOCATION'
if ($AlertEmails) {
  gh variable set ARON_ALERT_EMAILS  --body $AlertEmails   --repo $Repo; Check 'variable ARON_ALERT_EMAILS'
} else {
  Write-Host 'No -AlertEmails given: set the GitHub variable ARON_ALERT_EMAILS before the first deploy.' -ForegroundColor Yellow
}

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
  rights           Contributor + RBAC Administrator (data-plane roles only) on $ResourceGroup ONLY
  deploys from     branch $Branch only (environment '$Environment')
  GitHub           environment '$Environment', 3 secrets, 2 variables in $Repo

Nothing outside $ResourceGroup was changed. Tell me when this finished.
"@
