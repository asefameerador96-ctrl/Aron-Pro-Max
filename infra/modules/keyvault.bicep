// Key Vault (RBAC authorisation, soft delete) holding the runtime secrets of docs/24 s6.4 and s13.6.
// Secrets whose value Bicep knows (database URLs built from the admin password) are written here; the JWT key and
// the FCM service account and the web session secret are seeded by the deploy workflow (infra/scripts/seed-secrets.sh) only when absent, so a
// redeploy never rotates them by accident.

param location string
param tags object
param keyVaultName string
param logAnalyticsId string
@description('Purge protection cannot be turned off again; on for prod. Off for dev so the sponsor can purge a deleted vault; a rebuilt group within 90 days otherwise needs a new nameSuffix (GitHub variable ARON_NAME_SUFFIX).')
param purgeProtection bool
@secure()
param postgresAdminPassword string
param postgresAdminLogin string
param postgresHost string
param postgresReadHost string
@description('6432 with the built-in PgBouncer; 5432 where there is none (Burstable).')
param pooledPort int = 6432
param databaseName string
param secretNames object

resource kv 'Microsoft.KeyVault/vaults@2024-11-01' = {
  name: keyVaultName
  location: location
  tags: tags
  properties: {
    tenantId: subscription().tenantId
    sku: { family: 'A', name: 'standard' }
    enableRbacAuthorization: true
    enableSoftDelete: true
    softDeleteRetentionInDays: 90
    enablePurgeProtection: purgeProtection ? true : null
    // The GitHub runner seeds secrets and Container Apps read them over the public endpoint with Entra auth (RBAC);
    // a private endpoint is a later hardening step (README "Not in this version").
    publicNetworkAccess: 'Enabled'
    networkAcls: { defaultAction: 'Allow', bypass: 'AzureServices' }
  }
}

// Pooled connections go through the built-in PgBouncer (6432, transaction mode): no server-side prepared statements.
// Without PgBouncer (Burstable, TEST profile) the same URL points at 5432 and the app's own pool is the only pool.
var pooledParams = 'sslmode=require&prepareThreshold=0&user=${postgresAdminLogin}&password=${postgresAdminPassword}'
// Direct connections (5432) for Flyway (session advisory lock) and the worker (advisory locks for run-once jobs).
var directParams = 'sslmode=require&user=${postgresAdminLogin}&password=${postgresAdminPassword}'

resource secretDbPassword 'Microsoft.KeyVault/vaults/secrets@2024-11-01' = {
  parent: kv
  name: secretNames.dbAdminPassword
  properties: { value: postgresAdminPassword, contentType: 'text/plain' }
}

resource secretDbUrl 'Microsoft.KeyVault/vaults/secrets@2024-11-01' = {
  parent: kv
  name: secretNames.dbUrl
  properties: { value: 'jdbc:postgresql://${postgresHost}:${pooledPort}/${databaseName}?${pooledParams}', contentType: 'jdbc-url' }
}

resource secretDbDirectUrl 'Microsoft.KeyVault/vaults/secrets@2024-11-01' = {
  parent: kv
  name: secretNames.dbDirectUrl
  properties: { value: 'jdbc:postgresql://${postgresHost}:5432/${databaseName}?${directParams}', contentType: 'jdbc-url' }
}

resource secretDbReadUrl 'Microsoft.KeyVault/vaults/secrets@2024-11-01' = {
  parent: kv
  name: secretNames.dbReadUrl
  properties: { value: 'jdbc:postgresql://${postgresReadHost}:${pooledPort}/${databaseName}?${pooledParams}', contentType: 'jdbc-url' }
}

resource diag 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: 'to-log-analytics'
  scope: kv
  properties: {
    workspaceId: logAnalyticsId
    logs: [{ categoryGroup: 'audit', enabled: true }, { categoryGroup: 'allLogs', enabled: true }]
    metrics: [{ category: 'AllMetrics', enabled: true }]
  }
}

output keyVaultId string = kv.id
output keyVaultName string = kv.name
output keyVaultUri string = kv.properties.vaultUri
