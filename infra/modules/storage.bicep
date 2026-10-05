// Blob Storage for photos (written by phones with short-lived user-delegation SAS, docs/24 s4.11) and bundle
// snapshots (written by the server). Shared-key access is off, so the only SAS that works is a user-delegation SAS
// minted by the API's managed identity. BlobCreated events land in a storage queue the worker drains
// ("the server's blob-created event marks the media row stored").

param location string
param tags object
param storageName string
@allowed(['Standard_LRS', 'Standard_ZRS', 'Standard_GZRS', 'Standard_RAGZRS'])
param skuName string
param mediaContainer string
param bundlesContainer string
param mediaEventsQueue string
param eventGridTopicName string
param logAnalyticsId string

resource st 'Microsoft.Storage/storageAccounts@2024-01-01' = {
  name: storageName
  location: location
  tags: tags
  kind: 'StorageV2'
  sku: { name: skuName }
  properties: {
    accessTier: 'Hot'
    minimumTlsVersion: 'TLS1_2'
    supportsHttpsTrafficOnly: true
    allowBlobPublicAccess: false
    allowSharedKeyAccess: false
    defaultToOAuthAuthentication: true
    allowCrossTenantReplication: false
    // Phones PUT photos straight to Blob over the internet with a SAS, so the endpoint stays public; every
    // request still needs an Entra token or a user-delegation SAS.
    publicNetworkAccess: 'Enabled'
    networkAcls: { defaultAction: 'Allow', bypass: 'AzureServices' }
    encryption: {
      keySource: 'Microsoft.Storage'
      requireInfrastructureEncryption: false
      services: {
        blob: { enabled: true, keyType: 'Account' }
        queue: { enabled: true, keyType: 'Account' }
      }
    }
  }
}

resource blobs 'Microsoft.Storage/storageAccounts/blobServices@2024-01-01' = {
  parent: st
  name: 'default'
  properties: {
    deleteRetentionPolicy: { enabled: true, days: 14 }
    containerDeleteRetentionPolicy: { enabled: true, days: 14 }
  }
}

resource media 'Microsoft.Storage/storageAccounts/blobServices/containers@2024-01-01' = {
  parent: blobs
  name: mediaContainer
  properties: { publicAccess: 'None' }
}

resource bundles 'Microsoft.Storage/storageAccounts/blobServices/containers@2024-01-01' = {
  parent: blobs
  name: bundlesContainer
  properties: { publicAccess: 'None' }
}

resource queues 'Microsoft.Storage/storageAccounts/queueServices@2024-01-01' = {
  parent: st
  name: 'default'
}

resource mediaQueue 'Microsoft.Storage/storageAccounts/queueServices/queues@2024-01-01' = {
  parent: queues
  name: mediaEventsQueue
}

// docs/18 s2.8: photos Cool at 30 days and Cold at 90; bundle snapshots are deleted after 3 days.
resource lifecycle 'Microsoft.Storage/storageAccounts/managementPolicies@2024-01-01' = {
  parent: st
  name: 'default'
  properties: {
    policy: {
      rules: [
        {
          name: 'media-tiering'
          enabled: true
          type: 'Lifecycle'
          definition: {
            filters: { blobTypes: ['blockBlob'], prefixMatch: ['${mediaContainer}/'] }
            actions: {
              baseBlob: {
                tierToCool: { daysAfterModificationGreaterThan: 30 }
                tierToCold: { daysAfterModificationGreaterThan: 90 }
              }
            }
          }
        }
        {
          name: 'bundles-expiry'
          enabled: true
          type: 'Lifecycle'
          definition: {
            filters: { blobTypes: ['blockBlob'], prefixMatch: ['${bundlesContainer}/'] }
            actions: { baseBlob: { delete: { daysAfterModificationGreaterThan: 3 } } }
          }
        }
      ]
    }
  }
}

// BlobCreated in the media container -> storage queue (at-least-once; the worker's handler is idempotent).
resource topic 'Microsoft.EventGrid/systemTopics@2025-02-15' = {
  name: eventGridTopicName
  location: location
  tags: tags
  identity: { type: 'SystemAssigned' }
  properties: {
    source: st.id
    topicType: 'Microsoft.Storage.StorageAccounts'
  }
}

// The topic delivers with its own identity, so the storage account needs no shared key.
resource topicCanSend 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(st.id, topic.id, 'queue-sender')
  scope: st
  properties: {
    principalId: topic.identity.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'c6a89b2d-59bc-44d0-9896-0f6e12d7b80a')
  }
}

resource sub 'Microsoft.EventGrid/systemTopics/eventSubscriptions@2025-02-15' = {
  parent: topic
  name: 'media-blob-created'
  dependsOn: [topicCanSend, mediaQueue]
  properties: {
    deliveryWithResourceIdentity: {
      identity: { type: 'SystemAssigned' }
      destination: {
        endpointType: 'StorageQueue'
        properties: {
          resourceId: st.id
          queueName: mediaEventsQueue
          queueMessageTimeToLiveInSeconds: 604800
        }
      }
    }
    filter: {
      includedEventTypes: ['Microsoft.Storage.BlobCreated']
      subjectBeginsWith: '/blobServices/default/containers/${mediaContainer}/'
    }
    eventDeliverySchema: 'EventGridSchema'
    retryPolicy: { maxDeliveryAttempts: 30, eventTimeToLiveInMinutes: 1440 }
  }
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

resource diagQueue 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: 'to-log-analytics'
  scope: queues
  properties: {
    workspaceId: logAnalyticsId
    logs: [{ categoryGroup: 'allLogs', enabled: true }]
    metrics: [{ category: 'Transaction', enabled: true }]
  }
}

output storageId string = st.id
output storageName string = st.name
output blobEndpoint string = st.properties.primaryEndpoints.blob
