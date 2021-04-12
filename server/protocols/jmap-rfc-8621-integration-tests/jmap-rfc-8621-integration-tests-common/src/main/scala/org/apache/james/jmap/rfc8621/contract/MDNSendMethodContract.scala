/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *  http://www.apache.org/licenses/LICENSE-2.0                  *
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
import io.restassured.RestAssured._
import io.restassured.http.ContentType.JSON
import net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson
import org.apache.http.HttpStatus.SC_OK
import org.apache.james.GuiceJamesServer
import org.apache.james.jmap.core.ResponseObject.SESSION_STATE
import org.apache.james.jmap.http.UserCredential
import org.apache.james.jmap.rfc8621.contract.Fixture.{BOB, BOB_PASSWORD, DOMAIN, authScheme, baseRequestSpecBuilder, _}
import org.apache.james.mailbox.MessageManager.AppendCommand
import org.apache.james.mailbox.model.{MailboxPath, MessageId}
import org.apache.james.mime4j.dom.Message
import org.apache.james.modules.MailboxProbeImpl
import org.apache.james.utils.DataProbeImpl
import org.junit.jupiter.api.{BeforeEach, Test}

import java.nio.charset.StandardCharsets

trait MDNSendMethodContract {

  @BeforeEach
  def setUp(server: GuiceJamesServer): Unit = {
    server.getProbe(classOf[DataProbeImpl])
      .fluent()
      .addDomain(DOMAIN.asString())
      .addUser(BOB.asString(), BOB_PASSWORD)

    requestSpecification = baseRequestSpecBuilder(server)
      .setAuth(authScheme(UserCredential(BOB, BOB_PASSWORD)))
      .build()
  }

  def randomMessageId: MessageId

