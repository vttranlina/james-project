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

package org.apache.james.blob.objectstorage.aws;

import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.apache.james.blob.api.BlobId;
import org.apache.james.blob.api.BucketName;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.core.BytesWrapper;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class KeyDerivationUtil {

    private final S3AsyncClient client;
    private final S3BlobStoreConfiguration s3Configuration;
    private final BlobId.Factory blobIdFactory;

    public KeyDerivationUtil(S3AsyncClient client, S3BlobStoreConfiguration s3Configuration, BlobId.Factory blobIdFactory) {
        this.client = client;
        this.s3Configuration = s3Configuration;
        this.blobIdFactory = blobIdFactory;
    }

    // Derive a key from the master key and salt
    public static String deriveKey(String masterKey, String salt) throws Exception {
        char[] masterKeyChars = masterKey.toCharArray();
        byte[] saltAsByte = salt.getBytes(); // BlobId is used as the saltAsByte

        // Using PBKDF2 with HMAC-SHA-256
        PBEKeySpec spec = new PBEKeySpec(masterKeyChars, saltAsByte, 65536, 256); // 65536 iterations, 256-bit key
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] derivedKey = skf.generateSecret(spec).getEncoded();

        // Encode the derived key to Base64 for use in requests
        return Base64.getEncoder().encodeToString(derivedKey);
    }

    public Mono<Void> save(BucketName bucketName, String objectKey, String customerKey, byte[] data) {
        PutObjectRequest putRequest = PutObjectRequest.builder()
            .bucket(bucketName.asString())
            .key(objectKey)
            .sseCustomerAlgorithm("AES256")
            .sseCustomerKey(customerKey)
            .build();

        return Mono.fromFuture(() ->
                client.putObject(putRequest,
                    AsyncRequestBody.fromBytes(data)))
            .publishOn(Schedulers.parallel())
            .then();
    }

    public Mono<byte[]> readBytes(BucketName bucketName, String objectKey, String customerKey) {
        GetObjectRequest getRequest = GetObjectRequest.builder()
            .bucket(bucketName.asString())
            .key(objectKey)
            .sseCustomerAlgorithm("AES256")
            .sseCustomerKey(customerKey)
            .build();

        return Mono.fromFuture(() ->
                client.getObject(getRequest,
                    new MinimalCopyBytesResponseTransformer(s3Configuration, blobIdFactory.of(objectKey))))
            .publishOn(Schedulers.parallel())
            .map(BytesWrapper::asByteArrayUnsafe)
            .onErrorMap(e -> e.getCause() instanceof OutOfMemoryError, Throwable::getCause);
    }
}