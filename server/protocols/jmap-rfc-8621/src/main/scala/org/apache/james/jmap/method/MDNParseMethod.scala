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
import org.apache.james.jmap.mail.BlobId.toJava
import org.apache.james.jmap.mail.MDNParse.UnparsedBlobId
import org.apache.james.jmap.mail.{BlobId, MDNNotFound, MDNNotParsable, MDNParseFailure, MDNParseRequest, MDNParseResponse, MDNParsed}
import org.apache.james.jmap.routes.SessionSupplier
import org.apache.james.mailbox.model.{Blob, BlobId => JavaBlobId}
import org.apache.james.mailbox.{BlobManager, MailboxSession, MessageIdManager}
import org.apache.james.metrics.api.MetricFactory
import play.api.libs.json.{JsError, JsObject, JsSuccess}
import reactor.core.scala.publisher.{SFlux, SMono}
import eu.timepit.refined.auto._
import reactor.core.scheduler.Schedulers

import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.mail.Message

object MDNParseResults {
  def notFound(blobId: BlobId): MDNParseResults = MDNParseResults(Map.empty,MDNNotFound(Set()), MDNNotParsable(Set.empty))

  def empty(): MDNParseResults = MDNParseResults(Map(), MDNNotFound(Set()), MDNNotParsable(Set()))

  def merge(response1: MDNParseResults, response2: MDNParseResults) =
    MDNParseResults(response1.parsed ++ response2.parsed,
      response1.notFound.merge(response2.notFound),
      response2.notParsable.merge(response2.notParsable))
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

class MDNParseMethod @Inject()(messageIdManager: MessageIdManager,
                               blobManager: BlobManager,
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

  override def getRequest(mailboxSession: MailboxSession, invocation: Invocation): Either[IllegalArgumentException, MDNParseRequest] = {
    MDNParseSerializer.deserializeMDNParseRequest(invocation.arguments.value) match {
      case JsSuccess(emailGetRequest, _) => Right(emailGetRequest)
      case errors: JsError => Left(new IllegalArgumentException(ResponseSerializer.serialize(errors).toString))
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
      .flatMap(unparsed => BlobId.of(unparsed).fold(e => Some(unparsed), _ => None))

    val parsed: SFlux[MDNParseResults] = SFlux.fromIterable(parsedIds)
      .flatMap(blobId => blobToMDN(toJava(blobId), mailboxSession)
        .map(parsed => (blobId, parsed)))
      .map(pair => MDNParseResults(Map((pair._1, pair._2)), MDNNotFound.empty, MDNNotParsable.empty))

    val invalid: SFlux[MDNParseResults] = SFlux.fromIterable(invalidIds)
      .map(id => MDNParseResults(Map(), MDNNotFound.empty, MDNNotParsable(Set(id))))

    //TODO: notFound = invalid ++ blob notFound
//    val notFound: SFlux[MDNParseResults] = ???

    //TODO:
//    val notParsable: SFlux[MDNParseResults] = ???

    SFlux.merge(Seq(parsed, invalid))
      .reduce(MDNParseResults.empty())(MDNParseResults.merge)
      .map(result => result.asResponse(request.accountId))
  }

  private def blobToMDN(blobId: JavaBlobId, mailboxSession: MailboxSession): SMono[MDNParsed] = {
    SMono.fromCallable(() => blobManager.retrieve(blobId, mailboxSession))
//      .map(blob => new String(blob.getStream.readAllBytes(), StandardCharsets.UTF_8))
      .map(content => MDNParsed(None, None, None, None, None, None, None))
  }
}
