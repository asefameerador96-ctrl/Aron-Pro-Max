// prod = sized for the full fleet (8,500 SRs, about 9,850 app users and 130 web users; docs/18 s3.1 "Full fleet").
// The same file deploys into the final subscription's group; only AZURE_LOCATION and the group change.
using '../main.bicep'

param environmentName = 'prod'
param location = readEnvironmentVariable('AZURE_LOCATION', 'southeastasia')

param vnetAddressPrefix = '10.40.0.0/16'
param acaSubnetPrefix = '10.40.0.0/23'
param postgresSubnetPrefix = '10.40.2.0/28'

param logRetentionDays = 90
param logDailyQuotaGb = 8
param alertEmails = split(readEnvironmentVariable('ARON_ALERT_EMAILS', 'alerts@aron.invalid'), ',')
param budgetAmount = int(readEnvironmentVariable('ARON_BUDGET_AMOUNT', '7000'))
param budgetStartDate = '2026-10-01'

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
param postgresReadReplica = true
param postgresAdminPassword = readEnvironmentVariable('ARON_DB_ADMIN_PASSWORD', '')

param frontDoorSku = 'Premium_AzureFrontDoor'
param wafMode = 'Prevention'
// Managed rules log only until the pilot's real traffic shows no false positive on /v1/sync (docs/18 s2.4).
param wafManagedRuleSetAction = 'Log'
param wafIpRateLimitPer5Min = 60000
param frontDoorPrivateLink = true
