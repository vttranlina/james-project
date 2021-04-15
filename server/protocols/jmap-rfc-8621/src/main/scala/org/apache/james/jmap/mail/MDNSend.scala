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

package org.apache.james.jmap.mail

import cats.implicits.toTraverseOps
import org.apache.james.jmap.core.Id.Id
import org.apache.james.jmap.core.SetError.SetErrorDescription
import org.apache.james.jmap.core.{AccountId, Id, Properties, SetError}
import org.apache.james.jmap.method.WithAccountId
import org.apache.james.mailbox.model.MessageId
import play.api.libs.json.{JsObject, JsPath, JsonValidationError}

object MDNSend {
  val MDN_ALREADY_SENT_FLAG: String = "$mdnsent"
}

case class MDNSendId(id: Id)

object MDNSendRequestInvalidException {
  def parse(errors: collection.Seq[(JsPath, collection.Seq[JsonValidationError])]): MDNSendRequestInvalidException = {
    val setError = errors.head match {
      case (path, Seq()) => SetError.invalidArguments(SetErrorDescription(s"'$path' property in MDNSend object is not valid"))
      case (path, Seq(JsonValidationError(Seq("error.path.missing")))) => SetError.invalidArguments(SetErrorDescription(s"Missing '$path' property in MDNSend object"))
      case (path, Seq(JsonValidationError(Seq(message)))) => SetError.invalidArguments(SetErrorDescription(s"'$path' property in MDNSend object is not valid: $message"))
      case (path, _) => SetError.invalidArguments(SetErrorDescription(s"Unknown error on property '$path'"))
    }
    MDNSendRequestInvalidException(setError)
  }
}

case class MDNSendRequestInvalidException(error: SetError) extends Exception

case class MDNSendNotFoundException(description: String) extends Exception

case class MDNSendForbiddenException() extends Exception

case class MDNSendForbiddenFromException() extends Exception

case class MDNSendOverQuotaException() extends Exception

case class MDNSendTooLargeException() extends Exception

case class MDNSendRateLimitException() extends Exception

case class MDNSendInvalidPropertiesException() extends Exception

case class MDNSendAlreadySentException() extends Exception

object MDNSendCreateRequest {
  private val assignableProperties: Set[String] = Set("forEmailId", "subject", "textBody", "reportingUA",
    "finalRecipient", "includeOriginalMessage", "disposition", "extensionFields")

  def validateProperties(jsObject: JsObject): Either[MDNSendRequestInvalidException, JsObject] =
    jsObject.keys.diff(assignableProperties) match {
      case unknownProperties if unknownProperties.nonEmpty =>
        Left(MDNSendRequestInvalidException(SetError.invalidArguments(
          SetErrorDescription("Some unknown properties were specified"),
          Some(Properties.toProperties(unknownProperties.toSet)))))
      case _ => scala.Right(jsObject)
    }
}

case class MDNSendCreateRequest(forEmailId: ForEmailIdField,
                                subject: Option[SubjectField],
                                textBody: Option[TextBodyField],
                                reportingUA: Option[ReportUAField],
                                finalRecipient: Option[FinalRecipientField],
                                includeOriginalMessage: Option[IncludeOriginalMessageField],
                                disposition: MDNDisposition,
                                extensionFields: Option[Map[ExtensionFieldName, ExtensionFieldValue]]) {
  def validate: Either[MDNSendRequestInvalidException, MDNSendCreateRequest] =
    validateDisposition.flatMap(_ => validateReportUA)
      .flatMap(_ => validateFinalRecipient)

  def validateDisposition: Either[MDNSendRequestInvalidException, MDNSendCreateRequest] =
    disposition.valid match {
      case Left(value) => Left(value)
      case util.Right(_) => scala.Right(this)
    }

  def validateReportUA: Either[MDNSendRequestInvalidException, MDNSendCreateRequest] =
    reportingUA match {
      case None => scala.Right(this)
      case Some(value) => value.valid match {
        case Left(value) => Left(value)
        case util.Right(_) => scala.Right(this)
      }
    }

  def validateFinalRecipient: Either[MDNSendRequestInvalidException, MDNSendCreateRequest] =
    finalRecipient match {
      case None => scala.Right(this)
      case Some(value) => value.valid match {
        case Left(value) => Left(value)
        case util.Right(_) => scala.Right(this)
      }
    }
}

case class MDNSendCreateResponse(subject: Option[SubjectField],
                                 textBody: Option[TextBodyField],
                                 reportingUA: Option[ReportUAField],
                                 mdnGateway: Option[MDNGatewayField],
                                 originalRecipient: Option[OriginalRecipientField],
                                 finalRecipient: Option[FinalRecipientField],
                                 includeOriginalMessage: Option[IncludeOriginalMessageField],
                                 originalMessageId: Option[OriginalMessageIdField],
                                 error: Option[Seq[ErrorField]])

