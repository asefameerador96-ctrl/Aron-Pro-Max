// Private landing place for the Apsis history dump in the FINAL Azure account (D-OWNER-APSIS-DUMP-01; docs/status/infra-final.md,
// docs/requests/apsis-dump-landing.md). Standalone and deployed ONCE by the owner's laptop session into its OWN resource group
// (default rg-aron-apsis-landing), never by CI and never into rg-aron-prod:
//   - the deploy identity of the pipeline has no rights on this group, so it can never read the dump;
//   - the group is the unit of deletion: when the owner signs the migration reconciliation, deleting the group removes the
//     dump, its logs and its roles in one step (the lifecycle rule below is the safety net, not the plan);
//   - infra/scripts/reset-to-profile.sh compares every storage account of rg-aron-<env> with the profile's storage SKU; this
//     account must not be in that group (it is also tagged component=apsis-landing and skipped there).
//
// What it creates: one StorageV2 account (Standard_LRS: the dump is a transient copy of a file the owner keeps, so no zone or
// geo redundancy is paid for), shared-key access OFF (Microsoft Entra only; no account key and no account SAS exist),
// anonymous access off, TLS 1.2, HTTPS only, network default DENY with the owner's IP address(es) and the loader subnet(s)
// allowed, soft delete 7 days, a lifecycle rule that deletes dump blobs N days after their last write, diagnostic logs of
// every blob read, write and delete into Log Analytics, and two private containers:
//   apsis-dump     the vendor's .sql files, written by the owner, read by the loader (docs/requests/apsis-dump-landing.md)
//   apsis-reports  numbers-only reconciliation output, written by the loader, read by the owner
// Roles are assigned at CONTAINER scope: the owner gets Storage Blob Data Contributor on apsis-dump only; the loader's
// identity gets Storage Blob Data Reader on apsis-dump and Storage Blob Data Contributor on apsis-reports. Nobody gets a role
// on the account itself. Owner rights on the subscription do not include blob data access, hence the explicit assignment.
targetScope = 'resourceGroup'

@minLength(2)
@maxLength(8)
param namePrefix string = 'aron'
param location string = resourceGroup().location
@description('Short suffix of globally unique names; empty = derived from the resource group id (same rule as lib/naming.bicep).')
@maxLength(8)
param nameSuffix string = ''
param tags object = {}

@description('Object ids of the Entra users who upload the dump (the owner): Storage Blob Data Contributor on the apsis-dump container.')
param uploaderPrincipalIds array
@description('Public IPv4 addresses the uploads come from (single addresses: Azure Storage does not accept /31 or /32 rules). Empty = nobody can reach the data plane until one is added.')
param uploaderIpAddresses array = []
@description('Managed identities of the Apsis loader job (apsis-migration lane): Reader on apsis-dump, Contributor on apsis-reports. Empty until the loader exists.')
param loaderPrincipalIds array = []
@description('Subnets (full resource ids) the loader runs in; each needs the Microsoft.Storage service endpoint. Empty until the loader exists.')
param loaderSubnetIds array = []
@description('Delete dump blobs this many days after their last write (safety net; the planned removal is deleting the resource group after sign-off).')
@minValue(7)
@maxValue(365)
param dumpDeleteAfterDays int = 60
@description('Resource id of an existing Log Analytics workspace (for example log-aron-prod); empty = a small workspace is created here (30 days, 1 GB a day).')
param logAnalyticsWorkspaceId string = ''

var suffix = empty(nameSuffix) ? take(uniqueString(resourceGroup().id), 6) : nameSuffix
// 2 + at most 8 + 5 + at most 8 = 23 characters: inside the 24 of a storage account name, no truncation needed.
var storageName = toLower('st${namePrefix}apsis${suffix}')
var dumpContainer = 'apsis-dump'
var reportsContainer = 'apsis-reports'
var blobDataContributor = 'ba92f5b4-2d11-453d-a403-e96b0029c9fe'
var blobDataReader = '2a2b9908-6ea1-4ae2-8e65-a410df84e7d1'
var allTags = union(tags, { project: 'aron', component: 'apsis-landing', 'data-class': 'confidential', lifecycle: 'delete-after-migration-signoff' })

