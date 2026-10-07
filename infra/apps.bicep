// Aron: stage 2 of the deploy. The ONE backend image runs in three roles (docs/24 s6.1, D24-27):
//   migrate  Container Apps job, started by the workflow BEFORE any app revision changes (Flyway, then exit)
//   api      Ktor on 8080, external ingress, reached through Front Door
//   worker   aggregation and scheduled jobs, no ingress
// plus the Next.js web app (BFF, server runtime) when its image is given, and the Front Door origins and routes.
//
// deployServices=false deploys ONLY the migrate job with the new image (the workflow runs it, waits, then deploys
// again with deployServices=true). Resources of stage 1 are referenced by name (lib/naming.bicep), never re-created.

targetScope = 'resourceGroup'

import { names, suffixFor, secretNames } from 'lib/naming.bicep'

param namePrefix string = 'aron'
@description('dev = test account; stage and prod = final account (docs/30 s1).')
@allowed(['dev', 'stage', 'prod'])
param environmentName string
param location string = resourceGroup().location
param nameSuffix string = ''
param tags object = {}

@description('Backend image. deploy.sh passes the pushed digest (crarondevabc123.azurecr.io/aron-backend@sha256:...), so a re-pushed tag can never change what runs.')
param backendImage string
@description('The commit the image was built from (reported as `build` by /v1/health); empty = the image tag.')
param buildId string = ''
@description('Image of the migrate job; empty = backendImage. A rollback keeps the job on the newest image (deploy.sh).')
param migrateImage string = ''
@description('Web image; empty = no web app yet (web/ does not exist).')
param webImage string = ''
param deployServices bool = true
@description('Client image for the dblogins job (psql 16), imported into the registry by deploy.sh; empty = no job.')
param psqlImage string = ''
@description('api and worker connect as their own least-privilege logins (app_api, app_jobs) instead of the server admin; needs psqlImage (the dblogins job creates the logins). The migrate job always uses the admin login.')
param dbPerAppLogins bool = false
@description('api revisions: Single (dev: the new revision takes all traffic once ready) or Multiple (stage and prod, docs/30 s3: the previous revision stays active at 0 % so deploy.sh can put traffic back on it when the health gate fails).')
@allowed(['Single', 'Multiple'])
param apiRevisionsMode string = 'Single'
@description('Must match the infra deployment: Front Door reaches the apps over Private Link.')
param frontDoorPrivateLink bool
@description('Must match the infra deployment (deployFrontDoor). false (TEST profile): clients use the api Container Apps address.')
param frontDoorEnabled bool = true

// Connection pools per replica (ARON_DB_POOL_MAX / ARON_DB_READ_POOL_MAX). A Burstable server allows about 35 client
// connections, so the TEST profile keeps them small; replicas x (pool + read pool) must stay below the server limit.
param apiDbPoolMax int = 10
param apiDbReadPoolMax int = 10
param workerDbPoolMax int = 10
param workerDbReadPoolMax int = 10

// api sizing (docs/18 s2.5 and s3.4)
param apiCpu string = '1.0'
param apiMemory string = '2Gi'
param apiMinReplicas int
param apiMaxReplicas int
@description('Concurrent requests per replica before the HTTP rule adds a replica.')
param apiConcurrentRequests int = 50
@description('Pre-scale replicas for the morning download and evening upload storms (Asia/Dhaka); 0 = no cron rule.')
param apiPrescaleReplicas int = 0
@description('Readiness probe path; the contract has /v1/health/ready (database check).')
param apiReadinessPath string = '/v1/health/ready'

// worker sizing
param workerCpu string = '1.0'
param workerMemory string = '2Gi'
param workerMinReplicas int
param workerMaxReplicas int

// web sizing
param webCpu string = '0.5'
param webMemory string = '1Gi'
param webMinReplicas int
param webMaxReplicas int

