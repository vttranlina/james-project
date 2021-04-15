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

package org.apache.james.jmap.json

import org.apache.james.jmap.core.SetError.SetErrorDescription
import org.apache.james.jmap.core._
import org.apache.james.jmap.json.Fixture.id
import org.apache.james.jmap.json.MDNSerializationTest.{ACCOUNT_ID, SERIALIZER}
import org.apache.james.jmap.mail._
import org.apache.james.mailbox.model.{MessageId, TestMessageId}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsResult, Json}

object MDNSerializationTest {
  private val FACTORY: MessageId.Factory = new TestMessageId.Factory

  private val SERIALIZER: MDNSerializer = new MDNSerializer(FACTORY)

  private val ACCOUNT_ID: AccountId = AccountId(id)

}

class MDNSerializationTest extends AnyWordSpec with Matchers {

  "Deserialize MDNSendRequest" should {
    "MDNSendRequest should success" in {
      val mdnSendRequestActual: JsResult[MDNSendRequest] = SERIALIZER.deserializeMDNSendRequest(
        """{
          |  "accountId": "aHR0cHM6Ly93d3cuYmFzZTY0ZW5jb2RlLm9yZy8",
          |  "identityId": "I64588216",
          |  "send": {
          |    "k1546": {
          |      "forEmailId": "1",
          |      "subject": "Read receipt for: World domination",
          |      "textBody": "This receipt",
          |      "reportingUA": "joes-pc.cs.example.com; Foomail 97.1",
          |      "finalRecipient": "rfc822; tungexplorer@linagora.com",
          |      "includeOriginalMessage": true,
          |      "disposition": {
          |        "actionMode": "manual-action",
          |        "sendingMode": "mdn-sent-manually",
          |        "type": "displayed"
          |      },
          |      "extensionFields": {
          |        "EXTENSION-EXAMPLE": "example.com"
          |      }
          |    }
          |  }
          |}""".stripMargin)

      assert(mdnSendRequestActual.isSuccess)
    }

    "MDNSendCreateRequest should success" in {
      val mdnSendRequestActual: JsResult[MDNSendCreateRequest] = SERIALIZER.deserializeMDNSendCreateRequest(
        """{
          |    "forEmailId": "1",
          |    "subject": "Read receipt for: World domination",
          |    "textBody": "This receipt",
          |    "reportingUA": "joes-pc.cs.example.com; Foomail 97.1",
          |    "finalRecipient": "rfc822; tungexplorer@linagora.com",
          |    "includeOriginalMessage": true,
          |    "disposition": {
          |        "actionMode": "manual-action",
          |        "sendingMode": "mdn-sent-manually",
          |        "type": "displayed"
          |    },
          |    "extensionFields": {
          |        "EXTENSION-EXAMPLE": "example.com"
          |    }
          |}""".stripMargin)

      assert(mdnSendRequestActual.isSuccess)
    }
  }


  "Serialize MDNSendResponse" should {
    "MDNSendResponse should success" in {
      val mdn: MDNSendCreateResponse = MDNSendCreateResponse(
        subject = Some(SubjectField("Read receipt for: World domination")),
        textBody = Some(TextBodyField("This receipt")),
        reportingUA = Some(ReportUAField("joes-pc.cs.example.com; Foomail 97.1")),
        finalRecipient = Some(FinalRecipientField("rfc822; tungexplorer@linagora.com")),
        originalRecipient = Some(OriginalRecipientField("rfc822; tungexplorer@linagora.com")),
        mdnGateway = Some(MDNGatewayField("mdn gateway 1")),
        error = None,
        includeOriginalMessage = Some(IncludeOriginalMessageField(false)),
        originalMessageId = Some(OriginalMessageIdField("<199509192301.23456@example.org>")))

      val idSent: MDNSendId = MDNSendId(Id.validate("k1546").right.get)
      val idNotSent: MDNSendId = MDNSendId(Id.validate("k01").right.get)

      val response: MDNSendResponse = MDNSendResponse(
        accountId = ACCOUNT_ID,
        sent = Some(Map(idSent -> mdn)),
        notSent = Some(Map(idNotSent -> SetError(SetError.mdnAlreadySentValue, SetErrorDescription("mdnAlreadySent description"), None)))
      )

      val actualValue = SERIALIZER.serializeMDNSendResponse(response)

      val expectedValue = Json.prettyPrint(Json.parse(
        """{
          |  "accountId" : "aHR0cHM6Ly93d3cuYmFzZTY0ZW5jb2RlLm9yZy8",
          |  "sent" : {
          |    "k1546" : {
          |      "subject" : "Read receipt for: World domination",
          |      "textBody" : "This receipt",
          |      "reportingUA" : "joes-pc.cs.example.com; Foomail 97.1",
          |      "mdnGateway" : "mdn gateway 1",
          |      "originalRecipient" : "rfc822; tungexplorer@linagora.com",
          |      "finalRecipient" : "rfc822; tungexplorer@linagora.com",
          |      "includeOriginalMessage" : false,
          |      "originalMessageId": "<199509192301.23456@example.org>"
          |    }
          |  },
          |  "notSent" : {
          |    "k01" : {
          |      "type" : "mdnAlreadySent",
          |      "description" : "mdnAlreadySent description"
          |    }
          |  }
          |}""".stripMargin))
      actualValue should equal(Json.parse(expectedValue))
    }
  }
}
