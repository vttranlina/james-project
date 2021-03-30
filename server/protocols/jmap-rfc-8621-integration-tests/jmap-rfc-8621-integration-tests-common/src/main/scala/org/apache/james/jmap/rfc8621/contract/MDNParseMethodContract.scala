/** **************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 * *
 * http://www.apache.org/licenses/LICENSE-2.0                  *
 * *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 * ************************************************************** */

package org.apache.james.jmap.rfc8621.contract

import io.netty.handler.codec.http.HttpHeaderNames.ACCEPT
import io.restassured.RestAssured._
import io.restassured.http.ContentType.JSON
import net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson
import org.apache.http.HttpStatus.SC_OK
import org.apache.james.GuiceJamesServer
import org.apache.james.jmap.core.ResponseObject.SESSION_STATE
import org.apache.james.jmap.http.UserCredential
import org.apache.james.jmap.rfc8621.contract.Fixture._
import org.apache.james.mailbox.MessageManager.AppendCommand
import org.apache.james.mailbox.model.{MailboxPath, MessageId}
import org.apache.james.mime4j.dom.Message
import org.apache.james.mime4j.message.{BodyPartBuilder, MultipartBuilder, SingleBodyBuilder}
import org.apache.james.modules.MailboxProbeImpl
import org.apache.james.utils.DataProbeImpl
import org.junit.jupiter.api.{BeforeEach, Test}
import play.api.libs.json._

import java.nio.charset.StandardCharsets

object MDNParseMethodContract {

  private def mdnMessageTest: Message = {
    val mdnContent =
      """Reporting-UA: UA_name; UA_product
        |MDN-Gateway: smtp; apache.org
        |Original-Recipient: rfc822; originalRecipient
        |Final-Recipient: rfc822; final_recipient
        |Original-Message-ID: <original@message.id>
        |Disposition: automatic-action/MDN-sent-automatically;processed/error,failed
        |Error: Message1
        |Error: Message2
        |X-OPENPAAS-IP: 177.177.177.77
        |X-OPENPAAS-PORT: 8000
        |""".replace(System.lineSeparator(), "\r\n")
        .stripMargin
    val mdnBodyPart = BodyPartBuilder
      .create
      .setBody(SingleBodyBuilder.create
        .setText(mdnContent)
        .buildText)
      .setContentType("message/disposition-notification")
      .build

    val multipart = MultipartBuilder.create("report")
      .addTextPart("This is body of text part", StandardCharsets.UTF_8)
      .addBodyPart(mdnBodyPart)
      .build

    Message.Builder
      .of
      .setSubject("Subject MDN")
      .setSender(ANDRE.asString())
      .setFrom(ANDRE.asString())
      .setBody(multipart)
      .build
  }
}

