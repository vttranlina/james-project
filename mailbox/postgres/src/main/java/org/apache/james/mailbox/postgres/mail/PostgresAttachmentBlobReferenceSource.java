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

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.apache.james.backends.postgres.utils.PostgresExecutor;
import org.apache.james.blob.api.BlobId;
import org.apache.james.blob.api.BlobReferenceSource;
import org.apache.james.mailbox.postgres.mail.dao.PostgresAttachmentDAO;

import reactor.core.publisher.Flux;

public class PostgresAttachmentBlobReferenceSource implements BlobReferenceSource {

    private final PostgresAttachmentDAO postgresAttachmentDAO;

    @Inject
    @Singleton
    public PostgresAttachmentBlobReferenceSource(@Named(PostgresExecutor.BY_PASS_RLS_INJECT) PostgresExecutor postgresExecutor,
                                                 BlobId.Factory bloIdFactory) {
        this(new PostgresAttachmentDAO(postgresExecutor, bloIdFactory));
    }

    public PostgresAttachmentBlobReferenceSource(PostgresAttachmentDAO postgresAttachmentDAO) {
        this.postgresAttachmentDAO = postgresAttachmentDAO;
    }

    @Override
    public Flux<BlobId> listReferencedBlobs() {
        return postgresAttachmentDAO.listBlobs();
    }

}