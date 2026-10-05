// Azure Container Registry for the one backend image (roles api, worker, migrate) and the web image.
// Admin user off: Container Apps pull with their managed identity (AcrPull), the deploy identity pushes (AcrPush).

param location string
param tags object
param registryName string
@allowed(['Basic', 'Standard', 'Premium'])
param sku string
param logAnalyticsId string

resource acr 'Microsoft.ContainerRegistry/registries@2023-07-01' = {
  name: registryName
  location: location
  tags: tags
  sku: { name: sku }
  properties: {
    adminUserEnabled: false
    publicNetworkAccess: 'Enabled'
    zoneRedundancy: sku == 'Premium' ? 'Enabled' : 'Disabled'
    policies: {
      retentionPolicy: sku == 'Premium' ? { days: 30, status: 'enabled' } : null
    }
  }
}

resource diag 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: 'to-log-analytics'
  scope: acr
  properties: {
    workspaceId: logAnalyticsId
    logs: [{ categoryGroup: 'allLogs', enabled: true }]
    metrics: [{ category: 'AllMetrics', enabled: true }]
  }
}

output registryId string = acr.id
output loginServer string = acr.properties.loginServer