trait MDNParseMethodContract {

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
  def mdnParseHasValidBodyFormatShouldSucceed(guiceJamesServer: GuiceJamesServer): Unit = {
    import MDNParseMethodContract._
    val path: MailboxPath = MailboxPath.inbox(BOB)
    val mailboxProbe: MailboxProbeImpl = guiceJamesServer.getProbe(classOf[MailboxProbeImpl])
    mailboxProbe.createMailbox(path)

    val messageId: MessageId = mailboxProbe
      .appendMessage(BOB.asString(), path, AppendCommand.builder()
        .build(mdnMessageTest))
      .getMessageId

    val request: String =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:mdn",
         |    "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [[
         |    "MDN/parse",
         |    {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "blobIds": [ "${messageId.serialize()}" ]
         |    },
         |    "c1"]]
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
           |      [ "MDN/parse", {
           |         "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |         "parsed": {
           |           "${messageId.serialize()}": {
           |             "forEmailId": "todo",
           |             "subject": "todo",
           |             "reportingUA": "UA_name; UA_product",
           |             "disposition": {
           |               "actionMode": "automatic-action",
           |               "sendingMode": "MDN-sent-automatically",
           |               "type": "processed"
           |             },
           |             "finalRecipient": "rfc822; final_recipient",
           |             "originalMessageId": "<original@message.id>"
           |           }
           |         }
           |      }, "c1" ]]
           |}""".stripMargin)
  }

  @Test
  def mdnParseShouldFailWhenWrongAccountId(): Unit = {
    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:mdn",
         |    "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [[
         |    "MDN/parse",
         |    {
         |      "accountId": "unknownAccountId",
         |      "blobIds": [ "0f9f65ab-dc7b-4146-850f-6e4881093965" ]
         |    },
         |    "c1"]]
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

    assertThatJson(response).isEqualTo(
      s"""{
         |    "sessionState": "${SESSION_STATE.value}",
         |    "methodResponses": [[
         |            "error",
         |            {
         |                "type": "invalidArguments",
         |                "description": "accountId cannot be found"
         |            },
         |            "c1"
         |        ]]
         |}""".stripMargin)
  }

  @Test
  def mdnParseShouldFailWhenBothForEmailIdIsNullAndMissingOriginalMessageId(): Unit = {
  }

  @Test
  def mdnParseShouldFailWhenNumberOfBlobIdsTooLarge(): Unit = {
    val blogIds = LazyList.continually(randomMessageId.serialize()).take(201).toArray;
    val blogIdsJson = Json.stringify(Json.arr(blogIds)).replace("[[", "[").replace("]]", "]");
    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:mdn",
         |    "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [[
         |    "MDN/parse",
         |    {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "blobIds":  ${blogIdsJson}
         |    },
         |    "c1"]]
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
      .whenIgnoringPaths("methodResponses[0][1].description")
      .isEqualTo(
        s"""{
           |  "sessionState": "${SESSION_STATE.value}",
           |  "methodResponses": [[
           |    "error",
           |    {
           |          "type": "requestTooLarge",
           |          "description": "The number of ids requested by the client exceeds the maximum number the server is willing to process in a single method call"
           |    },
           |    "c1"]]
           |}""".stripMargin)
  }

  @Test
  def blobIdIsNotOfMDNShouldBeNotParsable(guiceJamesServer: GuiceJamesServer): Unit = {
    val path: MailboxPath = MailboxPath.inbox(BOB)
    val mailboxProbe: MailboxProbeImpl = guiceJamesServer.getProbe(classOf[MailboxProbeImpl])
    mailboxProbe.createMailbox(path)

    val messageId: MessageId = mailboxProbe
      .appendMessage(BOB.asString(), path, AppendCommand.builder()
        .build(Message.Builder
          .of
          .setSubject("Subject MDN")
          .setSender(ANDRE.asString())
          .setFrom(ANDRE.asString())
          .setBody(MultipartBuilder.create("report")
            .addTextPart("This is body of text part", StandardCharsets.UTF_8)
            .build)
          .build))
      .getMessageId

    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:mdn",
         |    "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [[
         |    "MDN/parse",
         |    {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "blobIds": [ "${messageId.serialize()}" ]
         |    },
         |    "c1"]]
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

    assertThatJson(response).isEqualTo(
      s"""{
         |    "sessionState": "${SESSION_STATE.value}",
         |    "methodResponses": [[
         |      "MDN/parse",
         |      {
         |        "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |        "notParsable": ["${messageId.serialize()}"]
         |      },
         |      "c1"
         |        ]]
         |}""".stripMargin)
  }

  @Test
  def blobIdsIsNotExitedShouldBeNotFound(): Unit = {
    val blobIdShouldNotFound = randomMessageId.serialize()
    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:mdn",
         |    "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [[
         |    "MDN/parse",
         |    {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "blobIds": [ "$blobIdShouldNotFound" ]
         |    },
         |    "c1"]]
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

    assertThatJson(response).isEqualTo(
      s"""{
         |    "sessionState": "${SESSION_STATE.value}",
         |    "methodResponses": [[
         |      "MDN/parse",
         |      {
         |        "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |        "notFound": ["$blobIdShouldNotFound"]
         |      },
         |      "c1"
         |        ]]
         |}""".stripMargin)
  }

  @Test
  def parseAndNotFoundAndNotParsableCanBeMixed(guiceJamesServer: GuiceJamesServer): Unit = {
    import MDNParseMethodContract._
    val path: MailboxPath = MailboxPath.inbox(BOB)
    val mailboxProbe: MailboxProbeImpl = guiceJamesServer.getProbe(classOf[MailboxProbeImpl])
    mailboxProbe.createMailbox(path)

    val blobIdParsable: MessageId = mailboxProbe
      .appendMessage(BOB.asString(), path, AppendCommand.builder()
        .build(mdnMessageTest))
      .getMessageId
    val blobIdNotParsable: MessageId = mailboxProbe
      .appendMessage(BOB.asString(), path, AppendCommand.builder()
        .build(Message.Builder
          .of
          .setSubject("Subject MDN")
          .setSender(ANDRE.asString())
          .setFrom(ANDRE.asString())
          .setBody(MultipartBuilder.create("report")
            .addTextPart("This is body of text part", StandardCharsets.UTF_8)
            .build)
          .build))
      .getMessageId
    val blobIdNotFound = randomMessageId
    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:mdn",
         |    "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [[
         |    "MDN/parse",
         |    {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "blobIds": [ "${blobIdParsable.serialize()}", "${blobIdNotParsable.serialize()}", "${blobIdNotFound.serialize()}" ]
         |    },
         |    "c1"]]
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

    assertThatJson(response).isEqualTo(
      s"""{
         |    "sessionState": "${SESSION_STATE.value}",
         |    "methodResponses": [[
         |      "MDN/parse",
         |      {
         |        "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |        "notFound": ["${blobIdNotFound.serialize()}"],
         |        "notParsable": ["${blobIdNotParsable.serialize()}"],
         |         "parsed": {
         |           "${blobIdParsable.serialize()}": {
         |             "forEmailId": "todo",
         |             "subject": "todo",
         |             "reportingUA": "UA_name; UA_product",
         |             "disposition": {
         |               "actionMode": "automatic-action",
         |               "sendingMode": "MDN-sent-automatically",
         |               "type": "processed"
         |             },
         |             "finalRecipient": "rfc822; final_recipient",
         |             "originalMessageId": "<original@message.id>"
         |           }
         |      }
         |      },
         |      "c1"
         |        ]]
         |}""".stripMargin)
  }


  @Test
  def mdnParseShouldReturnUnknownMethodWhenMissingOneCapability(): Unit = {
    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [[
         |    "MDN/parse",
         |    {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "blobIds": [ "123" ]
         |    },
         |    "c1"]]
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
  def mdnParseShouldReturnUnknownMethodWhenMissingAllCapabilities(): Unit = {
    val request =
      s"""{
         |  "using": [],
         |  "methodCalls": [[
         |    "MDN/parse",
         |    {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "blobIds": [ "123" ]
         |    },
         |    "c1"]]
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
         |      "description": "Missing capability(ies): urn:ietf:params:jmap:mdn, urn:ietf:params:jmap:mail"
         |    },
         |    "c1"]]
         |}""".stripMargin)
  }
}
