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

package org.apache.james.jmap.mailet;

import org.apache.james.mdn.MDN;
import org.apache.james.mdn.MDN.MDNParseContentTypeException;
import org.apache.james.mdn.MDN.MDNParseException;
import org.apache.james.mdn.MDNReport;
import org.apache.james.mdn.action.mode.DispositionActionMode;
import org.apache.james.mdn.fields.*;
import org.apache.james.mdn.modifier.DispositionModifier;
import org.apache.james.mdn.sending.mode.DispositionSendingMode;
import org.apache.james.mdn.type.DispositionType;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.message.BodyPart;
import org.apache.james.mime4j.message.BodyPartBuilder;
import org.apache.james.mime4j.message.MultipartBuilder;
import org.apache.james.mime4j.message.SingleBodyBuilder;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

public class ExtractMDNOriginalJMAPMessageIdTest {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void extractReportShouldRejectNonMultipartMessage() throws Exception {
        thrown.expect(MDNParseContentTypeException.class);
        thrown.expectMessage("MDN Message must be multipart");
        Message message = Message.Builder.of()
                .setBody("content", StandardCharsets.UTF_8)
                .build();
        MDN.parse(message);
    }

    @Test
    public void extractReportShouldRejectMultipartWithSinglePart() throws Exception {
        thrown.expect(MDN.MDNParseBodyPartInvalidException.class);
        thrown.expectMessage("MDN Message must contain at least two parts");
        Message message = Message.Builder.of()
                .setBody(
                        MultipartBuilder.create()
                                .setSubType("report")
                                .addTextPart("content", StandardCharsets.UTF_8)
                                .build())
                .build();
        MDN.parse(message);
    }

    @Test
    public void extractReportShouldRejectSecondPartWithBadContentType() throws Exception {
        thrown.expect(MDNParseException.class);
        thrown.expectMessage("MDN can not extract");
        Message message = Message.Builder.of()
                .setBody(MultipartBuilder.create()
                        .setSubType("report")
                        .addTextPart("first", StandardCharsets.UTF_8)
                        .addTextPart("second", StandardCharsets.UTF_8)
                        .build())
                .build();
        MDN.parse(message);
    }

    @Test
    public void extractReportShouldExtractMDNWhenValidMDN() throws Exception {
        BodyPart mdnBodyPart = BodyPartBuilder
                .create()
                .setBody(SingleBodyBuilder.create()
                        .setText(
                                "Reporting-UA: UA_name; UA_product\r\n" +
                                        "MDN-Gateway: rfc822; apache.org\r\n" +
                                        "Original-Recipient: rfc822; originalRecipient\r\n" +
                                        "Final-Recipient: rfc822; final_recipient\r\n" +
                                        "Original-Message-ID: <original@message.id>\r\n" +
                                        "Disposition: automatic-action/MDN-sent-automatically;processed/error,failed\r\n" +
                                        "Error: Message1\r\n" +
                                        "Error: Message2\r\n"
                                                .replace(System.lineSeparator(), "\r\n"))
                        .buildText())
                .setContentType("message/disposition-notification")
                .build();

        Message message = Message.Builder.of()
                .setBody(MultipartBuilder.create("report")
                        .addTextPart("first", StandardCharsets.UTF_8)
                        .addBodyPart(mdnBodyPart)
                        .build())
                .build();
        var mdnActual = MDN.parse(message);
        var mdnReportExpect = MDNReport.builder()
                .reportingUserAgentField(ReportingUserAgent.builder()
                        .userAgentName("UA_name")
                        .userAgentProduct("UA_product")
                        .build())
                .gatewayField(Gateway.builder()
                        .nameType(AddressType.RFC_822)
                        .name(Text.fromRawText("apache.org"))
                        .build())
                .originalRecipientField(OriginalRecipient.builder()
                        .originalRecipient(Text.fromRawText("originalRecipient"))
                        .addressType(AddressType.RFC_822)
                        .build())
                .finalRecipientField(FinalRecipient.builder()
                        .finalRecipient(Text.fromRawText("final_recipient"))
                        .addressType(AddressType.RFC_822)
                        .build())
                .originalMessageIdField("<original@message.id>")
                .dispositionField(Disposition.builder()
                        .actionMode(DispositionActionMode.Automatic)
                        .sendingMode(DispositionSendingMode.Automatic)
                        .type(DispositionType.Processed)
                        .addModifier(DispositionModifier.Error)
                        .addModifier(DispositionModifier.Failed)
                        .build())
                .addErrorField("Message1")
                .addErrorField("Message2")
                .build();

        Assert.assertEquals(mdnActual.getReport(), mdnReportExpect);
        Assert.assertEquals(mdnActual.getHumanReadableText(), "first");
    }
}