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

package org.apache.james.webadmin.routes;


import jakarta.inject.Inject;

import org.apache.james.mailbox.cassandra.mail.task.SolveMailboxFlagInconsistenciesService;
import org.apache.james.mailbox.cassandra.mail.task.SolveMailboxFlagInconsistenciesService.TargetFlag;
import org.apache.james.mailbox.cassandra.mail.task.SolveMailboxFlagInconsistencyTask;
import org.apache.james.webadmin.tasks.TaskFromRequestRegistry;
import org.apache.james.webadmin.tasks.TaskRegistrationKey;

public class SolveMessageDeletedInconsistenciesRequestToTask extends TaskFromRequestRegistry.TaskRegistration {
    private static final TaskRegistrationKey REGISTRATION_KEY = TaskRegistrationKey.of("SolveMessageDeletedInconsistencies");

    @Inject
    public SolveMessageDeletedInconsistenciesRequestToTask(SolveMailboxFlagInconsistenciesService service) {
        super(REGISTRATION_KEY, request -> toTask(service));
    }

    private static SolveMailboxFlagInconsistencyTask toTask(SolveMailboxFlagInconsistenciesService service) {
        return new SolveMailboxFlagInconsistencyTask(service, TargetFlag.DELETED);
    }
}