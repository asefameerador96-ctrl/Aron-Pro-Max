// prod = sized for the full fleet (8,500 SRs, about 9,850 app users and 130 web users; docs/18 s3.1 "Full fleet").
// The same file deploys into the final subscription's group; only AZURE_LOCATION and the group change.
using '../main.bicep'

// Values from the deploy environment (infra/deploy.sh). An unset OR empty variable takes the default, because GitHub
// passes an unset repository variable as an empty string.
var envLocation = readEnvironmentVariable('AZURE_LOCATION', '')
var envSuffix = readEnvironmentVariable('ARON_NAME_SUFFIX', '')
var envEmails = readEnvironmentVariable('ARON_ALERT_EMAILS', '')
var envBudget = readEnvironmentVariable('ARON_BUDGET_AMOUNT', '')
var envBudgetStart = readEnvironmentVariable('ARON_BUDGET_START_DATE', '')
var envReplica = readEnvironmentVariable('ARON_PG_READ_REPLICA', '')

param environmentName = 'prod'
param location = empty(envLocation) ? 'southeastasia' : envLocation
param nameSuffix = envSuffix

param vnetAddressPrefix = '10.40.0.0/16'
param acaSubnetPrefix = '10.40.0.0/23'
param postgresSubnetPrefix = '10.40.2.0/28'

param logRetentionDays = 90
param logDailyQuotaGb = 8
param alertEmails = map(split(empty(envEmails) ? 'alerts@aron.invalid' : envEmails, ','), e => trim(e))
param budgetAmount = int(empty(envBudget) ? '7000' : envBudget)
// Azure refuses a start date before the current month on create and refuses to move it later, so deploy.sh passes
// the existing budget's date, or the first day of the current month when the budget does not exist yet.
param budgetStartDate = empty(envBudgetStart) ? '2026-10-01' : envBudgetStart

param keyVaultPurgeProtection = true
param registrySku = 'Premium'
param storageSku = 'Standard_RAGZRS'

// D8ds_v5 (IOPS cap 12,800) on SSD v2 1,024 GiB at 12,000 IOPS; 300 MB/s is just above the D8 290 MiB/s cap.
param postgresSkuName = 'Standard_D8ds_v5'
param postgresStorageSizeGb = 1024
param postgresStorageIops = 12000
param postgresStorageThroughputMBps = 300
param postgresHaMode = 'ZoneRedundant'
param postgresBackupRetentionDays = 35
param postgresGeoRedundantBackup = true
// Premium SSD v2 must finish its first backup before an in-region replica can be created (Learn), so the first prod
// deploy runs with ARON_PG_READ_REPLICA=false and the next one adds the replica.
param postgresReadReplica = empty(envReplica) ? true : bool(envReplica)
param postgresAdminPassword = readEnvironmentVariable('ARON_DB_ADMIN_PASSWORD', '')

param frontDoorSku = 'Premium_AzureFrontDoor'
param wafMode = 'Prevention'
// Managed rules log only until the pilot's real traffic shows no false positive on /v1/sync (docs/18 s2.4).
param wafManagedRuleSetAction = 'Log'
param wafIpRateLimitPer5Min = 60000
param frontDoorPrivateLink = true
