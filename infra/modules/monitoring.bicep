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

// Ops workbook (N-062): sync health, errors, latency and the release markers deploy.sh writes (time charts show them).
// Workbooks are free; the queries read the same App Insights data the alerts use.
func kql(title string, query string, viz string) object => {
  type: 3
  content: {
    version: 'KqlItem/1.0'
    title: title
    query: query
    size: 0
    timeContext: { durationMs: 86400000 }
    queryType: 0
    resourceType: 'microsoft.insights/components'
    visualization: viz
    chartSettings: { showAnnotations: true }
  }
}

var workbookItems = [
  {
    type: 1
    content: { json: '## Aron operations\nSync health, errors and latency for the last 24 hours. Release markers (deploys, rollbacks) appear on the time charts. Alerts: ${appInsightsName} log alerts syncErrors, aggregationStuck, batch5xx, batchP95, dashboardsP95.' }
  }
  kql('Sync batches per 5 minutes (total, 5xx)', 'requests\n| where name has "/v1/sync/batch"\n| summarize total = count(), failed = countif(toint(resultCode) >= 500) by bin(timestamp, 5m)', 'timechart')
  kql('Sync batch latency p50 / p95 (ms)', 'requests\n| where name has "/v1/sync/batch"\n| summarize p50 = percentile(duration, 50), p95 = percentile(duration, 95) by bin(timestamp, 5m)', 'timechart')
  kql('Errors by logger (sync, analytics, all)', 'union traces, exceptions\n| where itemType == "exception" or severityLevel >= 3\n| extend logger = tostring(customDimensions.LoggerName)\n| summarize errors = count() by logger, bin(timestamp, 15m)', 'timechart')
  kql('Aggregation failures (key stays queued)', 'union traces, exceptions\n| where tostring(customDimensions.LoggerName) == "aron.analytics.worker" and (itemType == "exception" or severityLevel >= 3)\n| summarize failures = count() by bin(timestamp, 15m)', 'timechart')
  kql('Slowest endpoints (p95, ms)', 'requests\n| summarize calls = count(), p95 = percentile(duration, 95), failed = countif(toint(resultCode) >= 500) by name\n| top 15 by p95 desc', 'table')
  kql('Latest errors', 'union traces, exceptions\n| where itemType == "exception" or severityLevel >= 3\n| project timestamp, logger = tostring(customDimensions.LoggerName), message = coalesce(message, outerMessage), cloud_RoleName\n| top 50 by timestamp desc', 'table')
]

resource workbook 'Microsoft.Insights/workbooks@2023-06-01' = {
  name: guid(resourceGroup().id, appInsightsName, 'aron-ops-workbook')
  location: location
  tags: tags
  kind: 'shared'
  properties: {
    displayName: 'Aron operations (${appInsightsName})'
    category: 'workbook'
    sourceId: appi.id
    serializedData: string({ version: 'Notebook/1.0', items: workbookItems, isLocked: false })
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
output workbookName string = workbook.properties.displayName
