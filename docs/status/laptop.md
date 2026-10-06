# Laptop operator log

Owner's Windows laptop, `C:\Users\User\Aron`. No secrets recorded here.

## 2026-10-06

| Step | Command | Result |
|---|---|---|
| 1 | `git pull` | Fast-forward de59818..9fa618e |
| 2 | `.\infra\bootstrap-azure.ps1 -AlertEmails <owner's email>` | First run failed at "Granting rights" (see below). Fixed, then a rerun succeeded |
| 3 | `.\tools\make-signing-key.ps1` (once) | Keystore created in `%USERPROFILE%\AronSigning`; 4 `ANDROID_SIGNING_*` secrets set |
| 4 | `adb devices` | No devices attached |

The owner approved steps 2 and 3 after reading what the scripts actually do. Step 2 also registers resource providers
across the subscription and creates the empty group `rg-aron-scope-probe`.

### Bootstrap failure and fix

`az` is a `.cmd` wrapper on Windows, so cmd.exe re-parses its arguments and treats the `)` in a JMESPath function
(`--query 'length(@)'`) as the end of a block. The error was `-o was unexpected at this time`, and the script then
blamed identity replication. The fix in `infra/bootstrap-azure.ps1` does the Contributor count and the quota filter
in PowerShell, so the query has no parentheses. The script is idempotent, and the rerun completed every step.

### Verified state

- Subscription and tenant: the script's defaults (IDs are in the GitHub secrets, not repeated here).
- `rg-aron-dev` (southeastasia) created. The existing `rg-aron` was not touched.
- App `sp-aron-github-dev` (client id is in the `AZURE_CLIENT_ID` secret). It has 2 role assignments, both on
  `rg-aron-dev`: Contributor, and RBAC Administrator with the role condition.
- GitHub: environment `azure-dev`, secrets `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, variables
  `AZURE_RESOURCE_GROUP`, `AZURE_LOCATION`, `ARON_ALERT_EMAILS`.

### Compute quota, southeastasia

| name | used | limit |
|---|---|---|
| Total Regional Low-priority vCPUs | 0 | 3 |
| Total Regional vCPUs | 0 | 10 |

### Android signing key fingerprints (public)

```
SHA1:   95:C9:7E:AF:8A:C3:BF:52:84:B9:50:71:8F:C5:75:AF:60:B0:9C:83
SHA256: 46:8D:9B:4E:CC:B7:76:E9:F4:0E:20:E2:0A:AF:0F:14:62:7E:23:16:E9:01:D5:3A:F3:FC:9A:A4:E3:93:A4:FB
```

Owner action: back up `C:\Users\User\AronSigning` somewhere safe, offline.
