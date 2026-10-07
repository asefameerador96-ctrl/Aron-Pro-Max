// Azure Monitor alerts of docs/24 s13.1 that do not depend on application-defined metrics, plus database capacity
// alerts from docs/18 s3.5. Worker lag, payload_conflict and memo_no_duplicate need metrics the backend emits;
// they are added when the backend lane names them (docs/requests/infra-backend-runtime.md).

param location string
param tags object
param namePrefix string
param appInsightsId string
param postgresId string
param actionGroupId string
@description('Log-search rules are billed per rule per month; off in the TEST profile, metric alerts stay.')
param enableLogAlerts bool = true
@description('Service Health alert for this subscription (region incidents). Its scope is the subscription, which the deploy identity (rights on this group only) may not be able to use; off until the lead grants subscription Reader (docs/requests/infra-service-health-scope.md).')
param enableServiceHealthAlert bool = false

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

resource queryAlerts 'Microsoft.Insights/scheduledQueryRules@2023-12-01' = [for a in items(appRequests): if (enableLogAlerts) {
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
  // AUD-REL-04: the database is down (is_db_alive drops to 0). HA state changes reach the Resource Health alert below.
  { name: 'pg-not-alive', metric: 'is_db_alive', threshold: 1, window: 'PT5M', severity: 1, description: 'PostgreSQL is not alive for 5 minutes. Owner: infra lane. Runbook: RB-02 (database failover; not written yet, docs/runbooks/README.md).' }
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
          operator: m.metric == 'is_db_alive' ? 'LessThan' : 'GreaterThan'
          threshold: m.threshold
          timeAggregation: m.metric == 'connections_failed' ? 'Total' : (m.metric == 'is_db_alive' ? 'Minimum' : 'Average')
        }
      ]
    }
    autoMitigate: true
    actions: [{ actionGroupId: actionGroupId }]
  }
}]

// AUD-REL-04: Azure says a resource in this group is unavailable or degraded (PostgreSQL HA failover or degraded HA,
// Container Apps, Front Door, storage, Key Vault). Activity-log alerts are free. Service Health (region incidents) is
// subscription-scoped: enableServiceHealthAlert below.
resource resourceHealth 'Microsoft.Insights/activityLogAlerts@2020-10-01' = {
  name: '${namePrefix}-resource-health'
  location: 'global'
  tags: tags
  properties: {
    description: 'A resource in this group is Unavailable or Degraded according to Azure Resource Health. Owner: infra lane. Runbooks: RB-01 (apps), RB-02 (database).'
    enabled: true
    scopes: [resourceGroup().id]
    condition: {
      allOf: [
        { field: 'category', equals: 'ResourceHealth' }
        {
          anyOf: [
            { field: 'properties.currentHealthStatus', equals: 'Unavailable' }
            { field: 'properties.currentHealthStatus', equals: 'Degraded' }
          ]
        }
      ]
    }
    actions: { actionGroups: [{ actionGroupId: actionGroupId }] }
  }
}

// Activity-log alerts are stateless: they never send "Resolved". This companion mails when a resource that was
// Unavailable or Degraded is Available again, so a transient event (2026-10-07 13:11, Front Door during a deploy) is
// closed in the inbox too.
resource resourceHealthRecovered 'Microsoft.Insights/activityLogAlerts@2020-10-01' = {
  name: '${namePrefix}-resource-health-recovered'
  location: 'global'
  tags: tags
  properties: {
    description: 'A resource in this group is Available again after being Unavailable or Degraded (closes aron-*-resource-health). Owner: infra lane. Runbooks: RB-01 (apps), RB-02 (database).'
    enabled: true
    scopes: [resourceGroup().id]
    condition: {
      allOf: [
        { field: 'category', equals: 'ResourceHealth' }
        { field: 'properties.currentHealthStatus', equals: 'Available' }
        {
          anyOf: [
            { field: 'properties.previousHealthStatus', equals: 'Unavailable' }
            { field: 'properties.previousHealthStatus', equals: 'Degraded' }
          ]
        }
      ]
    }
    actions: { actionGroups: [{ actionGroupId: actionGroupId }] }
  }
}

resource serviceHealth 'Microsoft.Insights/activityLogAlerts@2020-10-01' = if (enableServiceHealthAlert) {
  name: '${namePrefix}-service-health'
  location: 'global'
  tags: tags
  properties: {
    description: 'Azure Service Health: an incident or planned maintenance affects this subscription. Owner: infra lane. Runbook: RB-01.'
    enabled: true
    scopes: [subscription().id]
    condition: {
      allOf: [
        { field: 'category', equals: 'ServiceHealth' }
        {
          anyOf: [
            { field: 'properties.incidentType', equals: 'Incident' }
            { field: 'properties.incidentType', equals: 'Maintenance' }
          ]
        }
      ]
    }
    actions: { actionGroups: [{ actionGroupId: actionGroupId }] }
  }
}
