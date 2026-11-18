# Request / agreement (infra-final <-> apsis-migration): where the Apsis dump lands in the FINAL account

From: infra lane F-INFRA1, 2026-11-18. To: lane apsis-migration (its design says, `docs/status/apsis-migration.md` s5 and the
security list: "the job's managed identity reads the dump from a private container (`docs/requests/apsis-dump-landing.md`)"), the
lead, and the owner (who runs the upload).

**Rules that hold for everything below.** The dump (26 `.sql` files, about 52.5 GB, the largest `live_geo_data_by_outlets.sql` at
31.1 GB; `docs/reference/apsis-db-inventory-2026-11.md`) never touches the repository, the TEST account, CI, a CI log, or an agent
container. It goes from the organisation's FTP server, through the owner's own machine, into a private container in the FINAL
account, signed in with the owner's own Entra identity (MFA). The repository is public: every `<...>` below is a placeholder that
the owner or the laptop operator fills in locally; no id, address, host name or user name is ever committed or pasted into chat.

## 1. What infra built (`infra/apsis-landing.bicep`, standalone, deployed once by the owner's laptop session)

| Item | Setting | Why |
|---|---|---|
| Own resource group `rg-aron-apsis-landing` | not `rg-aron-prod`, not touched by the pipeline | the pipeline's deploy identity has no rights there, so CI can never read the dump; deleting the group is the one-step removal after sign-off; `reset-to-profile.sh` never compares it with the profile |
| Storage account `st<prefix>apsis<suffix>` | StorageV2, `Standard_LRS`, Hot | a transient copy of a file the owner keeps: no zone or geo redundancy is paid for (docs/28 spirit); a re-upload is possible |
| Access | shared keys **disabled**, Entra only, `defaultToOAuthAuthentication`, no account SAS can exist, anonymous access off, TLS 1.2, HTTPS only, SFTP and local users off, copy scope AAD | Microsoft Learn, "Prevent Shared Key authorization for an Azure Storage account" |
| Network | default **Deny**; allowed: the owner's public IPv4 address(es) for the upload only (single addresses: Storage rejects /31 and /32 CIDR rules), the loader's blob private endpoint, and the `AzureServices` bypass (trusted Azure services still need an Entra role) | nothing is reachable from the internet at large; every request still needs an Entra token |
| Containers | `apsis-dump` (written by the owner, read by the loader), `apsis-reports` (written by the loader, read by the owner), both `publicAccess: None` | different permission models for input and for output |
| Roles (container scope only, never on the account) | owner: Storage Blob Data Contributor on `apsis-dump`; loader identity: Storage Blob Data Reader on `apsis-dump` and Storage Blob Data Contributor on `apsis-reports` | Owner rights on the subscription do not include blob data access, hence the explicit assignment |
| Lifecycle | delete blobs under `apsis-dump/` **60 days after their last write** (`dumpDeleteAfterDays`, 7 to 365) | safety net; the plan is the group deletion at sign-off |
| Soft delete | 7 days for blobs and containers | an accidental delete is recoverable for a week |
| Diagnostic logs | every blob read, write and delete (`allLogs`) and transaction metrics to Log Analytics (an existing workspace, or a small one created here: 30 days, 1 GB a day) | "only the owner and the loader touched the dump" can be shown |

## 2. Container layout (proposed by infra; apsis-migration please confirm or answer with a change in this file)

```
apsis-dump/                                  (input; the loader only reads)
  incoming/<yyyymmdd>/<original file name>.sql        the 26 vendor files, unchanged names and bytes
  incoming/<yyyymmdd>/MANIFEST.sha256                 "sha256  size  name" per file, made on the owner's machine before upload
apsis-reports/                               (output; the loader writes, the owner reads; numbers only, no row data)
  <run_id>/reconciliation-<run_id>.json
  <run_id>/reconciliation-<run_id>.txt
  <run_id>/chunks-<run_id>.csv                        apsis_ctl.chunk state, counts and checksums only
```

- `<yyyymmdd>` is the upload day in UTC; a second upload on another day is a new folder, never an overwrite of the first.
- One blob per vendor file (block blobs; the 31.1 GB file is a normal block blob, limit far above). No compression, no re-packaging:
  the loader reads the bytes the vendor produced.
- `MANIFEST.sha256` is written by the owner's machine from the LOCAL files before upload; the loader recomputes while it reads and
  stops on a mismatch. (Azure also stores a per-blob MD5 when `--put-md5` is used, see s4.)
- The loader needs **no write access to `apsis-dump`**, and nobody but the owner has any access to `apsis-reports` beyond the loader.

## 3. Deploy (laptop operator, after the owner's `az login` with MFA): only through the wrapper

