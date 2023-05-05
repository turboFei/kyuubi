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

import java.util.{Locale, Map => JMap}
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._

import com.google.common.cache.{CacheBuilder, CacheLoader, LoadingCache}

import org.apache.kyuubi.{KyuubiException, Logging, Utils}
import org.apache.kyuubi.config.{KyuubiConf, KyuubiEbayConf}
import org.apache.kyuubi.config.KyuubiEbayConf.{SESSION_CLUSTER, SESSION_TAG_CONF_FILE}
import org.apache.kyuubi.plugin.SessionConfAdvisor

class TagBasedSessionConfAdvisor extends SessionConfAdvisor with Logging {
  import TagBasedSessionConfAdvisor._

  private val tagConfFile = KyuubiConf().get(SESSION_TAG_CONF_FILE)
  private def clusterTagConfFile(cluster: Option[String]): Option[String] = {
    cluster.map(c => s"$tagConfFile.$c")
  }

  private val tessConfAdvisor = new TessConfAdvisor()

  private def validateSparkMaster(sessionConf: JMap[String, String]): Map[String, String] = {
    Option(sessionConf.get(SPARK_MASTER_KEY)).map { sparkMaster =>
      if (sparkMaster.toLowerCase(Locale.ROOT).startsWith("yarn")) {
        Map(SPARK_MASTER_KEY -> "yarn")
      } else if (sparkMaster.toLowerCase(Locale.ROOT).startsWith("k8s")) {
        Map(SPARK_MASTER_KEY -> sparkMaster)
      } else {
        throw new KyuubiException(
          s"Invalid spark master[$sparkMaster]." +
            s" The valid spark master can be one of the following: `yarn`, `k8s://HOST:PORT`.")
      }
    }.getOrElse(Map.empty[String, String])
  }

  override def getConfOverlay(
      user: String,
      sessionConf: JMap[String, String]): JMap[String, String] = {
    val sessionTag =
      KyuubiEbayConf.getSessionTag(sessionConf.asScala.toMap).getOrElse(KYUUBI_DEFAULT_TAG)
    val sessionCluster = sessionConf.asScala.get(SESSION_CLUSTER.key)

    val tagConf = fileConfCache.get(tagConfFile)
    val clusterTagConf = clusterTagConfFile(sessionCluster).map(fileConfCache.get)

    val tagLevelConfOverlay = KyuubiEbayConf.getTagConfOnly(tagConf, sessionTag) ++
      clusterTagConf.map(KyuubiEbayConf.getTagConfOnly(_, sessionTag)).getOrElse(Map.empty)
    val serviceOverwriteConfOverlay =
      KyuubiEbayConf.getTagConfOnly(tagConf, KYUUBI_OVERWRITE_TAG) ++
        clusterTagConf.map(KyuubiEbayConf.getTagConfOnly(_, KYUUBI_OVERWRITE_TAG)).getOrElse(
          Map.empty)

    val tagConfOverlay = tagLevelConfOverlay ++ serviceOverwriteConfOverlay
    val tessConfOverlay =
      tessConfAdvisor.getConfOverlay(user, (sessionConf.asScala ++ tagConfOverlay).asJava)

    (validateSparkMaster(sessionConf) ++ tagConfOverlay ++ tessConfOverlay.asScala).asJava
  }
}

object TagBasedSessionConfAdvisor extends Logging {
  private val reloadInterval: Long = KyuubiConf().get(KyuubiConf.SESSION_CONF_FILE_RELOAD_INTERVAL)

  // for kyuubi service side conf
  val KYUUBI_DEFAULT_TAG = "kyuubi_default"
  val KYUUBI_OVERWRITE_TAG = "kyuubi_overwrite"

  val SPARK_MASTER_KEY = "spark.master"

  private[ebay] lazy val fileConfCache: LoadingCache[String, KyuubiConf] =
    CacheBuilder.newBuilder()
      .expireAfterWrite(
        reloadInterval,
        TimeUnit.MILLISECONDS)
      .build(new CacheLoader[String, KyuubiConf] {
        override def load(tagFile: String): KyuubiConf = {
          val conf = KyuubiConf(false)
          Utils.tryLogNonFatalError {
            val propsFile = Utils.getPropertiesFile(tagFile)
            propsFile match {
              case None =>
                error(s"File not found $tagFile")
              case Some(_) =>
                Utils.getPropertiesFromFile(propsFile).foreach { case (k, v) =>
                  conf.set(k, v)
                }
            }
          }
          conf
        }
      })
}
