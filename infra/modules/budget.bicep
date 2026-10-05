// Monthly cost budget on THIS resource group only (no subscription scope needed), with e-mail alerts at 80 % of
// actual and 100 % of forecast spend (docs/18 s3.3 governance, docs/23 s6).

param budgetName string
@description('Monthly amount in the billing currency of the subscription.')
param amount int
param contactEmails array
@description('First day of a month, yyyy-MM-01. Fixed in the parameter file: Azure refuses to move a budget start date.')
param startDate string

resource budget 'Microsoft.Consumption/budgets@2024-08-01' = {
  name: budgetName
  properties: {
    category: 'Cost'
    amount: amount
    timeGrain: 'Monthly'
    timePeriod: { startDate: startDate }
    notifications: {
      actual80: {
        enabled: true
        operator: 'GreaterThanOrEqualTo'
        threshold: 80
        thresholdType: 'Actual'
        contactEmails: contactEmails
      }
      actual100: {
        enabled: true
        operator: 'GreaterThanOrEqualTo'
        threshold: 100
        thresholdType: 'Actual'
        contactEmails: contactEmails
      }
      forecast100: {
        enabled: true
        operator: 'GreaterThanOrEqualTo'
        threshold: 100
        thresholdType: 'Forecasted'
        contactEmails: contactEmails
      }
    }
  }
}

output budgetId string = budget.id