`infra/scripts/apsis-landing.sh` is the ONLY way the landing place is created or filled. Before it creates or uploads anything it
refuses unless: `--final-subscription-id` (typed by the person running it from the owner's value; never committed and never read
from the signed-in context, which would always match) equals the signed-in subscription; `--ack "the dump goes to the final account only"`
is given; the subscription does **not** hold `rg-aron-dev` (that is the TEST account) and **does** hold `rg-aron-prod` or
`rg-aron-stage`. The Bicep template repeats the first check (`expectedSubscriptionId`): a mismatch fails the deployment at
validation, before the storage account exists.

```bash
az account set --subscription <final-subscription-id>          # the shell must already be on the FINAL subscription
infra/scripts/apsis-landing.sh deploy --final-subscription-id <final-subscription-id> \
  --ack "the dump goes to the final account only" --region <region> --owner-ip <owner public IPv4 address> \
  --pe-subnet-id <snet-pe id> --dns-zone-id <blob private DNS zone id>
# optional, add to the same command when wanted:   --log-workspace-id <existing Log Analytics workspace id>
#                                                   --loader-principal-id <apsisLoaderPrincipalId output of the apps deployment>
```

The outputs give `storageAccountName` and `dumpContainerUrl`. A new role assignment can take a few minutes to apply. The wrapper
prints two warnings the owner must read: **(1) the lifecycle rule deletes dump blobs 60 days after their last write**: if the
migration will run longer (a long parallel run), re-upload or raise `dumpDeleteAfterDays` before then; **(2) remove the owner's
address after the upload** with the one supported command `infra/scripts/apsis-landing.sh remove-owner-ip --final-subscription-id <final-subscription-id> --ack "the dump goes to the final account only" --account <account> --owner-ip <ip>`,
so the private endpoint is the only way in. With `--log-workspace-id` the blob access logs (caller address, blob names) live in that
shared workspace and **outlive the group's deletion for the workspace's retention**; without it a small workspace is created in the
landing group and is deleted with it. If the owner's address changes: `az storage account network-rule add ... --ip-address <new-ip>`.
The network rule `bypass: AzureServices` stays (trusted Azure services still need an Entra role). Nothing in this template is deployed by a workflow.

## 4. Copy: FTP server -> owner's machine -> private container (owner, own machine)

Prerequisites on the owner's machine: [AzCopy v10](https://learn.microsoft.com/azure/storage/common/storage-use-azcopy-v10), the FTP
details of the organisation's server (host, user, folder; the password is typed when asked, never put in a script, a file or the
chat), and either about 60 GB of free local disk (path A) or a stable connection (path B). **The upload runs only through
`infra/scripts/apsis-landing.sh`** (same guard as s3: it refuses the TEST subscription before AzCopy starts).

**Sign in (once; browser with MFA, no key, no token pasted anywhere):**

```
azcopy login --tenant-id <tenant-id>
```

**Path A (recommended: resumable, verifiable): download, hash, upload.**

```bash
# 1. download with any resumable FTP client (WinSCP, lftp, FileZilla) into  ~/apsis-dump/<yyyymmdd>/
#    use FTPS or SFTP and REQUIRE encryption in the client: plain FTP sends the password unencrypted.
# 2. hash the local files (they are the reference the loader checks against):
cd ~/apsis-dump/<yyyymmdd> && sha256sum -- *.sql > MANIFEST.sha256 && wc -l MANIFEST.sha256   # expect 26
#    Windows PowerShell:
#    Get-ChildItem *.sql | ForEach-Object { "{0}  {1}  {2}" -f (Get-FileHash $_ -Algorithm SHA256).Hash.ToLower(), $_.Length, $_.Name } | Set-Content MANIFEST.sha256
# 3. upload the folder through the wrapper (stores an MD5 per blob; AzCopy stores none for files over 256 MB unless asked):
infra/scripts/apsis-landing.sh upload --final-subscription-id <final-subscription-id> --ack "the dump goes to the final account only" \
  --account <account> --source ~/apsis-dump/<yyyymmdd> --date <yyyymmdd>
# 4. a failed or interrupted run is resumed, not restarted:
azcopy jobs list
azcopy jobs resume <job-id>
```

**Path B (no local disk: stream each file from FTP straight into the container).** One file at a time; a dropped connection
means that one file starts again (no resume), and no source-side hash is possible, so the loader's own hash becomes the first
checksum on record (it logs it).

```bash
curl --fail --silent --show-error --ssl-reqd --user '<ftp-user>' 'ftp://<ftp-host>/<folder>/<file>.sql' \
 | infra/scripts/apsis-landing.sh upload-stream --final-subscription-id <final-subscription-id> \
     --ack "the dump goes to the final account only" --account <account> --date <yyyymmdd> --name <file>.sql
# --ssl-reqd makes curl REFUSE a server that cannot encrypt (--ssl would silently fall back to plain FTP and send the password in clear)
# curl asks for the FTP password on the terminal
```

