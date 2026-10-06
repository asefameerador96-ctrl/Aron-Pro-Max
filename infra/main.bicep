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

// Profile switches (docs/28: TEST profile in dev*, FINAL profile in prod*; same templates, only parameters differ)
@description('true: VNet, private PostgreSQL, VNet-injected Container Apps (FINAL). false: no VNet; PostgreSQL public endpoint limited to Azure services by its firewall, TLS required (TEST, saves the environment load balancer and public IPs).')
param privateNetworking bool = true
@description('true: Front Door + WAF in front of the apps (FINAL). false: clients use the Container Apps address directly (TEST).')
param deployFrontDoor bool = true
@description('Log-search alert rules on App Insights (each is billed monthly); metric alerts and the budget stay on regardless.')
param enableLogAlerts bool = true

// Network (used only when privateNetworking)
param vnetAddressPrefix string
param acaSubnetPrefix string
param postgresSubnetPrefix string

// Monitoring and cost
param logRetentionDays int = 30
@description('Log Analytics daily cap in GB, decimal string (docs/28: \'0.5\' in the TEST profile).')
param logDailyQuotaGb string
@minLength(1)
param alertEmails array
param budgetAmount int
param budgetStartDate string
@description('False only when the subscription\'s cost policy is off (Azure then refuses every budget); deploy.sh decides.')
param deployBudget bool = true

// Platform
param keyVaultPurgeProtection bool
@allowed(['Basic', 'Standard', 'Premium'])
param registrySku string
@allowed(['Standard_LRS', 'Standard_ZRS', 'Standard_GZRS', 'Standard_RAGZRS'])
param storageSku string
@description('Zone redundancy needs a VNet (privateNetworking); it is ignored without one.')
param containerEnvZoneRedundant bool = true

// PostgreSQL
@allowed(['Burstable', 'GeneralPurpose', 'MemoryOptimized'])
param postgresSkuTier string = 'GeneralPurpose'
@description('PremiumV2_LRS (SSD v2, GeneralPurpose/MemoryOptimized only) or Premium_LRS (SSD v1, any tier, required for Burstable).')
@allowed(['PremiumV2_LRS', 'Premium_LRS'])
param postgresStorageType string = 'PremiumV2_LRS'
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
var privateLink = deployFrontDoor && privateNetworking && frontDoorPrivateLink && frontDoorSku == 'Premium_AzureFrontDoor'
// Built-in PgBouncer does not exist on Burstable (Learn); the pooled URL then points at 5432 like the direct one.
var pgbouncerEnabled = postgresSkuTier != 'Burstable'
var databaseName = 'aron'

module network 'modules/network.bicep' = if (privateNetworking) {
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

// Network ids built from the names, not read from the network module's outputs: what-if cannot resolve a module
// output at preview time and would report every adopted server and environment as moving subnet (infra/deploy.sh
// refuses that). dependsOn keeps the creation order the outputs used to give.
var vnetId = resourceId('Microsoft.Network/virtualNetworks', n.vnet)
var pgSubnetId = resourceId('Microsoft.Network/virtualNetworks/subnets', n.vnet, 'snet-pg')
var acaSubnetId = resourceId('Microsoft.Network/virtualNetworks/subnets', n.vnet, 'snet-aca')

module postgres 'modules/postgres.bicep' = {
  name: 'postgres'
  dependsOn: [network]
  params: {
    location: location
    tags: allTags
    serverName: n.postgres
    replicaName: n.postgresReplica
    dnsZoneName: n.postgresDnsZone
    privateNetworking: privateNetworking
    vnetId: privateNetworking ? vnetId : ''
    subnetId: privateNetworking ? pgSubnetId : ''
    skuTier: postgresSkuTier
    storageType: postgresStorageType
    pgbouncerEnabled: pgbouncerEnabled
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
    pooledPort: pgbouncerEnabled ? 6432 : 5432
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
  dependsOn: [network]
  params: {
    location: location
    tags: allTags
    environmentName: n.containerEnv
    subnetId: privateNetworking ? acaSubnetId : ''

    logAnalyticsId: monitoring.outputs.logAnalyticsId
    zoneRedundant: privateNetworking && containerEnvZoneRedundant
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

module frontDoor 'modules/frontdoor.bicep' = if (deployFrontDoor) {
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
    enableLogAlerts: enableLogAlerts
  }
}

module budget 'modules/budget.bicep' = if (deployBudget) {
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
output frontDoorEndpointHost string = deployFrontDoor ? frontDoor!.outputs.endpointHost : ''
output frontDoorEnabled bool = deployFrontDoor
output postgresServerName string = n.postgres
output storageAccountName string = n.storage
output privateLinkOrigin bool = privateLink
output budgetName string = deployBudget ? n.budget : ''