  @Test
  def sendShouldBeSuccessWhenRequestIsValid(guiceJamesServer: GuiceJamesServer): Unit = {
    val path: MailboxPath = MailboxPath.inbox(BOB)
    val mailboxProbe: MailboxProbeImpl = guiceJamesServer.getProbe(classOf[MailboxProbeImpl])
    mailboxProbe.createMailbox(path)

    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setSender(BOB.asString)
      .setFrom(BOB.asString)
      .setTo(ANDRE.asString)
      .setBody("testmail", StandardCharsets.UTF_8)
      .build
    val emailIdRelated: MessageId = mailboxProbe
      .appendMessage(BOB.asString(), path, AppendCommand.builder()
        .build(message))
      .getMessageId

    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:core",
         |    "urn:ietf:params:jmap:mail",
         |    "urn:ietf:params:jmap:mdn"
         |  ],
         |  "methodCalls": [
         |    [
         |      "MDN/send",
         |      {
         |        "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |        "identityId": "I64588216",
         |        "send": {
         |          "k1546": {
         |            "forEmailId": "${emailIdRelated.serialize()}",
         |            "subject": "Read receipt for: World domination",
         |            "textBody": "This receipt shows that the email has been displayed on your recipient's computer. ",
         |            "reportingUA": "joes-pc.cs.example.com; Foomail 97.1",
         |            "disposition": {
         |              "actionMode": "manual-action",
         |              "sendingMode": "mdn-sent-manually",
         |              "type": "displayed"
         |            },
         |            "extensionFields": {
         |              "X-EXTENSION-EXAMPLE": "example.com"
         |            }
         |          }
         |        },
         |        "onSuccessUpdateEmail": {
         |          "#k1546": {
         |            "keywords/$$mdnsent": true
         |          }
         |        }
         |      },
         |      "c1"
         |    ]
         |  ]
         |}""".stripMargin

    val response = `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response)
      .whenIgnoringPaths("methodResponses[1][1].newState",
        "methodResponses[1][1].oldState")
      .isEqualTo(s"""{
                    |    "sessionState": "${SESSION_STATE.value}",
                    |    "methodResponses": [
                    |        [
                    |            "MDN/send",
                    |            {
                    |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
                    |                "sent": {
                    |                    "k1546": {
                    |                        "finalRecipient": "rfc822; bob@domain.tld",
                    |                        "includeOriginalMessage": false
                    |                    }
                    |                }
                    |            },
                    |            "c1"
                    |        ],
                    |        [
                    |            "Email/set",
                    |            {
                    |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
                    |                "oldState": "23",
                    |                "newState": "42",
                    |                "updated": {
                    |                    "${emailIdRelated.serialize()}": null
                    |                }
                    |            },
                    |            "c1"
                    |        ]
                    |    ]
                    |}""".stripMargin)
  }

  @Test
  def sendShouldBeErrorWhenMDNHasAlreadyBeenSet(guiceJamesServer: GuiceJamesServer): Unit = {
    val path: MailboxPath = MailboxPath.inbox(BOB)
    val mailboxProbe: MailboxProbeImpl = guiceJamesServer.getProbe(classOf[MailboxProbeImpl])
    mailboxProbe.createMailbox(path)

    val message: Message = Message.Builder
      .of
      .setSubject("Test subject")
      .setSender(BOB.asString)
      .setFrom(BOB.asString)
      .setTo(ANDRE.asString)
      .setBody("Original email, that mdn related", StandardCharsets.UTF_8)
      .build
    val emailIdRelated: MessageId = mailboxProbe
      .appendMessage(BOB.asString(), path, AppendCommand.builder()
        .build(message))
      .getMessageId

    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:core",
         |    "urn:ietf:params:jmap:mail",
         |    "urn:ietf:params:jmap:mdn"
         |  ],
         |  "methodCalls": [
         |    [
         |      "MDN/send",
         |      {
         |        "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |        "identityId": "I64588216",
         |        "send": {
         |          "k1546": {
         |            "forEmailId": "${emailIdRelated.serialize()}",
         |            "subject": "Read receipt for: World domination",
         |            "textBody": "This receipt shows that the email has been displayed on your recipient's computer. ",
         |            "reportingUA": "joes-pc.cs.example.com; Foomail 97.1",
         |            "disposition": {
         |              "actionMode": "manual-action",
         |              "sendingMode": "mdn-sent-manually",
         |              "type": "displayed"
         |            }
         |          }
         |        },
         |        "onSuccessUpdateEmail": {
         |          "#k1546": {
         |            "keywords/$$mdnsent": true
         |          }
         |        }
         |      },
         |      "c1"
         |    ]
         |  ]
         |}""".stripMargin

    `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)

    val response = `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response)
      .inPath(s"methodResponses[0][1].notSent")
      .isEqualTo("""{
                   |    "k1546": {
                   |        "type": "mdnAlreadySent",
                   |        "description": "The message has the $mdnsent keyword already set."
                   |    }
                   |}""".stripMargin)

  }

  @Test
  def mdnSendShouldReturnUnknownMethodWhenMissingOneCapability(): Unit = {
    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:core",
         |    "urn:ietf:params:jmap:mail"
         |  ],
         |  "methodCalls": [
         |    [
         |      "MDN/send",
         |      {
         |        "accountId": "ue150411c",
         |        "identityId": "I64588216",
         |        "send": {
         |          "k1546": {
         |            "forEmailId": "Md45b47b4877521042cec0938",
         |            "subject": "Read receipt for: World domination",
         |            "textBody": "This receipt shows that the email has been displayed on your recipient's computer. ",
         |            "reportingUA": "joes-pc.cs.example.com; Foomail 97.1",
         |            "disposition": {
         |              "actionMode": "manual-action",
         |              "sendingMode": "mdn-sent-manually",
         |              "type": "displayed"
         |            },
         |            "extension": {
         |              "X-EXTENSION-EXAMPLE": "example.com"
         |            }
         |          }
         |        },
         |        "onSuccessUpdateEmail": {
         |          "#k1546": {
         |            "keywords/mdnsent": true
         |          }
         |        }
         |      },
         |      "c1"
         |    ]
         |  ]
         |}""".stripMargin

    val response = `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
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
         |    "error",
         |    {
         |      "type": "unknownMethod",
         |      "description": "Missing capability(ies): urn:ietf:params:jmap:mdn"
         |    },
         |    "c1"]]
         |}""".stripMargin)
  }

  @Test
  def mdnSendShouldReturnUnknownMethodWhenMissingAllCapabilities(): Unit = {
    val request =
      s"""{
         |  "using": [],
         |  "methodCalls": [
         |    [
         |      "MDN/send",
         |      {
         |        "accountId": "ue150411c",
         |        "identityId": "I64588216",
         |        "send": {
         |          "k1546": {
         |            "forEmailId": "Md45b47b4877521042cec0938",
         |            "subject": "Read receipt for: World domination",
         |            "textBody": "This receipt shows that the email has been displayed on your recipient's computer. ",
         |            "reportingUA": "joes-pc.cs.example.com; Foomail 97.1",
         |            "disposition": {
         |              "actionMode": "manual-action",
         |              "sendingMode": "mdn-sent-manually",
         |              "type": "displayed"
         |            },
         |            "extension": {
         |              "X-EXTENSION-EXAMPLE": "example.com"
         |            }
         |          }
         |        },
         |        "onSuccessUpdateEmail": {
         |          "#k1546": {
         |            "keywords/mdnsent": true
         |          }
         |        }
         |      },
         |      "c1"
         |    ]
         |  ]
         |}""".stripMargin

    val response = `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
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
         |    "error",
         |    {
         |      "type": "unknownMethod",
         |      "description": "Missing capability(ies): urn:ietf:params:jmap:mdn, urn:ietf:params:jmap:mail, urn:ietf:params:jmap:core"
         |    },
         |    "c1"]]
         |}""".stripMargin)
  }

  @Test
  def sendShouldGetNotFoundWhenForEmailIdIsNotExist(guiceJamesServer: GuiceJamesServer): Unit = {
    val path: MailboxPath = MailboxPath.inbox(BOB)
    val mailboxProbe: MailboxProbeImpl = guiceJamesServer.getProbe(classOf[MailboxProbeImpl])
    mailboxProbe.createMailbox(path)

    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:core",
         |    "urn:ietf:params:jmap:mail",
         |    "urn:ietf:params:jmap:mdn"
         |  ],
         |  "methodCalls": [
         |    [
         |      "MDN/send",
         |      {
         |        "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |        "identityId": "I64588216",
         |        "send": {
         |          "k1546": {
         |            "forEmailId": "${randomMessageId.serialize()}",
         |            "subject": "Read receipt for: World domination",
         |            "textBody": "This receipt shows that the email has been displayed on your recipient's computer. ",
         |            "reportingUA": "joes-pc.cs.example.com; Foomail 97.1",
         |            "disposition": {
         |              "actionMode": "manual-action",
         |              "sendingMode": "mdn-sent-manually",
         |              "type": "displayed"
         |            },
         |            "extensionFields": {
         |              "X-EXTENSION-EXAMPLE": "example.com"
         |            }
         |          }
         |        },
         |        "onSuccessUpdateEmail": {
         |          "#k1546": {
         |            "keywords/mdnsent": true
         |          }
         |        }
         |      },
         |      "c1"
         |    ]
         |  ]
         |}""".stripMargin

    val response = `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when
      .post.prettyPeek()
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response)
      .inPath("methodResponses[0]")
      .isEqualTo(s"""[
                    |    "MDN/send",
                    |    {
                    |        "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
                    |        "notSent": {
                    |            "k1546": {
                    |                "type": "notFound",
                    |                "description": "The reference \\"forEmailId\\" cannot be found."
                    |            }
                    |        }
                    |    },
                    |    "c1"
                    |]""".stripMargin)
  }

  @Test
  def setShouldFailWhenOnSuccessUpdateEmailMissesTheCreationIdSharp(guiceJamesServer: GuiceJamesServer): Unit = {
    val path: MailboxPath = MailboxPath.inbox(BOB)
    val mailboxProbe: MailboxProbeImpl = guiceJamesServer.getProbe(classOf[MailboxProbeImpl])
    mailboxProbe.createMailbox(path)

    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setSender(BOB.asString)
      .setFrom(BOB.asString)
      .setTo(ANDRE.asString)
      .setBody("testmail", StandardCharsets.UTF_8)
      .build
    val emailIdRelated: MessageId = mailboxProbe
      .appendMessage(BOB.asString(), path, AppendCommand.builder()
        .build(message))
      .getMessageId

    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:core",
         |    "urn:ietf:params:jmap:mail",
         |    "urn:ietf:params:jmap:mdn"
         |  ],
         |  "methodCalls": [
         |    [
         |      "MDN/send",
         |      {
         |        "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |        "identityId": "I64588216",
         |        "send": {
         |          "k1546": {
         |            "forEmailId": "${emailIdRelated.serialize()}",
         |            "subject": "Read receipt for: World domination",
         |            "textBody": "This receipt shows that the email has been displayed on your recipient's computer. ",
         |            "reportingUA": "joes-pc.cs.example.com; Foomail 97.1",
         |            "disposition": {
         |              "actionMode": "manual-action",
         |              "sendingMode": "mdn-sent-manually",
         |              "type": "displayed"
         |            }
         |          }
         |        },
         |        "onSuccessUpdateEmail": {
         |          "notStored": {
         |            "keywords/$$mdnsent": true
         |          }
         |        }
         |      },
         |      "c1"
         |    ]
         |  ]
         |}""".stripMargin

    val response = `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response)
      .inPath("methodResponses[0]")
      .isEqualTo(
        s"""[
           |    "error",
           |    {
           |        "type": "invalidArguments",
           |        "description": "notStored cannot be retrieved as storage for MDNSend is not yet implemented"
           |    },
           |    "c1"
           |]""".stripMargin)
  }

  @Test
  def setShouldFailWhenOnSuccessDestroyEmailDoesNotReferenceACreationWithinThisCall(guiceJamesServer: GuiceJamesServer): Unit = {
    val path: MailboxPath = MailboxPath.inbox(BOB)
    val mailboxProbe: MailboxProbeImpl = guiceJamesServer.getProbe(classOf[MailboxProbeImpl])
    mailboxProbe.createMailbox(path)

    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setSender(BOB.asString)
      .setFrom(BOB.asString)
      .setTo(ANDRE.asString)
      .setBody("testmail", StandardCharsets.UTF_8)
      .build
    val emailIdRelated: MessageId = mailboxProbe
      .appendMessage(BOB.asString(), path, AppendCommand.builder()
        .build(message))
      .getMessageId

    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:core",
         |    "urn:ietf:params:jmap:mail",
         |    "urn:ietf:params:jmap:mdn"
         |  ],
         |  "methodCalls": [
         |    [
         |      "MDN/send",
         |      {
         |        "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |        "identityId": "I64588216",
         |        "send": {
         |          "k1546": {
         |            "forEmailId": "${emailIdRelated.serialize()}",
         |            "subject": "Read receipt for: World domination",
         |            "textBody": "This receipt shows that the email has been displayed on your recipient's computer. ",
         |            "reportingUA": "joes-pc.cs.example.com; Foomail 97.1",
         |            "disposition": {
         |              "actionMode": "manual-action",
         |              "sendingMode": "mdn-sent-manually",
         |              "type": "displayed"
         |            }
         |          }
         |        },
         |        "onSuccessUpdateEmail": {
         |          "#notReference": {
         |            "keywords/$$mdnsent": true
         |          }
         |        }
         |      },
         |      "c1"
         |    ]
         |  ]
         |}""".stripMargin

    val response = `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(request)
    .when
      .post.prettyPeek()
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response)
      .isEqualTo(
        s"""{
           |    "sessionState": "${SESSION_STATE.value}",
           |    "methodResponses": [
           |        [
           |            "error",
           |            {
           |                "type": "invalidArguments",
           |                "description": "#notReference cannot be referenced in current method call"
           |            },
           |            "c1"
           |        ]
           |    ]
           |}""".stripMargin)
  }
}
