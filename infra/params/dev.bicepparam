// dev = the pilot environment in rg-aron-dev (about 30 users; docs/18 s3.1 "Pilot" column), zone-redundant from
// day one because zone redundancy and geo-redundant backup can only be chosen at creation. See infra/README.md.
using '../main.bicep'

// Values from the deploy environment (infra/deploy.sh). An unset OR empty variable takes the default, because GitHub
// passes an unset repository variable as an empty string.
var envLocation = readEnvironmentVariable('AZURE_LOCATION', '')
var envSuffix = readEnvironmentVariable('ARON_NAME_SUFFIX', '')
var envEmails = readEnvironmentVariable('ARON_ALERT_EMAILS', '')
var envBudget = readEnvironmentVariable('ARON_BUDGET_AMOUNT', '')
var envBudgetStart = readEnvironmentVariable('ARON_BUDGET_START_DATE', '')

param environmentName = 'dev'
param location = empty(envLocation) ? 'southeastasia' : envLocation
param nameSuffix = envSuffix

param vnetAddressPrefix = '10.51.0.0/16'
param acaSubnetPrefix = '10.51.0.0/24'
param postgresSubnetPrefix = '10.51.2.0/28'

param logRetentionDays = 30
param logDailyQuotaGb = 1
param alertEmails = map(split(empty(envEmails) ? 'alerts@aron.invalid' : envEmails, ','), e => trim(e))
param budgetAmount = int(empty(envBudget) ? '800' : envBudget)
// Azure refuses a start date before the current month on create and refuses to move it later, so deploy.sh passes
// the existing budget's date, or the first day of the current month when the budget does not exist yet.
param budgetStartDate = empty(envBudgetStart) ? '2026-10-01' : envBudgetStart

param keyVaultPurgeProtection = false
param registrySku = 'Basic'
param storageSku = 'Standard_ZRS'

// D2ds_v5 (2 vCores) with a zone-redundant standby; SSD v2 128 GiB at the free 3,000 IOPS / 125 MB/s baseline.
param postgresSkuName = 'Standard_D2ds_v5'
param postgresStorageSizeGb = 128
param postgresStorageIops = 3000
param postgresStorageThroughputMBps = 125
param postgresHaMode = 'ZoneRedundant'
param postgresBackupRetentionDays = 7
param postgresGeoRedundantBackup = true
param postgresReadReplica = false
param postgresAdminPassword = readEnvironmentVariable('ARON_DB_ADMIN_PASSWORD', '')

// Standard: custom WAF rules only (managed rules and Private Link are Premium features).
param frontDoorSku = 'Standard_AzureFrontDoor'
param wafMode = 'Prevention'
param wafManagedRuleSetAction = 'Log'
param wafIpRateLimitPer5Min = 20000
param frontDoorPrivateLink = false
