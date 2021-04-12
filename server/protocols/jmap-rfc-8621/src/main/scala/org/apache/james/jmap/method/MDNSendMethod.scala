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

package org.apache.james.jmap.method

import eu.timepit.refined.auto._
import org.apache.james.jmap.core.CapabilityIdentifier.{CapabilityIdentifier, JMAP_CORE, JMAP_MAIL, JMAP_MDN}
import org.apache.james.jmap.core.Invocation._
import org.apache.james.jmap.core.{ClientId, Id, Invocation, ServerId}
import org.apache.james.jmap.json.{MDNSendSerializer, ResponseSerializer}
import org.apache.james.jmap.mail.MDNSend.MDNSendId
import org.apache.james.jmap.mail._
import org.apache.james.jmap.method.EmailSubmissionSetMethod.LOGGER
import org.apache.james.jmap.routes.{ProcessingContext, SessionSupplier}
import org.apache.james.lifecycle.api.Startable
import org.apache.james.mailbox.model.{FetchGroup, MessageId, MessageResult}
import org.apache.james.mailbox.{MailboxSession, MessageIdManager}
import org.apache.james.mdn.`type`.DispositionType
import org.apache.james.mdn.action.mode.DispositionActionMode
import org.apache.james.mdn.fields.{ExtensionField, ReportingUserAgent, Disposition => DispositionJava}
import org.apache.james.mdn.sending.mode.DispositionSendingMode
import org.apache.james.mdn.{MDN, MDNReport}
import org.apache.james.metrics.api.MetricFactory
import org.apache.james.mime4j.dom.Message
import org.apache.james.queue.api.MailQueueFactory.SPOOL
import org.apache.james.queue.api.{MailQueue, MailQueueFactory}
import org.apache.james.server.core.MailImpl
import play.api.libs.json.{JsError, JsObject, JsSuccess, Json}
import reactor.core.scala.publisher.{SFlux, SMono}
import reactor.core.scheduler.Schedulers

import javax.annotation.PreDestroy
import javax.inject.Inject
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.util.Try

