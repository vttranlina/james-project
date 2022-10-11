/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.jmap.rfc8621.contract

import io.netty.handler.codec.http.HttpHeaderNames.ACCEPT
import io.restassured.RestAssured.{`given`, requestSpecification}
import io.restassured.http.ContentType.JSON
import net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson
import org.apache.http.HttpStatus.SC_OK
import org.apache.james.GuiceJamesServer
import org.apache.james.core.quota.{QuotaCountLimit, QuotaSizeLimit}
import org.apache.james.jmap.core.ResponseObject.SESSION_STATE
import org.apache.james.jmap.core.UuidState.INSTANCE
import org.apache.james.jmap.http.UserCredential
import org.apache.james.jmap.mail.QuotaIdFactory
import org.apache.james.jmap.rfc8621.contract.Fixture.{ACCEPT_RFC8621_VERSION_HEADER, ANDRE, ANDRE_PASSWORD, BOB, BOB_PASSWORD, DOMAIN, authScheme, baseRequestSpecBuilder}
import org.apache.james.mailbox.MessageManager.AppendCommand
import org.apache.james.mailbox.model.MailboxPath
import org.apache.james.mime4j.dom.Message
import org.apache.james.modules.{MailboxProbeImpl, QuotaProbesImpl}
import org.apache.james.utils.DataProbeImpl
import org.junit.jupiter.api.{BeforeEach, Test}

import java.nio.charset.StandardCharsets


trait QuotaGetMethodContract {

