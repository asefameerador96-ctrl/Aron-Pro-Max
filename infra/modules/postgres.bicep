// Azure Database for PostgreSQL Flexible Server 16: zone-redundant HA (synchronous standby in a second zone),
// point-in-time restore, geo-redundant backup (settable ONLY at creation, docs/18 s2.6), built-in PgBouncer,
// private access through a delegated subnet, and an optional in-region read replica for dashboards (docs/24 s6.3).

param location string
param tags object
param serverName string
param replicaName string
param dnsZoneName string
@description('true: delegated subnet + private DNS zone, no public endpoint (FINAL). false: public endpoint whose firewall admits only Azure services, TLS required (TEST, no VNet).')
param privateNetworking bool = true
param vnetId string
param subnetId string
param logAnalyticsId string
param databaseName string
param adminLogin string
@secure()
param adminPassword string

@description('Compute SKU, for example Standard_D2ds_v5 (General Purpose) or Standard_B1ms (Burstable).')
param skuName string
@description('Burstable has no HA, no built-in PgBouncer, no Query Store and no SSD v2 (Learn).')
@allowed(['Burstable', 'GeneralPurpose', 'MemoryOptimized'])
param skuTier string = 'GeneralPurpose'
@description('PremiumV2_LRS: IOPS and throughput set independently of size, no autogrow (grow by hand at 70 %). Premium_LRS: SSD v1 with autogrow.')
@allowed(['PremiumV2_LRS', 'Premium_LRS'])
param storageType string = 'PremiumV2_LRS'
param storageSizeGb int
param storageIops int
param storageThroughputMBps int
@allowed(['ZoneRedundant', 'SameZone', 'Disabled'])
param haMode string
param primaryZone string = '1'
param standbyZone string = '2'
@minValue(7)
@maxValue(35)
param backupRetentionDays int
@description('Geo-redundant backup can only be chosen when the server is created; changing it later fails.')
param geoRedundantBackup bool
param enableReadReplica bool
param replicaZone string = '3'
@description('PgBouncer pool size per (user, database) pair; docs/18 s2.6 Little\'s-law check gives 20.')
param pgbouncerPoolSize int = 20
@description('Built-in PgBouncer; must be false on Burstable.')
param pgbouncerEnabled bool = true

var storage = storageType == 'PremiumV2_LRS' ? {
  type: 'PremiumV2_LRS'
  storageSizeGB: storageSizeGb
  iops: storageIops
  throughput: storageThroughputMBps
  autoGrow: 'Disabled'
} : {
  type: 'Premium_LRS'
  storageSizeGB: storageSizeGb
  autoGrow: 'Enabled'
}

var network = privateNetworking ? {
  delegatedSubnetResourceId: subnetId
  privateDnsZoneArmResourceId: dnsZone.id
  publicNetworkAccess: 'Disabled'
} : {
  publicNetworkAccess: 'Enabled'
}

resource dnsZone 'Microsoft.Network/privateDnsZones@2024-06-01' = if (privateNetworking) {
  name: dnsZoneName
  location: 'global'
  tags: tags
}

resource dnsLink 'Microsoft.Network/privateDnsZones/virtualNetworkLinks@2024-06-01' = if (privateNetworking) {
  parent: dnsZone
  name: 'vnet-link'
  location: 'global'
  tags: tags
  properties: {
    registrationEnabled: false
    virtualNetwork: { id: vnetId }
  }
}

resource server 'Microsoft.DBforPostgreSQL/flexibleServers@2024-08-01' = {
  name: serverName
  location: location
  tags: tags
  sku: { name: skuName, tier: skuTier }
  dependsOn: [dnsLink]
  properties: {
    version: '16'
    administratorLogin: adminLogin
    administratorLoginPassword: adminPassword
    authConfig: { activeDirectoryAuth: 'Disabled', passwordAuth: 'Enabled' }
    availabilityZone: primaryZone
    storage: storage
    backup: {
      backupRetentionDays: backupRetentionDays
      geoRedundantBackup: geoRedundantBackup ? 'Enabled' : 'Disabled'
    }
    highAvailability: haMode == 'Disabled' ? { mode: 'Disabled' } : {
      mode: haMode
      standbyAvailabilityZone: haMode == 'ZoneRedundant' ? standbyZone : primaryZone
    }
    network: network
    // Friday 01:00 to 02:00 Dhaka (UTC+6) = Thursday 19:00 UTC: the weekly off-day, never a trading day (docs/18 s2.6).
    maintenanceWindow: { customWindow: 'Enabled', dayOfWeek: 4, startHour: 19, startMinute: 0 }
  }
}

