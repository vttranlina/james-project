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

public class KeyDerivationUtil {

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

}