var suffix = suffixFor(nameSuffix, resourceGroup().id)
var n = names(namePrefix, environmentName, suffix)
var allTags = union({ project: 'aron', environment: environmentName, owner: 'aktcl', 'managed-by': 'bicep' }, tags)
var privateLink = frontDoorPrivateLink

resource env 'Microsoft.App/managedEnvironments@2025-07-01' existing = { name: n.containerEnv }
resource acr 'Microsoft.ContainerRegistry/registries@2023-07-01' existing = { name: n.registry }
resource kv 'Microsoft.KeyVault/vaults@2024-11-01' existing = { name: n.keyVault }
resource st 'Microsoft.Storage/storageAccounts@2024-01-01' existing = { name: n.storage }
resource appi 'Microsoft.Insights/components@2020-02-02' existing = { name: n.appInsights }
// Conditional: an unconditional `existing` node is read at deployment time and fails when there is no Front Door.
resource fd 'Microsoft.Cdn/profiles@2024-09-01' existing = if (frontDoorEnabled) { name: n.frontDoor }
resource fdEndpoint 'Microsoft.Cdn/profiles/afdEndpoints@2024-09-01' existing = if (frontDoorEnabled) {
  parent: fd
  name: n.frontDoorEndpoint
}
resource idApi 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' existing = { name: n.idApi }
resource idWorker 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' existing = { name: n.idWorker }
resource idMigrate 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' existing = { name: n.idMigrate }
resource idWeb 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' existing = { name: n.idWeb }

var kvSecretUrl = '${kv.properties.vaultUri}secrets/'
// Which Key Vault secret each app's database URLs come from (docs/requests/db-runtime-roles.md).
var perApp = dbPerAppLogins && !empty(psqlImage)
var apiDbUrlSecret = perApp ? secretNames.dbApiUrl : secretNames.dbUrl
var apiDbReadUrlSecret = perApp ? secretNames.dbApiReadUrl : secretNames.dbReadUrl
var workerDbUrlSecret = perApp ? secretNames.dbJobsDirectUrl : secretNames.dbDirectUrl
var workerDbReadUrlSecret = perApp ? secretNames.dbJobsReadUrl : secretNames.dbReadUrl

func kvSecret(name string, secret string, baseUrl string, identityId string) object => {
  name: name
  keyVaultUrl: '${baseUrl}${secret}'
  identity: identityId
}

// Environment shared by every backend role (docs/24 s6.4). Role-specific variables are appended per container.
// The commit (deploy.sh passes it with the digest); the API reports it in /v1/health as `build`. deploy.sh reads it
// back from ARON_BUILD for its ordering guard.
var build = empty(buildId) ? last(split(backendImage, ':')) : buildId

var commonEnv = [
  { name: 'ARON_ENV', value: environmentName }
  { name: 'ARON_BUILD', value: build }
  { name: 'ARON_BLOB_ACCOUNT', value: st.name }
  { name: 'ARON_BLOB_CONTAINER_MEDIA', value: 'media' }
  { name: 'ARON_BLOB_CONTAINER_BUNDLES', value: 'bundles' }
  { name: 'ARON_MEDIA_EVENTS_QUEUE', value: 'media-events' }
  { name: 'ARON_FRONT_DOOR_ID', value: frontDoorEnabled ? fd!.properties.frontDoorId : '' }
  { name: 'APPLICATIONINSIGHTS_CONNECTION_STRING', value: appi.properties.ConnectionString }
]

var appSecretRefs = [
  { name: 'ARON_JWT_SIGNING_KEY', secretRef: 'jwt-signing-key' }
  { name: 'ARON_JWT_KID', secretRef: 'jwt-kid' }
  { name: 'ARON_FCM_SERVICE_ACCOUNT_JSON', secretRef: 'fcm-service-account' }
  { name: 'ARON_DB_READ_URL', secretRef: 'db-read-url' }
]

