// dev-lite = the cheap TEST profile of docs/28 (binding), used only after an owner-approved reset of rg-aron-dev: the temporary test subscription, 5 to 10 pilot users, a USD 100 a month
// owner budget shared with Google Maps. Target about USD 35 to 50 a month (estimate in docs/status/infra.md).
// No fleet-sized, zone-redundant, geo-redundant or Premium value may appear here without the sponsor's written yes.
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

// TEST profile switches: no VNet (no environment load balancer or public IPs to pay for), no Front Door/WAF (clients
// use the Container Apps address), no log-search alert rules (billed per rule); metric alerts and the budget stay.
param privateNetworking = false
param deployFrontDoor = false
param enableLogAlerts = false
param containerEnvZoneRedundant = false

// Unused without privateNetworking; kept so the file also documents the address plan.
param vnetAddressPrefix = '10.51.0.0/16'
param acaSubnetPrefix = '10.51.0.0/24'
param postgresSubnetPrefix = '10.51.2.0/28'

param logRetentionDays = 30
// docs/28: 0.5 GB a day. Pilot ingestion is far below the 5 GB a month Log Analytics bills at no charge, so the cap is
// a guard against a runaway logger, not a cost line.
param logDailyQuotaGb = '0.5'
param alertEmails = map(split(empty(envEmails) ? 'alerts@aron.invalid' : envEmails, ','), e => trim(e))
// The Azure share of the owner's USD 100 a month (Maps takes the rest); e-mails at 50, 90 and 100 % and forecast 100 %.
param budgetAmount = int(empty(envBudget) ? '70' : envBudget)
// Azure refuses a start date before the current month on create and refuses to move it later, so deploy.sh passes
// the existing budget's date, or the first day of the current month when the budget does not exist yet.
param budgetStartDate = empty(envBudgetStart) ? '2026-10-01' : envBudgetStart
// deploy.sh sets ARON_DEPLOY_BUDGET=false only when Azure reports the subscription's cost policy is off (budgets
// cannot exist then); it warns loudly and the owner turns the policy on (docs/status/infra.md).
param deployBudget = envDeployBudget != 'false'

param keyVaultPurgeProtection = false
param registrySku = 'Basic'
param storageSku = 'Standard_LRS'

// Burstable B1ms (1 vCore, 2 GiB; USD 0.026/h in Southeast Asia, about USD 19 a month): single zone, no HA, no replica,
// no geo-redundant backup, 32 GiB SSD v1 (about USD 4.4), 7-day point-in-time restore. B2s is USD 0.104/h (about USD
// 76 a month) and would exceed the budget on its own. Burstable has no built-in PgBouncer: the app's pool is the pool.
param postgresSkuTier = 'Burstable'
param postgresSkuName = 'Standard_B1ms'
param postgresStorageType = 'Premium_LRS'
param postgresStorageSizeGb = 32
param postgresStorageIops = 120
param postgresStorageThroughputMBps = 25
param postgresHaMode = 'Disabled'
param postgresBackupRetentionDays = 7
param postgresGeoRedundantBackup = false
param postgresReadReplica = false
param postgresAdminPassword = readEnvironmentVariable('ARON_DB_ADMIN_PASSWORD', '')

// Front Door is not created in the TEST profile; these values only keep the template's parameter set complete.
param frontDoorSku = 'Standard_AzureFrontDoor'
param wafMode = 'Prevention'
param wafManagedRuleSetAction = 'Log'
param wafIpRateLimitPer5Min = 20000
param frontDoorPrivateLink = false