class MDNSendMethod @Inject()(serializer: MDNSendSerializer,
                              mailQueueFactory: MailQueueFactory[_ <: MailQueue],
                              messageIdManager: MessageIdManager,
                              emailSetMethod: EmailSetMethod,
                              messageIdFactory: MessageId.Factory,
                              val metricFactory: MetricFactory,
                              val sessionSupplier: SessionSupplier) extends MethodRequiringAccountId[MDNSendRequest] with Startable {
  override val methodName: MethodName = MethodName("MDN/send")
  override val requiredCapabilities: Set[CapabilityIdentifier] = Set(JMAP_MDN, JMAP_MAIL, JMAP_CORE)
  var queue: MailQueue = _

  def init: Unit =
    queue = mailQueueFactory.createQueue(SPOOL)

  @PreDestroy def dispose: Unit =
    Try(queue.close())
      .recover(e => LOGGER.debug("error closing queue", e))

  override def doProcess(capabilities: Set[CapabilityIdentifier],
                         invocation: InvocationWithContext,
                         mailboxSession: MailboxSession,
                         request: MDNSendRequest): SFlux[InvocationWithContext] =
    create(request, mailboxSession, invocation.processingContext)
      .flatMapMany(createdResults => {
        val explicitInvocation: InvocationWithContext = InvocationWithContext(
          invocation = Invocation(
            methodName = invocation.invocation.methodName,
            arguments = Arguments(serializer.serializeMDNSendResponse(createdResults._1.asResponse(request.accountId))
              .as[JsObject]),
            methodCallId = invocation.invocation.methodCallId),
          processingContext = createdResults._2)

        val emailSetCall: SMono[InvocationWithContext] = request.implicitEmailSetRequest(createdResults._1.resolveMessageId)
          .fold(e => SMono.error(e),
            emailSetRequest => emailSetMethod.doProcess(
              capabilities = capabilities,
              invocation = invocation,
              mailboxSession = mailboxSession,
              request = emailSetRequest))

        SFlux.concat(SMono.just(explicitInvocation), emailSetCall)
      })

  override def getRequest(mailboxSession: MailboxSession, invocation: Invocation): Either[Exception, MDNSendRequest] =
    serializer.deserializeMDNSendRequest(invocation.arguments.value) match {
      case JsSuccess(mdnSendRequest, _) => mdnSendRequest.validate
      case errors: JsError => Left(new IllegalArgumentException(Json.stringify(ResponseSerializer.serialize(errors))))
    }

  private def create(request: MDNSendRequest,
                     session: MailboxSession,
                     processingContext: ProcessingContext): SMono[(MDNSendResults, ProcessingContext)] =
    SFlux.fromIterable(request.send.view)
      .fold(MDNSendResults.empty -> processingContext) {
        (acc: (MDNSendResults, ProcessingContext), elem: (MDNSendId, JsObject)) => {
          val (mdnSendId, jsObject) = elem
          val (creationResult, updatedProcessingContext) = createMDNSend(session, mdnSendId, jsObject, acc._2)
          (MDNSendResults.merge(acc._1, creationResult) -> updatedProcessingContext)
        }
      }
      .subscribeOn(Schedulers.elastic())

  private def createMDNSend(session: MailboxSession,
                            mdnSendId: MDNSendId,
                            jsObject: JsObject,
                            processingContext: ProcessingContext): (MDNSendResults, ProcessingContext) =
    parseMDNRequest(jsObject)
      .flatMap(createRequest => sendMDN(session, mdnSendId, createRequest))
      .flatMap {
        case (results, id) => recordCreationIdInProcessingContext(mdnSendId, id, processingContext)
          .map(_ => results)
      }
      .fold(error => (MDNSendResults.notSent(mdnSendId, error) -> processingContext),
        creation => (creation -> processingContext))

  private def parseMDNRequest(jsObject: JsObject): Either[MDNSendParseException, MDNSendCreateRequest] =
      MDNSendCreateRequest.validateProperties(jsObject)
        .flatMap(validJson => serializer.deserializeMDNSendCreateRequest(validJson) match {
          case JsSuccess(createRequest, _) => createRequest.validate
          case JsError(errors) => Left(MDNSendParseException.parse(errors))
        })

  private def sendMDN(session: MailboxSession,
                      mdnSendId: MDNSendId,
                      requestEntry: MDNSendCreateRequest): Either[Throwable, (MDNSendResults, MessageId)] = {
    val mdnRelatedMessage: Either[Exception, MessageResult] = messageIdManager.getMessage(requestEntry.forEmailId.originalMessageId, FetchGroup.FULL_CONTENT, session)
      .asScala
      .toList
      .headOption
      .toRight(MDNSendForEmailIdNotFoundException("The reference \"forEmailId\" cannot be found."))

    val mdnRelatedMessageAlready: Either[Exception, MessageResult] = mdnRelatedMessage.flatMap(messageResult => {
      if (isMDNSentAlready(messageResult)) {
        Left(MDNSendAlreadySentException())
      } else {
        scala.Right(messageResult)
      }
    })

    mdnRelatedMessageAlready.flatMap(msg => {
      val result: Try[(MDNSendResults, MessageId)] = {
        val (mail, createResponse) = buildMailAndResponse(session, requestEntry, msg)
        queue.enQueue(mail)
        Try(MDNSendResults.sent(mdnSendId, createResponse, msg.getMessageId) -> msg.getMessageId)
      }
      result.toEither
    })
  }

  private def isMDNSentAlready(messageResultRelated: MessageResult): Boolean =
    messageResultRelated.getFlags.contains("$mdnsent")

  private def recordCreationIdInProcessingContext(mdnSendId: MDNSendId,
                                                  messageId: MessageId,
                                                  processingContext: ProcessingContext): Either[IllegalArgumentException, ProcessingContext] =
    for {
      creationId <- Id.validate(mdnSendId)
      serverAssignedId <- Id.validate(messageId.serialize())
    } yield {
      processingContext.recordCreatedId(ClientId(creationId), ServerId(serverAssignedId))
    }

  private def buildMailAndResponse(session: MailboxSession, requestEntry: MDNSendCreateRequest, messageRelated: MessageResult): (MailImpl, MDNSendCreateResponse) = {
    val sender = session.getUser.asString()
    val reportBuilder = MDNReport.builder()
      .dispositionField(DispositionJava.builder()
        .`type`(DispositionType.fromString(requestEntry.disposition.`type`).orElseThrow())
        .actionMode(DispositionActionMode.fromString(requestEntry.disposition.actionMode).orElseThrow())
        .sendingMode(DispositionSendingMode.fromString(requestEntry.disposition.sendingMode).orElseThrow())
        .build())
      .finalRecipientField(requestEntry.finalRecipient.getOrElse(FinalRecipientField(sender)).value)

    requestEntry.reportingUA.map(uaField => reportBuilder.reportingUserAgentField(ReportingUserAgent.builder().parse(uaField.value).build()))

    requestEntry.extensionFields.map(extensions => extensions
      .map(extension => reportBuilder.withExtensionField(ExtensionField.builder()
        .fieldName(extension._1)
        .rawValue(extension._2)
        .build())))

    val mdnBuilder = MDN.builder()
      .report(reportBuilder.build())
      .message(requestEntry.includeOriginalMessage
        .filter(isInclude => isInclude.value)
        .map(_ => getOriginalMessage(messageRelated)).toJava)

    requestEntry.textBody.map(textBody => mdnBuilder.humanReadableText(textBody.value))

    val newMessageId = messageIdFactory.generate().serialize()
    val mdn = mdnBuilder.build()
    val mimeMessage = mdn.asMimeMessage()

    mimeMessage.setFrom(sender)
    mimeMessage.setRecipients(javax.mail.Message.RecipientType.TO, getRecipientAddress(messageRelated, session))
    mimeMessage.setHeader("Message-Id", newMessageId)
    mimeMessage.setSubject(requestEntry.subject.getOrElse(SubjectField("subject todo")).value)

    val mdnSendCreateResponse = buildMDNSendCreateResponse(requestEntry, mdn)
    (MailImpl.fromMimeMessage(newMessageId, mimeMessage) -> mdnSendCreateResponse)
  }

  private def buildMDNSendCreateResponse(requestEntry: MDNSendCreateRequest, mdn: MDN) =
    MDNSendCreateResponse(
      subject = requestEntry.subject match {
        case Some(_) => None
        case None => Some(SubjectField(mdn.asMimeMessage().getSubject))
      },
      textBody = requestEntry.textBody match {
        case Some(_) => None
        case None => Some(TextBodyField(mdn.getHumanReadableText))
      },
      reportingUA = requestEntry.reportingUA match {
        case Some(_) => None
        case None => mdn.getReport.getReportingUserAgentField
          .map(ua => ReportUAField(ua.fieldValue()))
          .toScala
      },
      mdnGateway = mdn.getReport.getGatewayField
        .map(gateway => MDNGatewayField(gateway.fieldValue()))
        .toScala,
      originalRecipient = mdn.getReport.getOriginalRecipientField
        .map(originalRecipient => OriginalRecipientField(originalRecipient.fieldValue()))
        .toScala,
      includeOriginalMessage = requestEntry.includeOriginalMessage match {
        case Some(_) => None
        case None => Some(IncludeOriginalMessageField(mdn.getOriginalMessage.isPresent))
      },
      error = Option(mdn.getReport.getErrorFields.asScala
        .map(error => ErrorField(error.getText.formatted()))
        .toSeq)
        .filter(error => error.nonEmpty),
      extensionFields = requestEntry.extensionFields match {
        case Some(_) => None
        case None => Option(mdn.getReport.getExtensionFields.asScala
          .map(extension => (extension.getFieldName, extension.getRawValue))
          .toMap).filter(_.nonEmpty)
      },
      finalRecipient = requestEntry.finalRecipient match {
        case Some(_) => None
        case None => Some(FinalRecipientField(mdn.getReport.getFinalRecipientField.fieldValue()))
      })

  private def getOriginalMessage(messageRelated: MessageResult): Message =
    Message.Builder.of(messageRelated.getBody.getInputStream).build() //todo

  private def getRecipientAddress(messageRelated: MessageResult, session: MailboxSession): String =
    session.getUser.asString() //todo originalRecipient

}
