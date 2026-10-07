// dev = the REHEARSAL profile: exactly the resources that exist in rg-aron-dev since 2026-10-06 (docs/28 "Exception
// approved": kept for up to one week to rehearse the final topology; review 2026-10-10). Every value below matches what
// was created, so a deploy ADOPTS them; infra/deploy.sh runs a what-if first and refuses any change to the PostgreSQL
// server. No fleet-sized additions (no read replica, no bigger SKU, no Load Testing). The cheap TEST profile is
// dev-lite*.bicepparam, used only after an owner-approved reset.
using '../main.bicep'

// Values from the deploy environment (infra/deploy.sh). An unset OR empty variable takes the default, because GitHub
// passes an unset repository variable as an empty string.
var envLocation = readEnvironmentVariable('AZURE_LOCATION', '')
var envSuffix = readEnvironmentVariable('ARON_NAME_SUFFIX', '')
var envEmails = readEnvironmentVariable('ARON_ALERT_EMAILS', '')
var envBudget = readEnvironmentVariable('ARON_BUDGET_AMOUNT', '')
var envBudgetStart = readEnvironmentVariable('ARON_BUDGET_START_DATE', '')
var envDeployBudget = readEnvironmentVariable('ARON_DEPLOY_BUDGET', '')

param environmentName = 'dev'
param location = empty(envLocation) ? 'southeastasia' : envLocation
param nameSuffix = envSuffix

// As created: VNet, private PostgreSQL, Front Door Standard + WAF, log-search alerts.
param privateNetworking = true
param deployFrontDoor = true
param enableLogAlerts = true
param containerEnvZoneRedundant = true

param vnetAddressPrefix = '10.51.0.0/16'
param acaSubnetPrefix = '10.51.0.0/24'
param postgresSubnetPrefix = '10.51.2.0/28'

param logRetentionDays = 30
param logDailyQuotaGb = '1'
param alertEmails = map(split(empty(envEmails) ? 'alerts@aron.invalid' : envEmails, ','), e => trim(e))
// docs/28 exception: about USD 130 for the rehearsal week, e-mails at 50, 90 and 100 % and forecast 100 %.
param budgetAmount = int(empty(envBudget) ? '130' : envBudget)
param budgetStartDate = empty(envBudgetStart) ? '2026-10-01' : envBudgetStart
// deploy.sh sets ARON_DEPLOY_BUDGET=false only when Azure reports the subscription's cost policy is off (budgets
// cannot exist then); it warns loudly and the owner turns the policy on (docs/status/infra.md).
param deployBudget = envDeployBudget != 'false'

param keyVaultPurgeProtection = false
param registrySku = 'Basic'
param storageSku = 'Standard_ZRS'

// As created (do not change: tier, storage type and geo backup are fixed at creation; the deploy refuses changes).
param postgresSkuTier = 'GeneralPurpose'
param postgresStorageType = 'PremiumV2_LRS'
param postgresSkuName = 'Standard_D2ds_v5'
param postgresStorageSizeGb = 128
param postgresStorageIops = 3000
param postgresStorageThroughputMBps = 125
param postgresHaMode = 'ZoneRedundant'
// Live zones of an existing server, read by deploy.sh (a failover swaps them); empty for a new server.
param postgresPrimaryZone = readEnvironmentVariable('ARON_PG_PRIMARY_ZONE', '')
param postgresStandbyZone = readEnvironmentVariable('ARON_PG_STANDBY_ZONE', '')
param postgresBackupRetentionDays = 7
param postgresGeoRedundantBackup = true
param postgresReadReplica = false
param postgresAdminPassword = readEnvironmentVariable('ARON_DB_ADMIN_PASSWORD', '')

param frontDoorSku = 'Standard_AzureFrontDoor'
param wafMode = 'Prevention'
param wafManagedRuleSetAction = 'Log'
param wafIpRateLimitPer5Min = 20000
param frontDoorPrivateLink = false
