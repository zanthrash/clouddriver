/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.netflix.spinnaker.kato.aws.deploy.ops.discovery

import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.model.DescribeInstancesRequest
import com.google.common.annotations.VisibleForTesting
import com.netflix.spinnaker.kato.aws.deploy.description.EnableDisableInstanceDiscoveryDescription
import com.netflix.spinnaker.kato.aws.services.AsgService
import com.netflix.spinnaker.kato.aws.services.RegionScopedProviderFactory
import com.netflix.spinnaker.kato.data.task.Task
import groovy.transform.InheritConstructors
import groovy.transform.PackageScope
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestTemplate

@Slf4j
@Component
class DiscoverySupport {
  private static final long THROTTLE_MS = 150

  static final int DISCOVERY_RETRY_MAX = 10
  private static final long DEFAULT_DISCOVERY_RETRY_MS = 3000

  @Autowired
  RestTemplate restTemplate

  @Autowired
  RegionScopedProviderFactory regionScopedProviderFactory

  void updateDiscoveryStatusForInstances(EnableDisableInstanceDiscoveryDescription description,
                                         Task task,
                                         String phaseName,
                                         DiscoveryStatus discoveryStatus,
                                         List<String> instanceIds) {
    if (!description.credentials.discoveryEnabled) {
      throw new DiscoveryNotConfiguredException()
    }

    def region = description.region
    def discovery = String.format(description.credentials.discovery, region)

    def regionScopedProvider = regionScopedProviderFactory.forRegion(description.credentials, description.region)
    def amazonEC2 = regionScopedProviderFactory.amazonClientProvider.getAmazonEC2(description.credentials, region)
    def asgService = regionScopedProvider.asgService

    instanceIds.eachWithIndex { instanceId, index ->
      if (index > 0) {
        sleep THROTTLE_MS
      }

      def retryCount = 0
      while (true) {
        try {
          if (!task.status.isFailed()) {
            task.updateStatus phaseName, "Attempting to mark ${instanceId} as '${discoveryStatus.value}' in discovery (attempt: ${retryCount})."

            if (discoveryStatus == DiscoveryStatus.Disable && !verifyInstanceAndAsgExist(amazonEC2, asgService, instanceId, description.asgName)) {
              task.updateStatus phaseName, "Instance (${instanceId}) or ASG (${description.asgName}) no longer exist, skipping"
              return
            }

            def instanceDetails = restTemplate.getForEntity("${discovery}/v2/instances/${instanceId}", Map)
            def applicationName = instanceDetails?.body?.instance?.app
            if (applicationName) {
              restTemplate.put("${discovery}/v2/apps/${applicationName}/${instanceId}/status?value=${discoveryStatus.value}", [:])
              task.updateStatus phaseName, "Marked ${instanceId} as '${discoveryStatus.value}' in discovery."
            } else {
              task.updateStatus phaseName, "Instance '${instanceId}' does not exist in discovery, unable to mark as '${discoveryStatus.value}'"
              task.fail()
            }
          }
          break
        } catch (ResourceAccessException ex) {
          if (retryCount >= (DISCOVERY_RETRY_MAX - 1)) {
            throw ex
          }

          retryCount++
          sleep(getDiscoveryRetryMs());

        } catch (HttpServerErrorException | HttpClientErrorException e) {
          if (retryCount >= (DISCOVERY_RETRY_MAX - 1)) {
            throw e
          }

          if (e.statusCode.is5xxServerError()) {
            // automatically retry on server errors (but wait a little longer between attempts)
            sleep(getDiscoveryRetryMs() * 10)
            retryCount++
          } else if (e.statusCode == HttpStatus.NOT_FOUND) {
            // automatically retry on 404's
            retryCount++
            sleep(getDiscoveryRetryMs())
          } else {
            throw e
          }
        }
      }
    }
  }

  protected long getDiscoveryRetryMs() {
    return DEFAULT_DISCOVERY_RETRY_MS
  }

  enum DiscoveryStatus {
    Enable('UP'),
    Disable('OUT_OF_SERVICE')

    String value

    DiscoveryStatus(String value) {
      this.value = value
    }
  }

  @VisibleForTesting
  @PackageScope
  boolean verifyInstanceAndAsgExist(AmazonEC2 amazonEC2,
                                    AsgService asgService,
                                    String instanceId,
                                    String asgName) {
    def autoScalingGroup = asgService.getAutoScalingGroup(asgName)
    if (!autoScalingGroup || autoScalingGroup.status) {
      // ASG does not exist or is in the process of being deleted
      return false
    }
    log.info("AutoScalingGroup (${asgName}) exists")


    if (!autoScalingGroup.instances.find { it.instanceId == instanceId }) {
      return false
    }
    log.info("AutoScalingGroup (${asgName}) contains instance (${instanceId})")

    def instances = amazonEC2.describeInstances(
      new DescribeInstancesRequest().withInstanceIds(instanceId)
    ).reservations*.instances.flatten()
    if (!instances.find { it.instanceId == instanceId }) {
      return false
    }
    log.info("Instance (${instanceId}) exists")

    return true
  }

  @InheritConstructors
  static class DiscoveryNotConfiguredException extends RuntimeException {}
}