  @BeforeEach
  def setUp(server: GuiceJamesServer): Unit = {
    server.getProbe(classOf[DataProbeImpl])
      .fluent
      .addDomain(DOMAIN.asString)
      .addUser(BOB.asString, BOB_PASSWORD)
      .addUser(ANDRE.asString(), ANDRE_PASSWORD)

    requestSpecification = baseRequestSpecBuilder(server)
      .setAuth(authScheme(UserCredential(BOB, BOB_PASSWORD)))
      .addHeader(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .build
  }

  @Test
  def listShouldEmptyWhenAccountDoesNotHaveQuotas(): Unit = {
    val response = `given`
      .body(
        s"""{
           |  "using": [
           |    "urn:ietf:params:jmap:core",
           |    "urn:ietf:params:jmap:quota"],
           |  "methodCalls": [[
           |    "Quota/get",
           |    {
           |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |      "ids": null
           |    },
           |    "c1"]]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response).isEqualTo(
      s"""{
         |  "sessionState": "${SESSION_STATE.value}",
         |  "methodResponses": [[
         |    "Quota/get",
         |    {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "state": "${INSTANCE.value}",
         |      "list": [],
         |      "notFound": []
         |    },
         |    "c1"]]
         |}""".stripMargin)
  }

  @Test
  def quotaGetShouldReturnListWhenQuotasIsProvided(server: GuiceJamesServer): Unit = {
    val quotaProbe = server.getProbe(classOf[QuotaProbesImpl])
    val bobQuotaRoot = quotaProbe.getQuotaRoot(MailboxPath.inbox(BOB))
    quotaProbe.setMaxMessageCount(bobQuotaRoot, QuotaCountLimit.count(100L))
    quotaProbe.setMaxStorage(bobQuotaRoot, QuotaSizeLimit.size(99L))

    val response = `given`
      .body(
        s"""{
           |  "using": [
           |    "urn:ietf:params:jmap:core",
           |    "urn:ietf:params:jmap:quota"],
           |  "methodCalls": [[
           |    "Quota/get",
           |    {
           |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |      "ids": null
           |    },
           |    "c1"]]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response).isEqualTo(
      s"""{
         |    "sessionState": "${SESSION_STATE.value}",
         |    "methodResponses": [
         |        [
         |            "Quota/get",
         |            {
         |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |                "notFound": [],
         |                "state": "${INSTANCE.value}",
         |                "list": [
         |                    {
         |                        "used": 0,
         |                        "name": "account:count:Mail",
         |                        "id": "fbb433deb379b7ba87e2df898887056a0fa645839ee091d13e4da99e52b4c4f6",
         |                        "dataTypes": [
         |                            "Mail"
         |                        ],
         |                        "limit": 100,
         |                        "resourceType": "count",
         |                        "scope": "account"
         |                    },
         |                    {
         |                        "used": 0,
         |                        "name": "account:octets:Storage",
         |                        "id": "fbb433deb379b7ba87e2df898887056a0fa645839ee091d13e4da99e52b4c4f6",
         |                        "dataTypes": [
         |                            "Storage"
         |                        ],
         |                        "limit": 99,
         |                        "resourceType": "octets",
         |                        "scope": "account"
         |                    }
         |                ]
         |            },
         |            "c1"
         |        ]
         |    ]
         |}
         |""".stripMargin)
  }

  @Test
  def quotaGetShouldReturnEmptyListWhenIdsAreEmpty(server: GuiceJamesServer): Unit = {
    val quotaProbe = server.getProbe(classOf[QuotaProbesImpl])
    val bobQuotaRoot = quotaProbe.getQuotaRoot(MailboxPath.inbox(BOB))
    quotaProbe.setMaxMessageCount(bobQuotaRoot, QuotaCountLimit.count(100L))

    val response = `given`
      .body(
        s"""{
           |  "using": [
           |    "urn:ietf:params:jmap:core",
           |    "urn:ietf:params:jmap:quota"],
           |  "methodCalls": [[
           |    "Quota/get",
           |    {
           |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |      "ids": []
           |    },
           |    "c1"]]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response).isEqualTo(
      s"""{
         |    "sessionState": "${SESSION_STATE.value}",
         |    "methodResponses": [
         |        [
         |            "Quota/get",
         |            {
         |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |                "notFound": [],
         |                "state": "${INSTANCE.value}",
         |                "list": []
         |            },
         |            "c1"
         |        ]
         |    ]
         |}
         |""".stripMargin)
  }

  @Test
  def quotaGetShouldReturnNotFoundWhenIdDoesNotExist(server: GuiceJamesServer): Unit = {
    val quotaProbe = server.getProbe(classOf[QuotaProbesImpl])
    val bobQuotaRoot = quotaProbe.getQuotaRoot(MailboxPath.inbox(BOB))
    quotaProbe.setMaxMessageCount(bobQuotaRoot, QuotaCountLimit.count(100L))

    val response = `given`
      .body(
        s"""{
           |  "using": [
           |    "urn:ietf:params:jmap:core",
           |    "urn:ietf:params:jmap:quota"],
           |  "methodCalls": [[
           |    "Quota/get",
           |    {
           |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |      "ids": ["notfound123"]
           |    },
           |    "c1"]]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response).isEqualTo(
      s"""{
         |    "sessionState": "${SESSION_STATE.value}",
         |    "methodResponses": [
         |        [
         |            "Quota/get",
         |            {
         |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |                "notFound": [ "notfound123" ],
         |                "state": "${INSTANCE.value}",
         |                "list": []
         |            },
         |            "c1"
         |        ]
         |    ]
         |}
         |""".stripMargin)
  }

  @Test
  def quotaGetShouldReturnNotFoundAndListWhenMixCases(server: GuiceJamesServer): Unit = {
    val quotaProbe = server.getProbe(classOf[QuotaProbesImpl])
    val bobQuotaRoot = quotaProbe.getQuotaRoot(MailboxPath.inbox(BOB))
    quotaProbe.setMaxMessageCount(bobQuotaRoot, QuotaCountLimit.count(100L))

    val quotaId = QuotaIdFactory.from(bobQuotaRoot)

    val response = `given`
      .body(
        s"""{
           |  "using": [
           |    "urn:ietf:params:jmap:core",
           |    "urn:ietf:params:jmap:quota"],
           |  "methodCalls": [[
           |    "Quota/get",
           |    {
           |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |      "ids": ["notfound123", "${quotaId.value}"]
           |    },
           |    "c1"]]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response).isEqualTo(
      s"""{
         |    "sessionState": "${SESSION_STATE.value}",
         |    "methodResponses": [
         |        [
         |            "Quota/get",
         |            {
         |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |                "notFound": [ "notfound123" ],
         |                "state": "${INSTANCE.value}",
         |                "list": [
         |                    {
         |                        "used": 0,
         |                        "name": "account:count:Mail",
         |                        "id": "fbb433deb379b7ba87e2df898887056a0fa645839ee091d13e4da99e52b4c4f6",
         |                        "dataTypes": [
         |                            "Mail"
         |                        ],
         |                        "limit": 100,
         |                        "resourceType": "count",
         |                        "scope": "account"
         |                    }
         |                ]
         |            },
         |            "c1"
         |        ]
         |    ]
         |}
         |""".stripMargin)
  }

  @Test
  def quotaGetShouldReturnRightUsageQuota(server: GuiceJamesServer): Unit = {
    val quotaProbe = server.getProbe(classOf[QuotaProbesImpl])
    val bobQuotaRoot = quotaProbe.getQuotaRoot(MailboxPath.inbox(BOB))
    quotaProbe.setMaxMessageCount(bobQuotaRoot, QuotaCountLimit.count(100L))
    quotaProbe.setMaxStorage(bobQuotaRoot, QuotaSizeLimit.size(900L))

    server.getProbe(classOf[MailboxProbeImpl]).createMailbox(MailboxPath.inbox(BOB))
    server.getProbe(classOf[MailboxProbeImpl])
      .appendMessage(BOB.asString(), MailboxPath.inbox(BOB), AppendCommand.from(Message.Builder
        .of
        .setSubject("test")
        .setBody("testmail", StandardCharsets.UTF_8)
        .build))
      .getMessageId.serialize()

    val response = `given`
      .body(
        s"""{
           |  "using": [
           |    "urn:ietf:params:jmap:core",
           |    "urn:ietf:params:jmap:quota"],
           |  "methodCalls": [[
           |    "Quota/get",
           |    {
           |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |      "ids": null
           |    },
           |    "c1"]]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response).isEqualTo(
      s"""{
         |    "sessionState": "${SESSION_STATE.value}",
         |    "methodResponses": [
         |        [
         |            "Quota/get",
         |            {
         |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |                "notFound": [ ],
         |                "state": "${INSTANCE.value}",
         |                "list": [
         |                    {
         |                        "used": 1,
         |                        "name": "account:count:Mail",
         |                        "id": "fbb433deb379b7ba87e2df898887056a0fa645839ee091d13e4da99e52b4c4f6",
         |                        "dataTypes": [
         |                            "Mail"
         |                        ],
         |                        "limit": 100,
         |                        "resourceType": "count",
         |                        "scope": "account"
         |                    },
         |                    {
         |                        "used": 85,
         |                        "name": "account:octets:Storage",
         |                        "id": "fbb433deb379b7ba87e2df898887056a0fa645839ee091d13e4da99e52b4c4f6",
         |                        "dataTypes": [
         |                            "Storage"
         |                        ],
         |                        "limit": 900,
         |                        "resourceType": "octets",
         |                        "scope": "account"
         |                    }
         |                ]
         |            },
         |            "c1"
         |        ]
         |    ]
         |}
         |""".stripMargin)
  }


  @Test
  def quotaGetShouldFailWhenWrongAccountId(): Unit = {
    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:core",
         |    "urn:ietf:params:jmap:quota"],
         |  "methodCalls": [[
         |    "Quota/get",
         |    {
         |      "accountId": "unknownAccountId",
         |      "ids": null
         |    },
         |    "c1"]]
         |}""".stripMargin

    val response = `given`
      .body(request)
    .when
      .post
    .`then`
      .log().ifValidationFails()
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response).isEqualTo(
      s"""{
         |  "sessionState": "${SESSION_STATE.value}",
         |  "methodResponses": [
         |    ["error", {
         |      "type": "accountNotFound"
         |    }, "c1"]
         |  ]
         |}""".stripMargin)
  }

  @Test
  def quotaGetShouldFailWhenOmittingOneCapability(): Unit = {
    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:core"],
         |  "methodCalls": [[
         |    "Quota/get",
         |    {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "ids": null
         |    },
         |    "c1"]]
         |}""".stripMargin

    val response = `given`
      .body(request)
    .when
      .post
    .`then`
      .log().ifValidationFails()
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response).isEqualTo(
      s"""{
         |  "sessionState": "${SESSION_STATE.value}",
         |  "methodResponses": [[
         |    "error",
         |    {
         |      "type": "unknownMethod",
         |      "description":"Missing capability(ies): urn:ietf:params:jmap:quota"
         |    },
         |    "c1"]]
         |}""".stripMargin)
  }

  @Test
  def quotaGetShouldFailWhenOmittingAllCapability(): Unit = {
    val request =
      s"""{
         |  "using": [],
         |  "methodCalls": [[
         |    "Quota/get",
         |    {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "ids": null
         |    },
         |    "c1"]]
         |}""".stripMargin

    val response = `given`
      .body(request)
    .when
      .post
    .`then`
      .log().ifValidationFails()
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response).isEqualTo(
      s"""{
         |  "sessionState": "${SESSION_STATE.value}",
         |  "methodResponses": [[
         |    "error",
         |    {
         |      "type": "unknownMethod",
         |      "description":"Missing capability(ies): urn:ietf:params:jmap:quota, urn:ietf:params:jmap:core"
         |    },
         |    "c1"]]
         |}""".stripMargin)
  }

