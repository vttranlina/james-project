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

package org.apache.james.jmap.method

import eu.timepit.refined.auto._
import org.apache.james.jmap.core.CapabilityIdentifier.{CapabilityIdentifier, JMAP_MAIL, JMAP_MDN}
import org.apache.james.jmap.core.Invocation._
import org.apache.james.jmap.core.{AccountId, ErrorCode, Invocation}
import org.apache.james.jmap.json.{MDNParseSerializer, ResponseSerializer}
import org.apache.james.jmap.mail.MDNParse.UnparsedBlobId
import org.apache.james.jmap.mail.{BlobId, MDNNotFound, MDNNotParsable, MDNParseRequest, MDNParseResponse, MDNParsed}
import org.apache.james.jmap.routes.{BlobNotFoundException, BlobResolvers, SessionSupplier}
import org.apache.james.mailbox.MailboxSession
import org.apache.james.mdn.MDNReportParser
import org.apache.james.metrics.api.MetricFactory
import play.api.libs.json.{JsError, JsObject, JsSuccess}
import reactor.core.scala.publisher.{SFlux, SMono}

import java.io.InputStream
import java.nio.charset.StandardCharsets
import javax.inject.Inject

object MDNParseResults {
  def notFound(blobId: UnparsedBlobId): MDNParseResults = MDNParseResults(Map(), MDNNotFound(Set(blobId)), MDNNotParsable.empty())

  def notFound(blobId: BlobId): MDNParseResults = MDNParseResults(Map(), MDNNotFound(Set(blobId.value)), MDNNotParsable.empty())

  def notParse(blobId: UnparsedBlobId): MDNParseResults = MDNParseResults(Map(), MDNNotFound.empty(), MDNNotParsable(Set(blobId)))

  def notParse(blobId: BlobId): MDNParseResults = MDNParseResults(Map(), MDNNotFound.empty(), MDNNotParsable(Set(blobId.value)))

  def parse(blobId: BlobId, mdnParsed: MDNParsed): MDNParseResults = MDNParseResults(Map(blobId -> mdnParsed), MDNNotFound.empty(), MDNNotParsable.empty())


  def empty(): MDNParseResults = MDNParseResults(Map(), MDNNotFound(Set()), MDNNotParsable(Set()))

  def merge(response1: MDNParseResults, response2: MDNParseResults): MDNParseResults =
    MDNParseResults(response1.parsed ++ response2.parsed,
      response1.notFound.merge(response2.notFound),
      response1.notParsable.merge(response2.notParsable))
}

case class MDNParseResults(parsed: Map[BlobId, MDNParsed],
                           notFound: MDNNotFound,
                           notParsable: MDNNotParsable) {

  def merge(other: MDNParseResults): MDNParseResults = MDNParseResults(this.parsed ++ other.parsed,
    this.notFound.merge(other.notFound),
    this.notParsable.merge(other.notParsable))

  def asResponse(accountId: AccountId): MDNParseResponse = MDNParseResponse(
    accountId,
    parsed,
    notFound,
    notParsable
  )
}

case class RequestTooLargeException(description: String) extends Exception

class MDNParseMethod @Inject()(val blobResolvers: BlobResolvers,
                               val metricFactory: MetricFactory,
                               val sessionSupplier: SessionSupplier) extends MethodRequiringAccountId[MDNParseRequest] {
  override val methodName: MethodName = MethodName("MDN/parse")
  override val requiredCapabilities: Set[CapabilityIdentifier] = Set(JMAP_MDN, JMAP_MAIL)

  def doProcess(capabilities: Set[CapabilityIdentifier],
                invocation: InvocationWithContext,
                mailboxSession: MailboxSession,
                request: MDNParseRequest): SMono[InvocationWithContext] = {
    computeResponseInvocation(request, invocation.invocation, mailboxSession)
      .onErrorResume({
        case e: IllegalArgumentException => SMono.just(Invocation.error(ErrorCode.InvalidArguments, "error", invocation.invocation.methodCallId))
        case e: Throwable => SMono.error(e)
      }).map(InvocationWithContext(_, invocation.processingContext))
  }

  override def getRequest(mailboxSession: MailboxSession, invocation: Invocation): Either[Exception, MDNParseRequest] = {
    MDNParseSerializer.deserializeMDNParseRequest(invocation.arguments.value) match {
      case JsSuccess(emailGetRequest, _) => validateRequestParameters(emailGetRequest)
      case errors: JsError => Left(new IllegalArgumentException(ResponseSerializer.serialize(errors).toString))
    }
  }

  private def validateRequestParameters(request: MDNParseRequest): Either[RequestTooLargeException, MDNParseRequest] = {
    if (request.blobIds.value.length > 200) {
      Left(RequestTooLargeException("The number of ids requested by the client exceeds the maximum number the server is willing to process in a single method call"))
    } else {
      Right(request)
    }
  }

  def computeResponseInvocation(request: MDNParseRequest,
                                invocation: Invocation,
                                mailboxSession: MailboxSession): SMono[Invocation] =
    getMDNParses(request, mailboxSession)
      .map(res => Invocation(
        methodName,
        Arguments(MDNParseSerializer.serialize(res).as[JsObject]),
        invocation.methodCallId
      ))

  private def getMDNParses(request: MDNParseRequest,
                           mailboxSession: MailboxSession): SMono[MDNParseResponse] = {
    val parsedIds: Seq[BlobId] = request.blobIds.value
      .flatMap(BlobId.of(_).fold(e => None, value => Some(value)))

    val invalidIds: Seq[UnparsedBlobId] = request.blobIds.value
      .flatMap(unparsed => BlobId.of(unparsed).fold(_ => Some(unparsed), _ => None))

    val invalid: SFlux[MDNParseResults] = SFlux.fromIterable(invalidIds)
      .map(id => MDNParseResults.notFound(id))

    val parsed: SFlux[MDNParseResults] = SFlux.fromIterable(parsedIds)
      .flatMap(blobId => retrieve(blobId, mailboxSession))

    SFlux.merge(Seq(parsed, invalid))
      .reduce(MDNParseResults.empty())(MDNParseResults.merge)
      .map(result => result.asResponse(request.accountId))
  }

  private def retrieve(blobId: BlobId, mailboxSession: MailboxSession): SMono[MDNParseResults] = {
    blobResolvers.resolve(blobId, mailboxSession)
      .map(blob => convert(blobId, blob.content))
      .onErrorRecover {
        case e: BlobNotFoundException => MDNParseResults.notFound(e.blobId)
      }
  }

  private def convert(blobId: BlobId, blobContent: InputStream): MDNParseResults = {
    println(new String(blobContent.readAllBytes(), StandardCharsets.UTF_8))
    var parser = MDNReportParser.parse(blobContent, StandardCharsets.UTF_8.toString)
    if (blobId.equals(BlobId.of("1").toOption.get)) {
      var mockParser = MDNReportParser.parse(
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
          .stripMargin)
      //      parser = mockParser
    }
    if (parser.isSuccess) {
      MDNParseResults.parse(blobId, MDNParsed.convertFromMDNReport(parser.get))
    } else {
      MDNParseResults.notParse(blobId)
    }
  }
}
