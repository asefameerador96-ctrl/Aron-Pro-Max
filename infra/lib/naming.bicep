// Resource names for one Aron environment, computed in ONE place so infra (main.bicep) and apps (apps.bicep) agree.
// Globally unique names carry a short suffix derived from the resource group id, so the same code deploys unchanged
// into another group or subscription (docs/23 s6: the move is re-running this code).

@export()
@description('Names of every resource of one environment.')
func names(prefix string, env string, suffix string) object => {
  vnet: 'vnet-${prefix}-${env}'
  logAnalytics: 'log-${prefix}-${env}'
  appInsights: 'appi-${prefix}-${env}'
  actionGroup: 'ag-${prefix}-${env}'
  keyVault: take('kv-${prefix}-${env}-${suffix}', 24)
  registry: take(toLower(replace('cr${prefix}${env}${suffix}', '-', '')), 50)
  storage: take(toLower(replace('st${prefix}${env}${suffix}', '-', '')), 24)
  postgres: 'psql-${prefix}-${env}-${suffix}'
  postgresReplica: 'psql-${prefix}-${env}-${suffix}-r1'
  postgresDnsZone: '${prefix}-${env}-${suffix}.private.postgres.database.azure.com'
  containerEnv: 'cae-${prefix}-${env}'
  apiApp: 'ca-${prefix}-${env}-api'
  workerApp: 'ca-${prefix}-${env}-worker'
  webApp: 'ca-${prefix}-${env}-web'
  migrateJob: 'caj-${prefix}-${env}-migrate'
  idApi: 'id-${prefix}-${env}-api'
  idWorker: 'id-${prefix}-${env}-worker'
  idWeb: 'id-${prefix}-${env}-web'
  idMigrate: 'id-${prefix}-${env}-migrate'
  frontDoor: 'afd-${prefix}-${env}'
  frontDoorEndpoint: 'fde-${prefix}-${env}-${suffix}'
  wafPolicy: toLower(replace('waf${prefix}${env}', '-', ''))
  budget: 'budget-${prefix}-${env}'
  eventGridTopic: 'evgt-${prefix}-${env}-storage'
}

@export()
@description('Short, stable suffix for globally unique names: from the resource group id unless one is given.')
func suffixFor(explicitSuffix string, resourceGroupId string) string =>
  empty(explicitSuffix) ? take(uniqueString(resourceGroupId), 6) : explicitSuffix

@export()
@description('Key Vault secret names (docs/24 s6.4 and s13.6). The app reads them through Key Vault references.')
var secretNames = {
  jwtSigningKey: 'aron-jwt-signing-key'
  jwtKid: 'aron-jwt-kid'
  fcmServiceAccount: 'aron-fcm-service-account'
  dbAdminPassword: 'aron-db-admin-password'
  dbUrl: 'aron-db-url'
  dbDirectUrl: 'aron-db-direct-url'
  dbReadUrl: 'aron-db-read-url'
}

@export()
@description('Built-in role definition ids (stable GUIDs, identical in every tenant).')
var roles = {
  acrPull: '7f951dda-4ed3-4680-a7ca-43fe172d538d'
  acrPush: '8311e382-0749-4cb8-b61a-304f252e45ec'
  keyVaultSecretsUser: '4633458b-17de-408a-b874-0445c86b69e6'
  keyVaultSecretsOfficer: 'b86a8fe4-44ce-4948-aee5-eccb2c155cd7'
  storageBlobDataContributor: 'ba92f5b4-2d11-453d-a403-e96b0029c9fe'
  storageBlobDelegator: 'db58b8e5-c6ad-4a2a-8342-4190687cbf4a'
  storageQueueDataMessageProcessor: '8a0f0c08-91a1-4084-bc3d-661d67233fed'
  storageQueueDataMessageSender: 'c6a89b2d-59bc-44d0-9896-0f6e12d7b80a'
}
