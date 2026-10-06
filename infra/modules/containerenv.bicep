// Container Apps environment: workload profiles (Consumption profile), VNet-injected, zone-redundant (a creation-time
// choice that cannot be changed later). Logs go to Log Analytics through a diagnostic setting (no shared keys).

param location string
param tags object
param environmentName string
@description('Infrastructure subnet; empty = no VNet (Microsoft-managed network, no environment load balancer or public IPs to pay for).')
param subnetId string
param logAnalyticsId string
param zoneRedundant bool
@description('Disabled = only Front Door Premium reaches the apps, over Private Link (the deploy approves the link).')
param publicNetworkAccess string

resource env 'Microsoft.App/managedEnvironments@2025-07-01' = {
  name: environmentName
  location: location
  tags: tags
  properties: {
    zoneRedundant: zoneRedundant
    vnetConfiguration: empty(subnetId) ? null : {
      infrastructureSubnetId: subnetId
      internal: false
    }
    workloadProfiles: [
      { name: 'Consumption', workloadProfileType: 'Consumption' }
    ]
    appLogsConfiguration: { destination: 'azure-monitor' }
    publicNetworkAccess: publicNetworkAccess
    peerTrafficConfiguration: { encryption: { enabled: true } }
  }
}

resource diag 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: 'to-log-analytics'
  scope: env
  properties: {
    workspaceId: logAnalyticsId
    logs: [{ categoryGroup: 'allLogs', enabled: true }]
    metrics: [{ category: 'AllMetrics', enabled: true }]
  }
}

output environmentId string = env.id
output defaultDomain string = env.properties.defaultDomain