// ---------------------------------------------------------------------------------------------------- migrate job
resource migrate 'Microsoft.App/jobs@2025-07-01' = {
  name: n.migrateJob
  location: location
  tags: allTags
  identity: { type: 'UserAssigned', userAssignedIdentities: { '${idMigrate.id}': {} } }
  properties: {
    environmentId: env.id
    workloadProfileName: 'Consumption'
    configuration: {
      triggerType: 'Manual'
      replicaTimeout: 1800
      replicaRetryLimit: 0
      manualTriggerConfig: { parallelism: 1, replicaCompletionCount: 1 }
      registries: [{ server: acr.properties.loginServer, identity: idMigrate.id }]
      secrets: [kvSecret('db-direct-url', secretNames.dbDirectUrl, kvSecretUrl, idMigrate.id)]
    }
    template: {
      containers: [
        {
          name: 'migrate'
          image: empty(migrateImage) ? backendImage : migrateImage
          resources: { cpu: json('0.5'), memory: '1Gi' }
          env: [
            { name: 'ARON_ROLE', value: 'migrate' }
            { name: 'ARON_ENV', value: environmentName }
            { name: 'ARON_BUILD', value: build }
            // Flyway takes a session-level advisory lock, so it connects directly (5432), not through PgBouncer.
            { name: 'ARON_DB_URL', secretRef: 'db-direct-url' }
            { name: 'APPLICATIONINSIGHTS_CONNECTION_STRING', value: appi.properties.ConnectionString }
            { name: 'APPLICATIONINSIGHTS_ROLE_NAME', value: 'aron-migrate' }
            { name: 'AZURE_CLIENT_ID', value: idMigrate.properties.clientId }
          ]
        }
      ]
    }
  }
}

// ------------------------------------------------------------------------------------------------------- dblogins
// Creates or repairs the per-app logins (infra/sql/runtime-logins.sql, embedded at build time) as the admin login,
// inside the VNet. deploy.sh starts it after the migrations and before the apps; idempotent.
resource dblogins 'Microsoft.App/jobs@2025-07-01' = if (!empty(psqlImage)) {
  name: n.dbLoginsJob
  location: location
  tags: allTags
  identity: { type: 'UserAssigned', userAssignedIdentities: { '${idMigrate.id}': {} } }
  properties: {
    environmentId: env.id
    workloadProfileName: 'Consumption'
    configuration: {
      triggerType: 'Manual'
      replicaTimeout: 300
      replicaRetryLimit: 0
      manualTriggerConfig: { parallelism: 1, replicaCompletionCount: 1 }
      registries: [{ server: acr.properties.loginServer, identity: idMigrate.id }]
      secrets: [
        kvSecret('db-direct-url', secretNames.dbDirectUrl, kvSecretUrl, idMigrate.id)
        kvSecret('pw-app-api', secretNames.dbPwAppApi, kvSecretUrl, idMigrate.id)
        kvSecret('pw-app-worker', secretNames.dbPwAppWorker, kvSecretUrl, idMigrate.id)
        kvSecret('pw-app-jobs', secretNames.dbPwAppJobs, kvSecretUrl, idMigrate.id)
      ]
    }
    template: {
      containers: [
        {
          name: 'dblogins'
          image: psqlImage
          resources: { cpu: json('0.25'), memory: '0.5Gi' }
          // The JDBC URL minus its "jdbc:" prefix is a libpq URI (host, port, sslmode, user, password parameters).
          command: ['/bin/sh', '-c', 'printf "%s" "$ARON_SQL" > /tmp/logins.sql && exec psql "\${ARON_DB_URL#jdbc:}" -X -q -f /tmp/logins.sql']
          env: [
            { name: 'ARON_SQL', value: loadTextContent('sql/runtime-logins.sql') }
            { name: 'ARON_DB_URL', secretRef: 'db-direct-url' }
            { name: 'ARON_PW_APP_API', secretRef: 'pw-app-api' }
            { name: 'ARON_PW_APP_WORKER', secretRef: 'pw-app-worker' }
            { name: 'ARON_PW_APP_JOBS', secretRef: 'pw-app-jobs' }
          ]
        }
      ]
    }
  }
}

