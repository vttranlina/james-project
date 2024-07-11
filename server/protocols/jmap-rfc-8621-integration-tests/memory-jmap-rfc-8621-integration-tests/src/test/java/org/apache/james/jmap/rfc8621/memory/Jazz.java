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

package org.apache.james.jmap.rfc8621.memory;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import javax.mail.internet.InternetAddress;

import org.apache.james.core.MailAddress;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.berkeley.cs.jqf.fuzz.Fuzz;
import edu.berkeley.cs.jqf.fuzz.JQF;

@RunWith(JQF.class)
public class Jazz {
    private static final Logger logger = LoggerFactory.getLogger(Jazz.class);

    @Fuzz
    public void test(String str) {
        if (str == null) {
            return;
        }

        MailAddress m;
        try {
            m = new MailAddress(str);
        } catch (jakarta.mail.internet.AddressException e) {
            return;
        }

        try {
            new InternetAddress(str);
        } catch (Exception e) {
            if (e.getMessage().contains("contains illegal character")) {
                logger.error("_________Invalid InternetAddress with illegal character: {}", str);
                assertThat(e.getMessage()).isEqualTo("FAILED");
            }
        }
    }
}