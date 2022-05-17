/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.session

import scala.collection.JavaConverters._

import com.codahale.metrics.MetricRegistry
import org.apache.hive.service.rpc.thrift.TProtocolVersion

import org.apache.kyuubi.{KyuubiSQLException, Utils}
import org.apache.kyuubi.client.api.v1.dto.BatchRequest
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.events.{EventBus, KyuubiSessionEvent}
import org.apache.kyuubi.metrics.MetricsConstants.{CONN_OPEN, CONN_TOTAL}
import org.apache.kyuubi.metrics.MetricsSystem

class KyuubiBatchSessionImpl(
    protocol: TProtocolVersion,
    user: String,
    password: String,
    ipAddress: String,
    conf: Map[String, String],
    sessionManager: KyuubiSessionManager,
    val sessionConf: KyuubiConf,
    batchRequest: BatchRequest)
  extends KyuubiSession(protocol, user, password, ipAddress, conf, sessionManager) {
  override val handle: SessionHandle = sessionManager.newBatchSessionHandle(protocol)

  // TODO: Support batch conf advisor
  override val normalizedConf: Map[String, String] =
    sessionManager.validateBatchConf(Option(batchRequest.getConf.asScala.toMap)
      .getOrElse(Map.empty))

  batchRequest.setConf(normalizedConf.asJava)

  val sessionCluster =
    if (sessionManager.sessionClusterModeEnabled) {
      normalizedConf.get(SESSION_CLUSTER.key).orElse(sessionConf.get(SESSION_CLUSTER))
    } else {
      None
    }

  if (sessionManager.sessionClusterModeEnabled) {
    var gotClusterPropertiesFile = false

    val sessionClusterConf = KyuubiConf(false)
    Utils.getDefaultPropertiesFileForCluster(sessionCluster).foreach { clusterPropertiesFile =>
      gotClusterPropertiesFile = true
      Utils.getPropertiesFromFile(Option(clusterPropertiesFile)).foreach {
        case (key, value) => sessionClusterConf.set(key, value)
      }
    }

    if (!gotClusterPropertiesFile) {
      val clusterList = Utils.getDefinedPropertiesClusterList()
      throw KyuubiSQLException(
        s"Please specify the cluster to access with session conf[${SESSION_CLUSTER.key}]," +
          s" which should be one of ${clusterList.mkString("[", ",", "]")}")
    }

    sessionClusterConf.getUserDefaults(user).getAll.foreach { case (key, value) =>
      sessionConf.set(key, value)
    }
  }

  private[kyuubi] lazy val batchJobSubmissionOp = sessionManager.operationManager
    .newBatchJobSubmissionOperation(this, batchRequest)

  private val sessionEvent = KyuubiSessionEvent(this)
  sessionCluster.foreach(sessionEvent.sessionCluster = _)
  EventBus.post(sessionEvent)

  override def getSessionEvent: Option[KyuubiSessionEvent] = {
    Option(sessionEvent)
  }

  override def open(): Unit = {
    MetricsSystem.tracing { ms =>
      ms.incCount(CONN_TOTAL)
      ms.incCount(MetricRegistry.name(CONN_OPEN, user))
    }

    // we should call super.open before running batch job submission operation
    super.open()

    runOperation(batchJobSubmissionOp)
  }

  override def close(): Unit = {
    super.close()
    sessionEvent.endTime = System.currentTimeMillis()
    EventBus.post(sessionEvent)
    MetricsSystem.tracing(_.decCount(MetricRegistry.name(CONN_OPEN, user)))
  }
}
