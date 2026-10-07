// stage = STAGING apps (docs/30 s1), parameter file only: prod-shaped so the load test and the soak measure the real
// scale rules; nothing deploys it until the final account exists.
using '../apps.bicep'

// Values from the deploy environment (infra/deploy.sh); unset or empty takes the default.
var envLocation = readEnvironmentVariable('AZURE_LOCATION', '')
var envSuffix = readEnvironmentVariable('ARON_NAME_SUFFIX', '')
var envBackendImage = readEnvironmentVariable('ARON_BACKEND_IMAGE', '')
var envServices = readEnvironmentVariable('ARON_DEPLOY_SERVICES', '')
var envReadiness = readEnvironmentVariable('ARON_API_READINESS_PATH', '')
var envWorkerMin = readEnvironmentVariable('ARON_WORKER_MIN_REPLICAS', '')

param environmentName = 'stage'

param frontDoorEnabled = true
// D8ds_v5 behind PgBouncer: the app pools stay at their defaults (10 per replica).
param location = empty(envLocation) ? 'southeastasia' : envLocation
param nameSuffix = envSuffix
// The quickstart image only lets the file compile offline; deploy.sh always sets the real image.
param backendImage = empty(envBackendImage) ? 'mcr.microsoft.com/k8se/quickstart:latest' : envBackendImage
param webImage = readEnvironmentVariable('ARON_WEB_IMAGE', '')
param buildId = readEnvironmentVariable('ARON_BUILD_ID', '')
param migrateImage = readEnvironmentVariable('ARON_MIGRATE_IMAGE', '')
// docs/30 s3: the previous api revision stays active at 0 % so a failed health gate puts traffic back on it.
param apiRevisionsMode = 'Multiple'
param deployServices = empty(envServices) ? true : bool(envServices)
param frontDoorPrivateLink = true

// docs/18 s3.1 full fleet: api 3..30 (8 pre-scaled for the 06:15 and 16:45 storms), worker 1..10, web 2..6.
param apiMinReplicas = 3
param apiMaxReplicas = 30
param apiPrescaleReplicas = 8
param apiReadinessPath = empty(envReadiness) ? '/v1/health/ready' : envReadiness

param workerMinReplicas = int(empty(envWorkerMin) ? '1' : envWorkerMin)
param workerMaxReplicas = 10

// stage: one web replica is enough for the rehearsal's handful of web users.
param webMinReplicas = 1
param webMaxReplicas = 6
