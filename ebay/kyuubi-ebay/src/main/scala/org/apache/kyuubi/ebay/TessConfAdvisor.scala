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

package org.apache.kyuubi.ebay

import java.util.{Map => JMap}

import scala.collection.JavaConverters._

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiEbayConf._
import org.apache.kyuubi.plugin.SessionConfAdvisor

class TessConfAdvisor extends SessionConfAdvisor with Logging {
  import TagBasedSessionConfAdvisor.fileConfCache
  import TessConfAdvisor._

  private val tessConfFile = KyuubiConf().get(SESSION_TESS_CONF_FILE)
  private def clusterTessConfFile(cluster: Option[String]): Option[String] = {
    cluster.map(c => s"$tessConfFile.$c")
  }

  override def getConfOverlay(
      user: String,
      sessionConf: JMap[String, String]): JMap[String, String] = {
    if ("true".equalsIgnoreCase(sessionConf.get(ENGINE_SPARK_TESS_ENABLED.key))) {
      val sessionCluster = sessionConf.asScala.get(SESSION_CLUSTER.key)
      val tessConf = fileConfCache.get(tessConfFile)
      val clusterTessConf = clusterTessConfFile(sessionCluster).map(fileConfCache.get)

      val appName = Option(sessionConf.get(ADLC_APP)).map(_.trim).getOrElse("")
      val appInstance = Option(sessionConf.get(ADLC_AI)).map(_.trim).getOrElse("")
      val appImage = Option(sessionConf.get(ADLC_IMAGE)).map(_.trim).getOrElse("")

      val appConf = Map(
        DRIVER_ANNOTATION_APPLICATION_NAME -> appName,
        DRIVER_LABEL_APPLICATION_INSTANCE -> appInstance,
        DRIVER_ANNOTATION_SHERLOCK_LOGS -> appName,
        EXECUTOR_ANNOTATION_APPLICATION_NAME -> appName,
        EXECUTOR_LABEL_APPLICATION_INSTANCE -> appInstance,
        EXECUTOR_ANNOTATION_SHERLOCK_LOGS -> appName,
        CONTAINER_IMAGE -> appImage).filter(_._2.nonEmpty)

      val allConf = tessConf.getAll ++ clusterTessConf.map(_.getAll).getOrElse(Map.empty) ++ appConf

      if ("BATCH".equalsIgnoreCase(sessionConf.get(KYUUBI_SESSION_TYPE_KEY))) {
        toBatchConf(allConf).asJava
      } else {
        allConf.asJava
      }
    } else {
      Map.empty[String, String].asJava
    }
  }
}

object TessConfAdvisor {
  final private val ADLC = "kyuubi.hadoop.adlc."

  final val ADLC_APP = ADLC + "app"
  final val ADLC_AI = ADLC + "ai"
  final val ADLC_IMAGE = ADLC + "image"

  final private val DRIVER_LABEL_PREFIX = "spark.kubernetes.driver.label."
  final private val DRIVER_ANNOTATION_PREFIX = "spark.kubernetes.driver.annotation."
  final private val EXECUTOR_LABEL_PREFIX = "spark.kubernetes.executor.label."
  final private val EXECUTOR_ANNOTATION_PREFIX = "spark.kubernetes.executor.annotation."

  final private val APPLICATION_NAME = "application.tess.io/name"
  final private val APPLICATION_INSTANCE = "applicationinstance.tess.io/name"
  final private val SHERLOCK_LOGS = "io.sherlock.logs/namespace"

  final val DRIVER_ANNOTATION_APPLICATION_NAME = DRIVER_ANNOTATION_PREFIX + APPLICATION_NAME
  final val EXECUTOR_ANNOTATION_APPLICATION_NAME = EXECUTOR_ANNOTATION_PREFIX + APPLICATION_NAME
  final val DRIVER_LABEL_APPLICATION_INSTANCE = DRIVER_LABEL_PREFIX + APPLICATION_INSTANCE
  final val EXECUTOR_LABEL_APPLICATION_INSTANCE = EXECUTOR_LABEL_PREFIX + APPLICATION_INSTANCE
  final val DRIVER_ANNOTATION_SHERLOCK_LOGS = DRIVER_ANNOTATION_PREFIX + SHERLOCK_LOGS
  final val EXECUTOR_ANNOTATION_SHERLOCK_LOGS = EXECUTOR_ANNOTATION_PREFIX + SHERLOCK_LOGS

  final val CONTAINER_IMAGE = "spark.kubernetes.container.image"
}
