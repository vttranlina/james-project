/** **************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 * *
 * http://www.apache.org/licenses/LICENSE-2.0                 *
 * *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 * ************************************************************** */

package org.apache.james.jmap.mail

import eu.timepit.refined.api.Refined
import eu.timepit.refined.collection.NonEmpty
import org.apache.james.jmap.core.AccountId
import org.apache.james.jmap.mail.MDNParse._
import org.apache.james.jmap.method.WithAccountId
import org.apache.james.mdn.MDN
import org.apache.james.mdn.fields.{Disposition => JavaDisposition}

import scala.jdk.OptionConverters._
import scala.util.Try

object MDNParse {
  type UnparsedBlobIdConstraint = NonEmpty
  type UnparsedBlobId = String Refined UnparsedBlobIdConstraint
}

object BlobIds {
  def parse(blobIds: Seq[UnparsedBlobId]): Seq[Try[BlobId]] = {
    blobIds.map(unparsed => BlobId.of(unparsed.value))
  }
}

case class BlobIds(value: Seq[UnparsedBlobId])

case class MDNParseRequest(accountId: AccountId,
                           blobIds: BlobIds) extends WithAccountId {

  def validate: Either[IllegalArgumentException, MDNParseRequest] = {
    if (blobIds.value.length > 2) {
      Left(new IllegalArgumentException(s"The number of ids requested by the client exceeds the maximum number the server is willing to process in a single method call"))
    } else {
      scala.Right(this)
    }
  }
}

object MDNNotFound {
  def empty(): MDNNotFound = MDNNotFound(Set())
}

case class MDNNotFound(value: Set[UnparsedBlobId]) {
  def merge(other: MDNNotFound): MDNNotFound = MDNNotFound(this.value ++ other.value)
}

object MDNNotParsable {
  def empty(): MDNNotParsable = MDNNotParsable(Set())

  def merge(p1: MDNNotParsable, p2: MDNNotParsable): MDNNotParsable = MDNNotParsable(p1.value ++ p2.value)
}

case class MDNNotParsable(value: Set[UnparsedBlobId]) {
  def merge(other: MDNNotParsable): MDNNotParsable = MDNNotParsable(this.value ++ other.value)
}

case class MDNParseFailure(value: UnparsedBlobId)

object MDNDisposition {
  def convertFromJava(javaDisposition: JavaDisposition): MDNDisposition =
    MDNDisposition(actionMode = javaDisposition.getActionMode.getValue,
      sendingMode = javaDisposition.getSendingMode.getValue,
      `type` = javaDisposition.getType.getValue)
}

case class MDNDisposition(actionMode: String,
                          sendingMode: String,
                          `type`: String)

object MDNParsed {
  def convertFromMDN(mdn: MDN): MDNParsed = {
    MDNParsed(
      forEmailId = Some("todo"),
      subject = Some("todo"),
      textBody = None,
      reportingUA = mdn.getReport.getReportingUserAgentField
        .map(e => s"${e.getUserAgentName}; ${e.getUserAgentProduct.orElse("")}")
        .toScala,
      finalRecipient = s"${mdn.getReport.getFinalRecipientField.getAddressType.getType}; ${mdn.getReport.getFinalRecipientField.getFinalRecipient.formatted()}",
      originalMessageId = mdn.getReport.getOriginalMessageIdField
        .map(e => s"${e.getOriginalMessageId}")
        .toScala,
      disposition = MDNDisposition.convertFromJava(mdn.getReport.getDispositionField)
    )
  }
}

case class MDNParsed(forEmailId: Option[String],
                     subject: Option[String],
                     textBody: Option[String],
                     reportingUA: Option[String],
                     finalRecipient: String,
                     originalMessageId: Option[String],
                     disposition: MDNDisposition)

object MDNParseResults {
  def notFound(blobId: UnparsedBlobId): MDNParseResults = MDNParseResults(None, Some(MDNNotFound(Set(blobId))), None)

  def notFound(blobId: BlobId): MDNParseResults = MDNParseResults(None, Some(MDNNotFound(Set(blobId.value))), None)

  def notParse(blobId: UnparsedBlobId): MDNParseResults = MDNParseResults(None, None, Some(MDNNotParsable(Set(blobId))))

  def notParse(blobId: BlobId): MDNParseResults = MDNParseResults(None, None, Some(MDNNotParsable(Set(blobId.value))))

  def parse(blobId: BlobId, mdnParsed: MDNParsed): MDNParseResults = MDNParseResults(Some(Map(blobId -> mdnParsed)), None, None)

  def empty(): MDNParseResults = MDNParseResults(None, None, None)

  def merge(response1: MDNParseResults, response2: MDNParseResults): MDNParseResults = {
    MDNParseResults(
      parsed = (response1.parsed ++ response2.parsed).reduceOption((p1, p2) => p1 ++ p2),
      notFound = (response1.notFound ++ response2.notFound).reduceOption((p1, p2) => p1.merge(p2)),
      notParsable = (response1.notParsable ++ response2.notParsable).reduceOption((p1, p2) => p1.merge(p2)))
  }
}

case class MDNParseResults(parsed: Option[Map[BlobId, MDNParsed]],
                           notFound: Option[MDNNotFound],
                           notParsable: Option[MDNNotParsable]) {
  def asResponse(accountId: AccountId): MDNParseResponse = MDNParseResponse(
    accountId,
    parsed,
    notFound,
    notParsable
  )
}

case class MDNParseResponse(accountId: AccountId,
                            parsed: Option[Map[BlobId, MDNParsed]],
                            notFound: Option[MDNNotFound],
                            notParsable: Option[MDNNotParsable])