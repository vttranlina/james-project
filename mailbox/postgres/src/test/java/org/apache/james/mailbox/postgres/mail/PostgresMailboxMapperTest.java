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

package org.apache.james.mailbox.postgres.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.james.backends.postgres.PostgresExtension;
import org.apache.james.core.Username;
import org.apache.james.mailbox.model.Mailbox;
import org.apache.james.mailbox.model.MailboxACL;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.mailbox.model.UidValidity;
import org.apache.james.mailbox.postgres.PostgresMailboxId;
import org.apache.james.mailbox.postgres.mail.dao.PostgresMailboxDAO;
import org.apache.james.mailbox.store.mail.MailboxMapper;
import org.apache.james.mailbox.store.mail.model.MailboxMapperTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class PostgresMailboxMapperTest extends MailboxMapperTest {
    @RegisterExtension
    static PostgresExtension postgresExtension = PostgresExtension.withoutRowLevelSecurity(PostgresMailboxModule.MODULE);

    @Override
    protected MailboxMapper createMailboxMapper() {
        return new PostgresMailboxMapper(new PostgresMailboxDAO(postgresExtension.getPostgresExecutor()));
    }

    @Override
    protected MailboxId generateId() {
        return PostgresMailboxId.generate();
    }

    private PostgresMailboxDAO dao;

    @BeforeEach
    void beforeEach() {
        dao = new PostgresMailboxDAO(postgresExtension.getPostgresExecutor());
    }

    @Test
    void upsertACLShouldPersistACL() {
        Mailbox mailbox = mailboxMapper.create(benwaInboxPath, UidValidity.of(42)).block();

        Username user = Username.of("user1");
        Username user2 = Username.of("user2");
        MailboxACL mailboxACL = new MailboxACL(new MailboxACL.Entry(MailboxACL.EntryKey.createUserEntryKey(user), new MailboxACL.Rfc4314Rights(MailboxACL.Right.Administer)),
            new MailboxACL.Entry(MailboxACL.EntryKey.createUserEntryKey(user2), new MailboxACL.Rfc4314Rights(MailboxACL.Right.Read, MailboxACL.Right.Lookup, MailboxACL.Right.Write)));

        dao.upsertACL((PostgresMailboxId) mailbox.getMailboxId(), mailboxACL).block();

        assertThat(dao.getACL((PostgresMailboxId) mailbox.getMailboxId()).block()).isEqualTo(mailboxACL);
    }


    @Test
    void getRightByMailboxIdAndEntryKeyShouldWork() {
        // Given
        Mailbox mailbox = mailboxMapper.create(benwaInboxPath, UidValidity.of(42)).block();
        Username user = Username.of("user1");
        Username user2 = Username.of("user2");
        MailboxACL.EntryKey userEntryKey1 = MailboxACL.EntryKey.createUserEntryKey(user);
        MailboxACL mailboxACL = new MailboxACL(new MailboxACL.Entry(userEntryKey1, new MailboxACL.Rfc4314Rights(MailboxACL.Right.Administer)),
            new MailboxACL.Entry(MailboxACL.EntryKey.createUserEntryKey(user2), new MailboxACL.Rfc4314Rights(MailboxACL.Right.Read, MailboxACL.Right.Lookup, MailboxACL.Right.Write)));
        dao.upsertACL((PostgresMailboxId) mailbox.getMailboxId(), mailboxACL).block();

        // When
        MailboxACL.Rfc4314Rights rights = dao.getRightByMailboxIdAndEntryKey((PostgresMailboxId) mailbox.getMailboxId(), userEntryKey1).block();

        // Then
        assertThat(rights).isEqualTo(new MailboxACL.Rfc4314Rights(MailboxACL.Right.Administer));
    }

    @Test
    void upsertRightShouldWork() {
        // Given
        Mailbox mailbox = mailboxMapper.create(benwaInboxPath, UidValidity.of(42)).block();
        Username user = Username.of("user1");
        Username user2 = Username.of("user2");
        MailboxACL.EntryKey userEntryKey1 = MailboxACL.EntryKey.createUserEntryKey(user);
        MailboxACL mailboxACL = new MailboxACL(new MailboxACL.Entry(userEntryKey1, new MailboxACL.Rfc4314Rights(MailboxACL.Right.Administer)),
            new MailboxACL.Entry(MailboxACL.EntryKey.createUserEntryKey(user2), new MailboxACL.Rfc4314Rights(MailboxACL.Right.Read, MailboxACL.Right.Lookup, MailboxACL.Right.Write)));
        dao.upsertACL((PostgresMailboxId) mailbox.getMailboxId(), mailboxACL).block();

        // When
        MailboxACL.Rfc4314Rights newRight = new MailboxACL.Rfc4314Rights(MailboxACL.Right.Lookup);
        MailboxACL newMailboxACL = dao.upsertMailboxACLRight((PostgresMailboxId) mailbox.getMailboxId(), userEntryKey1, newRight).block();

        // Then
        assertThat(dao.getRightByMailboxIdAndEntryKey((PostgresMailboxId) mailbox.getMailboxId(), userEntryKey1).block())
            .isEqualTo(newRight);

        assertThat(newMailboxACL).isEqualTo(dao.getACL((PostgresMailboxId) mailbox.getMailboxId()).block());
    }

    @Test
    void deleteRightShouldWork(){
        // Given
        Mailbox mailbox = mailboxMapper.create(benwaInboxPath, UidValidity.of(42)).block();
        Username user = Username.of("user1");
        Username user2 = Username.of("user2");
        MailboxACL.EntryKey userEntryKey1 = MailboxACL.EntryKey.createUserEntryKey(user);
        MailboxACL.EntryKey userEntryKey2 = MailboxACL.EntryKey.createUserEntryKey(user2);
        MailboxACL mailboxACL = new MailboxACL(new MailboxACL.Entry(userEntryKey1, new MailboxACL.Rfc4314Rights(MailboxACL.Right.Administer)),
            new MailboxACL.Entry(userEntryKey2, new MailboxACL.Rfc4314Rights(MailboxACL.Right.Read, MailboxACL.Right.Lookup, MailboxACL.Right.Write)));
        dao.upsertACL((PostgresMailboxId) mailbox.getMailboxId(), mailboxACL).block();

        // when
        MailboxACL newMailboxACL = dao.deleteRightByMailboxIdAndEntryKey((PostgresMailboxId) mailbox.getMailboxId(), userEntryKey2).block();

        // Then
        assertThat(newMailboxACL).isEqualTo(dao.getACL((PostgresMailboxId) mailbox.getMailboxId()).block());
        assertThat(newMailboxACL).isEqualTo(new MailboxACL(new MailboxACL.Entry(userEntryKey1, new MailboxACL.Rfc4314Rights(MailboxACL.Right.Administer))));
    }
}
