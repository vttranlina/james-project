package org.apache.james.jmap.mail

import eu.timepit.refined.api.Refined
import eu.timepit.refined.collection.NonEmpty
import org.apache.james.jmap.core.AccountId
import org.apache.james.jmap.mail.MDNParse._
import org.apache.james.jmap.method.WithAccountId
import org.apache.james.mdn.MDNReport
import org.apache.james.mdn.fields.{Disposition => JavaDisposition}

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
  def convertFromMDNReport(mdnReport: MDNReport): MDNParsed = {
    MDNParsed(
      forEmailId = Some("forEmailIdTodo"),
      subject = Some("subjectTodo"),
      textBody = Some("textBodyTodo"),
      reportingUA = Some(mdnReport.getReportingUserAgentField.get().getUserAgentName),
      finalRecipient = mdnReport.getFinalRecipientField.formattedValue(),
      originalMessageId = Some(mdnReport.getOriginalMessageIdField.get().formattedValue()),
      disposition = MDNDisposition.convertFromJava(mdnReport.getDispositionField)
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
  def empty(accountId: AccountId): MDNParseResults = MDNParseResults(Map(), MDNNotFound(Set()), MDNNotParsable(Set()))

  def merge(response1: MDNParseResults, response2: MDNParseResults): MDNParseResults =
    MDNParseResults(response1.parsed ++ response2.parsed,
      response1.notFound.merge(response2.notFound),
      response2.notParsable.merge(response2.notParsable))
}

case class MDNParseResults(parsed: Map[BlobId, MDNParsed],
                           notFound: MDNNotFound,
                           notParsable: MDNNotParsable)

case class MDNParseResponse(accountId: AccountId,
                            parsed: Map[BlobId, MDNParsed],
                            notFound: MDNNotFound,
                            notParsable: MDNNotParsable)
