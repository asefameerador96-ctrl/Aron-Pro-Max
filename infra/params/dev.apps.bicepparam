// dev = the REHEARSAL profile (docs/28 exception): apps in the existing VNet-injected environment behind the existing
// Front Door. The apps themselves are new and kept small (pilot traffic); PgBouncer exists here, so default pools.
using '../apps.bicep'

// Values from the deploy environment (infra/deploy.sh); unset or empty takes the default.
var envLocation = readEnvironmentVariable('AZURE_LOCATION', '')
var envSuffix = readEnvironmentVariable('ARON_NAME_SUFFIX', '')
var envBackendImage = readEnvironmentVariable('ARON_BACKEND_IMAGE', '')
var envServices = readEnvironmentVariable('ARON_DEPLOY_SERVICES', '')
var envReadiness = readEnvironmentVariable('ARON_API_READINESS_PATH', '')
var envWorkerMin = readEnvironmentVariable('ARON_WORKER_MIN_REPLICAS', '')

param environmentName = 'dev'
param location = empty(envLocation) ? 'southeastasia' : envLocation
param nameSuffix = envSuffix
// The quickstart image only lets the file compile offline; deploy.sh always sets the real image.
param backendImage = empty(envBackendImage) ? 'mcr.microsoft.com/k8se/quickstart:latest' : envBackendImage
param webImage = readEnvironmentVariable('ARON_WEB_IMAGE', '')
param buildId = readEnvironmentVariable('ARON_BUILD_ID', '')
param migrateImage = readEnvironmentVariable('ARON_MIGRATE_IMAGE', '')
// Per-app database logins (docs/requests/db-runtime-roles.md); deploy.sh imports the psql image and sets it.
param psqlImage = readEnvironmentVariable('ARON_PSQL_IMAGE', '')
// Dev seed (db/seed without its global dev relaxations) + the SR slice smoke after every deploy (lead 2026-10-07).
// deploy.sh reads this exact line; stage and prod never carry it.
param devSeed = true
// ON in dev (2026-10-07): db closed docs/requests/db-runtime-roles-gaps.md (V0029, re-audit 19:55 UTC, no gap for app_api or
// app_worker) and dblogins succeeded (run 37673797109). Stage and prod switch on after a clean dev week (final account).
param dbPerAppLogins = true
param deployServices = empty(envServices) ? true : bool(envServices)
param frontDoorPrivateLink = false
param frontDoorEnabled = true

// Front Door probes the api every 60 s, so it stays warm anyway: one replica, up to two.
param apiCpu = '0.5'
param apiMemory = '1Gi'
param apiMinReplicas = 1
param apiMaxReplicas = 2
param apiPrescaleReplicas = 0
param apiReadinessPath = empty(envReadiness) ? '/v1/health/ready' : envReadiness

param workerCpu = '0.25'
param workerMemory = '0.5Gi'
param workerMinReplicas = int(empty(envWorkerMin) ? '1' : envWorkerMin)
param workerMaxReplicas = 1

param webCpu = '0.25'
param webMemory = '0.5Gi'
param webMinReplicas = 0
param webMaxReplicas = 1