**Verify (owner or operator):**

```bash
az storage blob list --account-name <account> --container-name apsis-dump --prefix incoming/<yyyymmdd>/ --auth-mode login \
  --query "[].{name:name, bytes:properties.contentLength}" -o table      # 26 .sql + MANIFEST.sha256, sizes equal to the local ones
azcopy list 'https://<account>.blob.core.windows.net/apsis-dump/incoming/<yyyymmdd>/'
```

**Evidence of who touched the dump (Log Analytics, KQL):**

```
StorageBlobLogs
| where TimeGenerated > ago(7d) and AccountName == "<account>"
| summarize requests = count(), bytes_out = sum(ResponseBodySize) by OperationName, AuthenticationType, CallerIpAddress
```

`AuthenticationType` must be `OAuth` for every row; an `AccountKey` or `SAS` row should not appear while shared keys stay disabled; if one does,
the account was changed: investigate.

## 5. What the loader needs (apsis-migration lane + lead; infra answers here)

1. **Identity.** The loader runs as a Container Apps job in `cae-aron-prod` (the database is private; docs/status/infra-final.md s2c).
   Give it a user-assigned managed identity; its **principal id** goes into `loaderPrincipalIds` on a re-run of the deployment of
   s3 (Reader on `apsis-dump`, Contributor on `apsis-reports`). Nothing else gets a role. **The loader is not shipped with the admin database URL as its default**: keep it absent until the least-privilege `apsis_loader` login exists (db-lane request).
2. **Network path (lead ruling 2026-11-18: blob private endpoint).** `main.bicep` (stage and prod, `privateNetworking`) now adds
   the subnet `snet-pe` (`10.40.2.32/27` prod, `10.41.2.32/27` stage) and the private DNS zone `privatelink.blob.core.windows.net`
   linked to the VNet (outputs `privateEndpointSubnetId`, `blobPrivateDnsZoneId` of the network module). `infra/apsis-landing.bicep`
   creates the endpoint there when given those two ids, so the loader job in `cae-aron-<env>` resolves the account to a private
   address; there is no service endpoint. The deploy of s3 then takes two more parameters:
   `privateEndpointSubnetId=<id>` and `blobPrivateDnsZoneId=<id>` (read them from `az deployment group show -g rg-aron-prod -n aron-infra --query "properties.outputs"` or `az network vnet subnet show` / `az network private-dns zone show`).
   **The owner's IP rule is for the upload only: after the upload is verified, re-run the deployment with `uploaderIpAddresses='[]'`
   so nothing but the private endpoint (and the AzureServices bypass) can reach the account.**
3. **Reads.** Stream with the Entra token of the job's managed identity (`DefaultAzureCredential` / `az storage blob download` with
   `--auth-mode login` / AzCopy `--auto-login-type=MSI`); no key, no SAS, no URL with a signature in a log. Range reads are fine
   (block blobs).
4. **Sign-off and removal.** When the owner signs the reconciliation, the owner (or the laptop operator on the owner's word) runs
   `az group delete --name rg-aron-apsis-landing --yes`. That removes the dump, the account, its logs workspace (when created
   there) and every role. The 60-day lifecycle rule is the safety net if that is forgotten. The reconciliation files in
   `apsis-reports` are numbers only; copy them to the owner's machine (`azcopy copy`) BEFORE the group is deleted if they are to be kept.
5. **Questions back to infra:** are 26 files, the names above and a `MANIFEST.sha256` per day enough for the loader? Does it want
   a second container for converted intermediate files (infra advises against: converted data is row data and belongs in the
   private database, not in storage)? Is 60 days the right safety-net length for the planned schedule?

## 6. Not verifiable without the live account

The template compiles, lints and passes `infra/tests/check_infra.py` (`ApsisLanding`). Not proven: the deployment itself, the
role assignment on a user principal, the `Deny` rule with the owner's address, AzCopy against the account, throughput of the 31 GB
file over the owner's connection, and the private-endpoint path for the loader (s5.2: DNS resolution from the job, the automatic connection approval).

## 7. Who reads the loader's logs

The loader prints counts and checksums by contract, but an error line can still carry a customer's phone number or id. Its logs are readable **only inside Azure, by the owner** (Log Analytics in the portal). `ops-diagnose` never prints job or app console logs for stage (metadata and metrics only, fixed names, the loader never included) and does not run for prod at all (read-only prod checks run from the laptop).

## 8. Note on the commands above

The landing commands need ids (`<snet-pe id>`, `<blob private DNS zone id>`, the loader principal id) that exist only after the stage/prod infra deploy, which waits for step 0 of `docs/setup/move-to-final-account.md`. Before that: deploy without `--pe-subnet-id`/`--dns-zone-id`, upload, and add the endpoint by re-running `deploy` with those ids later.
