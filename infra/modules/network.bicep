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

resource vnet 'Microsoft.Network/virtualNetworks@2024-05-01' = {
  name: vnetName
  location: location
  tags: tags
  properties: {
    addressSpace: {
      addressPrefixes: [addressPrefix]
    }
    subnets: [
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
    ]
  }
}

output vnetId string = vnet.id
output acaSubnetId string = vnet.properties.subnets[0].id
output postgresSubnetId string = vnet.properties.subnets[1].id