// ------------------------------------------------------------------------------------------------------------ api
var prescaleRules = apiPrescaleReplicas > 0 ? [
  {
    name: 'prescale-morning'
    custom: {
      type: 'cron'
      metadata: { timezone: 'Asia/Dhaka', start: '15 6 * * *', end: '0 10 * * *', desiredReplicas: string(apiPrescaleReplicas) }
    }
  }
  {
    name: 'prescale-evening'
    custom: {
      type: 'cron'
      metadata: { timezone: 'Asia/Dhaka', start: '45 16 * * *', end: '0 20 * * *', desiredReplicas: string(apiPrescaleReplicas) }
    }
  }
] : []

resource api 'Microsoft.App/containerApps@2025-07-01' = if (deployServices) {
  name: n.apiApp
  location: location
  tags: allTags
  identity: { type: 'UserAssigned', userAssignedIdentities: { '${idApi.id}': {} } }
  properties: {
    environmentId: env.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: apiRevisionsMode
      maxInactiveRevisions: 5
      ingress: {
        external: true
        targetPort: 8080
        transport: 'http'
        allowInsecure: false
        traffic: [{ latestRevision: true, weight: 100 }]
      }
      registries: [{ server: acr.properties.loginServer, identity: idApi.id }]
      secrets: [
        kvSecret('db-url', apiDbUrlSecret, kvSecretUrl, idApi.id)
        kvSecret('db-read-url', apiDbReadUrlSecret, kvSecretUrl, idApi.id)
        kvSecret('jwt-signing-key', secretNames.jwtSigningKey, kvSecretUrl, idApi.id)
        kvSecret('jwt-kid', secretNames.jwtKid, kvSecretUrl, idApi.id)
        kvSecret('fcm-service-account', secretNames.fcmServiceAccount, kvSecretUrl, idApi.id)
      ]
    }
    template: {
      containers: [
        {
          name: 'api'
          image: backendImage
          resources: { cpu: json(apiCpu), memory: apiMemory }
          env: concat(commonEnv, appSecretRefs, [
            { name: 'ARON_ROLE', value: 'api' }
            { name: 'PORT', value: '8080' }
            { name: 'ARON_DB_URL', secretRef: 'db-url' }
            { name: 'ARON_DB_POOL_MAX', value: string(apiDbPoolMax) }
            { name: 'ARON_DB_READ_POOL_MAX', value: string(apiDbReadPoolMax) }
            { name: 'APPLICATIONINSIGHTS_ROLE_NAME', value: 'aron-api' }
            { name: 'AZURE_CLIENT_ID', value: idApi.properties.clientId }
          ])
          probes: [
            {
              type: 'Startup'
              httpGet: { path: '/v1/health', port: 8080 }
              initialDelaySeconds: 5
              periodSeconds: 5
              failureThreshold: 30
            }
            {
              // Liveness checks the process only, so a database failover never restarts every replica.
              type: 'Liveness'
              httpGet: { path: '/v1/health', port: 8080 }
              periodSeconds: 10
              failureThreshold: 3
            }
            {
              type: 'Readiness'
              httpGet: { path: apiReadinessPath, port: 8080 }
              periodSeconds: 10
              failureThreshold: 3
            }
          ]
        }
      ]
      scale: {
        minReplicas: apiMinReplicas
        maxReplicas: apiMaxReplicas
        rules: concat([
          {
            name: 'http'
            http: { metadata: { concurrentRequests: string(apiConcurrentRequests) } }
          }
        ], prescaleRules)
      }
    }
  }
  dependsOn: [migrate]
}

