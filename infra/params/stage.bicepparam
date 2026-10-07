// stage = the STAGING profile of docs/30 s1: a production-shaped rehearsal in the FINAL account (full-size synthetic
// fleet, load test, failover and restore drills, release-candidate soak, the move rehearsal). PARAMETER FILE ONLY:
// nothing deploys it until the final account exists (deploy.sh and deploy.yml do not offer it; docs/28: no fleet-sized
// resource in the test account). Same templates as prod; it differs from prod.bicepparam only where marked "stage:".
using '../main.bicep'

// Values from the deploy environment (infra/deploy.sh). An unset OR empty variable takes the default, because GitHub
// passes an unset repository variable as an empty string.
var envLocation = readEnvironmentVariable('AZURE_LOCATION', '')
var envSuffix = readEnvironmentVariable('ARON_NAME_SUFFIX', '')
var envEmails = readEnvironmentVariable('ARON_ALERT_EMAILS', '')
var envBudget = readEnvironmentVariable('ARON_BUDGET_AMOUNT', '')
var envBudgetStart = readEnvironmentVariable('ARON_BUDGET_START_DATE', '')
var envDeployBudget = readEnvironmentVariable('ARON_DEPLOY_BUDGET', '')
var envReplica = readEnvironmentVariable('ARON_PG_READ_REPLICA', '')

param environmentName = 'stage'

// FINAL profile switches (docs/28): private networking, Front Door Premium + WAF, log-search alerts.
param privateNetworking = true
param deployFrontDoor = true
param enableLogAlerts = true
param containerEnvZoneRedundant = true
param postgresSkuTier = 'GeneralPurpose'
param postgresStorageType = 'PremiumV2_LRS'
param location = empty(envLocation) ? 'southeastasia' : envLocation
param nameSuffix = envSuffix

// stage: its own address space, so the two VNets could be peered for the move rehearsal.
param vnetAddressPrefix = '10.41.0.0/16'
param acaSubnetPrefix = '10.41.0.0/23'
param postgresSubnetPrefix = '10.41.2.0/28'

// stage: rehearsal logs are kept 30 days; the load test needs the same ingestion headroom as prod.
param logRetentionDays = 30
param logDailyQuotaGb = '8'
param alertEmails = map(split(empty(envEmails) ? 'alerts@aron.invalid' : envEmails, ','), e => trim(e))
// stage: runs at full size only during a rehearsal window; between windows the apps scale to their minimum.
param budgetAmount = int(empty(envBudget) ? '2500' : envBudget)
// Azure refuses a start date before the current month on create and refuses to move it later, so deploy.sh passes
// the existing budget's date, or the first day of the current month when the budget does not exist yet.
param budgetStartDate = empty(envBudgetStart) ? '2026-10-01' : envBudgetStart
// deploy.sh sets ARON_DEPLOY_BUDGET=false only when Azure reports the subscription's cost policy is off (budgets
// cannot exist then); it warns loudly and the owner turns the policy on (docs/status/infra.md).
param deployBudget = envDeployBudget != 'false'

// stage: torn down and recreated between rehearsals; purge protection would block the name for 90 days.
param keyVaultPurgeProtection = false
param registrySku = 'Premium'
// stage: synthetic media only; zone-redundant, no geo copy.
param storageSku = 'Standard_ZRS'

// D8ds_v5 (IOPS cap 12,800) on SSD v2 1,024 GiB at 12,000 IOPS; 300 MB/s is just above the D8 290 MiB/s cap.
param postgresSkuName = 'Standard_D8ds_v5'
param postgresStorageSizeGb = 1024
param postgresStorageIops = 12000
param postgresStorageThroughputMBps = 300
param postgresHaMode = 'ZoneRedundant'
// Live zones of an existing server, read by deploy.sh (a failover swaps them); empty for a new server.
param postgresPrimaryZone = readEnvironmentVariable('ARON_PG_PRIMARY_ZONE', '')
param postgresStandbyZone = readEnvironmentVariable('ARON_PG_STANDBY_ZONE', '')
// stage: the restore drill needs days, not weeks, of history.
param postgresBackupRetentionDays = 7
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
