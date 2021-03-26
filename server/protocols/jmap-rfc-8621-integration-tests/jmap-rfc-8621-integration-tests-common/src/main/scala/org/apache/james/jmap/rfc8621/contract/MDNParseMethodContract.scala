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
import org.apache.james.mailbox.model.{MailboxId, MailboxPath, MessageId}
import org.apache.james.mime4j.dom.Message
import org.apache.james.modules.MailboxProbeImpl
import org.apache.james.utils.DataProbeImpl
import org.junit.jupiter.api.{BeforeEach, Test}

import java.nio.charset.StandardCharsets

object MDNParseMethodContract {
  private def createTestMessage: Message = Message.Builder
    .of
    .setSubject("test")
    .setSender(ANDRE.asString())
    .setFrom(ANDRE.asString())
    .setSubject("World domination \r\n" +
      " and this is also part of the header")
    .setBody("testmail", StandardCharsets.UTF_8)
    .build
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
  def mdnParseShouldSucceed(guiceJamesServer: GuiceJamesServer): Unit = {
    val path: MailboxPath = MailboxPath.inbox(BOB)
    val mailboxProbe: MailboxProbeImpl = guiceJamesServer.getProbe(classOf[MailboxProbeImpl])
    val mailboxId: MailboxId = mailboxProbe.createMailbox(path)

    val message: Message = createTestMessage
    val messageId: MessageId = mailboxProbe
      .appendMessage(BOB.asString(), path, AppendCommand.builder()
        .build(message))
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
         |      "blobIds": [ "$messageId" ]
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
         |         "accountId": "ue150411c",
         |         "parsed": {
         |           "$messageId": {
         |             "forEmailId": "Md45b47b4877521042cec0938",
         |             "subject": "Read receipt for: World domination",
         |             "textBody": "This receipt shows that the",
         |             "reportingUA": "joes-pc.cs.example.com; Foomail 97.1",
         |             "disposition": {
         |               "actionMode": "manual-action",
         |               "sendingMode": "mdn-sent-manually",
         |               "type": "displayed"
         |             },
         |             "finalRecipient": "rfc822; john@example.com",
         |             "originalMessageId": "<199509192301.23456@example.org>"
         |           }
         |         }
         |      }, "0" ]]
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
         |                "description": "The accountId not found",
         |                "properties":["accountId"]
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
    val blogIds = LazyList.continually(randomMessageId.serialize()).take(200).mkString(", ");
    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:mdn",
         |    "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [[
         |    "MDN/parse",
         |    {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "blobIds": [ "$blogIds" ]
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
           |    "MDN/parse",
           |    {
           |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
           |      "error": {
           |          "type": "requestTooLarge",
           |          "description": "The number of blobIds exceeds maximum size of 200",
           |          "properties":["blobIds"]
           |      }
           |    },
           |    "c1"]]
           |}""".stripMargin)
  }

  @Test
  def blobIdHasProblemShouldBeNotParsable(): Unit = {
    val blobId: String = "notParsable";
    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:mdn",
         |    "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [[
         |    "MDN/parse",
         |    {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "blobIds": [ "$blobId" ]
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
         |        "notParsable": ["$blobId"]
         |      },
         |      "c1"
         |        ]]
         |}""".stripMargin)
  }

  @Test
  def blobIdsIsNotExitedShouldBeNotFound(): Unit = {
    val blobId = randomMessageId.serialize()
    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:mdn",
         |    "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [[
         |    "MDN/parse",
         |    {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "blobIds": [ "$blobId" ]
         |    },
         |    "0"]]
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
         |        "notFound": ["$blobId"]
         |      },
         |      "0"
         |        ]]
         |}""".stripMargin)
  }

  @Test
  def validAndInvalidCanBeMixed(): Unit = {
    val blobIdNotFound = randomMessageId.serialize()
    val blobIdNotParsable = "todo"
    val blobIdValid = "todo"
    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:mdn",
         |    "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [[
         |    "MDN/parse",
         |    {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "blobIds": [ "$blobIdNotFound", "$blobIdNotParsable", "$blobIdValid" ]
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
         |        "notFound": ["$blobIdNotFound"],
         |        "notParsable": ["$blobIdNotParsable"],
         |        "parsed": ["$blobIdNotParsable"]
         |      },
         |      "0"
         |        ]]
         |}""".stripMargin)
  }


  @Test
  def mdnParseShouldReturnUnknownMethodWhenMissingOneCapability(): Unit = {
    val blobId: String = "todo"
    val request =
      s"""{
         |  "using": [
         |    "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [[
         |    "MDN/parse",
         |    {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "blobIds": [ "$blobId" ]
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
    val blobId: String = "todo"
    val request =
      s"""{
         |  "using": [],
         |  "methodCalls": [[
         |    "MDN/parse",
         |    {
         |      "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
         |      "blobIds": [ "$blobId" ]
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
