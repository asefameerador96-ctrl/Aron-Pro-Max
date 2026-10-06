// Log Analytics + workspace-based Application Insights + the action group every alert uses.

param location string
param tags object
param logAnalyticsName string
param appInsightsName string
param actionGroupName string
@minValue(30)
param retentionDays int
@description('Daily ingestion cap in GB as a decimal string (for example \'0.5\'); \'-1\' = no cap. docs/18 s3.3: 5.5 GB a day for the fleet.')
param dailyQuotaGb string
@description('E-mail addresses that receive budget and platform alerts.')
param alertEmails array

resource law 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: logAnalyticsName
  location: location
  tags: tags
  properties: {
    sku: { name: 'PerGB2018' }
    retentionInDays: retentionDays
    workspaceCapping: { dailyQuotaGb: json(dailyQuotaGb) }
    features: { enableLogAccessUsingOnlyResourcePermissions: true }
  }
}

resource appi 'Microsoft.Insights/components@2020-02-02' = {
  name: appInsightsName
  location: location
  tags: tags
  kind: 'java'
  properties: {
    Application_Type: 'other'
    WorkspaceResourceId: law.id
    IngestionMode: 'LogAnalytics'
    // Telemetry is sent with the connection string; local auth stays on because the Java agent 3.7 in a container
    // authenticates with the connection string unless AAD auth is configured (left for the backend lane).
    DisableLocalAuth: false
  }
}

resource actionGroup 'Microsoft.Insights/actionGroups@2023-01-01' = {
  name: actionGroupName
  location: 'global'
  tags: tags
  properties: {
    groupShortName: take(replace(actionGroupName, '-', ''), 12)
    enabled: true
    emailReceivers: [for (email, i) in alertEmails: {
      name: 'email-${i}'
      emailAddress: email
      useCommonAlertSchema: true
    }]
  }
}

output logAnalyticsId string = law.id
output appInsightsId string = appi.id
output appInsightsConnectionString string = appi.properties.ConnectionString
output actionGroupId string = actionGroup.id
