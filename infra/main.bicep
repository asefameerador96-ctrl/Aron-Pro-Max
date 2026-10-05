// Aron: infrastructure of one environment, deployed INTO ONE EXISTING RESOURCE GROUP (no subscription-scope resource).
// Stage 1 of the deploy (infra/README.md): network, monitoring, Key Vault, registry, storage, PostgreSQL, Container
// Apps environment, identities and role assignments, Front Door + WAF, alerts and the budget. Stage 2 (apps.bicep)
// adds the migrate job, the api/worker/web apps and the Front Door routes once an image exists.
//
//   az deployment group create -g <group> -f infra/main.bicep -p infra/params/dev.bicepparam
//
// Subscription = the CLI's current subscription; region = parameter (default: the group's region); every name is
// derived from namePrefix/environmentName/nameSuffix, so the same code moves to another subscription unchanged.

targetScope = 'resourceGroup'

import { names, suffixFor, secretNames } from 'lib/naming.bicep'

@description('Short product prefix used in every name.')
@minLength(2)
@maxLength(8)
param namePrefix string = 'aron'
@allowed(['dev', 'prod'])
param environmentName string
@description('Azure region. Defaults to the resource group region (bootstrap: AZURE_LOCATION, southeastasia).')
param location string = resourceGroup().location
@description('Suffix for globally unique names; empty = derived from the resource group id.')
param nameSuffix string = ''
param tags object = {}

// Network
param vnetAddressPrefix string
param acaSubnetPrefix string
param postgresSubnetPrefix string

// Monitoring and cost
param logRetentionDays int = 30
param logDailyQuotaGb int
@minLength(1)
param alertEmails array
param budgetAmount int
param budgetStartDate string

// Platform
param keyVaultPurgeProtection bool
@allowed(['Basic', 'Standard', 'Premium'])
param registrySku string
@allowed(['Standard_LRS', 'Standard_ZRS', 'Standard_GZRS', 'Standard_RAGZRS'])
param storageSku string
param containerEnvZoneRedundant bool = true

// PostgreSQL
param postgresSkuName string
param postgresStorageSizeGb int
param postgresStorageIops int
param postgresStorageThroughputMBps int
@allowed(['ZoneRedundant', 'SameZone', 'Disabled'])
param postgresHaMode string = 'ZoneRedundant'
param postgresBackupRetentionDays int
param postgresGeoRedundantBackup bool = true
param postgresReadReplica bool
param postgresAdminLogin string = 'aronadmin'
@secure()
@minLength(16)
@description('Supplied by the deploy workflow (read back from Key Vault after the first run, generated before it).')
param postgresAdminPassword string

// Front Door
@allowed(['Standard_AzureFrontDoor', 'Premium_AzureFrontDoor'])
param frontDoorSku string
@allowed(['Detection', 'Prevention'])
param wafMode string = 'Prevention'
@allowed(['Block', 'Log'])
param wafManagedRuleSetAction string = 'Log'
param wafIpRateLimitPer5Min int
@description('Premium only: Front Door reaches the apps over Private Link and the environment refuses public traffic.')
param frontDoorPrivateLink bool

@description('Principal that runs the deployment; it gets AcrPush and Key Vault Secrets Officer in this group.')
param deployerObjectId string = deployer().objectId

var suffix = suffixFor(nameSuffix, resourceGroup().id)
var n = names(namePrefix, environmentName, suffix)
var allTags = union({ project: 'aron', environment: environmentName, owner: 'aktcl', 'managed-by': 'bicep' }, tags)
var privateLink = frontDoorPrivateLink && frontDoorSku == 'Premium_AzureFrontDoor'
var databaseName = 'aron'

module network 'modules/network.bicep' = {
  name: 'network'
  params: {
    location: location
    tags: allTags
    vnetName: n.vnet
    addressPrefix: vnetAddressPrefix
    acaSubnetPrefix: acaSubnetPrefix
    postgresSubnetPrefix: postgresSubnetPrefix
  }
}

module monitoring 'modules/monitoring.bicep' = {
  name: 'monitoring'
  params: {
    location: location
    tags: allTags
    logAnalyticsName: n.logAnalytics
    appInsightsName: n.appInsights
    actionGroupName: n.actionGroup
    retentionDays: logRetentionDays
    dailyQuotaGb: logDailyQuotaGb
    alertEmails: alertEmails
  }
}