resource db 'Microsoft.DBforPostgreSQL/flexibleServers/databases@2024-08-01' = {
  parent: server
  name: databaseName
  properties: { charset: 'UTF8', collation: 'en_US.utf8' }
}

var pgbouncerSettings = [
  { name: 'pgbouncer.enabled', value: 'true' }
  { name: 'pgbouncer.default_pool_size', value: string(pgbouncerPoolSize) }
  { name: 'pgbouncer.query_wait_timeout', value: '5' }
]
var baseSettings = [
  { name: 'azure.extensions', value: 'BTREE_GIST,PGCRYPTO,PG_STAT_STATEMENTS,POSTGIS' }
  { name: 'log_min_duration_statement', value: '500' }
  { name: 'track_io_timing', value: 'on' }
  { name: 'idle_in_transaction_session_timeout', value: '30000' }
]
// Query Store is not offered on Burstable.
var queryStoreSettings = skuTier == 'Burstable' ? [] : [{ name: 'pg_qs.query_capture_mode', value: 'top' }]
var settings = concat(pgbouncerEnabled ? pgbouncerSettings : [], baseSettings, queryStoreSettings)

// Server parameters must be written one at a time (parallel writes conflict on the server).
@batchSize(1)
resource config 'Microsoft.DBforPostgreSQL/flexibleServers/configurations@2024-08-01' = [for s in settings: {
  parent: server
  name: s.name
  dependsOn: [db]
  properties: { value: s.value, source: 'user-override' }
}]

// Read replica: asynchronous, no HA of its own, same SKU as the primary so WAL replay keeps up (docs/18 s2.3).
resource replica 'Microsoft.DBforPostgreSQL/flexibleServers@2024-08-01' = if (enableReadReplica) {
  name: replicaName
  location: location
  tags: tags
  sku: { name: skuName, tier: skuTier }
  dependsOn: [config]
  properties: {
    createMode: 'Replica'
    sourceServerResourceId: server.id
    availabilityZone: replicaZone
    storage: storage
    network: network
  }
}

// Server parameters are not replicated: the replica needs its own PgBouncer, or ARON_DB_READ_URL (port 6432) would point
// at nothing (Learn: read replicas, PgBouncer).
var replicaSettings = pgbouncerEnabled ? pgbouncerSettings : []

@batchSize(1)
resource replicaConfig 'Microsoft.DBforPostgreSQL/flexibleServers/configurations@2024-08-01' = [for s in replicaSettings: if (enableReadReplica) {
  parent: replica
  name: s.name
  properties: { value: s.value, source: 'user-override' }
}]

// TEST profile only: no VNet, so the firewall admits Azure services (0.0.0.0) and nothing else; TLS is required by
// default (require_secure_transport) and the admin password is 35 random characters in Key Vault.
resource allowAzure 'Microsoft.DBforPostgreSQL/flexibleServers/firewallRules@2024-08-01' = if (!privateNetworking) {
  parent: server
  name: 'AllowAllAzureServicesAndResourcesWithinAzureIps'
  properties: { startIpAddress: '0.0.0.0', endIpAddress: '0.0.0.0' }
  dependsOn: [config]
}

resource diag 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: 'to-log-analytics'
  scope: server
  properties: {
    workspaceId: logAnalyticsId
    logs: [{ categoryGroup: 'allLogs', enabled: true }]
    metrics: [{ category: 'AllMetrics', enabled: true }]
  }
}

output serverId string = server.id
output host string = server.properties.fullyQualifiedDomainName
output readHost string = enableReadReplica ? replica!.properties.fullyQualifiedDomainName : server.properties.fullyQualifiedDomainName
output replicaId string = enableReadReplica ? replica!.id : ''
