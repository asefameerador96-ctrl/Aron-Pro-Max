#Requires -Version 5.1
<#
.SYNOPSIS
  One-time GitHub governance for Aron (docs/30 s3): creates `main`, protects it, tags the first green deploy, creates the
  `azure-stage` and `azure-prod` environments, and turns on secret scanning where the plan allows it.
  Run by the laptop operator, with the owner's approval, after `gh auth login`. Idempotent; it never deletes anything,
  never force-pushes, and never changes the default branch; on the integration branch it only blocks force-push and deletion.

  Uses only `gh api` with JSON files (no JMESPath: docs/status/laptop.md lesson).
#>
[CmdletBinding()]
param(
  [string]$Repo = 'asefameerador96-ctrl/Aron-Pro-Max',
  [string]$Int  = 'claude/wonderful-thompson-k6ejnf',
  [string]$BaselineSha = 'aac06e0',          # first green deploy through Front Door (CI run 37489113157)
  [string]$BaselineTag = 'baseline-2026-10-06',
  [int]$OwnerId = 252441038,                   # GitHub user id of the owner (required reviewer for prod)
  [switch]$MakePrivate                          # also change the repository visibility from public to private
)
$ErrorActionPreference = 'Stop'
function Step([string]$m) { Write-Host "`n== $m" -ForegroundColor Cyan }
function Api([string]$method, [string]$url, [string]$bodyJson) {
  if ($bodyJson) {
    $f = New-TemporaryFile; Set-Content -Path $f -Value $bodyJson -Encoding utf8
    try { gh api --method $method $url --input $f 2>&1 } finally { Remove-Item $f -ErrorAction SilentlyContinue }
  } else { gh api --method $method $url 2>&1 }
}

if ($MakePrivate) {
  Step 'Make the repository private (it is public today: docs/30 s3a)'
  gh repo edit $Repo --visibility private --accept-visibility-change-consequences
  if ($LASTEXITCODE -ne 0) { throw 'could not change visibility' }
}

Step 'Current state'
$branches = (gh api "repos/$Repo/branches?per_page=100" | ConvertFrom-Json) | ForEach-Object { $_.name }
Write-Host ("Branches: " + ($branches -join ', '))
$intSha = (gh api "repos/$Repo/branches/$Int" | ConvertFrom-Json).commit.sha
Write-Host "INT head: $intSha"

Step 'Create main from the INT head (only if it does not exist)'
if ($branches -contains 'main') { Write-Host 'main already exists: not touched' }
else { Api POST "repos/$Repo/git/refs" (@{ ref = 'refs/heads/main'; sha = $intSha } | ConvertTo-Json) | Out-Null; Write-Host "main created at $intSha" }

Step "Tag the baseline ($BaselineTag)"
$baseFull = (gh api "repos/$Repo/commits/$BaselineSha" | ConvertFrom-Json).sha
$tags = (gh api "repos/$Repo/tags?per_page=100" | ConvertFrom-Json) | ForEach-Object { $_.name }
if ($tags -contains $BaselineTag) { Write-Host 'tag exists: not touched' }
else { Api POST "repos/$Repo/git/refs" (@{ ref = "refs/tags/$BaselineTag"; sha = $baseFull } | ConvertTo-Json) | Out-Null; Write-Host "tag $BaselineTag at $baseFull" }

Step 'Protect main (PR + required CI checks, no force push, no deletion, zero required approvals: one human)'
$protection = @{
  required_status_checks = @{
    strict = $true
    contexts = @(
      'Repository gates (secrets, migrations, contract)',
      'Contract lint',
      'Shared, db and backend (build and tests)',
      'Android debug APKs, unit tests and lint',
      'Release APKs and APK size gate',
      'Web (lint, types, tests, build, e2e)',
      'Container images (build and runtime smoke)',
      'Infra validation (Bicep, workflows)'
    )
  }
  enforce_admins = $false
  required_pull_request_reviews = @{ required_approving_review_count = 0; dismiss_stale_reviews = $false }
  restrictions = $null
  required_linear_history = $false
  allow_force_pushes = $false
  allow_deletions = $false
  required_conversation_resolution = $true
} | ConvertTo-Json -Depth 6
Api PUT "repos/$Repo/branches/main/protection" $protection | Out-Null
Write-Host 'main protection set'

Step "Protect the integration branch ($Int) from force-push and deletion only (lanes keep pushing directly: no PR, no required checks)"
$intProtection = @{
  required_status_checks = $null
  enforce_admins = $false
  required_pull_request_reviews = $null
  restrictions = $null
  allow_force_pushes = $false
  allow_deletions = $false
} | ConvertTo-Json -Depth 4
Api PUT "repos/$Repo/branches/$Int/protection" $intProtection | Out-Null
Write-Host "${Int}: force-push and deletion blocked"

Step 'Environments for the final account: azure-stage (from main) and azure-prod (owner approves, release tags server-v* only). azure-dev is left alone.'
# Names match deploy.yml, promote-prod.yml and the OIDC federated subjects (infra lane). A job that the changes filter skips
# reports success, so no required check stays pending.
$stageBody = @{ deployment_branch_policy = @{ protected_branches = $false; custom_branch_policies = $true } } | ConvertTo-Json -Depth 5
Api PUT "repos/$Repo/environments/azure-stage" $stageBody | Out-Null
Api POST "repos/$Repo/environments/azure-stage/deployment-branch-policies" (@{ name = 'main'; type = 'branch' } | ConvertTo-Json) | Out-Null
Write-Host 'environment azure-stage ready (branch main only)'
$prodBody = @{ wait_timer = 0; reviewers = @(@{ type = 'User'; id = $OwnerId }); deployment_branch_policy = @{ protected_branches = $false; custom_branch_policies = $true } } | ConvertTo-Json -Depth 5
Api PUT "repos/$Repo/environments/azure-prod" $prodBody | Out-Null
Api POST "repos/$Repo/environments/azure-prod/deployment-branch-policies" (@{ name = 'server-v*'; type = 'tag' } | ConvertTo-Json) | Out-Null
Write-Host 'environment azure-prod ready (owner is the required reviewer; tags server-v* only)'

Step 'Secret scanning and push protection (only where the plan supports it)'
try {
  Api PATCH "repos/$Repo" (@{ security_and_analysis = @{ secret_scanning = @{ status = 'enabled' }; secret_scanning_push_protection = @{ status = 'enabled' } } } | ConvertTo-Json -Depth 5) | Out-Null
  Write-Host 'secret scanning and push protection enabled'
} catch { Write-Warning "not available on this plan: $($_.Exception.Message) (gitleaks in CI covers it)" }

Write-Host "`nDone. Next: the lead opens the first promotion PR (INT to main) at the first daily gate." -ForegroundColor Green
