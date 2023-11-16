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

import static org.apache.james.mailbox.postgres.mail.PostgresMailboxModule.PostgresMailboxTable.MAILBOX_ACL;
import static org.apache.james.mailbox.postgres.mail.PostgresMailboxModule.PostgresMailboxTable.MAILBOX_ID;
import static org.apache.james.mailbox.postgres.mail.PostgresMailboxModule.PostgresMailboxTable.MAILBOX_NAME;
import static org.apache.james.mailbox.postgres.mail.PostgresMailboxModule.PostgresMailboxTable.MAILBOX_NAMESPACE;
import static org.apache.james.mailbox.postgres.mail.PostgresMailboxModule.PostgresMailboxTable.MAILBOX_UID_VALIDITY;
import static org.apache.james.mailbox.postgres.mail.PostgresMailboxModule.PostgresMailboxTable.TABLE_NAME;
import static org.apache.james.mailbox.postgres.mail.PostgresMailboxModule.PostgresMailboxTable.USER_NAME;
import static org.jooq.impl.DSL.count;

import java.util.function.Function;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.james.backends.postgres.utils.PostgresExecutor;
import org.apache.james.core.Username;
import org.apache.james.mailbox.exception.MailboxExistsException;
import org.apache.james.mailbox.exception.MailboxNotFoundException;
import org.apache.james.mailbox.model.Mailbox;
import org.apache.james.mailbox.model.MailboxACL;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.model.UidValidity;
import org.apache.james.mailbox.model.search.MailboxQuery;
import org.apache.james.mailbox.postgres.PostgresMailboxId;
import org.apache.james.mailbox.store.MailboxExpressionBackwardCompatibility;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;
import org.jooq.postgres.extensions.types.Hstore;

import com.github.fge.lambdas.Throwing;
import com.google.common.base.Preconditions;

