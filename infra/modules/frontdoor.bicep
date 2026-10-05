// Azure Front Door (Standard or Premium) with a WAF policy attached to the endpoint. Origins and routes are added
// by apps.bicep once the container apps exist. Managed rule sets and Private Link origins exist only on Premium
// (Microsoft Learn, read 2026-10-05); Standard gets the custom rules only.

param tags object
param profileName string
param endpointName string
param wafPolicyName string
param logAnalyticsId string
@allowed(['Standard_AzureFrontDoor', 'Premium_AzureFrontDoor'])
param sku string
@allowed(['Detection', 'Prevention'])
param wafMode string
@description('Action of the managed Default Rule Set (Premium only). Log through the pilot (docs/18 s2.4), Block later.')
@allowed(['Block', 'Log'])
param managedRuleSetAction string
@description('Per-socket-IP backstop per 5 minutes; 0 = no rate rule. Carrier NAT puts thousands of phones behind one IP, so this is a backstop, never the rate limit (that is per device in the API, docs/24 s3.6).')
param ipRateLimitPer5Min int
@description('Origin response timeout; the API answers within 25 s so it always answers before Front Door gives up.')
param originResponseTimeoutSeconds int = 60

var isPremium = sku == 'Premium_AzureFrontDoor'

resource profile 'Microsoft.Cdn/profiles@2024-09-01' = {
  name: profileName
  location: 'global'
  tags: tags
  sku: { name: sku }
  properties: {
    originResponseTimeoutSeconds: originResponseTimeoutSeconds
  }
}

resource endpoint 'Microsoft.Cdn/profiles/afdEndpoints@2024-09-01' = {
  parent: profile
  name: endpointName
  location: 'global'
  tags: tags
  properties: { enabledState: 'Enabled' }
}

var rateRule = ipRateLimitPer5Min > 0 ? [
  {
    name: 'IpBackstop'
    priority: 100
    enabledState: 'Enabled'
    ruleType: 'RateLimitRule'
    rateLimitDurationInMinutes: 5
    rateLimitThreshold: ipRateLimitPer5Min
    action: 'Block'
    matchConditions: [
      {
        // Any request (a match on the path prefix "/" counts everything).
        matchVariable: 'RequestUri'
        operator: 'Any'
        negateCondition: false
        matchValue: []
        transforms: []
      }
    ]
  }
] : []

var methodRule = [
  {
    name: 'AllowedMethodsOnly'
    priority: 200
    enabledState: 'Enabled'
    ruleType: 'MatchRule'
    action: 'Block'
    matchConditions: [
      {
        matchVariable: 'RequestMethod'
        operator: 'Equal'
        negateCondition: true
        matchValue: ['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS']
        transforms: []
      }
    ]
  }
]

resource waf 'Microsoft.Network/FrontDoorWebApplicationFirewallPolicies@2024-02-01' = {
  name: wafPolicyName
  location: 'global'
  tags: tags
  sku: { name: sku }
  properties: {
    policySettings: {
      enabledState: 'Enabled'
      mode: wafMode
      // The batch body is gzip; the WAF does not decompress, so it cannot match inside it (Learn WAF FAQ).
      requestBodyCheck: 'Enabled'
      // A blocked phone call must never be read as a business answer: no X-Aron-Api header here (docs/24 s3.1.6).
      customBlockResponseStatusCode: 403
    }
    customRules: { rules: concat(rateRule, methodRule) }
    managedRules: {
      managedRuleSets: isPremium ? [
        {
          ruleSetType: 'Microsoft_DefaultRuleSet'
          ruleSetVersion: '2.1'
          ruleSetAction: managedRuleSetAction
        }
        {
          ruleSetType: 'Microsoft_BotManagerRuleSet'
          ruleSetVersion: '1.1'
        }
      ] : []
    }
  }
}

resource security 'Microsoft.Cdn/profiles/securityPolicies@2024-09-01' = {
  parent: profile
  name: 'waf'
  properties: {
    parameters: {
      type: 'WebApplicationFirewall'
      wafPolicy: { id: waf.id }
      associations: [
        {
          domains: [{ id: endpoint.id }]
          patternsToMatch: ['/*']
        }
      ]
    }
  }
}

resource diag 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: 'to-log-analytics'
  scope: profile
  properties: {
    workspaceId: logAnalyticsId
    logs: [{ categoryGroup: 'allLogs', enabled: true }]
    metrics: [{ category: 'AllMetrics', enabled: true }]
  }
}

output profileId string = profile.id
output frontDoorId string = profile.properties.frontDoorId
output endpointHost string = endpoint.properties.hostName