  @Test
  def quotaGetShouldNotReturnQuotaDataOfOtherAccount(server: GuiceJamesServer): Unit = {
    val quotaProbe = server.getProbe(classOf[QuotaProbesImpl])
    val andreQuotaRoot = quotaProbe.getQuotaRoot(MailboxPath.inbox(ANDRE))
    quotaProbe.setMaxMessageCount(andreQuotaRoot, QuotaCountLimit.count(100L))

    val response = `given`
      .body(
        s"""{
           |  "using": [
           |    "urn:ietf:params:jmap:core",
           |    "urn:ietf:params:jmap:quota"],
           |  "methodCalls": [[
           |    "Quota/get",
           |    {
           |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |      "ids": null
           |    },
           |    "c1"]]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response).isEqualTo(
      s"""{
         |  "sessionState": "${SESSION_STATE.value}",
         |  "methodResponses": [[
         |    "Quota/get",
         |    {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "state": "${INSTANCE.value}",
         |      "list": [],
         |      "notFound": []
         |    },
         |    "c1"]]
         |}""".stripMargin)
  }

  @Test
  def quotaGetShouldReturnNotFoundWhenDoesNotPermission(server: GuiceJamesServer): Unit = {
    val quotaProbe = server.getProbe(classOf[QuotaProbesImpl])
    val andreQuotaRoot = quotaProbe.getQuotaRoot(MailboxPath.inbox(ANDRE))
    quotaProbe.setMaxMessageCount(andreQuotaRoot, QuotaCountLimit.count(100L))

    val quotaId = QuotaIdFactory.from(andreQuotaRoot)

    val response = `given`
      .body(
        s"""{
           |  "using": [
           |    "urn:ietf:params:jmap:core",
           |    "urn:ietf:params:jmap:quota"],
           |  "methodCalls": [[
           |    "Quota/get",
           |    {
           |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |      "ids": ["${quotaId.value}"]
           |    },
           |    "c1"]]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response).isEqualTo(
      s"""{
         |  "sessionState": "${SESSION_STATE.value}",
         |  "methodResponses": [[
         |    "Quota/get",
         |    {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "state": "${INSTANCE.value}",
         |      "list": [],
         |      "notFound": [ "${quotaId}" ]
         |    },
         |    "c1"]]
         |}""".stripMargin)
  }

  @Test
  def quotaGetShouldReturnIdWhenNoPropertiesRequested(server: GuiceJamesServer): Unit = {
    val quotaProbe = server.getProbe(classOf[QuotaProbesImpl])
    val quotaRoot = quotaProbe.getQuotaRoot(MailboxPath.inbox(BOB))
    quotaProbe.setMaxMessageCount(quotaRoot, QuotaCountLimit.count(100L))

    val response = `given`
      .body(
        s"""{
           |  "using": [
           |    "urn:ietf:params:jmap:core",
           |    "urn:ietf:params:jmap:quota"],
           |  "methodCalls": [[
           |    "Quota/get",
           |    {
           |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |      "ids": null,
           |      "properties": []
           |    },
           |    "c1"]]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response).isEqualTo(
      s"""{
         |  "sessionState": "${SESSION_STATE.value}",
         |  "methodResponses": [[
         |    "Quota/get",
         |    {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "state": "${INSTANCE.value}",
         |      "list": [
         |        {
         |          "id": "fbb433deb379b7ba87e2df898887056a0fa645839ee091d13e4da99e52b4c4f6"
         |        }
         |      ],
         |      "notFound": []
         |    },
         |    "c1"]]
         |}""".stripMargin)
  }

  @Test
  def quotaGetShouldReturnOnlyRequestedProperties(server: GuiceJamesServer): Unit = {
    val quotaProbe = server.getProbe(classOf[QuotaProbesImpl])
    val quotaRoot = quotaProbe.getQuotaRoot(MailboxPath.inbox(BOB))
    quotaProbe.setMaxMessageCount(quotaRoot, QuotaCountLimit.count(100L))

    val response = `given`
      .body(
        s"""{
           |  "using": [
           |    "urn:ietf:params:jmap:core",
           |    "urn:ietf:params:jmap:quota"],
           |  "methodCalls": [[
           |    "Quota/get",
           |    {
           |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |      "ids": null,
           |      "properties": ["name","used","limit"]
           |    },
           |    "c1"]]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response).isEqualTo(
      s"""{
         |  "sessionState": "${SESSION_STATE.value}",
         |  "methodResponses": [[
         |    "Quota/get",
         |    {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "state": "${INSTANCE.value}",
         |      "list": [
         |        {
         |          "id": "fbb433deb379b7ba87e2df898887056a0fa645839ee091d13e4da99e52b4c4f6",
         |          "used": 0,
         |          "name": "account:count:Mail",
         |          "limit": 100
         |        }
         |      ],
         |      "notFound": []
         |    },
         |    "c1"]]
         |}""".stripMargin)
  }

  @Test
  def quotaGetShouldFailWhenInvalidProperties(server: GuiceJamesServer): Unit = {
    val quotaProbe = server.getProbe(classOf[QuotaProbesImpl])
    val quotaRoot = quotaProbe.getQuotaRoot(MailboxPath.inbox(BOB))
    quotaProbe.setMaxMessageCount(quotaRoot, QuotaCountLimit.count(100L))

    val response = `given`
      .body(
        s"""{
           |  "using": [
           |    "urn:ietf:params:jmap:core",
           |    "urn:ietf:params:jmap:quota"],
           |  "methodCalls": [[
           |    "Quota/get",
           |    {
           |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |      "ids": null,
           |      "properties": ["invalid"]
           |    },
           |    "c1"]]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response).isEqualTo(
      s"""{
         |    "sessionState": "${SESSION_STATE.value}",
         |    "methodResponses": [
         |        [
         |            "error",
         |            {
         |                "type": "invalidArguments",
         |                "description": "The following properties [invalid] do not exist."
         |            },
         |            "c1"
         |        ]
         |    ]
         |}""".stripMargin)
  }

  @Test
  def quotaGetShouldFailWhenInvalidIds(server: GuiceJamesServer): Unit = {
    val quotaProbe = server.getProbe(classOf[QuotaProbesImpl])
    val quotaRoot = quotaProbe.getQuotaRoot(MailboxPath.inbox(BOB))
    quotaProbe.setMaxMessageCount(quotaRoot, QuotaCountLimit.count(100L))

    val response = `given`
      .body(
        s"""{
           |  "using": [
           |    "urn:ietf:params:jmap:core",
           |    "urn:ietf:params:jmap:quota"],
           |  "methodCalls": [[
           |    "Quota/get",
           |    {
           |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |      "ids": ["#==id"]
           |    },
           |    "c1"]]
           |}""".stripMargin)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response).isEqualTo(
      s"""{
         |    "sessionState": "${SESSION_STATE.value}",
         |    "methodResponses": [
         |        [
         |            "error",
         |            {
         |                "type": "invalidArguments",
         |                "description": "$${json-unit.any-string}"
         |            },
         |            "c1"
         |        ]
         |    ]
         |}""".stripMargin)
  }

}
