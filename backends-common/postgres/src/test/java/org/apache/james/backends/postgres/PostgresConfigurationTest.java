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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

class PostgresConfigurationTest {

    @Test
    void shouldThrowWhenMissingPostgresURI() {
        assertThatThrownBy(() -> PostgresConfiguration.builder()
            .build())
            .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("You need to specify Postgres URI");
    }

    @Test
    void shouldThrowWhenInvalidURI() {
        assertThatThrownBy(() -> PostgresConfiguration.builder()
            .url(":invalid")
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("You need to specify a valid Postgres URI");
    }

    @Test
    void shouldThrowWhenURIMissingCredential() {
        assertThatThrownBy(() -> PostgresConfiguration.builder()
            .url("postgresql://localhost:5432")
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Postgres URI need to contains user credential");
    }

    @Test
    void shouldParseValidURI() {
        PostgresConfiguration configuration = PostgresConfiguration.builder()
            .url("postgresql://username:password@postgreshost:5672")
            .build();

        assertThat(configuration.getUri().getHost()).isEqualTo("postgreshost");
        assertThat(configuration.getUri().getPort()).isEqualTo(5672);
        assertThat(configuration.getCredential().getUsername()).isEqualTo("username");
        assertThat(configuration.getCredential().getPassword()).isEqualTo("password");
    }

    @Test
    void rowLevelSecurityShouldBeDisabledByDefault() {
        PostgresConfiguration configuration = PostgresConfiguration.builder()
            .url("postgresql://username:password@postgreshost:5672")
            .build();

        assertThat(configuration.rowLevelSecurityEnabled()).isFalse();
    }

    @Test
    void databaseNameShouldFallbackToDefaultWhenNotSet() {
        PostgresConfiguration configuration = PostgresConfiguration.builder()
            .url("postgresql://username:password@postgreshost:5672")
            .build();

        assertThat(configuration.getDatabaseName()).isEqualTo("postgres");
    }

    @Test
    void databaseSchemaShouldFallbackToDefaultWhenNotSet() {
        PostgresConfiguration configuration = PostgresConfiguration.builder()
            .url("postgresql://username:password@postgreshost:5672")
            .build();

        assertThat(configuration.getDatabaseSchema()).isEqualTo("public");
    }

    @Test
    void shouldReturnCorrespondingProperties() {
        PostgresConfiguration configuration = PostgresConfiguration.builder()
            .url("postgresql://username:password@postgreshost:5672")
            .rowLevelSecurityEnabled()
            .databaseName("databaseName")
            .databaseSchema("databaseSchema")
            .build();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(configuration.rowLevelSecurityEnabled()).isEqualTo(true);
            softly.assertThat(configuration.getDatabaseName()).isEqualTo("databaseName");
            softly.assertThat(configuration.getDatabaseSchema()).isEqualTo("databaseSchema");
        });
    }
}
