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


import static org.apache.james.mailbox.postgres.mail.PostgresCommons.DATE_TO_LOCAL_DATE_TIME;
import static org.apache.james.mailbox.postgres.mail.PostgresCommons.IN_CLAUSE_MAX_SIZE;
import static org.apache.james.mailbox.postgres.mail.PostgresCommons.LOCAL_DATE_TIME_DATE_FUNCTION;
import static org.apache.james.mailbox.postgres.mail.PostgresCommons.TABLE_FIELD;
import static org.apache.james.mailbox.postgres.mail.PostgresCommons.UNNEST_FIELD;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageTable.BLOB_ID;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageTable.BODY_START_OCTET;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageTable.HEADER_CONTENT;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.IS_ANSWERED;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.IS_DELETED;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.IS_DRAFT;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.IS_FLAGGED;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.IS_RECENT;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.IS_SEEN;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.MAILBOX_ID;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.MESSAGE_ID;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.MESSAGE_UID;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.MOD_SEQ;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.SAVE_DATE;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.TABLE_NAME;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.THREAD_ID;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageToMailboxTable.USER_FLAGS;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import javax.mail.Flags;
import javax.mail.Flags.Flag;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.james.backends.postgres.utils.PostgresExecutor;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.ModSeq;
import org.apache.james.mailbox.model.ComposedMessageId;
import org.apache.james.mailbox.model.ComposedMessageIdWithMetaData;
import org.apache.james.mailbox.model.Content;
import org.apache.james.mailbox.model.MessageRange;
import org.apache.james.mailbox.model.ThreadId;
import org.apache.james.mailbox.model.UpdatedFlags;
import org.apache.james.mailbox.postgres.PostgresMailboxId;
import org.apache.james.mailbox.postgres.PostgresMessageId;
import org.apache.james.mailbox.postgres.mail.PostgresMessageModule.MessageTable;
import org.apache.james.mailbox.store.mail.model.MailboxMessage;
import org.apache.james.mailbox.store.mail.model.impl.Properties;
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
import org.jooq.UpdateConditionStep;
import org.jooq.impl.DSL;

