/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package yaooqinn.kyuubi.session.security

import org.apache.hadoop.security.UserGroupInformation
import org.apache.spark.{KyuubiSparkUtil, SparkConf}

import yaooqinn.kyuubi.utils.KyuubiHadoopUtil

/**
 * An interface for secured service token collectors
 */
private[security] trait TokenCollector {

  /**
   * Obtain tokens from secured services, such as Hive Metastore Server. HDFS etc.
   * @param conf a SparkConf
   */
  def obtainTokens(conf: SparkConf): Unit

  /**
   * Check whether a service need tokens to visit
   * @param conf a SparkConf
   * @return true if the service to visit requires tokens
   */
  def tokensRequired(conf: SparkConf): Boolean = UserGroupInformation.isSecurityEnabled

}

private[kyuubi] object TokenCollector {

  /**
   * Obtain tokens from all secured services if required.
   * @param conf a SparkConf
   */
  def obtainTokenIfRequired(conf: SparkConf): Unit = {
    Seq(HiveTokenCollector, HDFSTokenCollector).foreach { co =>
      if (co.tokensRequired(conf)) co.obtainTokens(conf)
    }
  }

  /**
   * Obtain ugi for a user with username.
   */
  def userUGI(conf: SparkConf, username: String,
    withImpersonation: Boolean = true): UserGroupInformation = {
    val currentUser = UserGroupInformation.getLoginUser
    if (withImpersonation) {
      if (UserGroupInformation.isSecurityEnabled) {
        if (conf.contains(KyuubiSparkUtil.PRINCIPAL) && conf.contains(KyuubiSparkUtil.KEYTAB)) {
          // If principal and keytab are configured, do re-login in case of token expiry.
          // Do not check keytab file existing as spark-submit has it done
          currentUser.reloginFromKeytab()
        }
        val user = UserGroupInformation.createProxyUser(username, currentUser)
        KyuubiHadoopUtil.doAs(user)(TokenCollector.obtainTokenIfRequired(conf))
        user
      } else {
        UserGroupInformation.createRemoteUser(username)
      }
    } else {
      currentUser
    }
  }
}
