// Virtual network for one environment: a subnet for the Container Apps environment (workload profiles, zone-redundant)
// and a subnet delegated to PostgreSQL Flexible Server (private access, no public endpoint on the database).

param location string
param tags object
param vnetName string
@description('Address space of the VNet, for example 10.51.0.0/16 (docs/18 s2.9: non-overlapping per environment).')
param addressPrefix string
@description('Container Apps infrastructure subnet; workload profiles need at least /27, /24 leaves room to grow.')
param acaSubnetPrefix string
@description('PostgreSQL delegated subnet (/28 is the minimum; primary, standby and replica take one address each).')
param postgresSubnetPrefix string
@description('Private endpoint subnet (D-FINAL-INFRA-08): private endpoints of the final account, first the Apsis dump landing account (infra/apsis-landing.bicep), so the loader job inside the VNet reaches it without public exposure.')
param privateEndpointSubnetPrefix string = ''

resource nsgAca 'Microsoft.Network/networkSecurityGroups@2024-05-01' = {
  name: '${vnetName}-snet-aca-nsg'
  location: location
  tags: tags
  properties: {
    securityRules: []
  }
}

resource nsgPg 'Microsoft.Network/networkSecurityGroups@2024-05-01' = {
  name: '${vnetName}-snet-pg-nsg'
  location: location
  tags: tags
  properties: {
    securityRules: [
      {
        // Only the Container Apps subnet (and the PostgreSQL subnet itself, for HA replication) reach the database.
        name: 'allow-aca-to-postgres'
        properties: {
          priority: 100
          direction: 'Inbound'
          access: 'Allow'
          protocol: 'Tcp'
          sourceAddressPrefix: acaSubnetPrefix
          sourcePortRange: '*'
          destinationAddressPrefix: postgresSubnetPrefix
          destinationPortRanges: ['5432', '6432']
        }
      }
      {
        name: 'allow-postgres-subnet-internal'
        properties: {
          priority: 110
          direction: 'Inbound'
          access: 'Allow'
          protocol: '*'
          sourceAddressPrefix: postgresSubnetPrefix
          sourcePortRange: '*'
          destinationAddressPrefix: postgresSubnetPrefix
          destinationPortRange: '*'
        }
      }
      {
        name: 'deny-vnet-to-postgres'
        properties: {
          priority: 4000
          direction: 'Inbound'
          access: 'Deny'
          protocol: '*'
          sourceAddressPrefix: 'VirtualNetwork'
          sourcePortRange: '*'
          destinationAddressPrefix: postgresSubnetPrefix
          destinationPortRange: '*'
        }
      }
    ]
  }
}

var peEnabled = !empty(privateEndpointSubnetPrefix)

resource vnet 'Microsoft.Network/virtualNetworks@2024-05-01' = {
  name: vnetName
  location: location
  tags: tags
  properties: {
    addressSpace: {
      addressPrefixes: [addressPrefix]
    }
    subnets: concat([
      {
        name: 'snet-aca'
        properties: {
          addressPrefix: acaSubnetPrefix
          networkSecurityGroup: { id: nsgAca.id }
          delegations: [
            {
              name: 'aca'
              properties: { serviceName: 'Microsoft.App/environments' }
            }
          ]
        }
      }
      {
        name: 'snet-pg'
        properties: {
          addressPrefix: postgresSubnetPrefix
          networkSecurityGroup: { id: nsgPg.id }
          delegations: [
            {
              name: 'postgres'
              properties: { serviceName: 'Microsoft.DBforPostgreSQL/flexibleServers' }
            }
          ]
        }
      }
    ], peEnabled ? [
      {
        // Appended last so the existing subnet output indexes do not move. Network policies for private endpoints stay at
        // their default (NSG rules are not applied to private endpoints; the account's own firewall and Entra roles are the gate).
        name: 'snet-pe'
        properties: { addressPrefix: privateEndpointSubnetPrefix }
      }
    ] : [])
  }
}

// Private DNS for blob private endpoints (Learn: "Azure Private Endpoint private DNS zone values"): the loader resolves
// <account>.blob.core.windows.net to the endpoint's private address inside this VNet only.
resource blobDnsZone 'Microsoft.Network/privateDnsZones@2024-06-01' = if (peEnabled) {
  name: 'privatelink.blob.${environment().suffixes.storage}'
  location: 'global'
  tags: tags
}

resource blobDnsLink 'Microsoft.Network/privateDnsZones/virtualNetworkLinks@2024-06-01' = if (peEnabled) {
  parent: blobDnsZone
  name: '${vnetName}-link'
  location: 'global'
  tags: tags
  properties: {
    registrationEnabled: false
    virtualNetwork: { id: vnet.id }
  }
}

output vnetId string = vnet.id
output acaSubnetId string = vnet.properties.subnets[0].id
output postgresSubnetId string = vnet.properties.subnets[1].id
output privateEndpointSubnetId string = peEnabled ? vnet.properties.subnets[2].id : ''
output blobPrivateDnsZoneId string = peEnabled ? blobDnsZone!.id : ''
