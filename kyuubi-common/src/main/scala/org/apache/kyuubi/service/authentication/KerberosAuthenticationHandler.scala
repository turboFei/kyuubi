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

package org.apache.kyuubi.service.authentication

import java.io.{File, IOException}
import java.security.{PrivilegedActionException, PrivilegedExceptionAction}
import javax.security.auth.Subject
import javax.security.auth.kerberos.{KerberosPrincipal, KeyTab}
import javax.security.sasl.AuthenticationException
import javax.servlet.ServletException
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

import org.apache.commons.codec.binary.Base64
import org.apache.hadoop.security.authentication.server.HttpConstants
import org.apache.hadoop.security.authentication.util.KerberosName
import org.ietf.jgss.{GSSContext, GSSCredential, GSSManager, Oid}

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.service.authentication.AuthSchemes.{AuthScheme, NEGOTIATE}
import org.apache.kyuubi.service.authentication.KerberosUtil._

class KerberosAuthenticationHandler extends AuthenticationHandler with Logging {
  import KerberosAuthenticationHandler._

  private var gssManager: GSSManager = _
  private var conf: KyuubiConf = _
  private var serverSubject = new Subject()
  private var keytab: String = _
  private var principal: String = _

  override val authScheme: AuthScheme = AuthSchemes.NEGOTIATE

  override def authenticationSupported: Boolean = {
    !keytab.isEmpty && !principal.isEmpty
  }

  override def init(conf: KyuubiConf): Unit = {
    this.conf = conf
    keytab = conf.get(KyuubiConf.SERVER_SPNEGO_KEYTAB).getOrElse("")
    principal = conf.get(KyuubiConf.SERVER_SPNEGO_KEYTAB).getOrElse("")
    if (authenticationSupported) {
      val keytabFile = new File(keytab)
      if (!keytabFile.exists()) {
        throw new ServletException(s"Keytab[$keytab] does not exists")
      }
      if (!principal.startsWith("HTTP/")) {
        throw new ServletException(s"SPNEGO principal[$principal] does not start with HTTP/")
      }

      info(s"Using keytab $keytab, for principal $principal")
      serverSubject.getPrivateCredentials().add(KeyTab.getInstance(keytabFile))
      serverSubject.getPrincipals.add(new KerberosPrincipal(principal))

      // TODO: support to config kerberos.name.rules and kerberos.rule.mechanism
      // set default rules if no rules set, otherwise it will throw exception
      // when parse the kerberos name
      if (!KerberosName.hasRulesBeenSet) {
        KerberosName.setRules("DEFAULT")
      }

      try {
        gssManager = Subject.doAs(
          serverSubject,
          new PrivilegedExceptionAction[GSSManager] {
            override def run(): GSSManager = {
              GSSManager.getInstance()
            }
          })
      } catch {
        case e: PrivilegedActionException => throw e.getException
        case e: Exception => throw new ServletException(e)
      }
    }
  }

  override def destroy(): Unit = {
    keytab = null
    serverSubject = null
  }

  override def authenticate(
      request: HttpServletRequest,
      response: HttpServletResponse): AuthUser = {
    beforeAuth(request)
    var authUser: AuthUser = null
    val authorization = getAuthorization(request)
    val base64 = new Base64(0)
    val clientToken = base64.decode(authorization)
    try {
      val serverPrincipal = getTokenServerName(clientToken)
      if (!serverPrincipal.startsWith("HTTP/")) {
        throw new IllegalArgumentException(
          s"Invalid server principal $serverPrincipal decoded from client request")
      }
      authUser = Subject.doAs(
        serverSubject,
        new PrivilegedExceptionAction[AuthUser] {
          override def run(): AuthUser = {
            runWithPrincipal(serverPrincipal, clientToken, base64, response)
          }
        })
    } catch {
      case ex: PrivilegedActionException =>
        ex.getException match {
          case ioe: IOException =>
            throw ioe
          case e: Exception => throw new AuthenticationException("SPNEGO authentication failed", e)
        }

      case e: Exception => throw new AuthenticationException("SPNEGO authentication failed", e)
    }
    authUser
  }

  def runWithPrincipal(
      serverPrincipal: String,
      clientToken: Array[Byte],
      base64: Base64,
      response: HttpServletResponse): AuthUser = {
    var gssContext: GSSContext = null
    var gssCreds: GSSCredential = null
    var authUser: AuthUser = null
    try {
      debug(s"SPNEGO initialized with server principal $serverPrincipal")
      gssCreds = gssManager.createCredential(
        gssManager.createName(serverPrincipal, NT_GSS_KRB5_PRINCIPAL_OID),
        GSSCredential.INDEFINITE_LIFETIME,
        Array[Oid](GSS_SPNEGO_MECH_OID, GSS_KRB5_MECH_OID),
        GSSCredential.ACCEPT_ONLY)
      gssContext = gssManager.createContext(gssCreds)
      val serverToken = gssContext.acceptSecContext(clientToken, 0, clientToken.length)
      if (serverToken != null && serverToken.length > 0) {
        val authenticate = base64.encodeToString(serverToken)
        response.setHeader(WWW_AUTHENTICATE, s"$NEGOTIATE $authenticate")
      }
      if (!gssContext.isEstablished) {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)
        debug("SPNEGO in progress")
      } else {
        val clientPrincipal = gssContext.getSrcName.toString
        val kerberosName = new KerberosName(clientPrincipal)
        val userName = kerberosName.getShortName
        authUser = AuthUser(userName, clientPrincipal)
        response.setStatus(HttpServletResponse.SC_OK)
        debug(s"SPNEGO completed for client principal $clientPrincipal")
      }
    } finally {
      if (gssContext != null) {
        gssContext.dispose()
      }
      if (gssCreds != null) {
        gssCreds.dispose()
      }
    }
    authUser
  }
}

object KerberosAuthenticationHandler {

  /**
   * HTTP header used by the SPNEGO server endpoint during an authentication sequence.
   */
  val WWW_AUTHENTICATE: String = HttpConstants.WWW_AUTHENTICATE_HEADER
}
