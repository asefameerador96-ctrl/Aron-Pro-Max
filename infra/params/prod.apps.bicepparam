using '../apps.bicep'

param environmentName = 'prod'
param location = readEnvironmentVariable('AZURE_LOCATION', 'southeastasia')
param backendImage = readEnvironmentVariable('ARON_BACKEND_IMAGE', 'mcr.microsoft.com/k8se/quickstart:latest')
param webImage = readEnvironmentVariable('ARON_WEB_IMAGE', '')
param deployServices = bool(readEnvironmentVariable('ARON_DEPLOY_SERVICES', 'true'))
param frontDoorPrivateLink = true

// docs/18 s3.1 full fleet: api 3..30 (8 pre-scaled for the 06:15 and 16:45 storms), worker 1..10, web 2..6.
param apiMinReplicas = 3
param apiMaxReplicas = 30
param apiPrescaleReplicas = 8
param apiReadinessPath = readEnvironmentVariable('ARON_API_READINESS_PATH', '/v1/health/ready')

param workerMinReplicas = 1
param workerMaxReplicas = 10

param webMinReplicas = 2
param webMaxReplicas = 6
