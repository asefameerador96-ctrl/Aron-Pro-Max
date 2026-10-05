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
param deployServices = empty(envServices) ? true : bool(envServices)
param frontDoorPrivateLink = false

param apiMinReplicas = 1
param apiMaxReplicas = 3
param apiPrescaleReplicas = 0
// The Day-1 backend serves /v1/health only; switch to /v1/health/ready when the backend lane ships it
// (docs/requests/infra-backend-runtime.md).
param apiReadinessPath = empty(envReadiness) ? '/v1/health' : envReadiness

param workerMinReplicas = int(empty(envWorkerMin) ? '1' : envWorkerMin)
param workerMaxReplicas = 2

param webMinReplicas = 1
param webMaxReplicas = 2