// --------------------------------------------------------------------------------------------------------- worker
resource worker 'Microsoft.App/containerApps@2025-07-01' = if (deployServices) {
  name: n.workerApp
  location: location
  tags: allTags
  identity: { type: 'UserAssigned', userAssignedIdentities: { '${idWorker.id}': {} } }
  properties: {
    environmentId: env.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      maxInactiveRevisions: 5
      registries: [{ server: acr.properties.loginServer, identity: idWorker.id }]
      secrets: [
        kvSecret('db-direct-url', workerDbUrlSecret, kvSecretUrl, idWorker.id)
        kvSecret('db-read-url', workerDbReadUrlSecret, kvSecretUrl, idWorker.id)
        kvSecret('jwt-signing-key', secretNames.jwtSigningKey, kvSecretUrl, idWorker.id)
        kvSecret('jwt-kid', secretNames.jwtKid, kvSecretUrl, idWorker.id)
        kvSecret('fcm-service-account', secretNames.fcmServiceAccount, kvSecretUrl, idWorker.id)
      ]
    }
    template: {
      containers: [
        {
          name: 'worker'
          image: backendImage
          resources: { cpu: json(workerCpu), memory: workerMemory }
          env: concat(commonEnv, appSecretRefs, [
            { name: 'ARON_ROLE', value: 'worker' }
            // Run-once jobs take PostgreSQL advisory locks; session locks need a direct connection, not PgBouncer.
            { name: 'ARON_DB_URL', secretRef: 'db-direct-url' }
            { name: 'ARON_DB_POOL_MAX', value: string(workerDbPoolMax) }
            { name: 'ARON_DB_READ_POOL_MAX', value: string(workerDbReadPoolMax) }
            { name: 'APPLICATIONINSIGHTS_ROLE_NAME', value: 'aron-worker' }
            { name: 'AZURE_CLIENT_ID', value: idWorker.properties.clientId }
          ])
        }
      ]
      scale: {
        minReplicas: workerMinReplicas
        maxReplicas: workerMaxReplicas
      }
    }
  }
  dependsOn: [migrate]
}

// ------------------------------------------------------------------------------------------------------------ web
var deployWeb = deployServices && !empty(webImage)

// The one public address of the API: Front Door, or the api app itself when there is no Front Door (TEST profile).
var apiHost = frontDoorEnabled ? fdEndpoint!.properties.hostName : (deployServices ? api!.properties.configuration.ingress.fqdn : '')

resource web 'Microsoft.App/containerApps@2025-07-01' = if (deployWeb) {
  name: n.webApp
  location: location
  tags: allTags
  identity: { type: 'UserAssigned', userAssignedIdentities: { '${idWeb.id}': {} } }
  properties: {
    environmentId: env.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      maxInactiveRevisions: 5
      ingress: {
        external: true
        targetPort: 3000
        transport: 'http'
        allowInsecure: false
        traffic: [{ latestRevision: true, weight: 100 }]
      }
      registries: [{ server: acr.properties.loginServer, identity: idWeb.id }]
      secrets: [kvSecret('session-secret', secretNames.webSessionSecret, kvSecretUrl, idWeb.id)]
    }
    template: {
      containers: [
        {
          name: 'web'
          image: webImage
          resources: { cpu: json(webCpu), memory: webMemory }
          env: [
            { name: 'NODE_ENV', value: 'production' }
            { name: 'PORT', value: '3000' }
            { name: 'HOSTNAME', value: '0.0.0.0' }
            { name: 'ARON_ENV', value: environmentName }
            // The BFF calls the API on the same public address as every other client (Front Door when there is one).
            { name: 'ARON_API_BASE_URL', value: 'https://${apiHost}' }
            // Seals the BFF session cookies; the web server refuses to start in production without it.
            { name: 'ARON_SESSION_SECRET', secretRef: 'session-secret' }
            { name: 'APPLICATIONINSIGHTS_CONNECTION_STRING', value: appi.properties.ConnectionString }
          ]
        }
      ]
      scale: {
        minReplicas: webMinReplicas
        maxReplicas: webMaxReplicas
        rules: [{ name: 'http', http: { metadata: { concurrentRequests: '50' } } }]
      }
    }
  }
}