import com.google.common.collect.Iterables;

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

    public static final Function<byte[], Content> BYTE_TO_CONTENT_FUNCTION = contentAsBytes -> new Content() {
        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(contentAsBytes);
        }

        @Override
        public long size() {
            return contentAsBytes.length;
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
                IS_RECENT.eq(true), Optional.empty(), dslContext)))
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

    public Mono<Void> deleteByMailboxIdAndMessageUids(PostgresMailboxId mailboxId, List<MessageUid> uids) {
        Function<List<MessageUid>, Mono<Void>> deletePublisherFunction = uidsToDelete -> postgresExecutor.executeVoid(dslContext -> Mono.from(dslContext.deleteFrom(TABLE_NAME)
            .where(MAILBOX_ID.eq(mailboxId.asUuid()))
            .and(MESSAGE_UID.in(uidsToDelete.stream().map(MessageUid::asLong).toArray(Long[]::new)))));

        if (uids.size() <= IN_CLAUSE_MAX_SIZE) {
            return deletePublisherFunction.apply(uids);
        } else {
            return Flux.fromIterable(Iterables.partition(uids, IN_CLAUSE_MAX_SIZE))
                .flatMap(deletePublisherFunction::apply)
                .then();
        }
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

    public Flux<Pair<SimpleMailboxMessage.Builder, String>> findMessagesByMailboxId(PostgresMailboxId mailboxId, int limit) {
        return postgresExecutor.executeRows(dslContext -> Flux.from(dslContext.select()
                .from(MESSAGES_JOIN_MAILBOX_MESSAGES_CONDITION_STEP)
                .where(MAILBOX_ID.eq(mailboxId.asUuid()))
                .orderBy(DEFAULT_SORT_ORDER_BY)
                .limit(limit)))
            .map(record -> Pair.of(toMailboxMessage(record), record.get(BLOB_ID)));
    }

    public Flux<Pair<SimpleMailboxMessage.Builder, String>> findMessagesByMailboxIdAndBetweenUIDs(PostgresMailboxId mailboxId, MessageUid from, MessageUid to, int limit) {
        return postgresExecutor.executeRows(dslContext -> Flux.from(dslContext.select()
                .from(MESSAGES_JOIN_MAILBOX_MESSAGES_CONDITION_STEP)
                .where(MAILBOX_ID.eq(mailboxId.asUuid()))
                .and(MESSAGE_UID.greaterOrEqual(from.asLong()))
                .and(MESSAGE_UID.lessOrEqual(to.asLong()))
                .orderBy(DEFAULT_SORT_ORDER_BY)
                .limit(limit)))
            .map(record -> Pair.of(toMailboxMessage(record), record.get(BLOB_ID)));
    }

    public Mono<Pair<SimpleMailboxMessage.Builder, String>> findMessageByMailboxIdAndUid(PostgresMailboxId mailboxId, MessageUid uid) {
        return postgresExecutor.executeRow(dslContext -> Mono.from(dslContext.select()
                .from(MESSAGES_JOIN_MAILBOX_MESSAGES_CONDITION_STEP)
                .where(MAILBOX_ID.eq(mailboxId.asUuid()))
                .and(MESSAGE_UID.eq(uid.asLong()))))
            .map(record -> Pair.of(toMailboxMessage(record), record.get(BLOB_ID)));
    }

    public Flux<Pair<SimpleMailboxMessage.Builder, String>> findMessagesByMailboxIdAndAfterUID(PostgresMailboxId mailboxId, MessageUid from, int limit) {
        return postgresExecutor.executeRows(dslContext -> Flux.from(dslContext.select()
                .from(MESSAGES_JOIN_MAILBOX_MESSAGES_CONDITION_STEP)
                .where(MAILBOX_ID.eq(mailboxId.asUuid()))
                .and(MESSAGE_UID.greaterOrEqual(from.asLong()))
                .orderBy(DEFAULT_SORT_ORDER_BY)
                .limit(limit)))
            .map(record -> Pair.of(toMailboxMessage(record), record.get(BLOB_ID)));
    }

    public Flux<SimpleMailboxMessage.Builder> findMessagesByMailboxIdAndUIDs(PostgresMailboxId mailboxId, List<MessageUid> uids) {
        Function<List<MessageUid>, Flux<SimpleMailboxMessage.Builder>> queryPublisherFunction = uidsToFetch -> postgresExecutor.executeRows(dslContext -> Flux.from(dslContext.select()
                .from(MESSAGES_JOIN_MAILBOX_MESSAGES_CONDITION_STEP)
                .where(MAILBOX_ID.eq(mailboxId.asUuid()))
                .and(MESSAGE_UID.in(uidsToFetch.stream().map(MessageUid::asLong).toArray(Long[]::new)))
                .orderBy(DEFAULT_SORT_ORDER_BY)))
            .map(this::toMailboxMessage);

        if (uids.size() <= IN_CLAUSE_MAX_SIZE) {
            return queryPublisherFunction.apply(uids);
        } else {
            return Flux.fromIterable(Iterables.partition(uids, IN_CLAUSE_MAX_SIZE))
                .flatMap(queryPublisherFunction);
        }
    }

    public Flux<MessageUid> findDeletedMessagesByMailboxId(PostgresMailboxId mailboxId) {
        return postgresExecutor.executeRows(dslContext -> Flux.from(dslContext.select(MESSAGE_UID)
                .from(MESSAGES_JOIN_MAILBOX_MESSAGES_CONDITION_STEP)
                .where(MAILBOX_ID.eq(mailboxId.asUuid()))
                .and(IS_DELETED.eq(true))
                .orderBy(DEFAULT_SORT_ORDER_BY)))
            .map(RECORD_TO_MESSAGE_UID_FUNCTION);
    }

    public Flux<MessageUid> findDeletedMessagesByMailboxIdAndBetweenUIDs(PostgresMailboxId mailboxId, MessageUid from, MessageUid to) {
        return postgresExecutor.executeRows(dslContext -> Flux.from(dslContext.select(MESSAGE_UID)
                .from(MESSAGES_JOIN_MAILBOX_MESSAGES_CONDITION_STEP)
                .where(MAILBOX_ID.eq(mailboxId.asUuid()))
                .and(IS_DELETED.eq(true))
                .and(MESSAGE_UID.greaterOrEqual(from.asLong()))
                .and(MESSAGE_UID.lessOrEqual(to.asLong()))
                .orderBy(DEFAULT_SORT_ORDER_BY)))
            .map(RECORD_TO_MESSAGE_UID_FUNCTION);
    }

    public Flux<MessageUid> findDeletedMessagesByMailboxIdAndAfterUID(PostgresMailboxId mailboxId, MessageUid from) {
        return postgresExecutor.executeRows(dslContext -> Flux.from(dslContext.select(MESSAGE_UID)
                .from(MESSAGES_JOIN_MAILBOX_MESSAGES_CONDITION_STEP)
                .where(MAILBOX_ID.eq(mailboxId.asUuid()))
                .and(IS_DELETED.eq(true))
                .and(MESSAGE_UID.greaterOrEqual(from.asLong()))
                .orderBy(DEFAULT_SORT_ORDER_BY)))
            .map(RECORD_TO_MESSAGE_UID_FUNCTION);
    }

    public Mono<MessageUid> findDeletedMessageByMailboxIdAndUid(PostgresMailboxId mailboxId, MessageUid uid) {
        return postgresExecutor.executeRow(dslContext -> Mono.from(dslContext.select(MESSAGE_UID)
                .from(MESSAGES_JOIN_MAILBOX_MESSAGES_CONDITION_STEP)
                .where(MAILBOX_ID.eq(mailboxId.asUuid()))
                .and(IS_DELETED.eq(true))
                .and(MESSAGE_UID.eq(uid.asLong()))))
            .map(RECORD_TO_MESSAGE_UID_FUNCTION);
    }

    public Flux<ComposedMessageIdWithMetaData> findMessagesMetadata(PostgresMailboxId mailboxId, MessageRange range) {
        return postgresExecutor.executeRows(dslContext -> Flux.from(dslContext.select()
                .from(TABLE_NAME)
                .where(MAILBOX_ID.eq(mailboxId.asUuid()))
                .and(MESSAGE_UID.greaterOrEqual(range.getUidFrom().asLong()))
                .and(MESSAGE_UID.lessOrEqual(range.getUidTo().asLong()))
                .orderBy(DEFAULT_SORT_ORDER_BY)))
            .map(recordToComposedMessageIdWithMetaData(mailboxId));
    }

    public Flux<ComposedMessageIdWithMetaData> findMessagesMetadata(PostgresMailboxId mailboxId, List<MessageUid> messageUids) {
        Function<List<MessageUid>, Flux<ComposedMessageIdWithMetaData>> queryPublisherFunction = uidsToFetch -> postgresExecutor.executeRows(dslContext -> Flux.from(dslContext.select()
                .from(TABLE_NAME)
                .where(MAILBOX_ID.eq(mailboxId.asUuid()))
                .and(MESSAGE_UID.in(uidsToFetch.stream().map(MessageUid::asLong).toArray(Long[]::new)))
                .orderBy(DEFAULT_SORT_ORDER_BY)))
            .map(recordToComposedMessageIdWithMetaData(mailboxId));

        if (messageUids.size() <= IN_CLAUSE_MAX_SIZE) {
            return queryPublisherFunction.apply(messageUids);
        } else {
            return Flux.fromIterable(Iterables.partition(messageUids, IN_CLAUSE_MAX_SIZE))
                .flatMap(queryPublisherFunction);
        }
    }


    public Flux<ComposedMessageIdWithMetaData> findAllRecentMessageMetadata(PostgresMailboxId mailboxId) {
        return postgresExecutor.executeRows(dslContext -> Flux.from(dslContext.select()
                .from(TABLE_NAME)
                .where(MAILBOX_ID.eq(mailboxId.asUuid()))
                .and(IS_RECENT.eq(true))
                .orderBy(DEFAULT_SORT_ORDER_BY)))
            .map(recordToComposedMessageIdWithMetaData(mailboxId));
    }

    private Function<Record, ComposedMessageIdWithMetaData> recordToComposedMessageIdWithMetaData(PostgresMailboxId mailboxId) {
        return record -> ComposedMessageIdWithMetaData
            .builder()
            .composedMessageId(new ComposedMessageId(mailboxId,
                PostgresMessageId.Factory.of(record.get(MESSAGE_ID)),
                MessageUid.of(record.get(MESSAGE_UID))))
            .threadId(extractThreadId(record))
            .flags(RECORD_TO_FLAGS_FUNCTION.apply(record))
            .modSeq(ModSeq.of(record.get(MOD_SEQ)))
            .build();
    }

    public Mono<Void> updateFlag(PostgresMailboxId mailboxId, MessageUid uid, UpdatedFlags updatedFlags) {
        return postgresExecutor.executeVoid(dslContext ->
            Mono.from(buildUpdateFlagStatement(dslContext, updatedFlags, mailboxId, uid)));
    }

    public Mono<Flags> listDistinctUserFlags(PostgresMailboxId mailboxId) {
        return postgresExecutor.executeRows(dslContext -> Flux.from(dslContext.selectDistinct(UNNEST_FIELD.apply(USER_FLAGS))
                .from(TABLE_NAME)
                .where(MAILBOX_ID.eq(mailboxId.asUuid()))))
            .map(record -> record.get(0, String.class))
            .collectList()
            .map(flagList -> {
                Flags flags = new Flags();
                flagList.forEach(flags::add);
                return flags;
            });
    }

    private UpdateConditionStep<Record> buildUpdateFlagStatement(DSLContext dslContext, UpdatedFlags updatedFlags,
                                                                 PostgresMailboxId mailboxId, MessageUid uid) {
        return Optional.of(dslContext.update(TABLE_NAME))
            .map(updateStatement -> {
                if (updatedFlags.isChanged(Flag.ANSWERED)) {
                    return updateStatement.set(IS_ANSWERED, updatedFlags.isModifiedToSet(Flag.ANSWERED));
                } else {
                    return updateStatement;
                }
            }).map(updateStatement -> {
                if (updatedFlags.isChanged(Flag.DELETED)) {
                    return updateStatement.set(IS_DELETED, updatedFlags.isModifiedToSet(Flag.DELETED));
                } else {
                    return updateStatement;
                }
            }).map(updateStatement -> {
                if (updatedFlags.isChanged(Flag.DRAFT)) {
                    return updateStatement.set(IS_DRAFT, updatedFlags.isModifiedToSet(Flag.DRAFT));
                } else {
                    return updateStatement;
                }
            }).map(updateStatement -> {
                if (updatedFlags.isChanged(Flag.FLAGGED)) {
                    return updateStatement.set(IS_FLAGGED, updatedFlags.isModifiedToSet(Flag.FLAGGED));
                } else {
                    return updateStatement;
                }
            }).map(updateStatement -> {
                if (updatedFlags.isChanged(Flag.RECENT)) {
                    return updateStatement.set(IS_RECENT, updatedFlags.getNewFlags().contains(Flag.RECENT));
                } else {
                    return updateStatement;
                }
            }).map(updateStatement -> {
                if (updatedFlags.isChanged(Flag.SEEN)) {
                    return updateStatement.set(IS_SEEN, updatedFlags.isModifiedToSet(Flag.SEEN));
                } else {
                    return updateStatement;
                }
            }).map(updateStatement -> updateStatement.set(USER_FLAGS, updatedFlags.getNewFlags().getUserFlags()))
            .map(updateStatement -> updateStatement.where(MAILBOX_ID.eq(mailboxId.asUuid()))
                .and(MESSAGE_UID.eq(uid.asLong())))
            .get();
    }


    private SimpleMailboxMessage.Builder toMailboxMessage(Record record) {
        return SimpleMailboxMessage.builder()
            .messageId(PostgresMessageId.Factory.of(record.get(MESSAGE_ID)))
            .mailboxId(PostgresMailboxId.of(record.get(MAILBOX_ID)))
            .uid(MessageUid.of(record.get(MESSAGE_UID)))
            .threadId(extractThreadId(record))
            .internalDate(LOCAL_DATE_TIME_DATE_FUNCTION.apply(record.get(MessageTable.INTERNAL_DATE, LocalDateTime.class)))
            .saveDate(LOCAL_DATE_TIME_DATE_FUNCTION.apply(record.get(SAVE_DATE, LocalDateTime.class)))
            .flags(RECORD_TO_FLAGS_FUNCTION.apply(record))
            .size(record.get(MessageTable.SIZE))
            .bodyStartOctet(record.get(BODY_START_OCTET))
            .content(BYTE_TO_CONTENT_FUNCTION.apply(record.get(HEADER_CONTENT)))
            .properties(extractProperties(record));
    }

    private Properties extractProperties(Record record) {
        PropertyBuilder property = new PropertyBuilder();

        property.setMediaType(record.get(MessageTable.MIME_TYPE));
        property.setSubType(record.get(MessageTable.MIME_SUBTYPE));
        property.setTextualLineCount(Optional.ofNullable(record.get(MessageTable.TEXTUAL_LINE_COUNT))
            .map(Long::valueOf)
            .orElse(null));
        return property.build();
    }

    private ThreadId extractThreadId(Record record) {
        return Optional.ofNullable(record.get(THREAD_ID))
            .map(threadIdAsUuid -> ThreadId.fromBaseMessageId(PostgresMessageId.Factory.of(threadIdAsUuid)))
            .orElse(ThreadId.fromBaseMessageId(PostgresMessageId.Factory.of(record.get(MESSAGE_ID))));
    }

    public Mono<Void> insert(MailboxMessage mailboxMessage) {
        return postgresExecutor.executeVoid(dslContext -> Mono.from(dslContext.insertInto(TABLE_NAME)
            .set(MAILBOX_ID, ((PostgresMailboxId) mailboxMessage.getMailboxId()).asUuid())
            .set(MESSAGE_UID, mailboxMessage.getUid().asLong())
            .set(MOD_SEQ, mailboxMessage.getModSeq().asLong())
            .set(MESSAGE_ID, ((PostgresMessageId) mailboxMessage.getMessageId()).asUuid())
            .set(IS_DELETED, mailboxMessage.isDeleted())
            .set(IS_ANSWERED, mailboxMessage.isAnswered())
            .set(IS_DRAFT, mailboxMessage.isDraft())
            .set(IS_FLAGGED, mailboxMessage.isFlagged())
            .set(IS_RECENT, mailboxMessage.isRecent())
            .set(IS_SEEN, mailboxMessage.isSeen())
            .set(USER_FLAGS, mailboxMessage.createFlags().getUserFlags())
            .set(SAVE_DATE, mailboxMessage.getSaveDate().map(DATE_TO_LOCAL_DATE_TIME).orElse(null))));
    }

}
