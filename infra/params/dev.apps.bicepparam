using '../apps.bicep'

param environmentName = 'dev'
param location = readEnvironmentVariable('AZURE_LOCATION', 'southeastasia')
param backendImage = readEnvironmentVariable('ARON_BACKEND_IMAGE', 'mcr.microsoft.com/k8se/quickstart:latest')
param webImage = readEnvironmentVariable('ARON_WEB_IMAGE', '')
param deployServices = bool(readEnvironmentVariable('ARON_DEPLOY_SERVICES', 'true'))
param frontDoorPrivateLink = false

param apiMinReplicas = 1
param apiMaxReplicas = 3
param apiPrescaleReplicas = 0
// The Day-1 backend serves /v1/health only; switch to /v1/health/ready when the backend lane ships it
// (docs/requests/infra-backend-runtime.md).
param apiReadinessPath = readEnvironmentVariable('ARON_API_READINESS_PATH', '/v1/health')

param workerMinReplicas = 1
param workerMaxReplicas = 2

param webMinReplicas = 1
param webMaxReplicas = 2
