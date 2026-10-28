// Data-lake storage (docs/status/data-architecture-review-2026-10-25.md s4, action row 7; D-INFRA-LAKE-01): one
// StorageV2 account with hierarchical namespace, Hot tier, container `lake`. The worker's nightly export (signed in
// to PostgreSQL as lake_export, which reads only dw.v_*) writes Parquet to lake/v1/<view>/business_date=YYYY-MM-DD/
// with its managed identity; shared-key access is off, so no account key exists to leak. TEST profile: Standard_LRS,
// no zone or geo redundancy (docs/28); the FINAL size is a parameter of the final account's profile.

param location string
param tags object
param storageName string
@description('Pilot: Standard_LRS (docs/28). The final account sets its own; the lake later also holds archived partitions past the 13-month hot window, so it is no longer only a derived copy there.')
@allowed(['Standard_LRS', 'Standard_ZRS'])
param skuName string = 'Standard_LRS'
param lakeContainer string = 'lake'
param logAnalyticsId string

resource st 'Microsoft.Storage/storageAccounts@2024-01-01' = {
  name: storageName
  location: location
  // component=lake: infra/scripts/reset-to-profile.sh compares this account with lakeStorageSku, not storageSku.
  tags: union(tags, { component: 'lake' })
  kind: 'StorageV2'
  sku: { name: skuName }
  properties: {
    isHnsEnabled: true
    accessTier: 'Hot'
    minimumTlsVersion: 'TLS1_2'
    supportsHttpsTrafficOnly: true
    allowBlobPublicAccess: false
    allowSharedKeyAccess: false
    defaultToOAuthAuthentication: true
    allowCrossTenantReplication: false
    isSftpEnabled: false
    isLocalUserEnabled: false
    // TEST profile: the worker has no VNet, so the endpoint stays public; every request needs an Entra token (no key,
    // no SAS is ever minted for this account).
    publicNetworkAccess: 'Enabled'
    networkAcls: { defaultAction: 'Allow', bypass: 'AzureServices' }
    encryption: {
      keySource: 'Microsoft.Storage'
      requireInfrastructureEncryption: false
      services: {
        blob: { enabled: true, keyType: 'Account' }
      }
    }
  }
}

resource blobs 'Microsoft.Storage/storageAccounts/blobServices@2024-01-01' = {
  parent: st
  name: 'default'
  properties: {
    // Versioning and change feed are not available with a hierarchical namespace; soft delete is.
    deleteRetentionPolicy: { enabled: true, days: 14 }
    containerDeleteRetentionPolicy: { enabled: true, days: 14 }
  }
}

resource lake 'Microsoft.Storage/storageAccounts/blobServices/containers@2024-01-01' = {
  parent: blobs
  name: lakeContainer
  properties: { publicAccess: 'None' }
}

resource diagBlob 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: 'to-log-analytics'
  scope: blobs
  properties: {
    workspaceId: logAnalyticsId
    logs: [{ categoryGroup: 'allLogs', enabled: true }]
    metrics: [{ category: 'Transaction', enabled: true }]
  }
}

output storageId string = st.id
output storageName string = st.name
output dfsEndpoint string = st.properties.primaryEndpoints.dfs
