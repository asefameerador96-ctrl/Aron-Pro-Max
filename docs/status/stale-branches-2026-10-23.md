# Remote branches on 2026-10-23 (lead list for the owner; nothing deleted)

Generated from `git for-each-ref refs/remotes/origin` after `git fetch --prune`; INT = `claude/wonderful-thompson-k6ejnf` at 1dd9e44c. "merged" = the branch head is an ancestor of INT. "ahead" = commits not on INT, and "non-merge ahead" = those that are not merge commits; a ref whose only extra commits are merges carries no content INT lacks. Owner rule: branches are deleted only with the owner's word (CLAUDE.md rule 13, git governance); the lead proposes, the owner decides. Proposed command once the owner says yes: `git push origin --delete <branch>` per row marked delete, run by the lead after wave 4 closes.

## Summary

| Proposal | Branches |
|---|---|
| stale train staging ref, delete | 176 |
| lane branch fully on INT; keep while the lane is active, else delete after wave 4 | 45 |
| active or unpromoted lane work, keep | 6 |
| dependabot PR branch (owner row P), untouched | 3 |
| HELD until the owner says promote hardening, keep | 2 |
| not on INT: inspect (salvage or abandoned work) | 2 |
| merged (only merge commits ahead), delete | 1 |
| INT, keep | 1 |
| train ref with non-merge commits not on INT: inspect before deleting | 1 |
| release branch, keep | 1 |
| total | 238 |

## Rows

