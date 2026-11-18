# Lead review: lane/infra-final at 2f13c5bc3

Reviewed 2026-11-18 by the lead (Opus pass, two lenses, three refuters per blocking finding). Read from code only: nothing deployed, no Azure account touched, pwsh not run.

## workflows-and-dev-safety verdict=fail
SUMMARY: I reviewed this read-only with git only: git diff origin/claude/wonderful-thompson-k6ejnf...2f13c5bc3, plus git show of whole files at 2f13c5bc3. I built, ran and installed nothing, and touched no Azure account.

The dev deploy on the integration branch behaves as before. The trigger is still workflow_run from ci on the integration branch only. The environment still resolves to azure-dev for dev and dev-lite, and the secret and variable names are unchanged. The new workflow-level `actions: read` permission and the new ARON_FINAL_ACCOUNT and ARON_PROD_API_BASE_URL env values are harmless for dev. The replica gate in deploy.sh only unsets ARON_PG_READ_REPLICA for dev, and only stage.bicepparam and prod.bicepparam read that variable. In apps.bicep, serverEnv is still 'dev' for dev and the lab-grant secret is off by default, so the dev apps get the same settings.

No path reaches stage, prod or the final subscription without an explicit owner action:
- stage needs a manual dispatch from main, ARON_FINAL_ACCOUNT=true and an environment with a required reviewer, and deploy.sh refuses it again on its own.
- prod still needs promote-prod, a server-v* tag and the azure-prod reviewer.
- The release-app job now runs in azure-prod but has no id-token permission, so it cannot sign in to Azure.

A missing environment-scoped Azure secret falls back to the repository-wide TEST values, and that fails closed. The test identity only trusts the azure-dev subject, and the final identity has no rights in the test subscription, so sign-in or the scope check fails.

I found no secret echoed or written to an artifact. The lab grant key is generated in a mktemp folder that is deleted on exit and is passed to Key Vault with --file. The what-if guard's only new allowance is an exact `<existing server>-r1` Create, and it still refuses a stray second server.

bootstrap-azure.ps1 only registers providers, creates two resource groups, the identity, its role assignments and federation, and writes environment-scoped GitHub values. It is idempotent to re-run.

One blocker. tools/github-governance.ps1 does not parse. It is now step 1 of the owner's final-account runbook, and the bootstrap for azure-stage and azure-prod refuses to run without it, so the documented move fails at its first command.

Not verified:
- The PowerShell parse failure: pwsh is not installed here, so I found it by reading the code and counting braces, not by running the parser.
- Whether GITHUB_TOKEN with `actions: read` can read environment protection_rules. If it cannot, stage and prod fail closed.
- Whether a duplicate POST of a deployment-branch policy makes the governance re-run end in its error summary.
- Live Azure behaviour, Bicep compilation and the check_infra tests.

### BLOCKING upheld=3/3: tools/github-governance.ps1 does not parse, and the final-account runbook now depends on it
WHERE: tools/github-governance.ps1 line 116 (a bare `}` after the AUD-DG-08 comment); docs/setup/move-to-final-account.md section 1; infra/bootstrap-azure.ps1 step 6 (the else branch)
WHY: Commit 4c899ca110 removed the `foreach ($old in 'staging','prod') {` loop but left its closing brace, so the script has one more `}` than `{`. PowerShell parses the whole script before running it, so a stray top-level `}` stops it with 'Unexpected token }' and nothing runs. The defect predates this lane, but this lane makes the script the mandatory first owner step of the move and edits the lines right next to the brace. bootstrap-azure.ps1 now throws for azure-stage and azure-prod unless those environments already have a required reviewer, and only this script creates them. check_infra.py only does substring checks on the file and never parses it, so CI does not catch it.
SCENARIO: The owner follows move-to-final-account.md section 1 and runs `pwsh tools/github-governance.ps1`. It fails at once with a parse error, so azure-stage and azure-prod are never created with a reviewer and the app-v* tag policy is never added. The next command, `bootstrap-azure.ps1 -Environment azure-stage ...`, still creates resources in the final account first: the resource groups rg-aron-stage and rg-aron-scope-probe, the identity, its role assignments and federation. It then throws 'run tools/github-governance.ps1 first'. The documented move cannot get past step 1 without someone editing the script.