module postgres 'modules/postgres.bicep' = {
  name: 'postgres'
  params: {
    location: location
    tags: allTags
    serverName: n.postgres
    replicaName: n.postgresReplica
    dnsZoneName: n.postgresDnsZone
    vnetId: network.outputs.vnetId
    subnetId: network.outputs.postgresSubnetId
    logAnalyticsId: monitoring.outputs.logAnalyticsId
    databaseName: databaseName
    adminLogin: postgresAdminLogin
    adminPassword: postgresAdminPassword
    skuName: postgresSkuName
    storageSizeGb: postgresStorageSizeGb
    storageIops: postgresStorageIops
    storageThroughputMBps: postgresStorageThroughputMBps
    haMode: postgresHaMode
    backupRetentionDays: postgresBackupRetentionDays
    geoRedundantBackup: postgresGeoRedundantBackup
    enableReadReplica: postgresReadReplica
  }
}

module keyVault 'modules/keyvault.bicep' = {
  name: 'keyvault'
  params: {
    location: location
    tags: allTags
    keyVaultName: n.keyVault
    logAnalyticsId: monitoring.outputs.logAnalyticsId
    purgeProtection: keyVaultPurgeProtection
    postgresAdminPassword: postgresAdminPassword
    postgresAdminLogin: postgresAdminLogin
    postgresHost: postgres.outputs.host
    postgresReadHost: postgres.outputs.readHost
    databaseName: databaseName
    secretNames: secretNames
  }
}

module registry 'modules/registry.bicep' = {
  name: 'registry'
  params: {
    location: location
    tags: allTags
    registryName: n.registry
    sku: registrySku
    logAnalyticsId: monitoring.outputs.logAnalyticsId
  }
}

module storage 'modules/storage.bicep' = {
  name: 'storage'
  params: {
    location: location
    tags: allTags
    storageName: n.storage
    skuName: storageSku
    mediaContainer: 'media'
    bundlesContainer: 'bundles'
    mediaEventsQueue: 'media-events'
    eventGridTopicName: n.eventGridTopic
    logAnalyticsId: monitoring.outputs.logAnalyticsId
  }
}

module containerEnv 'modules/containerenv.bicep' = {
  name: 'containerenv'
  params: {
    location: location
    tags: allTags
    environmentName: n.containerEnv
    subnetId: network.outputs.acaSubnetId
    logAnalyticsId: monitoring.outputs.logAnalyticsId
    zoneRedundant: containerEnvZoneRedundant
    publicNetworkAccess: privateLink ? 'Disabled' : 'Enabled'
  }
}

module identities 'modules/identities.bicep' = {
  name: 'identities'
  params: {
    location: location
    tags: allTags
    names: n
    registryName: n.registry
    keyVaultName: n.keyVault
    storageName: n.storage
    deployerObjectId: deployerObjectId
  }
  dependsOn: [registry, keyVault, storage]
}

module frontDoor 'modules/frontdoor.bicep' = {
  name: 'frontdoor'
  params: {
    tags: allTags
    profileName: n.frontDoor
    endpointName: n.frontDoorEndpoint
    wafPolicyName: n.wafPolicy
    logAnalyticsId: monitoring.outputs.logAnalyticsId
    sku: frontDoorSku
    wafMode: wafMode
    managedRuleSetAction: wafManagedRuleSetAction
    ipRateLimitPer5Min: wafIpRateLimitPer5Min
  }
}

module alerts 'modules/alerts.bicep' = {
  name: 'alerts'
  params: {
    location: location
    tags: allTags
    namePrefix: '${namePrefix}-${environmentName}'
    appInsightsId: monitoring.outputs.appInsightsId
    postgresId: postgres.outputs.serverId
    actionGroupId: monitoring.outputs.actionGroupId
  }
}

module budget 'modules/budget.bicep' = {
  name: 'budget'
  params: {
    budgetName: n.budget
    amount: budgetAmount
    contactEmails: alertEmails
    startDate: budgetStartDate
  }
}

output resourceGroupName string = resourceGroup().name
output location string = location
output nameSuffix string = suffix
output registryLoginServer string = registry.outputs.loginServer
output registryName string = n.registry
output keyVaultName string = keyVault.outputs.keyVaultName
output containerEnvName string = n.containerEnv
output migrateJobName string = n.migrateJob
output apiAppName string = n.apiApp
output workerAppName string = n.workerApp
output webAppName string = n.webApp
output frontDoorProfileName string = n.frontDoor
output frontDoorEndpointHost string = frontDoor.outputs.endpointHost
output postgresServerName string = n.postgres
output storageAccountName string = n.storage
output privateLinkOrigin bool = privateLink
output budgetName string = n.budget
