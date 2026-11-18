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


## Delta review 2f13c5bc3..0804cf942 (rounds 2 and 3), 2026-11-18

Read from code and scratch copies with a fake az; nothing deployed, no Azure account touched.

### prior-blocking-and-guards verdict=fail
SUMMARY: Read-only review of 0804cf942 (delta 2f13c5bc3..0804cf942 and the whole lane against the integration branch). I used git show and diff, plus python3 and bash on scratch copies under /tmp/claude-0/-home-user-Aron-Pro-Max/a5c3c45a-b89c-5768-9d5f-6bc05c04fc63/scratchpad/infra-r3-guards/. Nothing was checked out, built or installed, and az was never run.

The four prior blockers:

(1) governance.ps1: FIXED. The stray brace is gone. psbalance.py reports ok on every .ps1 file in the repo (10 files, tools/checks included) and reports "line 116: unexpected '}'" on the old 2f13c5bc3 copy. check_infra runs it on infra/*.ps1 and tools/*.ps1. psbalance is sound for the cases asked about:
- a stray brace after a comment line is caught;
- a brace inside a comment, a here-string (LF and CRLF) or a block comment is correctly ignored.
It has small gaps, listed in the lows.

(2) Runbook dead end: FIXED. Step 0 of move-to-final-account.md is honest: main is the 2026-10-07 baseline, and stage, promote-prod and the stage/prod workflows wait for the owner's approval and the first promotion. Governance, bootstrap, quotas, a laptop what-if (validate.sh, read-only) and the landing are listed as possible now, and none of them needs main.

(3) Apsis test-account guard: MOSTLY FIXED. apsis-landing.sh checks five things before anything is created:
- the --final-subscription-id format;
- the exact --ack phrase;
- signed-in subscription == typed id, case-insensitive. A failed az call ends in die, and empty or CR-suffixed output fails closed (tested with a fake az);
- the subscription does not hold rg-aron-dev;
- it holds rg-aron-stage or rg-aron-prod.

The Bicep expectedSubscriptionId check is real in design. The int() of a non-number sits inside the storage name, which the role-assignment guids and diagnostics depend on. I did not compile it, so whether ARM rejects it at validation (lazy if()) is unverified.

Remaining gaps:
- The rg-aron-dev probe fails open on an az error. I demonstrated this with a fake az: deploy went ahead.
- The template check only compares two values the operator supplies. A direct `az deployment group create` with the TEST id typed in passes.
- One new blocker: the landing doc's own "remove the owner IP" step cannot be done through the wrapper.

(4) Lab-grant public key: FIXED for stage and prod. Only aron-lab-grant-public is read for the deploy summary. The private key is never printed. The seed writes the public half first and heals a missing public half from the private key. However, the stage-lite profile added in this delta enables the lab grant but is never seeded (blocking below).

deploy.sh subscription check: fails closed when AZURE_SUBSCRIPTION_ID is missing, az is not signed in, or the ids differ. deploy.yml now passes the environment secret. The check is circular, though: it compares against whatever the bootstrap wrote, and the bootstrap never refuses the TEST subscription.

Bootstrap order and names: FIXED. The reviewer check runs before az account set. The names derive from -Environment. Test names (-dev) are refused for final environments. No subscription or tenant id is printed.

Dev deploy from the integration branch: unchanged. workflow_run still resolves to azure-dev; AZURE_SUBSCRIPTION_ID is only used for stage and prod; labGrantEnabled is false for dev.

Not verified:
- Bicep compile and ARM validation of the guard;
- Container Apps behaviour with a missing Key Vault secret reference (I expect the revision to fail to provision);
- az CRLF output under Git Bash on the Windows laptop;
- live GitHub environment state, and whether a previous governance run already put app-v* on azure-prod;
- the check_infra test run itself;
- real pwsh parsing.

#### BLOCKING upheld=3/3: stage-lite deploy enables the lab grant but never seeds its key: blocker 4 is not fixed for the new stage-lite profile
WHERE: infra/scripts/seed-secrets.sh line 99 (`[[ "${PROFILE:-}" == stage || "${PROFILE:-}" == prod ]]`); infra/deploy.sh line 450 passes PROFILE=stage-lite; infra/params/stage-lite.apps.bicepparam line 42 (`param labGrantEnabled = true`); apps.bicep lines 171-174 and 392 (Key Vault reference to aron-lab-grant-key); deploy.sh line 883 uses ENV_NAME
WHY: This delta adds stage-lite (deploy.yml choice, guards and params) and docs/status/infra-final.md D3 recommends it as the staging size. Every other stage check keys on ENV_NAME (stage), but the lab-key seeding keys on PROFILE, so for stage-lite it is skipped. The apps template still sets ARON_LAB_GRANT_KEY from a Key Vault secret that does not exist.
SCENARIO: The owner says yes to D3 and runs deploy from main with environment stage-lite as the first final-account staging deploy. seed-secrets.sh creates the JWT, session and MFA keys but neither aron-lab-grant-key nor aron-lab-grant-public. The apps stage then deploys ca-aron-stage-api with a Key Vault secret reference to a missing secret. I expect (not verified live) the revision to fail to provision and the deploy to fail. If it gets as far as the summary, the summary shows 'Lab grant public key (stage) | NOT READABLE', so the owner again has no public half to build into the APK. Fix: test ENV_NAME (or PROFILE%-lite) in seed-secrets.sh, and add a check_infra case for stage-lite.

LOWS:
- apsis-landing.sh fails open on an az error in the TEST-account probe. `[ "$(az group exists --name rg-aron-dev 2>/dev/null)" = true ]` treats an az error (throttling, expired token, network) as 'no rg-aron-dev'. With a fake az (rg-aron-dev probe exits 1, rg-aron-stage true, typed id = signed-in id), the deploy went on to `az group create` and `az deployment group create`. Exploiting it needs the TEST id typed by mistake, a TEST subscription that holds rg-aron-stage or rg-aron-prod, and a transient error, but the check should be `out=$(az group exists ...) || die` followed by an exact true/false match.
- apsis-landing.sh checks the default subscription once and never pins later calls. `az group create`, `az deployment group create`, `az storage account show` and `az ad signed-in-user show` run without `--subscription "$final_sub"`. The laptop operator session shares ~/.azure with other dev tasks, so a concurrent `az account set` to the test subscription between the guard and the create would create the empty, tagged rg-aron-apsis-landing in the TEST account. The Bicep check would then stop the storage account. Pass --subscription on every call.
- The Bicep expectedSubscriptionId check only compares two values. It cannot tell test from final, so a direct `az deployment group create -f infra/apsis-landing.bicep -p expectedSubscriptionId=<test id> ...` deploys the landing into the TEST account. Only the wrapper detects rg-aron-dev. This is acceptable as defence in depth, but the doc's wording 'The Bicep template repeats the first check' should not be read as protection against direct use.
- bootstrap-azure.ps1 never refuses the TEST subscription for azure-stage or azure-prod, and deploy.sh's new check is circular: it compares the signed-in subscription with the AZURE_SUBSCRIPTION_ID that the bootstrap wrote. If the owner passes the test subscription id with -Environment azure-stage, the bootstrap creates rg-aron-stage, sp-aron-github-stage, its roles and federation in the TEST account and writes the test id into azure-stage. Once ARON_FINAL_ACCOUNT=true, a prod-shaped stage deploy then passes every subscription check in the test account; only the what-if and profile checks remain. Suggested fix: the bootstrap could refuse any subscription that holds rg-aron-dev when -Environment is not azure-dev, the same rule as the landing wrapper.
- On the Windows laptop under Git Bash, az.cmd's tsv output may end in \r. The wrapper then refuses even the correct final subscription. This fails closed (I tested a CR-suffixed id), but the owner step would fail with a misleading 'not the one you named' message. Strip \r, or state in the doc that WSL or Linux bash is required. Not verified on Windows.
- psbalance.py gaps (all false passes are rare): a `#` inside a bare word (`foo#bar }`) is treated as a comment, so a stray `}` after it is missed; braces inside `$( )` within a double-quoted here-string are not checked; a here-string header with trailing whitespace is not recognised as one (harmless in practice). Curly quotes give a false failure, which fails closed. The check_infra glob is non-recursive (tools/checks/*.ps1 is not covered; those files are balanced today).
- docs/setup/move-to-final-account.md line 63 still says governance makes 'azure-prod (server-v* and app-v* tags ...)', and the bootstrap-azure.ps1 parameter comment for -Branch says the same. Since this delta, app-v* belongs to the app-release environment. The stale comments may lead the owner to expect, or approve, app-v* jobs in azure-prod.
- The governance script never deletes. If the previous (2f13c5bc3) version was ever run live, azure-prod keeps its app-v* deployment-branch policy, and the 'no app tag can reach the prod OIDC subject' claim in D-FINAL-INFRA-11 is then false until the owner removes it by hand. Tell the owner to check the azure-prod tag policies.
- docs/requests/apsis-dump-landing.md s3: the copyable deploy command includes --pe-subnet-id and --dns-zone-id, which exist only after the stage or prod main.bicep deploy (after step 0). Step 0 lists the landing as doable NOW, and the wrapper treats the two ids as optional. The doc should show the pre-step-0 variant without them and say the endpoint is added by a later re-run (which needs the close/re-run mode from the blocking finding).
- (refuted 3/3) The landing doc's 'remove the owner IP after upload' step cannot be done through the wrapper that is declared the only way: The wrapper requires --owner-ip and always passes uploaderIpAddresses=["<ip>"]. The documented hardening step, which leaves only the private endpoint, therefore has no supported path. The operator must either leave the owner's public IP allowed on the dump account, or run `az deployment group create` on apsis-landing.bicep directly. A direct run skips the wrapper's rg-aron-dev and rg-aron-stage/prod checks and keeps only the template's check, which compares two operator-supplied values.

### round2-new-surface verdict=fail
SUMMARY: Read-only review of lane/infra-final at 0804cf942. I fetched with git only, compared 2f13c5bc3..0804cf942 and the whole lane against the integration branch, and read the files with git show. The only things I ran were python3 -I infra/tests/psbalance.py on scratch copies of github-governance.ps1 and bootstrap-azure.ps1 (both "ok") and a two-line bash check of the seed-secrets profile test. I did not run az, Bicep, check_infra, pwsh or any workflow, and I touched no Azure or GitHub state.

What holds:
- **Dev is unchanged.** dev keeps privateNetworking=true with no privateEndpointSubnetPrefix, so `concat([...], [])` leaves the subnet list, outputs [0] and [1], and every output deploy.sh reads exactly as before. The new outputs are empty for dev. dev-lite has no VNet at all. deploy.yml still picks azure-dev for dev and dev-lite and the integration-branch trigger is the same. ops-diagnose for dev still uses azure-dev, still runs from the integration branch only, and its default prefixes come out the same (caj-aron-dev-migrate, ca-aron-dev-worker).
- **Address plan.** snet-pe 10.4x.2.32/27 sits inside the /16 and overlaps neither snet-aca nor snet-pg. It is added after the existing subnets, and the blob DNS zone and its VNet link exist only when the prefix is set.
- **Apsis landing.** It now has the guard that was missing: apsis-landing.sh checks the typed subscription id against the signed-in one, requires the ack phrase, refuses a subscription that holds rg-aron-dev, and requires rg-aron-stage or rg-aron-prod, all before `az group create`. The Bicep `expectedSubscriptionId` check stops the deployment before the storage account exists. The private endpoint and its DNS zone group are created only when given ids.
- **stage-lite against an existing prod-shaped stage.** It cannot shrink or replace the database. whatif-guard refuses any change to sku, storage or HA, so the infra stage stops before anything changes. Incremental mode never deletes the -r1 replica. reset.yml stays dev only.
- **Loader job.** It exists only when both the image and its own DB secret name are set, and no profile sets them. deploy.yml does not forward the ARON_APSIS_* variables, so a loader can only be deployed by a person exporting them. It has its own identity, its only secret is a Key Vault reference, it has no retry and a 12 h timeout.
- **app-release.** It has no id-token permission and no federated credential, and azure-prod no longer admits app-v* tags.
- **check_infra.py.** The Storage Blob Data Reader exception now applies to apsis-landing.bicep only. Removed assertions were replaced by equal or stricter ones (for example the exact options list for deploy.yml). I found none silently weakened.

Three blocking problems:
1. A first stage-lite deploy fails at the apps step, and stage-lite is the recommended default for D3.
2. The whatif and ops-diagnose workflows can never run for prod, although the runbook offers them.
3. ops-diagnose for stage or prod can put the Apsis loader's raw console output, or other unfiltered logs, into a public job log.

Not verified:
- Bicep compilation, including how Bicep and ARM handle the int() trick.
- That Container Apps fails provisioning on a missing Key Vault secret. This is high confidence but not run.
- That ARM returns VNet subnets in the order they were created (the existing [0]/[1] outputs and the new [2] rely on it).
- That GitHub refuses a branch run for an environment whose policy lists only a tag. This is standard behaviour, not tested here.
- What the loader actually logs: its code is not on this branch.
- Live cost of stage-lite.

#### BLOCKING upheld=3/3: stage-lite never gets its lab-grant key pair, so its first apps deploy fails and the owner gets no public key
WHERE: infra/scripts/seed-secrets.sh line 99 (`if [[ "${PROFILE:-}" == stage || "${PROFILE:-}" == prod ]]`) against infra/params/stage-lite.apps.bicepparam (`param labGrantEnabled = true`) and infra/apps.bicep lines 171-174 (api secret ref lab-grant-key -> aron-lab-grant-key); deploy.sh line 450 passes PROFILE=stage-lite
WHY: deploy.sh hands the profile (stage-lite), not ENV_NAME, to seed-secrets.sh, which matches only the exact words stage or prod. A bash check confirms that stage-lite skips the key generation. The stage-lite apps file still turns labGrantEnabled on, so the api container app references a Key Vault secret that was never created. check_infra.py pins the faulty condition at lines 2660 and 2887, so CI cannot catch it.
SCENARIO: The owner accepts D3 (staging-lite, the recommended default in docs/status/infra-final.md) and dispatches deploy with stage-lite on a freshly bootstrapped rg-aron-stage. main.bicep succeeds and seeding skips the lab-grant step. apps.bicep then creates ca-aron-stage-api with a Key Vault reference to aron-lab-grant-key, which does not exist, so provisioning of the revision fails and the deploy goes red. If it got further, the summary row would read 'Lab grant public key (stage): NOT READABLE: the seeding step did not run', and no stage APK could honour a lab grant. The workaround (one deploy with the prod-shaped stage file first) is documented nowhere.

#### BLOCKING upheld=3/3: whatif and ops-diagnose can never run for prod: GitHub refuses main and the guard refuses a tag
WHERE: tools/github-governance.ps1 (azure-prod deployment-branch policy: only `server-v*`, type tag); infra/scripts/final-account-guard.sh (`stage|stage-lite|prod` requires `refs/heads/main`); .github/workflows/whatif.yml and ops-diagnose.yml (prod -> environment azure-prod); docs/setup/move-to-final-account.md s0 table ('the whatif and ops-diagnose workflows for stage and prod (they too run from main only)'); DECISIONS D-FINAL-INFRA-09
WHY: azure-prod has custom deployment policies with a single tag rule, so a job on refs/heads/main that names azure-prod is rejected by GitHub before any step runs. From a server-v* tag the environment is admitted, but final-account-guard.sh refuses because the ref is not main. No ref passes both checks. The runbook and D-FINAL-INFRA-09 present a prod what-if 'after the bootstrap of an environment' as an owner action.
SCENARIO: After bootstrapping azure-prod, the owner opens Actions > whatif > Run workflow on main with environment prod to preview the first prod deploy. GitHub fails the job: 'Branch main is not allowed to deploy to azure-prod due to environment protection rules'. Retrying from the server-v1.0.0 tag passes the environment, then the guard step fails with 'prod runs only from main'. The documented prod preview and prod diagnostics are a dead end, and the owner reaches the first prod deploy without the promised what-if.

#### BLOCKING upheld=3/3: ops-diagnose for stage or prod prints unfiltered job console logs, chosen by free text, into a PUBLIC job log, including the Apsis loader
WHERE: infra/scripts/diagnose.sh, section 'console log of job executions starting with $JOB_PREFIX' (no line filter, 400 lines of up to 400 characters, also copied into GITHUB_STEP_SUMMARY), the system-log query (`* has '$JOB_PREFIX'`) and the PostgreSQLLogs query (ERROR lines kept 'as the server wrote it'); .github/workflows/ops-diagnose.yml (job_prefix, since and until are free-text inputs pasted into KQL between single quotes; stage and prod now allowed); infra/params/stage.apps.bicepparam and prod.apps.bicepparam (loader caj-aron-<env>-apsis-loader can exist)
WHY: The workflow header promises 'never a secret ... drops DETAIL and parameter lines', but only the worker query is filtered. The job query prints whatever any job whose name starts with the prefix wrote. Nothing excludes the apsis-loader job, and a driver exception text (COPY CONTEXT lines, 'invalid input syntax ... "<value>"', stack traces holding row fields) is outside the loader's 'counts only' contract. PostgreSQL ERROR lines can also carry values, for example `invalid input syntax for type ...: "..."` or `value too long`. Because the inputs are pasted into KQL inside single quotes, a value such as `x' or 1==1 or exec startswith '` returns every app's console log, including the api's. Until this lane, ops-diagnose could reach only dev, which holds synthetic data; now it reaches the final account, and the repository is public.
SCENARIO: A rehearsal load of the dump runs in stage (stage.apps.bicepparam supports the loader, and stage is in the final account, where the dump may go). The loader hits a bad row and pgjdbc logs `ERROR: invalid input syntax for type bigint: "<a customer's phone or NID>"` together with the COPY context line. An agent lane dispatches ops-diagnose on main for stage, with job_prefix caj-aron-stage-apsis-loader, to 'see why the loader failed', and the owner approves the azure-stage run. The raw lines from the vendor dump are written to the public Actions log and step summary, which anyone can read. Prod gets the same exposure as soon as blocker 2 is fixed by admitting main on azure-prod.

LOWS:
- whatif.yml is not fully read-only: it runs infra/scripts/scope-check.sh, which attempts `az group create` of a probe group (deleted again only if creation succeeds). The test that forbids write verbs scans only the text of whatif.yml, so a write is reachable through the script. RBAC normally refuses it, but on an over-privileged final identity a what-if run would create and delete a group in the final subscription, which contradicts 'changes nothing'.
- whatif.yml does not do the live-value preparation deploy.sh does. It sets no ARON_PG_PRIMARY_ZONE or ARON_PG_STANDBY_ZONE, no ARON_PG_READ_REPLICA gate, no ARON_BUDGET_START_DATE (the default '2026-10-01' is before the current month and Azure refuses that on create) and no ARON_DEPLOY_BUDGET. After a failover drill it reports 'a real deploy would stop', and on a fresh stage group it shows a replica create and may fail on the budget date. Both differ from what deploy.sh actually does.
- Apsis loader rights are not minimal. Key Vault Secrets User is granted on the whole vault, so the identity can read the admin DB URL, the lab-grant private key, the FCM and MFA keys and every other secret, and AcrPull covers the whole registry. A secret-scoped role assignment is possible. apsisLoaderDbSecretName also accepts any name, the admin URL's included, so 'never shipped on the admin database URL' rests on convention only. The test named 'only pulls and reads its secret' does not check scope, and test_role_assignments_are_least_privilege only walks main.json, not apps.json.
- apps.bicep says the loader is 'Final account only' but has no environmentName or serverEnv guard. Only the absence of the ARON_APSIS_* reads in dev.apps.bicepparam keeps it out of dev. A one-line assert (environmentName != 'dev') would make it hard.
- Removing the owner's IP from the landing account is documented three different ways. apsis-landing.sh requires --owner-ip on every deploy and always sends uploaderIpAddresses=[owner_ip], yet its own warning says 're-run deploy with the owner address removed'. Section 5.2 of the landing document says re-run with uploaderIpAddresses='[]', and section 3 says that is not offered and to use `az storage account network-rule remove`. Worse, the later re-run that adds --loader-principal-id (that id exists only after the apps deploy) must pass --owner-ip again, which silently re-opens the IP rule that was removed by hand. Every request still needs Entra, so this is not an exposure.
- The landing private endpoint takes the landing group's --region. If that differs from the VNet region, private-endpoint creation fails; the wrapper does not derive the region from the subnet.
- The network outputs index vnet.properties.subnets[0], [1] and now [2] by position. If ARM ever returns subnets in another order (for example by name, which would put snet-pe before snet-pg), postgresSubnetId would point at snet-pe. Using resourceId(..., 'snet-pg') or 'snet-pe' would remove the reliance. Unverified.
- snet-pe has no NSG. This matches the comment (network policies at their default), but it is the only subnet without one.
- stage-lite parameter comments are copied from stage and now wrong: 'D8ds_v5 (IOPS cap 12,800) on SSD v2 1,024 GiB' sits above the D2ds_v5/128 lines, 'deploy.sh and deploy.yml do not offer it', 'docs/18 s3.1 full fleet: api 3..30' sits above 1..3, and 'D8ds_v5 behind PgBouncer'. logDailyQuotaGb stays 8 (the prod-shaped cap; at the cap Log Analytics alone could approach the stated ~708 a month), and the budget alert is 1,200, not ~708.
- deploy.yml concurrency groups are deploy-stage and deploy-stage-lite, so both can run at once against rg-aron-stage. Only deploy.sh's resource-group lock serializes them.
- The documents contradict each other: move-to-final-account.md s6 item 2 still says ops-diagnose is 'hard-wired to azure-dev on purpose and never touch[es] the final account', while s0 and D-FINAL-INFRA-09 make it environment-aware for stage and prod.
- diagnose.sh picks the PostgreSQL server with `[?starts_with(name,'psql-aron-')] | [0]`. In stage or prod that can be the -r1 replica or a drill restore rather than the primary.
- release-app.yml does not check that the app-release environment exists with a reviewer, unlike deploy.yml, whatif.yml and ops-diagnose.yml. If governance has not run, GitHub creates app-release unprotected on the first app-v* tag, and the release is built with the repository-wide TEST Maps and Firebase values without approval.
- Tags are not protected. A lane can push any server-v* tag and dispatch a modified workflow from it into azure-prod; only the owner's approval stands in the way. This is the same class as before the round, and it now also applies to the new whatif and ops-diagnose files on such a tag.


## Delta review 0804cf942..5406dd986 (round 4), 2026-11-18

### fixes verdict=pass
SUMMARY: Read-only review of 5406dd986 (delta 0804cf942..5406dd986, plus the whole lane against the integration branch). Nothing was checked out, built or installed, az was never run, and no Azure or GitHub state was touched. I used git show and diff, plus `git archive` into /tmp/claude-0/-home-user-Aron-Pro-Max/a5c3c45a-b89c-5768-9d5f-6bc05c04fc63/scratchpad/infra-r4-verify/. In that copy I ran apsis-landing.sh and diagnose.sh against a fake az, and five of the new or changed check_infra tests. All five pass.

**The three earlier blockers are fixed:**
1. **stage-lite lab-grant key.** seed-secrets.sh now matches stage, stage-lite and prod. The new per-profile test is real: it parses the seed condition and compares it with `labGrantEnabled` in each compiled `<profile>.apps.parameters.json`. stage, stage-lite and prod are true; dev and dev-lite are unset. deploy.sh passes `PROFILE=stage-lite`.
2. **prod dead end.** whatif.yml and ops-diagnose.yml no longer offer prod. Their environment expressions can no longer resolve to azure-prod. final-account-guard.sh refuses prod outright, and an unknown profile also fails closed. The laptop path for the what-if is given: `AZURE_RESOURCE_GROUP=rg-aron-prod ARON_ENV=prod infra/validate.sh`, in whatif.yml and in the guard's message. validate.sh only runs `az deployment group what-if`.
3. **Public log exposure.** For stage, the workflow forces `mode=metadata` and fixed names (caj-aron-stage-migrate and ca-aron-stage-worker), so the free-text `job_prefix` and `worker_app` inputs are ignored. `since` and `until` still reach diagnose.sh, but only as quoted az arguments; no KQL runs in metadata mode. I read diagnose.sh in full, then ran it with a fake az. In metadata mode it makes no `server-logs` or `log-analytics` call. The worker-console block also sits inside the `MODE=full` block that closes at line 133. What metadata mode does print:
   - fixed-field `show` queries of the server, app and revisions;
   - eleven named parameter values;
   - metrics;
   - the activity log, as op, status and substatus `localizedValue` only (no caller, no properties);
   - the diagnostic-settings JSON.

   No log line, activity message, app environment value, connection string or Key Vault reference value can reach the output.

**Boundary lows:**
- **Fixed:**
  - `--subscription` is on every resource-scoped az call in apsis-landing.sh.
  - Carriage returns are stripped.
  - The new `remove-owner-ip` command runs only after the full guard and `require_landing_account`. It only runs `az storage account network-rule remove`, so it cannot add an IP or open the account.
  - The bootstrap refuses a final environment in a subscription that holds rg-aron-dev. The check fails closed on an az error or on any answer other than exactly true or false, and it runs before provider registration or any other creation.
  - The loader's Key Vault role is scoped to its one secret, and the loader cannot exist in dev or dev-lite (both set environmentName 'dev').
  - whatif.yml no longer runs scope-check.sh.
  - Dev deploy and dev ops-diagnose from the integration branch are unchanged, apart from the primary-server filter.
- **NOT fixed, though D-FINAL-INFRA-12 and the new test say it is:** the rg-aron-dev probe in apsis-landing.sh still fails open (first low below). By impact it is a low: it also needs a TEST subscription that holds rg-aron-stage or rg-aron-prod, and none is documented.

**Not verified:**
- Bicep compile and ARM handling of the role assignment scoped to an existing conditional secret.
- Whether GitHub masks the subscription id in the diagnostic-settings output and in the step summary.
- That the `actions: read` token can read the app-release environment.
- Windows PowerShell 5.1 behaviour of `2>$null` under `ErrorActionPreference = 'Stop'` in the new bootstrap probe (it would fail closed if it throws).
- validate.sh on a Windows laptop (it downloads Linux binaries).
- The full check_infra suite, which needs the compiled templates.
- Any live GitHub or Azure state.

LOWS:
- apsis-landing.sh still fails open on the rg-aron-dev probe; it is listed as fixed in D-FINAL-INFRA-12 but is not. `group_exists` calls `die` inside `$(...)`, so the `exit 1` only leaves the subshell: `[ "$(group_exists rg-aron-dev)" = true ]` sees an empty string and goes on. I demonstrated it with a fake az: the rg-aron-dev probe exits 1, rg-aron-prod is false, rg-aron-stage is true. The wrapper printed '::error::cannot tell whether rg-aron-dev exists (az failed); nothing was created or uploaded', then ran `az group create ... rg-aron-apsis-landing` and `az deployment group create`, and exited 0. The message is therefore false. The new test does not catch this because it fails all three probes, so the rg-aron-stage and rg-aron-prod check refuses instead. Fix: `dev_state="$(group_exists rg-aron-dev)" || exit 1`, and the same for prod and stage, before comparing; add a test case where only the dev probe fails.
- check_infra.py test_wrapper_fails_closed...: the assertion named 'every az call names the subscription' is written as `self.assertNotRegex(...) if False else None`, so it never runs. It is an inert check that looks like coverage. No other check confirms that every az call carries --subscription; I checked by reading: all do, except `account show` (the check itself) and `ad signed-in-user show`, which is tenant-level.
- The owner IP can be silently re-opened. Landing doc s8 says to deploy without the private-endpoint ids, upload, then re-run `deploy` with the ids later. That re-run (and the one that adds --loader-principal-id) requires --owner-ip and always sends uploaderIpAddresses=[ip], so it re-opens the rule that `remove-owner-ip` closed. The wrapper repeats its warning, and every request still needs Entra, so this is not an exposure. The doc should say to run `remove-owner-ip` again after every re-run.
- move-to-final-account.md line 35 still lists 'the whatif and ops-diagnose workflows for stage and prod (they too run from main only)' as available after step 0, although prod is no longer offered. The prod laptop diagnose path appears nowhere as a working command. diagnose.sh defaults to the dev names (caj-aron-dev-migrate, ca-aron-dev-worker), so the owner must pass `AZURE_RESOURCE_GROUP=rg-aron-prod infra/scripts/diagnose.sh <since> <until> caj-aron-prod-migrate ca-aron-prod-worker`. The default mode is full, which is fine on a private terminal. The laptop what-if through validate.sh also does not run whatif-guard.py, does not pin a subscription (it is read-only, so harmless), and downloads Linux binaries, which will not run under Git Bash on Windows (unverified).
- In metadata mode diagnose.sh still prints `az monitor diagnostic-settings list -o json | head -60`. That output holds full resource ids (workspaceId, storageAccountId and so on), which contain the FINAL subscription id, and it goes to the public job log and the step summary. GitHub probably masks it, because AZURE_SUBSCRIPTION_ID is a secret the job references, but I did not verify that. The project otherwise avoids printing subscription ids (the bootstrap prints none). Project the category names and enabled flags only.
- The loader's Key Vault role is now scoped to the secret resource. This needs the secret to exist before the apps deploy; otherwise the role assignment fails (fails closed). The old vault-scope assignment, if one was ever created, would not be removed in incremental mode, but no profile has deployed a loader, so nothing should exist. apsisLoaderDbSecretName can still name the admin URL secret: that is by convention only.
- release-app.yml: the app-release reviewer check runs inside the job that already names `environment: app-release`. If the environment is missing, GitHub creates it unprotected before the guard refuses. This fails closed, but it leaves an unprotected environment behind until the owner runs governance.
- ops-diagnose.yml echoes the user-supplied job prefix and worker name in its 'window ...' line before overriding them for stage. This is harmless (it is the dispatcher's own input), but it reads as if they were used.

### regressions verdict=pass
SUMMARY: Regressions lens on 5406dd986 (delta 0804cf942..5406dd986, whole lane vs origin/claude/wonderful-thompson-k6ejnf; merge base a4691a061, and the integration branch has not touched .github/ or infra/ since then).

Dev behaviour is otherwise unchanged:
- deploy.yml is untouched in the delta. Its lane changes leave the dev and dev-lite paths as they are: azure-dev, the integration-branch check and the same deploy.sh call.
- drill, reset, acr-purge, cost-report and ci.yml are not touched. Only deploy, ops-diagnose, promote-prod, release-app and whatif changed, and lib.sh is unchanged.
- dev ops-diagnose keeps its inputs (environment defaults to dev, job_prefix/worker_app empty, which falls back to caj-aron-dev-migrate / ca-aron-dev-worker), runs MODE=full, and keeps the migrate console and worker log sections. I ran the integration-branch and the head diagnose.sh side by side on scratch copies with a fake az. Both exited 0 and printed identical section headers. The az call sequence differs only in the server-picking query, which now skips -r1 and -drill- servers; that is an improvement.
- final-account-guard.sh dev allows the integration branch and refuses main (run locally).
- No check_infra.py or test_gates.py test method was removed, and tools/ci is unchanged. The changed assertions are re-targeted or stricter. The two deliberate relaxations are stage being deployable and stage dbPerAppLogins being true; both are documented in D-FINAL-INFRA-02/03.
- No new real id, email, subscription or tenant id. The new GUIDs are built-in role ids (some in a forbidden list) and test fixtures (11111111-..., 00000000-...-0001); the one email is alerts@aron.invalid.
- The release-app guard step cannot affect the dev APKs: ci.yml does not call release-app.yml. It only runs in the release job after the inert-guard job reports go=true. A legitimate app-v* release passes once tools/github-governance.ps1 has created app-release with a reviewer, as the runbook orders. test_gates' release-app index checks still hold by text.

One plausible blocker, introduced by the delta: in dev, the apsisKvRead role assignment is now scoped to a secret whose name is empty. Several docs also still contradict the new "dev and stage only" rule and the azure-prod tag policy.

Not verified: I did not compile Bicep (no bicep CLI; compiled templates absent), so check_infra.py and test_gates.py were not executed. I did not run actionlint or shellcheck. No ARM what-if or deploy was run, so the empty-scope question cannot be settled here. The python jmespath module is not installed, so the new `!ends_with` / `!contains` query was not evaluated; it is valid JMESPath syntax. The gh api environment read under GITHUB_TOKEN was not exercised live.

LOWS:
- docs/setup/move-to-final-account.md:35 ('Waits for step 0' column) still says 'the `whatif` and `ops-diagnose` workflows for stage and prod'. This contradicts the workflows (prod is no longer offered), line 192 of the same file and D-FINAL-INFRA-12. It should say stage only, with prod done from the laptop.
- docs/status/infra-final.md:141 (owner step 6) still says governance creates 'azure-prod with tags `server-v*` and `app-v*`' and does not mention app-release. The script now creates azure-prod with server-v* only, plus app-release with app-v*. The owner is told to compare this against the plan the script prints, so the two will disagree. D-FINAL-INFRA-12 claims this stale text was fixed.
- docs/status/infra-final.md:120-121 still lists `ops-diagnose.yml:45` as hard-wired to azure-dev. Line 207-208 says ops-diagnose is 'deliberately dev-only until the owner asks' and then, in the same bullet, that it runs for dev and stage.
- DECISIONS.md D-FINAL-INFRA-09 still says ops-diagnose covers (dev, stage, prod) and that whatif runs a scope check. It is superseded by D-FINAL-INFRA-12; this is acceptable in an append-only log, but D-09 has no 'superseded' marker.
- The final-account-guard.sh header comment still says 'azure-stage / azure-prod exists'; only azure-stage (and app-release) are checked now.
- Whole lane (not the delta): whatif-guard.py now exempts a Create of '<existing server>-r1' for every profile, dev included. The exemption is narrow (an exact id), but it is a gate relaxation that also applies to the test account.
- Whole lane: release-app's release job now fails closed when app-release is missing or has no reviewer. This is intended, and the runbook orders governance first, but a first app-v* tag pushed before governance runs will create app-release unprotected and then go red rather than inert.
- ops-diagnose: the dev branch check moved from an inline step (before checkout) to final-account-guard.sh (after checkout). The effect is the same, and the workflow gains `actions: read`, which is harmless.
- (refuted 2/3) apsisKvRead now has an empty-name scope in dev (plausible break of every dev apps deploy): In dev, dev.apps.bicepparam does not set apsisLoaderDbSecretName, so it is ''. The condition is false, but ARM (languageVersion 1.0, no symbolic names configured) still expands the name and type of condition-false resources, and usually the scope too, to build ids and dependsOn (apsisLoader dependsOn apsisKvRead). That would yield '.../vaults/<kv>/secrets//providers/Microsoft.Authorization/roleAssignments/<guid>'. Empty-name segments in condition-false resources are a known cause of InvalidTemplate at preflight. Before the delta the scope was `kv`, which is always valid. Bicep build and lint do not catch this; only ARM does.


## Spot-check 5406dd986..6f6a470b1 (round 5), 2026-11-18: PASS

### guards verdict=pass
Items 1, 2 and the replica exemption hold up. I fetched lane/infra-final, read 6f6a470b1 with git show, and ran everything on scratch copies under /tmp/claude-0/-home-user-Aron-Pro-Max/a5c3c45a-b89c-5768-9d5f-6bc05c04fc63/scratchpad/infra-r5-apsis/ with a fake az on PATH.

(1) apsis-landing.sh probes now stop the script. I ran a matrix where exactly one probe misbehaves (rg-aron-dev, rg-aron-stage or rg-aron-prod), either by az exiting 1 or by az printing garbage. The new script exits 1 in all 6 cases with "cannot tell whether <rg> exists ... nothing was created or uploaded", and there are 0 group create calls in the fake-az log. The old script (5406dd986) exits 0 and calls group create plus deployment group create in all 6, so the fix is real. The baseline where every probe answers normally still deploys. lib.sh sets -euo pipefail, so the `|| exit 1` is belt-and-braces.

(2) The --subscription test is a real assertion. Against the new script, test_wrapper_fails_closed_on_az_errors_names_the_subscription_and_strips_cr passes. It fails against the old script ("a failing dev probe must not fall through to creation"). I made four mutants, each removing `--subscription "$final_sub"` from one az call (line 51 group exists, 78 group create, 86 deployment group create, 94 network-rule remove). Each one makes the test fail. Line 51 is caught only by the run-time log check, because the line-by-line check skips it (it contains " die "). The new "only the dev probe fails" sub-test does take effect: the replace string matches the fake az text, and the old script fails it.

(3) Replica exemption. whatif-guard.py changes by one line only: is_replica now also requires os.environ ARON_ALLOW_REPLICA == "true", an exact match. With the variable unset, "false" or "TRUE", an -r1 Create next to an existing server gives rc 1; only "true" gives rc 0. deploy.sh sets it true only when ENV_NAME (PROFILE without -lite) is stage or prod. whatif.yml sets it true only for PROFILE stage*, and its options are dev, dev-lite, stage, stage-lite. Evaluated: dev and dev-lite give false; stage and stage-lite give true. Those are the only two callers of the guard. dev and dev-lite set postgresReadReplica = false, so the dev deploy never needs the exemption and is not affected. deploy.sh passes bash -n.

The three guard tests (FinalAccountStage replica, SizingParameters.test_what_if_guard_decisions, test_deploy_runs_the_what_if_guard_before_main) pass. No existing assertion was loosened: the only edits to old tests are the new allow parameter defaulting to "true" plus one new refusal case.

Not verified: the full check_infra.py suite (it needs compiled templates and the bicep CLI, which I did not build). I also did not check that the apps.bicep apsisSecretName placeholder compiles or that ARM accepts it for dev. No real az or ARM what-if was run. I did not review the docs, DECISIONS.md or diagnose.sh changes beyond the diff, and did not check the requested items against GitHub Actions itself.

LOWS:
- The line-by-line --subscription check in check_infra.py skips any line containing " die " or 'echo "'. A new az call written as `x="$(az ...)" || die ...` that lacks --subscription would get past that check. Today the one such line (51, group exists) is covered by the run-time log assertion instead.
- The azs() helper (line 45) is excluded from the line-by-line check (`az "$@"`), and no run-time assertion checks --subscription on its storage account show call. If azs lost the flag, the test would not notice.
- The unit test simulates only a failing dev probe. A failing stage-only or prod-only probe, and garbage from a single probe, are not covered by a unit test; I exercised them by hand and they are refused.
- whatif.yml's ARON_ALLOW_REPLICA line is not pinned by any test, unlike deploy.sh's, so it could drift. For example, if it became always-true, dev what-if previews would exempt -r1 again. The real dev deploy would still refuse, because it goes through deploy.sh.
- The stray `code = line.split('#')[0] if ...startswith('#')` in the new test does nothing, since comment lines are skipped anyway, and inline trailing comments are not stripped. Cosmetic.

### dev-template-and-docs verdict=pass
Items 3, 4 and 5 hold, and I found no blocker. What I did: ran git fetch, which hit a lock-ref race, but origin/lane/infra-final was already at 6f6a470b1. I read the 5406dd986..6f6a470b1 delta plus the apps.bicep, params and main.bicep diff against origin/claude/wonderful-thompson-k6ejnf, and read diagnose.sh, ops-diagnose.yml, validate.sh, naming.bicep, whatif-guard.py and DECISIONS.md at 6f6a470b1. I also ran apsis-landing.sh against a fake az in scratch. When only the rg-aron-dev probe fails, the script stops with rc=1 and creates nothing. With every probe answering, it goes on to group create.

(3) apps.bicep: `apsisSecretName` falls back to the non-empty 'apsis-loader-unused'. It is used for the conditional `existing` secret's name, for the role assignment's guid name, for its scope (which compiles to a resourceId with variables('apsisSecretName')), and for the job's kvSecret. On the dev path the condition is false: `environmentName != 'dev'` plus an empty image and an empty secret name. So ARM gets no empty name segment to validate. A false-condition `existing` resource is not read, and the placeholder secret is never read or granted. Against the integration branch, the dev and dev-lite param files are unchanged and no new parameter is set in dev. labGrantEnabled defaults to false, privateEndpointSubnetPrefix defaults to empty, and serverEnv equals 'dev' for dev. Nothing new deploys in dev.

The compiled-template test DevApplicationsAreUntouchedByTheLoader is real. It reads infra/.compiled/apps.json and the *.apps.parameters.json files, which validate.sh builds before running check_infra.py, and asserts the variable expression, the secret's name and that the raw parameter is absent from every name and scope.

The whatif-guard replica exemption fails closed: it needs ARON_ALLOW_REPLICA set exactly to 'true'. deploy.sh sets it only for stage and prod (ENV_NAME is the profile with -lite stripped) and whatif.yml only for stage*. Dev has postgresReadReplica=false, so dev loses nothing, and a test was added for allow=false.

(4) In metadata mode, diagnose.sh projects only the diagnostic-setting name, category, group and enabled flag. No other section prints the server id on the success path. There is one exception on error paths (see lows).

(5) The prod commands in the runbook match the scripts. validate.sh uses AZURE_RESOURCE_GROUP plus ARON_ENV and loads params/${ARON_ENV}.bicepparam. diagnose.sh takes since, until, job prefix, worker and mode, and caj-aron-prod-migrate / ca-aron-prod-worker follow naming.bicep. DECISIONS.md is +1/-0: D-FINAL-INFRA-13 is the last line (2155) and no earlier entry was edited.

Not verified: I did not run bicep build, validate.sh or check_infra.py (no binaries, and I was told not to download), so the compiled JSON shapes are inferred from the test's own expectations. I made no ARM or what-if call, so ARM's handling of a false-condition existing scope rests on reasoning only. I did not check whether GitHub masks secret values in the step summary. I did not check that a laptop prod what-if works with ARON_BUDGET_START_DATE and the other variables left empty.

LOWS:
- diagnose.sh metadata mode can still print the PostgreSQL resource id, which contains the subscription id, when a command fails. try() echoes '(command failed: $*)', and that includes --resource "$sid" / --resource-id "$sid" for diagnostic-settings, metrics and activity-log. az's 2>&1 errors (for example an AuthorizationFailed scope) also quote the id. In ops-diagnose this goes to the job log and GITHUB_STEP_SUMMARY. GitHub probably masks it because AZURE_SUBSCRIPTION_ID is a secret, but I did not verify that for the step summary. On a laptop prod run the output stays local.
- The new '--subscription on every az call' check in check_infra.py skips every line containing ' die ', and that includes the `az group exists` line inside group_exists(). So the check does not cover the probe the round was about. The line does carry --subscription today; the guard is just weaker than D-FINAL-INFRA-13 claims.
- docs/setup/move-to-final-account.md line 35 still says the `whatif` and `ops-diagnose` workflows run 'for stage and prod (they too run from main only)'. The new section at line 132 and D-FINAL-INFRA-12/13 say they never run for prod. This stale wording is left over.
- The prod diagnose command in the runbook keeps bracketed placeholders (<since-utc> <until-utc>) in a copyable command, unlike the landing doc's no-bracket rule. A pasted command would fail on the placeholder rather than do harm.
- The laptop prod what-if through validate.sh sets only ARON_DB_ADMIN_PASSWORD. ARON_BUDGET_START_DATE, the zones and the other variables that deploy.sh derives from live state stay empty, so the preview can differ from a real deploy or fail on the budget start date. I did not verify this; the stage line before this delta has the same caveat.
