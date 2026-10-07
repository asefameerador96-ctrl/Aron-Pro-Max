// dev-lite = the TEST profile of docs/28 (used only after an owner-approved reset): api 0 to 2 replicas (scale to zero), worker 1 at the minimum size, web 0 to 1.
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
param deployServices = empty(envServices) ? true : bool(envServices)
param frontDoorPrivateLink = false
param frontDoorEnabled = false

// api: scale to zero between calls (a cold start of a few seconds is invisible to an offline-first phone, which
// retries); 0.5 vCPU / 1 GiB is the smallest size that runs the JVM with headroom.
param apiCpu = '0.5'
param apiMemory = '1Gi'
param apiMinReplicas = 0
param apiMaxReplicas = 2
param apiPrescaleReplicas = 0
param apiReadinessPath = empty(envReadiness) ? '/v1/health/ready' : envReadiness

// worker: one replica at the minimum size (scheduled jobs need a running process; idle replicas bill at the idle rate).
param workerCpu = '0.25'
param workerMemory = '0.5Gi'
param workerMinReplicas = int(empty(envWorkerMin) ? '1' : envWorkerMin)
param workerMaxReplicas = 1

param webCpu = '0.25'
param webMemory = '0.5Gi'
param webMinReplicas = 0
param webMaxReplicas = 1

// Burstable B1ms admits about 35 client connections: api 2 x (4 + 1) + worker (3 + 2) + migrate 2 = 17, and still
// 32 while a deploy briefly runs the old and new revisions side by side (the read URL is the same server here).
param apiDbPoolMax = 4
param apiDbReadPoolMax = 1
param workerDbPoolMax = 3
param workerDbReadPoolMax = 2