// ---------------------------------------------------------------------------------------------------- Front Door
func origin(host string, privateLink bool, envId string, location string) object => union({
  hostName: host
  originHostHeader: host
  httpPort: 80
  httpsPort: 443
  priority: 1
  weight: 1000
  enabledState: 'Enabled'
  enforceCertificateNameCheck: true
}, privateLink ? {
  sharedPrivateLinkResource: {
    privateLink: { id: envId }
    groupId: 'managedEnvironments'
    privateLinkLocation: location
    requestMessage: 'aron-frontdoor'
  }
} : {})

resource ogApi 'Microsoft.Cdn/profiles/originGroups@2024-09-01' = if (deployServices && frontDoorEnabled) {
  parent: fd
  name: 'og-api'
  properties: {
    loadBalancingSettings: { sampleSize: 4, successfulSamplesRequired: 3, additionalLatencyInMilliseconds: 50 }
    healthProbeSettings: { probePath: '/v1/health', probeRequestType: 'HEAD', probeProtocol: 'Https', probeIntervalInSeconds: 60 }
    sessionAffinityState: 'Disabled'
  }
}

resource originApi 'Microsoft.Cdn/profiles/originGroups/origins@2024-09-01' = if (deployServices && frontDoorEnabled) {
  parent: ogApi
  name: 'api'
  properties: origin(api!.properties.configuration.ingress.fqdn, privateLink, env.id, location)
}

resource routeApi 'Microsoft.Cdn/profiles/afdEndpoints/routes@2024-09-01' = if (deployServices && frontDoorEnabled) {
  parent: fdEndpoint
  name: 'rt-api'
  dependsOn: [originApi]
  properties: {
    originGroup: { id: ogApi.id }
    patternsToMatch: ['/v1/*', '/v1']
    supportedProtocols: ['Http', 'Https']
    httpsRedirect: 'Enabled'
    forwardingProtocol: 'HttpsOnly'
    linkToDefaultDomain: 'Enabled'
    enabledState: 'Enabled'
    // No caching: every /v1 answer is per user or per device (bundle, batch); never cache it at the edge.
  }
}

resource ogWeb 'Microsoft.Cdn/profiles/originGroups@2024-09-01' = if (deployWeb && frontDoorEnabled) {
  parent: fd
  name: 'og-web'
  properties: {
    loadBalancingSettings: { sampleSize: 4, successfulSamplesRequired: 3, additionalLatencyInMilliseconds: 50 }
    healthProbeSettings: { probePath: '/', probeRequestType: 'HEAD', probeProtocol: 'Https', probeIntervalInSeconds: 100 }
    sessionAffinityState: 'Disabled'
  }
}

resource originWeb 'Microsoft.Cdn/profiles/originGroups/origins@2024-09-01' = if (deployWeb && frontDoorEnabled) {
  parent: ogWeb
  name: 'web'
  properties: origin(web!.properties.configuration.ingress.fqdn, privateLink, env.id, location)
}

resource routeWeb 'Microsoft.Cdn/profiles/afdEndpoints/routes@2024-09-01' = if (deployWeb && frontDoorEnabled) {
  parent: fdEndpoint
  name: 'rt-web'
  dependsOn: [originWeb, routeApi]
  properties: {
    originGroup: { id: ogWeb.id }
    patternsToMatch: ['/*']
    supportedProtocols: ['Http', 'Https']
    httpsRedirect: 'Enabled'
    forwardingProtocol: 'HttpsOnly'
    linkToDefaultDomain: 'Enabled'
    enabledState: 'Enabled'
  }
}

output migrateJobName string = migrate.name
output dbLoginsJobName string = empty(psqlImage) ? '' : dblogins.name
output dbPerAppLogins bool = perApp
output apiFqdn string = deployServices ? api!.properties.configuration.ingress.fqdn : ''
output webDeployed bool = deployWeb
output apiHost string = apiHost
@description('Where the web app answers: the Front Door endpoint (route /*), or its own address without Front Door.')
output webHost string = deployWeb ? (frontDoorEnabled ? apiHost : web!.properties.configuration.ingress.fqdn) : ''
