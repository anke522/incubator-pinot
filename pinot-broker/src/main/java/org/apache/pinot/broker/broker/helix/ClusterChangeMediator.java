/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.broker.broker.helix;

import com.google.common.base.Preconditions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.helix.HelixConstants.ChangeType;
import org.apache.helix.NotificationContext;
import org.apache.helix.api.listeners.BatchMode;
import org.apache.helix.api.listeners.ExternalViewChangeListener;
import org.apache.helix.api.listeners.InstanceConfigChangeListener;
import org.apache.helix.api.listeners.LiveInstanceChangeListener;
import org.apache.helix.api.listeners.PreFetch;
import org.apache.helix.model.ExternalView;
import org.apache.helix.model.InstanceConfig;
import org.apache.helix.model.LiveInstance;
import org.apache.pinot.common.metrics.BrokerMeter;
import org.apache.pinot.common.metrics.BrokerMetrics;
import org.apache.pinot.common.metrics.BrokerTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The {@code ClusterChangeMediator} handles the changes from Helix cluster.
 * <p>
 * <p>If there is no change callback in 1 hour, proactively check changes so that the changes are getting processed even
 * when callbacks stop working.
 * <p>NOTE: disable Helix batch-mode and perform deduplication in this class.
 * <p>NOTE: disable Helix pre-fetch to reduce the ZK accesses.
 */
@BatchMode(enabled = false)
@PreFetch(enabled = false)
public class ClusterChangeMediator implements ExternalViewChangeListener, InstanceConfigChangeListener, LiveInstanceChangeListener {
  private static final Logger LOGGER = LoggerFactory.getLogger(ClusterChangeMediator.class);

  // Add 1 second interval between change checks to deduplicate multiple changes of the same type
  private static final long CHANGE_CHECK_INTERVAL_MS = 1000L;
  // If no change got for 1 hour, proactively check changes
  private static final long PROACTIVE_CHANGE_CHECK_INTERVAL_MS = 3600 * 1000L;

  private final Map<ChangeType, ClusterChangeHandler> _changeHandlerMap;
  private final Map<ChangeType, Long> _lastChangeTimeMap = new ConcurrentHashMap<>();
  private final Map<ChangeType, Long> _lastProcessTimeMap = new ConcurrentHashMap<>();

  private final Thread _clusterChangeHandlingThread;

  public ClusterChangeMediator(Map<ChangeType, ClusterChangeHandler> changeHandlerMap, BrokerMetrics brokerMetrics) {
    _changeHandlerMap = changeHandlerMap;

    // Initialize last process time map
    long initTime = System.currentTimeMillis();
    for (ChangeType changeType : changeHandlerMap.keySet()) {
      _lastProcessTimeMap.put(changeType, initTime);
    }

    _clusterChangeHandlingThread = new Thread("ClusterChangeHandlingThread") {
      @Override
      public void run() {
        while (true) {
          try {
            for (Map.Entry<ChangeType, ClusterChangeHandler> entry : _changeHandlerMap.entrySet()) {
              ChangeType changeType = entry.getKey();
              ClusterChangeHandler changeHandler = entry.getValue();
              long currentTime = System.currentTimeMillis();
              Long lastChangeTime = _lastChangeTimeMap.remove(changeType);
              if (lastChangeTime != null) {
                brokerMetrics.addTimedValue(BrokerTimer.CLUSTER_CHANGE_QUEUE_TIME, currentTime - lastChangeTime,
                    TimeUnit.MILLISECONDS);
                processClusterChange(changeType, changeHandler);
              } else {
                long lastProcessTime = _lastProcessTimeMap.get(changeType);
                if (currentTime - lastProcessTime > PROACTIVE_CHANGE_CHECK_INTERVAL_MS) {
                  LOGGER.info("Proactive check {} change", changeType);
                  brokerMetrics.addMeteredGlobalValue(BrokerMeter.PROACTIVE_CLUSTER_CHANGE_CHECK, 1L);
                  processClusterChange(changeType, changeHandler);
                }
              }
            }

            // Add an interval between change checks to deduplicate multiple changes of the same type
            Thread.sleep(CHANGE_CHECK_INTERVAL_MS);
          } catch (InterruptedException e) {
            LOGGER.warn("Cluster change handling thread is interrupted, stopping the thread");
            break;
          } catch (Exception e) {
            LOGGER.error("Caught exception while handling changes", e);
          }
        }
      }
    };
    _clusterChangeHandlingThread.start();
  }

  private void processClusterChange(ChangeType changeType, ClusterChangeHandler changeHandler) {
    long startTime = System.currentTimeMillis();
    LOGGER.info("Start processing {} change", changeType);
    changeHandler.processClusterChange();
    long endTime = System.currentTimeMillis();
    LOGGER.info("Finish processing {} change in {}ms", changeType, endTime - startTime);
    _lastProcessTimeMap.put(changeType, endTime);
  }

  @Override
  public void onExternalViewChange(List<ExternalView> externalViewList, NotificationContext changeContext) {
    Preconditions.checkState(externalViewList.isEmpty(), "Helix pre-fetch should be disabled");
    Preconditions.checkState(_clusterChangeHandlingThread.isAlive(), "Cluster change handling thread is not alive");

    enqueueChange(ChangeType.EXTERNAL_VIEW);
  }

  @Override
  public void onInstanceConfigChange(List<InstanceConfig> instanceConfigs, NotificationContext changeContext) {
    Preconditions.checkState(instanceConfigs.isEmpty(), "Helix pre-fetch should be disabled");
    Preconditions.checkState(_clusterChangeHandlingThread.isAlive(), "Cluster change handling thread is not alive");

    enqueueChange(ChangeType.INSTANCE_CONFIG);
  }

  @Override
  public void onLiveInstanceChange(List<LiveInstance> liveInstances, NotificationContext changeContext) {
    Preconditions.checkState(liveInstances.isEmpty(), "Helix pre-fetch should be disabled");
    Preconditions.checkState(_clusterChangeHandlingThread.isAlive(), "Cluster change handling thread is not alive");

    enqueueChange(ChangeType.LIVE_INSTANCE);
  }

  /**
   * Enqueues a change from the Helix callback.
   *
   * @param changeType Type of the change
   */
  private void enqueueChange(ChangeType changeType) {
    LOGGER.info("Enqueue {} change", changeType);
    _lastChangeTimeMap.put(changeType, System.currentTimeMillis());
  }
}