LOWS:
- bootstrap-azure.ps1 checks the GitHub environment's reviewer only in step 6. Steps 1-5 have already created resources in the final subscription by then: provider registrations, the resource groups, the app and service principal, role assignments and federated credentials. A run before governance therefore leaves those behind and then throws. Re-running is idempotent, but the reviewer check should come before step 1.
- bootstrap-azure.ps1 keeps the dev defaults (-ResourceGroup rg-aron-dev, -AppName sp-aron-github-dev) even when -Environment is azure-stage or azure-prod. If the owner leaves those out, the script creates rg-aron-dev in the final subscription and writes AZURE_RESOURCE_GROUP=rg-aron-dev into azure-stage. If the final subscription shares a tenant with the test one, it also reuses the test deploy identity and gives it rights and an azure-stage trust in the final account. The defaults should follow -Environment, or the script should refuse dev names for a final environment.
- Environment-scoped values fall back silently to repository-wide ones. Until the owner sets them in azure-stage or azure-prod, the stage and prod deploys pick up the repository-wide TEST FCM_SERVICE_ACCOUNT_JSON, MAPS_WEB_KEY and PLAY_INTEGRITY_SERVICE_ACCOUNT_JSON (and ARON_BUDGET_AMOUNT and ARON_NAME_SUFFIX) and copy the test Firebase account into the final Key Vault. release-app picks up the test MAPS_ANDROID_KEY and GOOGLE_SERVICES_JSON. The runbook orders the stage and prod deploys (section 2) before the Google keys (section 4), so this is the expected path. The release-app.yml comment says the test keys are 'never mixed in', which is not true. Nothing fails closed when the azure-prod values are missing.
- The azure-prod environment now admits app-v* tags. The prod deploy identity's OIDC subject is environment:azure-prod, so any job on any app-v* tag that names azure-prod and asks for id-token: write can sign in as the prod deployer once the reviewer approves. The owner may approve such a job thinking it is an APK release. This is the same class of exposure as server-v*, but it is wider, and tags are not shown to be protected.
- In bootstrap-azure.ps1, under Windows PowerShell 5.1 with ErrorActionPreference=Stop, `gh api ... 2>$null` turns a 404 on stderr into a terminating NativeCommandError. The run then stops with a cryptic error instead of the friendly 'run governance first' message. It still fails closed, and the runbook calls the script with `.\` rather than pwsh.
- In seed-secrets.sh, if writing aron-lab-grant-public fails after aron-lab-grant-key has been stored, later deploys see the private key as present and never create the public half. The operator then has nothing to build into the APK.
- In check_infra.py, ALLOWED_ROLES now includes Storage Blob Data Reader so the apsis-landing tests pass. That set also guards main.json and naming.bicep, so a pipeline template could now grant Reader and pass the test, but the bootstrap's RBAC condition would refuse it at deploy time. The Reader exception should stay inside the ApsisLanding tests.
- reset-to-profile.sh now skips any storage account tagged component=apsis-landing in whatever group it checks, including rg-aron-dev. A wrongly placed or wrongly sized landing account in the test account would then go unflagged.
- The 'Explain a failed sign-in' message in deploy.yml still names only the azure-dev environment and the default bootstrap, which misleads the owner when a stage or prod sign-in fails.
- The runbook step 'then remove the repository-wide values' does not say which values. It must not remove MAPS_*, GOOGLE_SERVICES_JSON or FCM (ci.yml and the dev APKs still read them), nor ARON_FINAL_ACCOUNT, ARON_PROD_API_BASE_URL or ARON_DEPLOY_FREEZE_DHAKA (repository-only by design).
- deploy.sh guards stage and prod only with the ARON_FINAL_ACCOUNT boolean and never checks that the signed-in subscription is the final one. A person running deploy.sh by hand, with that variable exported and az pointed at the test subscription, is stopped only by the profile and what-if checks further down.

## bicep-landing-and-docs verdict=fail
SUMMARY: I reviewed the diff origin/claude/wonderful-thompson-k6ejnf...2f13c5bc3 with git only, reading apsis-landing.bicep, apps.bicep, naming.bicep, the params, bootstrap-azure.ps1, deploy.sh, seed-secrets.sh, whatif-guard, reset-to-profile, deploy.yml, github-governance.ps1, the four owner documents and the DECISIONS additions.

**What holds:**
- apsis-landing.bicep is private as stated:
  - shared keys off and OAuth by default, so no account SAS can be made;
  - anonymous access off, TLS 1.2, HTTPS only, SFTP and local users off, copy scope AAD;
  - network default Deny with IP and subnet rules only;
  - soft delete 7 days on blobs and containers, plus the lifecycle rule. Versioning is off, which matches the docs (they never claim versioning).
  - Roles are only built-in role ids, at container scope, three kinds. There are no secret outputs. No private endpoint is built, which matches the docs (it is flagged OPEN).
  - The pipeline identity is limited to rg-aron-<env> by the bootstrap, so the claim that CI cannot read the landing group holds.
  - No workflow or deploy.sh references the template; check_infra asserts this.
- Dev is unchanged in effect:
  - labGrantEnabled defaults to false and no dev param file sets it;
  - serverEnv is 'dev' for both dev and dev-lite;
  - the replica gate and the lab-grant seeding run only for stage and prod;
  - the dev deploy.yml environment expression and the integration-branch trigger are unchanged.
- Stage and prod go through three ARON_FINAL_ACCOUNT checks plus a required-reviewer check that fails closed. A stage job that falls back to the repository-wide TEST secrets cannot sign in, because the test identity trusts only environment:azure-dev.
- No new subscription id, tenant id, email, phone or personal name was added; the only GUIDs are built-in role ids. The old test subscription id is removed from the default but stays in git history, which the docs say openly.

**Three blocking problems:**
1. The stage step in the runbook cannot be run as written: main is still the 2026-10-07 commit.
2. The Apsis landing template has no guard against the TEST account.
3. The lab-grant public-key read step will be refused by Key Vault.

**Not verified:** nothing was built, linted or run, so bicep compile, what-if and check_infra are taken on trust from the lane's log. I made no Azure or live GitHub calls, so these are unchecked:
- the environments API answer to github.token;
- the actual required checks on main;
- the AzCopy PipeBlob and --put-md5 behaviour on piped uploads;
- whether the lifecycle prefix and diagnostic category group are accepted by the live API;
- whether a service endpoint works on a Microsoft.App-delegated subnet;
- whether the PKCS#8 format matches tools/make-lab-grant-key.sh (that file is not on INT yet).

I also did not review check_infra.py beyond its Apsis and role-set lines, and I did not review the release-app.yml or promote-prod.yml diffs.

### BLOCKING upheld=3/3: Stage deploy (and promote-prod) step cannot be run: main lacks the code and no step says to promote it first
WHERE: docs/setup/move-to-final-account.md s2.2 and s2.5; docs/status/infra-final.md s3(a) steps 10 and 13; tools/github-governance.ps1 ('main already exists: not touched')
WHY: origin/main is c433001a4f (2026-10-07). Its deploy.yml offers only [dev, dev-lite, prod]: no stage, no ARON_FINAL_ACCOUNT or reviewer check for stage, and no replica gate or lab-key seeding in deploy.sh. The runbook tells the owner to run deploy from branch main with environment stage. A server-v* tag on a soaked main commit is also required. Nothing in the owner list says main must first be brought up to the integration branch through the daily gate pull request, and that pull request is itself on hold until the owner approves the release flow (rule 11). Re-running github-governance.ps1, which step 6 requires, leaves main alone.
SCENARIO: The owner finishes steps 1 to 9, then opens Actions > deploy > Run workflow on main. There is no 'stage' choice, so step 10 cannot be done, and step 13 (tag and promote) has nothing soaked to tag. The documented order dead-ends. If the owner then dispatches from the integration branch, the stage guard refuses it (correctly), and the docs give no way forward.

### BLOCKING upheld=2/3: Apsis landing template has no test-account guard; only a placeholder 'az account set' stands between the dump and the TEST subscription
WHERE: infra/apsis-landing.bicep (no subscription or ack parameter, no check); docs/requests/apsis-dump-landing.md s3
WHY: Every other final-account path is gated (ARON_FINAL_ACCOUNT three times, environment reviewers). This template is run by the laptop operator session, which runs dev operations against the TEST subscription day to day. Its only safeguard is a first line 'az account set --subscription <final-subscription-id>'. Nothing confirms the active subscription before 'az group create' and 'az deployment group create'. Examples of a guard: a required expectedSubscriptionId parameter compared with subscription().subscriptionId, or a documented 'az account show' check against a known name or tag. The rule is that the dump may enter only the final account's private storage.
SCENARIO: The laptop shell is still on the test subscription from a dev task, and the placeholder line is skipped or mistyped. The operator creates rg-aron-apsis-landing and the storage account in the TEST account, the outputs give a dump URL, and the owner runs azcopy, uploading 52 GB of vendor history with personal data and credential tables into the test account. That is the exact boundary the rules forbid.

### BLOCKING upheld=3/3: Lab-grant public key read step fails: nobody but the pipeline identity has data-plane rights on the RBAC Key Vault
WHERE: docs/setup/move-to-final-account.md s5.4; docs/status/infra-final.md s3(a) step 12 ('az keyvault secret show --vault-name <kv> --name aron-lab-grant-public')
WHY: keyvault.bicep uses enableRbacAuthorization. identities.bicep grants Secrets User only to the four app identities and Secrets Officer only to the deploying service principal. Subscription Owner is a control-plane role and gives no right to read secret values. The docs neither grant a role to the owner or operator nor publish the public half anywhere else, such as the deploy summary. The public half is not a secret, so it could simply be printed in the deploy summary.
SCENARIO: After the first stage deploy, the operator runs the documented command signed in as the owner and gets Forbidden (no secrets/get permission). The per-environment public key cannot be built into the APK, so release builds honour no lab grant and the ADB device walks stay blocked, and the owner step reads as broken.

LOWS:
- The landing deploy command puts an optional parameter in square brackets, '[logAnalyticsWorkspaceId="$(...)"]', inside a bash block that is meant to be copied. Pasted as is, the brackets become a literal argument and az refuses it. Either show two variants or drop the brackets.
- Path B uses 'curl --ftp-ssl ftp://...'. --ftp-ssl (now --ssl) is opportunistic and silently falls back to plain FTP, which sends the FTP password in clear text. Use --ssl-reqd (or ftps://) so the step refuses instead.
- Ordering conflict: docs/status/infra-final.md step 8 says to move the TEST values into azure-dev BEFORE the step-7 bootstrap. move-to-final-account.md s1 puts it after the bootstrap. Either order works because environment values win over repository values, but the two owner documents disagree.
- infra-final.md step 8 tells the operator (the laptop agent session) to run 'gh secret set ... --body <value>' with values the owner 're-supplies'. That puts the client, tenant and test subscription ids into a session transcript and shell history. The runbook's interactive prompt ('paste when asked') is the better wording, and these are identifiers rather than credentials.
- bootstrap-azure.ps1 (unchanged lines) still prints 'Using subscription <id>' and 'Tenant: <id>' to the operator session's output. Same identifier-in-transcript concern.
- Re-running tools/github-governance.ps1 (step 6) also re-PUTs main's branch protection with the script's 8 required checks. The status file itself says the live list has more (e2e and analytics shards), so step 6 can silently drop required checks on main. Tell the operator to re-read and update the list first.
- The quota arithmetic is loose. Staging-lite 'adds 6 and fits in 40' only by using up the 8 vCores of headroom. The quota document counts web as 6 x 0.5 replicas while the status file says web 3.
- Honest wording: 'so the move cannot fail on schema drift' (infra-final.md s2, 0.4 row) breaks the 'never cannot-fail' rule. Prefer 'is not exposed to schema drift'. Also, 'any AccountKey or SAS row is impossible by configuration' would be more accurate as 'should not appear while shared keys stay disabled'.
- networkAcls bypass 'AzureServices' on the landing account is not mentioned in the landing doc's network row. It still needs Entra, but the doc says only owner IPs and the loader subnet are allowed.
- If logAnalyticsWorkspaceId points at log-aron-prod, the blob access logs (owner IP, blob names) outlive the group deletion for that workspace's retention. The doc mentions this only indirectly ('when created there').
- The 60-day lifecycle rule could delete the dump in the middle of a long parallel run. It is flagged only as a question to the migration lane, with no owner-visible warning.
- Stage now runs ARON_ENV=prod, so any backend behaviour keyed on prod (alerts, strictness, integrity) also applies in staging. This is intended, but it is not listed as a stage-specific consequence for the drills.
