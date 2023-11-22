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

package org.apache.james.mailbox.postgres.mail.dao;


import static org.apache.james.mailbox.postgres.mail.PostgresCommons.LOCAL_DATE_TIME_DATE_FUNCTION;
import static org.apache.james.mailbox.postgres.mail.PostgresCommons.TABLE_FIELD;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageTable.BODY_START_OCTET;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.IS_ANSWERED;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.IS_DELETED;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.IS_DRAFT;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.IS_FLAGGED;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.IS_RECENT;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.IS_SEEN;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.MAILBOX_ID;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.MESSAGE_ID;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.MESSAGE_UID;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.TABLE_NAME;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.THREAD_ID;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.USER_FLAGS;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;

import javax.mail.Flags;

import org.apache.james.backends.postgres.utils.PostgresExecutor;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.model.Content;
import org.apache.james.mailbox.model.ThreadId;
import org.apache.james.mailbox.postgres.PostgresMailboxId;
import org.apache.james.mailbox.postgres.PostgresMessageId;
import org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageTable;
import org.apache.james.mailbox.store.mail.model.MailboxMessage;
import org.apache.james.mailbox.store.mail.model.impl.PropertyBuilder;
import org.apache.james.mailbox.store.mail.model.impl.SimpleMailboxMessage;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.SelectFinalStep;
import org.jooq.SelectSeekStep1;
import org.jooq.SortField;
import org.jooq.TableOnConditionStep;
import org.jooq.impl.DSL;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class PostgresMailboxMessageDAO {
    private static final Function<Record, MessageUid> RECORD_TO_MESSAGE_UID_FUNCTION = record -> MessageUid.of(record.get(MESSAGE_UID));
    private static final Function<Record, Flags> RECORD_TO_FLAGS_FUNCTION = record -> {
        Flags flags = new Flags();
        Optional.ofNullable(record.get(USER_FLAGS)).stream()
            .flatMap(Arrays::stream)
            .forEach(flags::add);
        return flags;
    };

    private static final TableOnConditionStep<Record> MESSAGES_JOIN_MAILBOX_MESSAGES_CONDITION_STEP = TABLE_NAME.join(MessageTable.TABLE_NAME)
        .on(TABLE_FIELD.of(TABLE_NAME, MESSAGE_ID).eq(TABLE_FIELD.of(MessageTable.TABLE_NAME, MessageTable.MESSAGE_ID)));

    public static final SortField<Long> DEFAULT_SORT_ORDER_BY = MESSAGE_UID.asc();

    private static SelectFinalStep<Record1<Long>> selectMessageUidByMailboxIdAndExtraConditionQuery(PostgresMailboxId mailboxId, Condition extraCondition, Optional<Integer> limit, DSLContext dslContext) {
        SelectSeekStep1<Record1<Long>, Long> queryWithoutLimit = dslContext.select(MESSAGE_UID)
            .from(TABLE_NAME)
            .where(MAILBOX_ID.eq((mailboxId.asUuid())))
            .and(extraCondition)
            .orderBy(MESSAGE_UID.asc());
        return limit.map(limitValue -> (SelectFinalStep<Record1<Long>>) queryWithoutLimit.limit(limitValue))
            .orElse(queryWithoutLimit);
    }

    private static final Content EMPTY_CONTENT = new Content() {
        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public long size() {
            return 0;
        }
    };

    private final PostgresExecutor postgresExecutor;

    private final ThreadId.Factory threadIdFactory;

    public PostgresMailboxMessageDAO(PostgresExecutor postgresExecutor) {
        this.postgresExecutor = postgresExecutor;
        this.threadIdFactory = new ThreadId.Factory(new PostgresMessageId.Factory());
    }

    public Mono<MessageUid> findFirstUnseenMessageUid(PostgresMailboxId mailboxId) {
        return postgresExecutor.executeRow(dslContext -> Mono.from(selectMessageUidByMailboxIdAndExtraConditionQuery(mailboxId,
                IS_SEEN.eq(false), Optional.of(1), dslContext)))
            .map(RECORD_TO_MESSAGE_UID_FUNCTION);
    }

    public Flux<MessageUid> findAllRecentMessageUid(PostgresMailboxId mailboxId) {
        return postgresExecutor.executeRows(dslContext -> Flux.from(selectMessageUidByMailboxIdAndExtraConditionQuery(mailboxId,
                IS_SEEN.eq(true), Optional.empty(), dslContext)))
            .map(RECORD_TO_MESSAGE_UID_FUNCTION);
    }

    public Flux<MessageUid> listAllMessageUid(PostgresMailboxId mailboxId) {
        return postgresExecutor.executeRows(dslContext -> Flux.from(selectMessageUidByMailboxIdAndExtraConditionQuery(mailboxId,
                DSL.noCondition(), Optional.empty(), dslContext)))
            .map(RECORD_TO_MESSAGE_UID_FUNCTION);
    }

    public Mono<MessageUid> findLastMessageUid(PostgresMailboxId mailboxId) {
        return postgresExecutor.executeRow(dslContext -> Mono.from(dslContext.select(MESSAGE_UID)
                .from(TABLE_NAME)
                .where(MAILBOX_ID.eq(mailboxId.asUuid()))
                .orderBy(MESSAGE_UID.desc())
                .limit(1)))
            .map(RECORD_TO_MESSAGE_UID_FUNCTION);
    }

    public Mono<Void> deleteByMailboxId(PostgresMailboxId mailboxId) {
        return postgresExecutor.executeVoid(dslContext -> Mono.from(dslContext.deleteFrom(TABLE_NAME)
            .where(MAILBOX_ID.eq(mailboxId.asUuid()))));
    }

    public Mono<Void> deleteByMailboxIdAndMessageUid(PostgresMailboxId mailboxId, MessageUid messageUid) {
        return postgresExecutor.executeVoid(dslContext -> Mono.from(dslContext.deleteFrom(TABLE_NAME)
            .where(MAILBOX_ID.eq(mailboxId.asUuid()))
            .and(MESSAGE_UID.eq(messageUid.asLong()))));
    }

    public Mono<Void> deleteByMailboxIdAndMessageUidBetween(PostgresMailboxId mailboxId, MessageUid from, MessageUid to) {
        return postgresExecutor.executeVoid(dslContext -> Mono.from(dslContext.deleteFrom(TABLE_NAME)
            .where(MAILBOX_ID.eq(mailboxId.asUuid()))
            .and(MESSAGE_UID.greaterOrEqual(from.asLong()))
            .and(MESSAGE_UID.lessOrEqual(to.asLong()))));
    }

    public Mono<Void> deleteByMailboxIdAndMessageUidAfter(PostgresMailboxId mailboxId, MessageUid from) {
        return postgresExecutor.executeVoid(dslContext -> Mono.from(dslContext.deleteFrom(TABLE_NAME)
            .where(MAILBOX_ID.eq(mailboxId.asUuid()))
            .and(MESSAGE_UID.greaterOrEqual(from.asLong()))));
    }

    public Mono<Integer> countUnseenMessagesByMailboxId(PostgresMailboxId mailboxId) {
        return postgresExecutor.executeCount(dslContext -> Mono.from(dslContext.selectCount()
            .from(TABLE_NAME)
            .where(MAILBOX_ID.eq(mailboxId.asUuid()))
            .and(IS_SEEN.eq(false))));
    }

    public Mono<Integer> countTotalMessagesByMailboxId(PostgresMailboxId mailboxId) {
        return postgresExecutor.executeCount(dslContext -> Mono.from(dslContext.selectCount()
            .from(TABLE_NAME)
            .where(MAILBOX_ID.eq(mailboxId.asUuid()))));
    }

    public Flux<MailboxMessage> findMessagesByMailboxId(PostgresMailboxId mailboxId) {
        return postgresExecutor.executeRows(dslContext -> Flux.from(dslContext.select()
                .from(MESSAGES_JOIN_MAILBOX_MESSAGES_CONDITION_STEP)
                .where(MAILBOX_ID.eq(mailboxId.asUuid()))
                .orderBy(DEFAULT_SORT_ORDER_BY)))
            .map(this::toMailboxMessage);
    }

    public Flux<MailboxMessage> findMessagesByMailboxIdAndBetweenUIDs(PostgresMailboxId mailboxId, MessageUid from, MessageUid to) {
        return postgresExecutor.executeRows(dslContext -> Flux.from(dslContext.select()
                .from(MESSAGES_JOIN_MAILBOX_MESSAGES_CONDITION_STEP)
                .where(MAILBOX_ID.eq(mailboxId.asUuid()))
                .and(MESSAGE_UID.greaterOrEqual(from.asLong()))
                .and(MESSAGE_UID.lessOrEqual(to.asLong()))
                .orderBy(DEFAULT_SORT_ORDER_BY)))
            .map(this::toMailboxMessage);
    }

    public Mono<MailboxMessage> findMessageByUid(PostgresMailboxId mailboxId, MessageUid uid) {
        return postgresExecutor.executeRow(dslContext -> Mono.from(dslContext.select()
                .from(MESSAGES_JOIN_MAILBOX_MESSAGES_CONDITION_STEP)
                .where(MAILBOX_ID.eq(mailboxId.asUuid()))
                .and(MESSAGE_UID.eq(uid.asLong()))))
            .map(this::toMailboxMessage);
    }

    public Flux<MailboxMessage> findMessagesByMailboxIdAndAfterUID(PostgresMailboxId mailboxId, MessageUid from) {
        return postgresExecutor.executeRows(dslContext -> Flux.from(dslContext.select()
                .from(MESSAGES_JOIN_MAILBOX_MESSAGES_CONDITION_STEP)
                .where(MAILBOX_ID.eq(mailboxId.asUuid()))
                .and(MESSAGE_UID.greaterOrEqual(from.asLong()))
                .orderBy(DEFAULT_SORT_ORDER_BY)))
            .map(this::toMailboxMessage);
    }

    public Flux<MailboxMessage> findDeletedMessagesByMailboxId(PostgresMailboxId mailboxId) {

        return postgresExecutor.executeRows(dslContext -> Flux.from(dslContext.select()
                .from(MESSAGES_JOIN_MAILBOX_MESSAGES_CONDITION_STEP)
                .where(MAILBOX_ID.eq(mailboxId.asUuid()))
                .and(IS_DELETED.eq(true))
                .orderBy(DEFAULT_SORT_ORDER_BY)))
            .map(this::toMailboxMessage);
    }

    public Flux<MailboxMessage> findDeletedMessagesByMailboxIdAndBetweenUIDs(PostgresMailboxId mailboxId, MessageUid from, MessageUid to) {
        return postgresExecutor.executeRows(dslContext -> Flux.from(dslContext.select()
                .from(MESSAGES_JOIN_MAILBOX_MESSAGES_CONDITION_STEP)
                .where(MAILBOX_ID.eq(mailboxId.asUuid()))
                .and(IS_DELETED.eq(true))
                .and(MESSAGE_UID.greaterOrEqual(from.asLong()))
                .and(MESSAGE_UID.lessOrEqual(to.asLong()))
                .orderBy(DEFAULT_SORT_ORDER_BY)))
            .map(this::toMailboxMessage);
    }

    public Flux<MailboxMessage> findDeletedMessagesByMailboxIdAndAfterUID(PostgresMailboxId mailboxId, MessageUid from) {
        return postgresExecutor.executeRows(dslContext -> Flux.from(dslContext.select()
                .from(MESSAGES_JOIN_MAILBOX_MESSAGES_CONDITION_STEP)
                .where(MAILBOX_ID.eq(mailboxId.asUuid()))
                .and(IS_DELETED.eq(true))
                .and(MESSAGE_UID.greaterOrEqual(from.asLong()))
                .orderBy(DEFAULT_SORT_ORDER_BY)))
            .map(this::toMailboxMessage);
    }

    public Mono<MailboxMessage> findDeletedMessageByUid(PostgresMailboxId mailboxId, MessageUid uid) {
        return postgresExecutor.executeRow(dslContext -> Mono.from(dslContext.select()
                .from(MESSAGES_JOIN_MAILBOX_MESSAGES_CONDITION_STEP)
                .where(MAILBOX_ID.eq(mailboxId.asUuid()))
                .and(IS_DELETED.eq(true))
                .and(MESSAGE_UID.eq(uid.asLong()))))
            .map(this::toMailboxMessage);
    }

    private MailboxMessage toMailboxMessage(Record record) {
        return SimpleMailboxMessage.builder()
            .messageId(PostgresMessageId.Factory.of(record.get(MESSAGE_ID)))
            .mailboxId(PostgresMailboxId.of(record.get(MAILBOX_ID)))
            .uid(MessageUid.of(record.get(MESSAGE_UID)))
            .threadId(Optional.ofNullable(record.get(THREAD_ID))
                .map(threadIdFactory::fromString)
                .orElse(ThreadId.fromBaseMessageId(PostgresMessageId.Factory.of(record.get(MESSAGE_ID)))))
            .internalDate(LOCAL_DATE_TIME_DATE_FUNCTION.apply(record.get(MessageTable.INTERNAL_DATE, LocalDateTime.class)))
            .flags(RECORD_TO_FLAGS_FUNCTION.apply(record))
            .size(record.get(MessageTable.SIZE))
            .bodyStartOctet(record.get(BODY_START_OCTET))
            .content(EMPTY_CONTENT)
            .properties(new PropertyBuilder())
            .build();
    }

    public Mono<Void> insert(MailboxMessage mailboxMessage) {
        return postgresExecutor.executeVoid(dslContext -> Mono.from(dslContext.insertInto(TABLE_NAME)
            .set(MAILBOX_ID, ((PostgresMailboxId) mailboxMessage.getMailboxId()).asUuid())
            .set(MESSAGE_UID, mailboxMessage.getUid().asLong())
            .set(MESSAGE_ID, ((PostgresMessageId) mailboxMessage.getMessageId()).asUuid())
            .set(IS_DELETED, mailboxMessage.isDeleted())
            .set(IS_ANSWERED, mailboxMessage.isAnswered())
            .set(IS_DRAFT, mailboxMessage.isDraft())
            .set(IS_FLAGGED, mailboxMessage.isFlagged())
            .set(IS_RECENT, mailboxMessage.isRecent())
            .set(IS_SEEN, mailboxMessage.isSeen())));
    }

}