| Branch | Last commit | Merged into INT | Ahead | Non-merge ahead | Proposal |
|---|---|---|---|---|---|
| `claude/amazing-kilby-fdc9ab` | 2026-10-17 | no | 14 | 11 | HELD until the owner says promote hardening, keep |
| `claude/bc-s5-salvage` | 2026-10-07 | no | 2 | 2 | not on INT: inspect (salvage or abandoned work) |
| `claude/db-s3-salvage` | 2026-10-07 | no | 3 | 2 | not on INT: inspect (salvage or abandoned work) |
| `claude/db-wip-v0023` | 2026-10-07 | yes | 0 | 0 | merged (only merge commits ahead), delete |
| `claude/wonderful-thompson-k6ejnf` | 2026-10-23 | yes | 0 | 0 | INT, keep |
| `dependabot/github_actions/actions-minor-patch-09117aa1a9` | 2026-10-22 | no | 1 | 1 | dependabot PR branch (owner row P), untouched |
| `dependabot/gradle/gradle-minor-patch-e9bc3756c2` | 2026-10-18 | no | 1 | 1 | dependabot PR branch (owner row P), untouched |
| `dependabot/npm_and_yarn/web/npm-minor-patch-e59ac77173` | 2026-10-22 | no | 1 | 1 | dependabot PR branch (owner row P), untouched |
| `lane/android-amo` | 2026-10-18 | no | 1 | 1 | active or unpromoted lane work, keep |
| `lane/android-core` | 2026-10-07 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/android-core-ui` | 2026-10-07 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/android-geo-dpc` | 2026-10-07 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/android-map` | 2026-10-22 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/android-print` | 2026-10-22 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/android-sr-a` | 2026-10-07 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/android-sr-a-check` | 2026-10-07 | no | 1 | 1 | active or unpromoted lane work, keep |
| `lane/android-sr-b` | 2026-10-07 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/android-sys` | 2026-10-07 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/android-tso` | 2026-10-18 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/android-units` | 2026-10-22 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/backend-admin` | 2026-10-23 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/backend-authz` | 2026-10-23 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/backend-core` | 2026-10-23 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/backend-reports` | 2026-10-23 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/db` | 2026-10-23 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/infra` | 2026-10-08 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/lead-backend` | 2026-10-18 | no | 1 | 1 | active or unpromoted lane work, keep |
| `lane/lead-brand` | 2026-10-19 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/lead-ci-contract-base` | 2026-10-22 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/lead-ci-gitleaks` | 2026-10-22 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/lead-ci-jvm-timeout` | 2026-10-22 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/lead-colors` | 2026-10-17 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/lead-colors-calm` | 2026-10-17 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/lead-contract` | 2026-10-18 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/lead-contract-v1-3` | 2026-10-07 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/lead-directive-1022` | 2026-10-22 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/lead-docs` | 2026-10-15 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/lead-docs-1022` | 2026-10-23 | no | 3 | 2 | active or unpromoted lane work, keep |
| `lane/lead-docs-gate` | 2026-10-17 | no | 1 | 1 | active or unpromoted lane work, keep |
| `lane/lead-docs-night` | 2026-10-20 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/lead-execbit` | 2026-10-17 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/lead-hardening` | 2026-10-17 | no | 15 | 12 | active or unpromoted lane work, keep |
| `lane/lead-hardening-int` | 2026-10-17 | no | 15 | 12 | HELD until the owner says promote hardening, keep |
| `lane/lead-mdm-test-fix` | 2026-10-22 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/lead-pushprobe` | 2026-10-07 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/lead-sizebase` | 2026-10-17 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/lead-web-next-bump` | 2026-10-22 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/mdm` | 2026-10-22 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/qa` | 2026-10-23 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/train-20261007T0636` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T0848` | 2026-10-07 | no | 6 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1000` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1020` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1044` | 2026-10-07 | no | 4 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1054` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1100-i` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1105` | 2026-10-07 | no | 5 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1108-s` | 2026-10-07 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1116-d` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1123-e` | 2026-10-07 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1136-f` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1209-g` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1220-h` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1228-k` | 2026-10-07 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1233-m` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1236-n` | 2026-10-07 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1243-p` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1250-q` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1256-r` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1302-s` | 2026-10-07 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1317-t` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1325-u` | 2026-10-07 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1333-v` | 2026-10-07 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1340-w` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1348-x` | 2026-10-07 | no | 2 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1401-y` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1416-z` | 2026-10-07 | no | 3 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1432-ia` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1439-zb` | 2026-10-07 | no | 3 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1512-zc` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1522-zd` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1546-zf` | 2026-10-07 | no | 2 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1608-zg` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1610-zh` | 2026-10-07 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1625-zi` | 2026-10-07 | no | 2 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1636-zk` | 2026-10-07 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1638-zj` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1641-zl` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1648-zm` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1703-zo` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1714-zp` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1736-zq` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1807-zr` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1812-zs` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1823-zt` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1849-zv` | 2026-10-07 | no | 3 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1852-zu` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1854-zw` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1856-handover` | 2026-10-07 | no | 1 | 1 | train ref with non-merge commits not on INT: inspect before deleting |
| `lane/train-20261007T1915-zx` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1934-zy` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1938-zz` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1941-zza` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T1951-zzb` | 2026-10-07 | no | 2 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2002-zzc` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2024-zzd` | 2026-10-07 | no | 4 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2024-zze` | 2026-10-07 | no | 6 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2037-zzf` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2050-zzg` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2051-zzh` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2058-zzi` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2059-zzj` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2113-zzk` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2130-zzl` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2154-zzm` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2206-zzo` | 2026-10-07 | no | 3 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2208-zzn` | 2026-10-07 | no | 2 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2213-zzp` | 2026-10-07 | no | 5 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2218-zzq` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2243-zzr` | 2026-10-07 | no | 2 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2244-zzs` | 2026-10-07 | no | 2 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2247-zzt` | 2026-10-07 | no | 4 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2251-zzu` | 2026-10-07 | no | 4 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2254-zzv` | 2026-10-07 | no | 4 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2259-zzw` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2332-zzx` | 2026-10-07 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2332-zzy` | 2026-10-07 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2338-zzz` | 2026-10-07 | no | 2 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2341-zzw2` | 2026-10-07 | no | 3 | 0 | stale train staging ref, delete |
| `lane/train-20261007T2347-zzf` | 2026-10-07 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261008T0047-handover` | 2026-10-08 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261008T0110-zza` | 2026-10-08 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261008T0136-zzb` | 2026-10-08 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261008T0318-zzc` | 2026-10-08 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261008T0332-zzd` | 2026-10-08 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261008T0334-zze` | 2026-10-08 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261008T0450-zzf` | 2026-10-08 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261008T0452-handover` | 2026-10-08 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261008T0459-zzg` | 2026-10-08 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261008T0522-zzh` | 2026-10-08 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261008T0533-zzi` | 2026-10-08 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261008T0545-zzj` | 2026-10-08 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261008T0545-zzk` | 2026-10-08 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261008T0636-zzl` | 2026-10-08 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261008T0642-zzm` | 2026-10-08 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261008T0647-zzn` | 2026-10-08 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261008T0731-zzo` | 2026-10-08 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261008T0805-handover` | 2026-10-08 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261008T0810-zzp` | 2026-10-08 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261015T0610-handover` | 2026-10-15 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261015T0635-zza` | 2026-10-15 | no | 3 | 0 | stale train staging ref, delete |
| `lane/train-20261015T0655-zzb` | 2026-10-15 | no | 2 | 0 | stale train staging ref, delete |
| `lane/train-20261015T0700-zzc` | 2026-10-15 | no | 3 | 0 | stale train staging ref, delete |
| `lane/train-20261015T0702-zzd` | 2026-10-15 | no | 3 | 0 | stale train staging ref, delete |
| `lane/train-20261015T0716-zze` | 2026-10-15 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261015T0732-zzf` | 2026-10-15 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261015T0920-zzg` | 2026-10-15 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261015T0930-zzh` | 2026-10-15 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261015T0950-zzi` | 2026-10-15 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261015T1022-zzj` | 2026-10-15 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261015T1040-handover` | 2026-10-15 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261015T1046-zzk` | 2026-10-15 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261015T1110-zzl` | 2026-10-15 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261015T1125-zzm` | 2026-10-15 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261015T1127-zzn` | 2026-10-15 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261015T1152-zzo` | 2026-10-15 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261015T1157-zzp` | 2026-10-15 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261015T1200-zzq` | 2026-10-15 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261015T1229-zzs` | 2026-10-15 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261015T1230-zzr` | 2026-10-15 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261015T1236-zzt` | 2026-10-15 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261015T1300-zzu` | 2026-10-15 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261015T1302-zzu` | 2026-10-15 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261015T1310-zzv` | 2026-10-15 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261015T1345-zzx` | 2026-10-15 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261015T1352-zzz` | 2026-10-15 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261015T1408-zzc` | 2026-10-15 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261017T2145-amo` | 2026-10-17 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261017T2205-webqa` | 2026-10-17 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261017T2230-amo2` | 2026-10-17 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261017T2245-tso` | 2026-10-17 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261017T2250-tsoqa` | 2026-10-17 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261017T2305-web` | 2026-10-17 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261017T2330-all3` | 2026-10-17 | no | 3 | 0 | stale train staging ref, delete |
| `lane/train-20261017T2345-ct` | 2026-10-17 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261017T2345-tso` | 2026-10-17 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261017T2355-ctsoamo` | 2026-10-17 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0036-lbe` | 2026-10-18 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0040-gl` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0055-glweb` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0102-lbeqa` | 2026-10-18 | no | 3 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0112-ctqaamo` | 2026-10-18 | no | 3 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0130-ctamo` | 2026-10-18 | no | 2 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0133-ctamotso` | 2026-10-18 | no | 3 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0135-ctamo2` | 2026-10-18 | no | 2 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0145-ctamo3` | 2026-10-18 | no | 2 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0147-ctamo3b` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0222-qa` | 2026-10-18 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0222-tsoweb` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0240-qa2` | 2026-10-18 | no | 2 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0312-webamo` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0338-qatso` | 2026-10-18 | no | 2 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0350-qa3` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0405-lb` | 2026-10-18 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0435-tso` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0500-qa4` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0526-amolb` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0604-amo2` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0636-amoweb` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0641-all3b` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0658-qaamo` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0703-qaamoweb` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0716-qaat` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0717-qatlb` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T0815-authz` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T1100-authz2` | 2026-10-18 | no | 1 | 0 | stale train staging ref, delete |
| `lane/train-20261018T1235-authzc3` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T1241-authzweb` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T1556-authzgeo` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T1634-authzperm` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T1712-authzenrol` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T1809-webgeo` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T1817-webgeotest` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-20261018T1859-web` | 2026-10-18 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-handover-14` | 2026-10-19 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/train-handover-s12` | 2026-10-17 | yes | 0 | 0 | stale train staging ref, delete |
| `lane/ui-amo` | 2026-10-19 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/ui-calm-glass` | 2026-10-17 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/ui-kit` | 2026-10-19 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/ui-packshots` | 2026-10-19 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/ui-sr-a` | 2026-10-19 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/ui-sr-b` | 2026-10-19 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/ui-tso` | 2026-10-19 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/ui-tso-wip` | 2026-10-19 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/web-admin` | 2026-10-23 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/web-config` | 2026-10-15 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `lane/web-dashboard` | 2026-10-23 | yes | 0 | 0 | lane branch fully on INT; keep while the lane is active, else delete after wave 4 |
| `main` | 2026-10-07 | yes | 0 | 0 | release branch, keep |

## The three rows marked inspect (lead reading, 2026-10-23)

- `claude/bc-s5-salvage` (2 non-merge commits, 2026-10-07): backend-core session 5 salvage of F-SYS-012 (server geo re-check on ingest) and checker fixes, written as "WIP, superseded by the woken session". F-SYS-012 is on INT from that woken session; the branch is a safety copy only. Proposal: delete after the owner's yes.
- `claude/db-s3-salvage` (2 non-merge commits, 2026-10-07): db session 3 salvage marked "NOT for lane/db as is": migrations numbered V0039 to V0045 that collide with the numbers INT shipped, plus `db/held/outlet_search_trgm.sql`. Every request it answered was redone on `lane/db` with the right numbers. Proposal: delete after the owner's yes (the held trigram index is in `db/held/` on INT if ever wanted).
- `lane/train-20261007T1856-handover` (1 non-merge commit, 2026-10-07): integrator session 3's handover line in `docs/status/train.md`; the file on INT has every later handover. Proposal: delete with the other train refs.

## What the owner is asked

Nothing today. When wave 4 closes, the lead will ask once: "delete the 176 train staging refs, the 3 inspected refs and the lane branches of lanes that have ended (list attached)?" The two HELD hardening heads, `main`, INT, active lane branches and the dependabot branches stay whatever the answer.

## Added 2026-10-24 10:52 UTC (lead)
| `lane/android-tso-wip` | 6701ef62 | android-tso #4's scratch branch (same commit as lane/android-tso at the time, 09:45); the lane tried to delete it and the container's git proxy refuses ref deletion | delete with the rest, on the owner's word |
