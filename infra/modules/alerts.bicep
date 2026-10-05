// Azure Monitor alerts of docs/24 s13.1 that do not depend on application-defined metrics, plus database capacity
// alerts from docs/18 s3.5. Worker lag, payload_conflict and memo_no_duplicate need metrics the backend emits;
// they are added when the backend lane names them (docs/requests/infra-backend-runtime.md).

param location string
param tags object
param namePrefix string
param appInsightsId string
param postgresId string
param actionGroupId string

var appRequests = {
  batch5xx: {
    description: 'POST /v1/sync/batch: more than 1 % 5xx over 5 minutes (docs/24 s13.1).'
    severity: 1
    window: 'PT5M'
    query: '''
requests
| where name has "/v1/sync/batch"
| summarize total = count(), failed = countif(toint(resultCode) >= 500)
| where total >= 20 and failed * 100.0 / total > 1
'''
  }
  batchP95: {
    description: 'POST /v1/sync/batch: p95 above 3 s over 10 minutes (docs/24 s13.1, SLO 1.5 s).'
    severity: 2
    window: 'PT10M'
    query: '''
requests
| where name has "/v1/sync/batch"
| summarize p95 = percentile(duration, 95), total = count()
| where total >= 20 and p95 > 3000
'''
  }
  dashboardsP95: {
    description: 'Dashboards and app home: p95 above 1 s over 10 minutes (docs/24 s13.1).'
    severity: 3
    window: 'PT10M'
    query: '''
requests
| where name has "/v1/dashboards" or name has "/v1/app/home"
| summarize p95 = percentile(duration, 95), total = count()
| where total >= 20 and p95 > 1000
'''
  }
}

resource queryAlerts 'Microsoft.Insights/scheduledQueryRules@2023-12-01' = [for a in items(appRequests): {
  name: '${namePrefix}-${a.key}'
  location: location
  tags: tags
  properties: {
    displayName: '${namePrefix}-${a.key}'
    description: a.value.description
    severity: a.value.severity
    enabled: true
    scopes: [appInsightsId]
    evaluationFrequency: 'PT5M'
    windowSize: a.value.window
    criteria: {
      allOf: [
        {
          query: a.value.query
          timeAggregation: 'Count'
          operator: 'GreaterThan'
          threshold: 0
          failingPeriods: { numberOfEvaluationPeriods: 1, minFailingPeriodsToAlert: 1 }
        }
      ]
    }
    autoMitigate: true
    actions: { actionGroups: [actionGroupId] }
  }
}]

var pgMetrics = [
  { name: 'pg-cpu', metric: 'cpu_percent', threshold: 80, window: 'PT15M', severity: 2, description: 'PostgreSQL CPU above 80 % for 15 minutes: next SKU (docs/18 s3.5).' }
  { name: 'pg-storage', metric: 'storage_percent', threshold: 70, window: 'PT30M', severity: 2, description: 'PostgreSQL storage above 70 %: grow now, SSD v2 has no autogrow (docs/18 s3.5).' }
  { name: 'pg-connections-failed', metric: 'connections_failed', threshold: 10, window: 'PT5M', severity: 2, description: 'PostgreSQL failed connections.' }
]

resource metricAlerts 'Microsoft.Insights/metricAlerts@2018-03-01' = [for m in pgMetrics: {
  name: '${namePrefix}-${m.name}'
  location: 'global'
  tags: tags
  properties: {
    description: m.description
    severity: m.severity
    enabled: true
    scopes: [postgresId]
    evaluationFrequency: 'PT5M'
    windowSize: m.window
    criteria: {
      'odata.type': 'Microsoft.Azure.Monitor.SingleResourceMultipleMetricCriteria'
      allOf: [
        {
          criterionType: 'StaticThresholdCriterion'
          name: m.metric
          metricName: m.metric
          metricNamespace: 'Microsoft.DBforPostgreSQL/flexibleServers'
          operator: 'GreaterThan'
          threshold: m.threshold
          timeAggregation: m.metric == 'connections_failed' ? 'Total' : 'Average'
        }
      ]
    }
    autoMitigate: true
    actions: [{ actionGroupId: actionGroupId }]
  }
}]
