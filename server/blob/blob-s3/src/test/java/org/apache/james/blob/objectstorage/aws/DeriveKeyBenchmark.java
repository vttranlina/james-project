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

import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
public class DeriveKeyBenchmark {

    public static final String ALGORITHM = "PBKDF2WithHmacSHA512";
    public static final int ITERATION_COUNT = 65536;
    public static final int KEY_LENGTH = 256;

    @Setup
    public void setup() {
        System.out.println();
        System.out.println("ALGORITHM: " + ALGORITHM);
        System.out.println("ITERATION_COUNT: " + ITERATION_COUNT);
        System.out.println("KEY_LENGTH: " + KEY_LENGTH);
    }

    @Benchmark
    public void testDeriveKey() throws Exception {
        String masterKey = generateRandomString(16);
        String salt = generateRandomString(16);
        deriveKey(masterKey, salt);
    }

    public static String deriveKey(String masterKey, String salt) throws Exception {
        char[] masterKeyChars = masterKey.toCharArray();
        byte[] saltAsByte = salt.getBytes();

        PBEKeySpec spec = new PBEKeySpec(masterKeyChars, saltAsByte, ITERATION_COUNT, KEY_LENGTH);
        SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGORITHM);
        byte[] derivedKey = skf.generateSecret(spec).getEncoded();

        return Base64.getEncoder().encodeToString(derivedKey);
    }

    private static String generateRandomString(int length) {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
            .include(DeriveKeyBenchmark.class.getSimpleName())
            .forks(1)
            .build();
        new Runner(opt).run();
    }
}