resource ownLaw 'Microsoft.OperationalInsights/workspaces@2023-09-01' = if (empty(logAnalyticsWorkspaceId)) {
  name: 'log-${namePrefix}-apsis-${suffix}'
  location: location
  tags: allTags
  properties: {
    sku: { name: 'PerGB2018' }
    retentionInDays: 30
    workspaceCapping: { dailyQuotaGb: 1 }
  }
}
var workspaceId = empty(logAnalyticsWorkspaceId) ? ownLaw!.id : logAnalyticsWorkspaceId

resource st 'Microsoft.Storage/storageAccounts@2024-01-01' = {
  name: storageName
  location: location
  tags: allTags
  kind: 'StorageV2'
  sku: { name: 'Standard_LRS' }
  properties: {
    accessTier: 'Hot'
    minimumTlsVersion: 'TLS1_2'
    supportsHttpsTrafficOnly: true
    allowBlobPublicAccess: false
    allowSharedKeyAccess: false
    defaultToOAuthAuthentication: true
    allowCrossTenantReplication: false
    allowedCopyScope: 'AAD'
    isSftpEnabled: false
    isLocalUserEnabled: false
    // Reachable only from the listed owner addresses and the loader's subnets; every request still needs an Entra token.
    publicNetworkAccess: 'Enabled'
    networkAcls: {
      defaultAction: 'Deny'
      bypass: 'AzureServices'
      ipRules: [for ip in uploaderIpAddresses: { value: ip, action: 'Allow' }]
      virtualNetworkRules: [for s in loaderSubnetIds: { id: s, action: 'Allow' }]
    }
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
    deleteRetentionPolicy: { enabled: true, days: 7 }
    containerDeleteRetentionPolicy: { enabled: true, days: 7 }
  }
}

resource dump 'Microsoft.Storage/storageAccounts/blobServices/containers@2024-01-01' = {
  parent: blobs
  name: dumpContainer
  properties: { publicAccess: 'None' }
}

resource reports 'Microsoft.Storage/storageAccounts/blobServices/containers@2024-01-01' = {
  parent: blobs
  name: reportsContainer
  properties: { publicAccess: 'None' }
}

// Safety net: the dump is removed N days after its last write even if nobody deletes the group. Uncommitted blocks of a
// failed upload are garbage-collected by the service itself.
resource lifecycle 'Microsoft.Storage/storageAccounts/managementPolicies@2024-01-01' = {
  parent: st
  name: 'default'
  properties: {
    policy: {
      rules: [
        {
          name: 'dump-expiry'
          enabled: true
          type: 'Lifecycle'
          definition: {
            filters: { blobTypes: ['blockBlob'], prefixMatch: ['${dumpContainer}/'] }
            actions: { baseBlob: { delete: { daysAfterModificationGreaterThan: dumpDeleteAfterDays } } }
          }
        }
      ]
    }
  }
}

resource uploaders 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for p in uploaderPrincipalIds: {
  name: guid(dump.id, p, blobDataContributor)
  scope: dump
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', blobDataContributor)
    principalId: p
    principalType: 'User'
  }
}]

resource loaderReadsDump 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for p in loaderPrincipalIds: {
  name: guid(dump.id, p, blobDataReader)
  scope: dump
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', blobDataReader)
    principalId: p
    principalType: 'ServicePrincipal'
  }
}]

resource loaderWritesReports 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for p in loaderPrincipalIds: {
  name: guid(reports.id, p, blobDataContributor)
  scope: reports
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', blobDataContributor)
    principalId: p
    principalType: 'ServicePrincipal'
  }
}]

// Every blob read, write and delete, with the caller's identity and address, so "the dump was only touched by the owner and the
// loader" can be shown from the logs.
resource diagBlob 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: 'to-log-analytics'
  scope: blobs
  properties: {
    workspaceId: workspaceId
    logs: [{ categoryGroup: 'allLogs', enabled: true }]
    metrics: [{ category: 'Transaction', enabled: true }]
  }
}

output storageAccountName string = st.name
output blobEndpoint string = st.properties.primaryEndpoints.blob
output dumpContainerUrl string = '${st.properties.primaryEndpoints.blob}${dumpContainer}'
output reportsContainerUrl string = '${st.properties.primaryEndpoints.blob}${reportsContainer}'