case class MDNSendRequest(accountId: AccountId,
                          identityId: IdentityId,
                          send: Map[MDNSendId, JsObject],
                          onSuccessUpdateEmail: Option[Map[MDNSendId, JsObject]]) extends WithAccountId {

  def validate: Either[IllegalArgumentException, MDNSendRequest] = {
    val supportedCreationIds: List[MDNSendId] = send.keys.toList
    onSuccessUpdateEmail.getOrElse(Map())
      .keys
      .toList
      .map(id => validateOnSuccessUpdateEmail(id, supportedCreationIds))
      .sequence
      .map(_ => this)
  }

  private def validateOnSuccessUpdateEmail(creationId: MDNSendId, supportedCreationIds: List[MDNSendId]): Either[IllegalArgumentException, MDNSendId] =
    if (creationId.id.value.startsWith("#")) {
      val realId = creationId.id.value.substring(1)
      val validateId: Either[IllegalArgumentException, MDNSendId] = Id.validate(realId).map(id => MDNSendId(id))
      validateId.flatMap(mdnSendId => if (supportedCreationIds.contains(mdnSendId)) {
        scala.Right(mdnSendId)
      } else {
        Left(new IllegalArgumentException(s"${creationId.id.value} cannot be referenced in current method call"))
      })
    } else {
      Left(new IllegalArgumentException(s"${creationId.id.value} cannot be retrieved as storage for MDNSend is not yet implemented"))
    }

  def implicitEmailSetRequest(messageIdResolver: MDNSendId => Either[IllegalArgumentException, Option[MessageId]]): Either[IllegalArgumentException, EmailSetRequest] =
    resolveOnSuccessUpdateEmail(messageIdResolver)
      .map(update => EmailSetRequest(
        accountId = accountId,
        create = None,
        update = update,
        destroy = None))

  def resolveOnSuccessUpdateEmail(messageIdResolver: MDNSendId => Either[IllegalArgumentException, Option[MessageId]]): Either[IllegalArgumentException, Option[Map[UnparsedMessageId, JsObject]]] =
    onSuccessUpdateEmail.map(map => map.toList
      .map {
        case (creationId, json) => messageIdResolver.apply(creationId).map(msgOpt => msgOpt.map(messageId => (EmailSet.asUnparsed(messageId), json)))
      }
      .sequence
      .map(list => list.flatten.toMap))
      .sequence
      .map {
        case Some(value) if value.isEmpty => None
        case e => e
      }
}

case class MDNSendResponse(accountId: AccountId,
                           sent: Option[Map[MDNSendId, MDNSendCreateResponse]],
                           notSent: Option[Map[MDNSendId, SetError]])

object MDNSendResults {
  def empty: MDNSendResults = MDNSendResults(None, None, Map.empty)

  def sent(mdnSendId: MDNSendId, mdnResponse: MDNSendCreateResponse, forEmailId: MessageId): MDNSendResults =
    MDNSendResults(Some(Map(mdnSendId -> mdnResponse)), None, Map(mdnSendId -> forEmailId))

  def notSent(mdnSendId: MDNSendId, throwable: Throwable): MDNSendResults = {
    val setError: SetError = throwable match {
      case notFound: MDNSendNotFoundException => SetError.notFound(SetErrorDescription(notFound.description))
      case _: MDNSendForbiddenException => SetError(SetError.forbiddenValue,
        SetErrorDescription("Violate an Access Control List (ACL) or other permissions policy."),
        None)
      case _: MDNSendForbiddenFromException => SetError(SetError.forbiddenFromValue,
        SetErrorDescription("The user is not allowed to use the given \"finalRecipient\" property."),
        None)
      case _: MDNSendOverQuotaException => SetError(SetError.overQuotaValue,
        SetErrorDescription("Exceed a server-defined limit on the number or total size of sent MDNs."),
        None)
      case _: MDNSendTooLargeException => SetError(SetError.tooLargeValue,
        SetErrorDescription("Limit for the maximum size of an MDN or more generally, on email message."),
        None)
      case _: MDNSendRateLimitException => SetError(SetError.rateLimitValue,
        SetErrorDescription("Too many MDNs or email messages have been created recently, and a server-defined rate limit has been reached. It may work if tried again later."),
        None)
      case _: MDNSendInvalidPropertiesException => SetError(SetError.invalidArgumentValue,
        SetErrorDescription("The record given is invalid in some way."),
        None)
      case _: MDNSendAlreadySentException => SetError(SetError.mdnAlreadySentValue,
        SetErrorDescription("The message has the $mdnsent keyword already set."),
        None)
      case parseError: MDNSendRequestInvalidException => parseError.error
    }
    MDNSendResults(None, Some(Map(mdnSendId -> setError)), Map.empty)
  }

  def merge(result1: MDNSendResults, result2: MDNSendResults): MDNSendResults = MDNSendResults(
    sent = (result1.sent ++ result2.sent).reduceOption((sent1, sent2) => sent1 ++ sent2),
    notSent = (result1.notSent ++ result2.notSent).reduceOption((notSent1, notSent2) => notSent1 ++ notSent2),
    mdnSentIdResolver = result1.mdnSentIdResolver ++ result2.mdnSentIdResolver)
}

case class MDNSendResults(sent: Option[Map[MDNSendId, MDNSendCreateResponse]],
                          notSent: Option[Map[MDNSendId, SetError]],
                          mdnSentIdResolver: Map[MDNSendId, MessageId]) {

  def resolveMessageId(sendId: MDNSendId): Either[IllegalArgumentException, Option[MessageId]] =
    if (sendId.id.value.startsWith("#")) {
      val realId = sendId.id.value.substring(1)
      val validatedId: Either[IllegalArgumentException, MDNSendId] = Id.validate(realId).map(id => MDNSendId(id))
      validatedId
        .left.map(s => new IllegalArgumentException(s))
        .flatMap(id => retrieveMessageId(id)
          .map(id => scala.Right(Some(id))).getOrElse(scala.Right(None)))
    } else {
      Left(new IllegalArgumentException(s"${sendId.id.value} cannot be retrieved as storage for MDNSend is not yet implemented"))
    }

  private def retrieveMessageId(creationId: MDNSendId): Option[MessageId] =
    sent.getOrElse(Map.empty).
      filter(sentResult => sentResult._1.equals(creationId)).keys
      .headOption
      .flatMap(mdnSendId => mdnSentIdResolver.get(mdnSendId))

  def asResponse(accountId: AccountId): MDNSendResponse = MDNSendResponse(accountId, sent, notSent)
}
