/*
 * Copyright 2016 Target, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.openstack.provider.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.cats.agent.AgentDataType
import com.netflix.spinnaker.cats.agent.CacheResult
import com.netflix.spinnaker.cats.cache.CacheData
import com.netflix.spinnaker.cats.cache.RelationshipCacheFilter
import com.netflix.spinnaker.cats.provider.ProviderCache
import com.netflix.spinnaker.clouddriver.cache.OnDemandAgent
import com.netflix.spinnaker.clouddriver.cache.OnDemandAgent.OnDemandResult
import com.netflix.spinnaker.clouddriver.cache.OnDemandMetricsSupport
import com.netflix.spinnaker.clouddriver.openstack.cache.CacheResultBuilder
import com.netflix.spinnaker.clouddriver.openstack.cache.Keys
import com.netflix.spinnaker.clouddriver.openstack.cache.UnresolvableKeyException
import com.netflix.spinnaker.clouddriver.openstack.deploy.exception.OpenstackProviderException
import com.netflix.spinnaker.clouddriver.openstack.model.OpenstackFloatingIP
import com.netflix.spinnaker.clouddriver.openstack.model.OpenstackLoadBalancer
import com.netflix.spinnaker.clouddriver.openstack.model.OpenstackNetwork
import com.netflix.spinnaker.clouddriver.openstack.model.OpenstackPort
import com.netflix.spinnaker.clouddriver.openstack.model.OpenstackSubnet
import com.netflix.spinnaker.clouddriver.openstack.model.OpenstackVip
import com.netflix.spinnaker.clouddriver.openstack.security.OpenstackNamedAccountCredentials
import groovy.util.logging.Slf4j
import org.openstack4j.model.network.ext.HealthMonitor
import org.openstack4j.model.network.ext.LbPool
import org.openstack4j.model.network.ext.Vip

import static com.netflix.spinnaker.cats.agent.AgentDataType.Authority.AUTHORITATIVE
import static com.netflix.spinnaker.clouddriver.cache.OnDemandAgent.OnDemandType.LoadBalancer
import static com.netflix.spinnaker.clouddriver.openstack.OpenstackCloudProvider.ID
import static com.netflix.spinnaker.clouddriver.openstack.cache.Keys.Namespace.FLOATING_IPS
import static com.netflix.spinnaker.clouddriver.openstack.cache.Keys.Namespace.LOAD_BALANCERS
import static com.netflix.spinnaker.clouddriver.openstack.cache.Keys.Namespace.NETWORKS
import static com.netflix.spinnaker.clouddriver.openstack.cache.Keys.Namespace.PORTS
import static com.netflix.spinnaker.clouddriver.openstack.cache.Keys.Namespace.SUBNETS
import static com.netflix.spinnaker.clouddriver.openstack.cache.Keys.Namespace.VIPS
import static com.netflix.spinnaker.clouddriver.openstack.provider.OpenstackInfrastructureProvider.ATTRIBUTES

@Slf4j
class OpenstackLoadBalancerCachingAgent extends AbstractOpenstackCachingAgent implements OnDemandAgent {

  final ObjectMapper objectMapper
  final OnDemandMetricsSupport metricsSupport

  Collection<AgentDataType> providedDataTypes = Collections.unmodifiableSet([
    AUTHORITATIVE.forType(LOAD_BALANCERS.ns)
  ] as Set)

  String agentType = "${accountName}/${region}/${OpenstackLoadBalancerCachingAgent.simpleName}"
  String onDemandAgentType = "${agentType}-OnDemand"

  OpenstackLoadBalancerCachingAgent(final OpenstackNamedAccountCredentials account,
                                    final String region,
                                    final ObjectMapper objectMapper,
                                    final Registry registry) {
    super(account, region)
    this.objectMapper = objectMapper
    this.metricsSupport = new OnDemandMetricsSupport(
      registry,
      this,
      "${ID}:${LoadBalancer}")
  }

  @Override
  CacheResult loadData(ProviderCache providerCache) {
    log.info("Describing items in ${agentType}")

    List<LbPool> pools = clientProvider.getAllLoadBalancerPools(region)

    List<String> loadBalancerKeys = pools.collect { Keys.getLoadBalancerKey(it.name, it.id, accountName, region) }

    buildLoadDataCache(providerCache, loadBalancerKeys) { CacheResultBuilder cacheResultBuilder ->
      buildCacheResult(providerCache, pools, cacheResultBuilder)
    }
  }

  CacheResult buildCacheResult(ProviderCache providerCache, List<LbPool> pools, CacheResultBuilder cacheResultBuilder) {
    pools?.collect { pool ->
      String loadBalancerKey = Keys.getLoadBalancerKey(pool.name, pool.id, accountName, region)

      if (shouldUseOnDemandData(cacheResultBuilder, loadBalancerKey)) {
        moveOnDemandDataToNamespace(objectMapper, typeReference, cacheResultBuilder, loadBalancerKey)
      } else {
        //health monitors get looked up
        Set<HealthMonitor> healthMonitors = pool.healthMonitors?.collect { healthId ->
          clientProvider.getHealthMonitor(region, healthId)
        }?.toSet()

        //vips cached
        Map<String, Object> vipMap = providerCache.get(VIPS.ns, Keys.getVipKey(pool.vipId, accountName, region))?.attributes
        OpenstackVip vip = vipMap ? objectMapper.convertValue(vipMap, OpenstackVip) : null

        //ips cached
        OpenstackFloatingIP ip = null
        if (vip) {
          Collection<String> portFilters = providerCache.filterIdentifiers(PORTS.ns, Keys.getPortKey('*', accountName, region))
          Collection<CacheData> portsData = providerCache.getAll(PORTS.ns, portFilters, RelationshipCacheFilter.none())
          CacheData portCacheData = portsData?.find { p -> p.attributes?.name == "vip-${vip.id}" }
          Map<String, Object> portAttributes = portCacheData?.attributes
          OpenstackPort port = objectMapper.convertValue(portAttributes, OpenstackPort)
          if (port) {
            Collection<String> ipFilters = providerCache.filterIdentifiers(FLOATING_IPS.ns, Keys.getFloatingIPKey('*', accountName, region))
            Collection<CacheData> ipsData = providerCache.getAll(FLOATING_IPS.ns, ipFilters, RelationshipCacheFilter.none())
            CacheData ipCacheData = ipsData.find { i -> i.attributes?.portId == port.id }
            Map<String, Object> ipAttributes = ipCacheData?.attributes
            ip = objectMapper.convertValue(ipAttributes, OpenstackFloatingIP)
          }
        }

        //subnets cached
        Map<String, Object> subnetMap = providerCache.get(SUBNETS.ns, Keys.getSubnetKey(pool.subnetId, accountName, region))?.attributes
        OpenstackSubnet subnet = subnetMap ? objectMapper.convertValue(subnetMap, OpenstackSubnet) : null

        //networks cached
        OpenstackNetwork network = null
        if (ip) {
          Map<String, Object> networkMap = providerCache.get(NETWORKS.ns, Keys.getNetworkKey(ip.networkId, accountName, region))?.attributes
          network = networkMap ? objectMapper.convertValue(networkMap, OpenstackNetwork) : null
        }

        //create load balancer. Server group relationships are not cached here as they are cached in the server group caching agent.
        OpenstackLoadBalancer loadBalancer = OpenstackLoadBalancer.from(pool, vip, subnet, network, ip, healthMonitors, accountName, region)

        cacheResultBuilder.namespace(LOAD_BALANCERS.ns).keep(loadBalancerKey).with {
          attributes = objectMapper.convertValue(loadBalancer, ATTRIBUTES)
        }
      }
    }

    log.info("Caching ${cacheResultBuilder.namespace(LOAD_BALANCERS.ns).keepSize()} load balancers in ${agentType}")
    log.info("Caching ${cacheResultBuilder.onDemand.toKeep.size()} onDemand entries in ${agentType}")
    log.info("Evicting ${cacheResultBuilder.onDemand.toEvict.size()} onDemand entries in ${agentType}")

    cacheResultBuilder.build()
  }

  @Override
  boolean handles(OnDemandAgent.OnDemandType type, String cloudProvider) {
    type == LoadBalancer && cloudProvider == ID
  }

  @Override
  OnDemandResult handle(ProviderCache providerCache, Map<String, ? extends Object> data) {
    OnDemandResult result = null

    if (data.containsKey("loadBalancerName") && data.account == accountName && data.region == region) {
      String loadBalancerName = data.loadBalancerName.toString()

      LbPool pool = metricsSupport.readData {
        LbPool lbResult = null
        try {
          LbPool lbPool = clientProvider.getLoadBalancerPoolByName(region, loadBalancerName)

          /*
            TODO - Replace with lbaasv2
             As per specification(https://wiki.openstack.org/wiki/Neutron/LBaaS/API_1.0#Synchronous_versus_Asynchronous_Plugin_Behavior),
             lbaasv1 supports asynchronous modification of resources via status ... However, testing has shown this NOT to be the case.
             Added vip check to ensure the deletion of a load balancer could be identified and the UI will be updated immediately.  This is
             a stop gap until it's replaced with lbaasv2.
          */
          if (lbPool && clientProvider.getVip(region, lbPool.vipId)) {
            lbResult = lbPool
          }
        } catch (OpenstackProviderException ope) {
          //Do nothing ... Exception is thrown if a pool isn't found
        }

        return lbResult
      }

      List<LbPool> pools = []
      String loadBalancerKey = Keys.getLoadBalancerKey(loadBalancerName, '*', accountName, region)

      if (pool) {
        pools = [pool]
        loadBalancerKey = Keys.getLoadBalancerKey(loadBalancerName, pool.id, accountName, region)
      }

      CacheResult cacheResult = metricsSupport.transformData {
        buildCacheResult(providerCache, pools, new CacheResultBuilder(startTime: Long.MAX_VALUE))
      }

      String namespace = LOAD_BALANCERS.ns
      String resolvedKey = null
      try {
        resolvedKey = resolveKey(providerCache, namespace, loadBalancerKey)
        processOnDemandCache(cacheResult, objectMapper, metricsSupport, providerCache, resolvedKey)
      } catch (UnresolvableKeyException uke) {
        log.info("Load balancer ${loadBalancerName} is not resolvable", uke)
      }

      result = buildOnDemandCache(pool, onDemandAgentType, cacheResult, namespace, resolvedKey)
    }

    log.info("On demand cache refresh (data: ${data}) succeeded.")

    result
  }

  @Override
  Collection<Map> pendingOnDemandRequests(ProviderCache providerCache) {
    getAllOnDemandCacheByRegionAndAccount(providerCache, accountName, region)
  }
}