import io.r2dbc.spi.Result;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class PostgresMailboxDAO {
    private static final char SQL_WILDCARD_CHAR = '%';
    private static final String DUPLICATE_VIOLATION_MESSAGE = "duplicate key value violates unique constraint";
    private final Function<MailboxACL, Hstore> MAILBOX_ACL_TO_HSTORE_FUNCTION = acl -> Hstore.hstore(acl.getEntries()
        .entrySet()
        .stream()
        .collect(Collectors.toMap(
            entry -> entry.getKey().serialize(),
            entry -> entry.getValue().serialize())));

    private final Function<Hstore, MailboxACL> HSTORE_TO_MAILBOX_ACL_FUNCTION = hstore -> new MailboxACL(hstore.data()
        .entrySet()
        .stream()
        .collect(Collectors.toMap(
            entry -> MailboxACL.EntryKey.deserialize(entry.getKey()),
            Throwing.function(entry -> MailboxACL.Rfc4314Rights.deserialize(entry.getValue())))));

    private final PostgresExecutor postgresExecutor;

    @Inject
    public PostgresMailboxDAO(PostgresExecutor postgresExecutor) {
        this.postgresExecutor = postgresExecutor;
    }

    public Mono<Mailbox> create(MailboxPath mailboxPath, UidValidity uidValidity) {
        final PostgresMailboxId mailboxId = PostgresMailboxId.generate();

        return postgresExecutor.executeVoid(dslContext -> Mono.from(dslContext.insertInto(TABLE_NAME, MAILBOX_ID, MAILBOX_NAME, USER_NAME, MAILBOX_NAMESPACE, MAILBOX_UID_VALIDITY)
                .values(mailboxId.asUuid(), mailboxPath.getName(), mailboxPath.getUser().asString(), mailboxPath.getNamespace(), uidValidity.asLong())))
            .thenReturn(new Mailbox(mailboxPath, uidValidity, mailboxId))
            .onErrorMap(e -> e instanceof DataAccessException && e.getMessage().contains(DUPLICATE_VIOLATION_MESSAGE),
                e -> new MailboxExistsException(mailboxPath.getName()));
    }

    public Mono<MailboxId> rename(Mailbox mailbox) {
        Preconditions.checkNotNull(mailbox.getMailboxId(), "A mailbox we want to rename should have a defined mailboxId");

        return findMailboxByPath(mailbox.generateAssociatedPath())
            .flatMap(m -> Mono.error(new MailboxExistsException(mailbox.getName())))
            .then(update(mailbox));
    }

    private Mono<MailboxId> update(Mailbox mailbox) {
        return postgresExecutor.executeRow(dslContext -> Mono.from(dslContext.update(TABLE_NAME)
                .set(MAILBOX_NAME, mailbox.getName())
                .set(USER_NAME, mailbox.getUser().asString())
                .set(MAILBOX_NAMESPACE, mailbox.getNamespace())
                .where(MAILBOX_ID.eq(((PostgresMailboxId) mailbox.getMailboxId()).asUuid()))
                .returning(MAILBOX_ID)))
            .map(record -> mailbox.getMailboxId())
            .switchIfEmpty(Mono.error(new MailboxNotFoundException(mailbox.getMailboxId())));
    }


    public Mono<MailboxACL> getACL(PostgresMailboxId mailboxId) {
        return postgresExecutor.dslContext()
            .flatMap(dsl -> Mono.from(dsl.select(MAILBOX_ACL)
                .from(TABLE_NAME)
                .where(MAILBOX_ID.eq(mailboxId.asUuid()))))
            .map(record -> record.get(MAILBOX_ACL))
            .map(HSTORE_TO_MAILBOX_ACL_FUNCTION);
    }

    public Mono<Void> upsertACL(PostgresMailboxId mailboxId, MailboxACL acl) {
        return postgresExecutor.executeVoid(dslContext -> Mono.from(dslContext.update(TABLE_NAME)
            .set(MAILBOX_ACL, MAILBOX_ACL_TO_HSTORE_FUNCTION.apply(acl))
            .where(MAILBOX_ID.eq(mailboxId.asUuid()))));
    }

    public Mono<MailboxACL.Rfc4314Rights> getRightByMailboxIdAndEntryKey(PostgresMailboxId mailboxId, MailboxACL.EntryKey entryKey) {
        return postgresExecutor.connection()
            .flatMap(con -> Mono.from(con.createStatement("select mailbox_acl-> $1 as acl_right from mailbox where mailbox_id = $2")
                    .bind("$1", entryKey.serialize())
                    .bind("$2", mailboxId.asUuid())
                    .execute())
                .flatMapMany(result -> result.map((row, rowMetadata) -> row.get(0, String.class)))
                .last())
            .map(Throwing.function(MailboxACL.Rfc4314Rights::deserialize));
    }

    public Mono<Void> upsertMailboxACLRight(PostgresMailboxId mailboxId, MailboxACL.EntryKey entryKey, MailboxACL.Rfc4314Rights rights) {
        return postgresExecutor.connection()
            .flatMapMany(con -> con.createStatement("update mailbox set mailbox_acl[$1] = $2 where mailbox_id = $3")
                .bind("$1", entryKey.serialize())
                .bind("$2", rights.serialize())
                .bind("$3", mailboxId.asUuid())
                .execute())
            .flatMap(Result::getRowsUpdated)
            .then();
    }

    public Mono<Void> delete(MailboxId mailboxId) {
        return postgresExecutor.executeVoid(dslContext -> Mono.from(dslContext.deleteFrom(TABLE_NAME)
            .where(MAILBOX_ID.eq(((PostgresMailboxId) mailboxId).asUuid()))));
    }

    public Mono<Mailbox> findMailboxByPath(MailboxPath mailboxPath) {
        return postgresExecutor.executeRow(dsl -> Mono.from(dsl.selectFrom(TABLE_NAME)
            .where(MAILBOX_NAME.eq(mailboxPath.getName())
                .and(USER_NAME.eq(mailboxPath.getUser().asString()))
                .and(MAILBOX_NAMESPACE.eq(mailboxPath.getNamespace())))))
            .map(this::asMailbox);
    }

    public Mono<Mailbox> findMailboxById(MailboxId id) {
        return postgresExecutor.executeRow(dsl -> Mono.from(dsl.selectFrom(TABLE_NAME)
            .where(MAILBOX_ID.eq(((PostgresMailboxId) id).asUuid()))))
            .map(this::asMailbox)
            .switchIfEmpty(Mono.error(new MailboxNotFoundException(id)));
    }

    public Flux<Mailbox> findMailboxWithPathLike(MailboxQuery.UserBound query) {
        String pathLike = MailboxExpressionBackwardCompatibility.getPathLike(query);

        return postgresExecutor.executeRows(dsl -> Flux.from(dsl.selectFrom(TABLE_NAME)
            .where(MAILBOX_NAME.like(pathLike)
                .and(USER_NAME.eq(query.getFixedUser().asString()))
                .and(MAILBOX_NAMESPACE.eq(query.getFixedNamespace())))))
            .map(this::asMailbox)
            .filter(query::matches);
    }

    public Mono<Boolean> hasChildren(Mailbox mailbox, char delimiter) {
        String name = mailbox.getName() + delimiter + SQL_WILDCARD_CHAR;

        return postgresExecutor.executeRows(dsl -> Flux.from(dsl.select(count()).from(TABLE_NAME)
                .where(MAILBOX_NAME.like(name)
                    .and(USER_NAME.eq(mailbox.getUser().asString()))
                    .and(MAILBOX_NAMESPACE.eq(mailbox.getNamespace())))))
            .map(record -> record.get(0, Integer.class))
            .filter(count -> count > 0)
            .hasElements();
    }

    public Flux<Mailbox> getAll() {
        return postgresExecutor.executeRows(dsl -> Flux.from(dsl.selectFrom(TABLE_NAME)))
            .map(this::asMailbox);
    }

    private Mailbox asMailbox(Record record) {
        return new Mailbox(new MailboxPath(record.get(MAILBOX_NAMESPACE), Username.of(record.get(USER_NAME)), record.get(MAILBOX_NAME)),
            UidValidity.of(record.get(MAILBOX_UID_VALIDITY)), PostgresMailboxId.of(record.get(MAILBOX_ID)));
    }
}
