// One user-assigned identity per process role, each with only the data-plane rights its role needs:
//   api     pull image, read secrets, read/write blobs and mint user-delegation SAS for phones
//   worker  pull image, read secrets, read/write blobs (bundles), drain the media-events queue
//   migrate pull image, read secrets (the direct database URL)
//   web     pull image, read secrets (the BFF session secret)
// The deploying principal (the GitHub identity) gets AcrPush and Key Vault Secrets Officer so the workflow can push
// images and seed secrets; it already holds Contributor + RBAC Administrator on this group only (bootstrap).

import { roles } from '../lib/naming.bicep'

param location string
param tags object
param names object
param registryName string
param keyVaultName string
param storageName string
@description('Object id of the principal that runs the deployment (deployer().objectId); empty skips its grants.')
param deployerObjectId string
@allowed(['ServicePrincipal', 'User'])
param deployerPrincipalType string = 'ServicePrincipal'

resource acr 'Microsoft.ContainerRegistry/registries@2023-07-01' existing = { name: registryName }
resource kv 'Microsoft.KeyVault/vaults@2024-11-01' existing = { name: keyVaultName }
resource st 'Microsoft.Storage/storageAccounts@2024-01-01' existing = { name: storageName }

var identityNames = [names.idApi, names.idWorker, names.idMigrate, names.idWeb]
var api = 0
var worker = 1
var migrate = 2
var web = 3

resource ids 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = [for n in identityNames: {
  name: n
  location: location
  tags: tags
}]

// Every role pulls the image.
resource acrPull 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for (n, i) in identityNames: {
  name: guid(acr.id, n, roles.acrPull)
  scope: acr
  properties: {
    principalId: ids[i].properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roles.acrPull)
  }
}]

// Every role reads its secrets through Key Vault references.
resource kvRead 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for i in [api, worker, migrate, web]: {
  name: guid(kv.id, identityNames[i], roles.keyVaultSecretsUser)
  scope: kv
  properties: {
    principalId: ids[i].properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roles.keyVaultSecretsUser)
  }
}]

// api (photos, bundles) and worker (bundles, media checks) read and write blobs.
resource blobRw 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for i in [api, worker]: {
  name: guid(st.id, identityNames[i], roles.storageBlobDataContributor)
  scope: st
  properties: {
    principalId: ids[i].properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roles.storageBlobDataContributor)
  }
}]

// Only the API mints user-delegation SAS for phones.
resource apiDelegator 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(st.id, names.idApi, roles.storageBlobDelegator)
  scope: st
  properties: {
    principalId: ids[api].properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roles.storageBlobDelegator)
  }
}

resource workerQueue 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(st.id, names.idWorker, roles.storageQueueDataMessageProcessor)
  scope: st
  properties: {
    principalId: ids[worker].properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roles.storageQueueDataMessageProcessor)
  }
}

resource deployerPush 'Microsoft.Authorization/roleAssignments@2022-04-01' = if (!empty(deployerObjectId)) {
  name: guid(acr.id, deployerObjectId, roles.acrPush)
  scope: acr
  properties: {
    principalId: deployerObjectId
    principalType: deployerPrincipalType
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roles.acrPush)
  }
}

resource deployerSecrets 'Microsoft.Authorization/roleAssignments@2022-04-01' = if (!empty(deployerObjectId)) {
  name: guid(kv.id, deployerObjectId, roles.keyVaultSecretsOfficer)
  scope: kv
  properties: {
    principalId: deployerObjectId
    principalType: deployerPrincipalType
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roles.keyVaultSecretsOfficer)
  }
}

output apiIdentityId string = ids[api].id
output workerIdentityId string = ids[worker].id
output migrateIdentityId string = ids[migrate].id
output webIdentityId string = ids[web].id
