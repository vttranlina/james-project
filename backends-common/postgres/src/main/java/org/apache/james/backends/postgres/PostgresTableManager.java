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

package org.apache.james.backends.postgres;

import javax.inject.Inject;

import org.apache.james.backends.postgres.utils.PostgresExecutor;
import org.apache.james.lifecycle.api.Startable;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import io.r2dbc.spi.Result;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class PostgresTableManager implements Startable {
    public static final int INITIALIZATION_PRIORITY = 1;
    private static final Logger LOGGER = LoggerFactory.getLogger(PostgresTableManager.class);
    private final PostgresExecutor postgresExecutor;
    private final PostgresModule module;
    private final boolean rowLevelSecurityEnabled;

    @Inject
    public PostgresTableManager(PostgresExecutor postgresExecutor,
                                PostgresModule module,
                                PostgresConfiguration postgresConfiguration) {
        this.postgresExecutor = postgresExecutor;
        this.module = module;
        this.rowLevelSecurityEnabled = postgresConfiguration.rowLevelSecurityEnabled();
    }

    @VisibleForTesting
    public PostgresTableManager(PostgresExecutor postgresExecutor, PostgresModule module, boolean rowLevelSecurityEnabled) {
        this.postgresExecutor = postgresExecutor;
        this.module = module;
        this.rowLevelSecurityEnabled = rowLevelSecurityEnabled;
    }

    public void initPostgres() {
        initializePostgresExtension()
            .then(initializeTables())
            .then(initializeTableIndexes())
            .block();
    }

    public Mono<Void> initializePostgresExtension() {
        return postgresExecutor.connection()
            .flatMapMany(connection -> connection.createStatement("CREATE EXTENSION IF NOT EXISTS hstore")
                .execute())
            .flatMap(Result::getRowsUpdated)
            .then();
    }

    public Mono<Void> initializeTables() {
        return postgresExecutor.dslContext()
            .flatMap(dsl -> Flux.fromIterable(module.tables())
                .flatMap(table -> Mono.from(table.getCreateTableStepFunction().apply(dsl))
                    .then(alterTableIfNeeded(table))
                    .doOnSuccess(any -> LOGGER.info("Table {} created", table.getName()))
                    .onErrorResume(exception -> handleTableCreationException(table, exception)))
                .then());
    }

    private Mono<Void> handleTableCreationException(PostgresTable table, Throwable e) {
        if (e instanceof DataAccessException && e.getMessage().contains(String.format("\"%s\" already exists", table.getName()))) {
            return Mono.empty();
        }
        LOGGER.error("Error while creating table: {}", table.getName(), e);
        return Mono.error(e);
    }

    private Mono<Void> alterTableIfNeeded(PostgresTable table) {
        return executeAdditionalAlterQueries(table)
            .then(enableRLSIfNeeded(table));
    }

    private Mono<Void> executeAdditionalAlterQueries(PostgresTable table) {
        return Flux.fromIterable(table.getAdditionalAlterQueries())
            .concatMap(alterSQLQuery -> postgresExecutor.connection()
                .flatMapMany(connection -> connection.createStatement(alterSQLQuery)
                    .execute())
                .flatMap(Result::getRowsUpdated)
                .then()
                .onErrorResume(e -> {
                    if (e.getMessage().contains("already exists")) {
                        return Mono.empty();
                    }
                    LOGGER.error("Error while executing ALTER query for table {}", table.getName(), e);
                    return Mono.error(e);
                }))
            .then();
    }

    private Mono<Void> enableRLSIfNeeded(PostgresTable table) {
        if (rowLevelSecurityEnabled && table.supportsRowLevelSecurity()) {
            return alterTableEnableRLS(table);
        }
        return Mono.empty();
    }

    public Mono<Void> alterTableEnableRLS(PostgresTable table) {
        return postgresExecutor.connection()
            .flatMapMany(connection -> connection.createStatement(rowLevelSecurityAlterStatement(table.getName()))
                .execute())
            .flatMap(Result::getRowsUpdated)
            .then();
    }

    private String rowLevelSecurityAlterStatement(String tableName) {
        String policyName = "domain_" + tableName + "_policy";
        return "set app.current_domain = ''; alter table " + tableName + " add column if not exists domain varchar(255) not null default current_setting('app.current_domain')::text ;" +
            "do $$ \n" +
            "begin \n" +
            "    if not  exists( select policyname from pg_policies where policyname = '" + policyName + "') then \n" +
            "        execute 'alter table " + tableName + " enable row level security; alter table " + tableName + " force row level security; create policy " + policyName + " on " + tableName + " using (domain = current_setting(''app.current_domain'')::text)';\n" +
            "    end if;\n" +
            "end $$;";
    }

    public Mono<Void> truncate() {
        return postgresExecutor.dslContext()
            .flatMap(dsl -> Flux.fromIterable(module.tables())
                .flatMap(table -> Mono.from(dsl.truncateTable(table.getName()))
                    .doOnSuccess(any -> LOGGER.info("Table {} truncated", table.getName()))
                    .doOnError(e -> LOGGER.error("Error while truncating table {}", table.getName(), e)))
                .then());
    }

    public Mono<Void> initializeTableIndexes() {
        return postgresExecutor.dslContext()
            .flatMap(dsl -> Flux.fromIterable(module.tableIndexes())
                .concatMap(index -> Mono.from(index.getCreateIndexStepFunction().apply(dsl))
                    .doOnSuccess(any -> LOGGER.info("Index {} created", index.getName()))
                    .onErrorResume(e -> handleIndexCreationException(index, e)))
                .then());
    }

    private Mono<? extends Integer> handleIndexCreationException(PostgresIndex index, Throwable e) {
        if (e instanceof DataAccessException && e.getMessage().contains(String.format("\"%s\" already exists", index.getName()))) {
            return Mono.empty();
        }
        LOGGER.error("Error while creating index {}", index.getName(), e);
        return Mono.error(e);
    }

}
