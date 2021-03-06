/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.orc.impl;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.crypto.key.KeyProvider;
import org.apache.hadoop.crypto.key.KeyProviderCryptoExtension;
import org.apache.hadoop.crypto.key.KeyProviderFactory;
import org.apache.hadoop.crypto.key.kms.KMSClientProvider;
import org.apache.hadoop.io.BytesWritable;
import org.apache.orc.EncryptionAlgorithm;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.security.Key;
import java.sql.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static junit.framework.Assert.assertEquals;

public class TestHadoopShimsPre2_7 {

  @Test(expected = IllegalArgumentException.class)
  public void testFindingUnknownEncryption() {
    KeyProvider.Metadata meta = new KMSClientProvider.KMSMetadata(
        "XXX/CTR/NoPadding", 128, "", new HashMap<String, String>(),
        new Date(0), 1);
    HadoopShimsPre2_7.findAlgorithm(meta);
  }

  @Test
  public void testFindingAesEncryption()  {
    KeyProvider.Metadata meta = new KMSClientProvider.KMSMetadata(
        "AES/CTR/NoPadding", 128, "", new HashMap<String, String>(),
        new Date(0), 1);
    assertEquals(EncryptionAlgorithm.AES_CTR_128,
        HadoopShimsPre2_7.findAlgorithm(meta));
    meta = new KMSClientProvider.KMSMetadata(
        "AES/CTR/NoPadding", 256, "", new HashMap<String, String>(),
        new Date(0), 1);
    assertEquals(EncryptionAlgorithm.AES_CTR_256,
        HadoopShimsPre2_7.findAlgorithm(meta));
    meta = new KMSClientProvider.KMSMetadata(
        "AES/CTR/NoPadding", 512, "", new HashMap<String, String>(),
        new Date(0), 1);
    assertEquals(EncryptionAlgorithm.AES_CTR_256,
        HadoopShimsPre2_7.findAlgorithm(meta));
  }

  @Test
  public void testHadoopKeyProvider() throws IOException {
    HadoopShims shims = new HadoopShimsPre2_7();
    Configuration conf = new Configuration();
    conf.set("hadoop.security.key.provider.path", "test:///");
    // Hard code the random so that we know the bytes that will come out.
    HadoopShims.KeyProvider provider = shims.getKeyProvider(conf, new Random(24));
    List<String> keyNames = provider.getKeyNames();
    assertEquals(2, keyNames.size());
    assertEquals(true, keyNames.contains("pii"));
    assertEquals(true, keyNames.contains("secret"));
    HadoopShims.KeyMetadata piiKey = provider.getCurrentKeyVersion("pii");
    assertEquals(1, piiKey.getVersion());
    LocalKey localKey = provider.createLocalKey(piiKey);
    byte[] encrypted = localKey.getEncryptedKey();
    // make sure that we get exactly what we expect to test the encryption
    assertEquals("c7 ab 4f bb 38 f4 de ad d0 b3 59 e2 21 2a 95 32",
        new BytesWritable(encrypted).toString());
    // now check to make sure that we get the expected bytes back
    assertEquals("c7 a1 d0 41 7b 24 72 44 1a 58 c7 72 4a d4 be b3",
        new BytesWritable(localKey.getDecryptedKey().getEncoded()).toString());
    Key key = provider.decryptLocalKey(piiKey, encrypted);
    assertEquals(new BytesWritable(localKey.getDecryptedKey().getEncoded()).toString(),
        new BytesWritable(key.getEncoded()).toString());
  }

  /**
   * Create a Hadoop KeyProvider that lets us test the interaction
   * with the Hadoop code.
   * Must only be used in unit tests!
   */
  public static class TestKeyProviderFactory extends KeyProviderFactory {

    @Override
    public KeyProvider createProvider(URI uri,
                                      Configuration conf) throws IOException {
      if ("test".equals(uri.getScheme())) {
        KeyProvider provider = new TestKeyProvider(conf);
        // populate a couple keys into the provider
        byte[] piiKey = new byte[]{0,1,2,3,4,5,6,7,8,9,0xa,0xb,0xc,0xd,0xe,0xf};
        KeyProvider.Options aes128 = new KeyProvider.Options(conf);
        provider.createKey("pii", piiKey, aes128);
        byte[] piiKey2 = new byte[]{0x10,0x11,0x12,0x13,0x14,0x15,0x16,0x17,
            0x18,0x19,0x1a,0x1b,0x1c,0x1d,0x1e,0x1f};
        provider.rollNewVersion("pii", piiKey2);
        byte[] secretKey = new byte[]{0x20,0x21,0x22,0x23,0x24,0x25,0x26,0x27,
            0x28,0x29,0x2a,0x2b,0x2c,0x2d,0x2e,0x2f};
        provider.createKey("secret", secretKey, aes128);
        return KeyProviderCryptoExtension.createKeyProviderCryptoExtension(provider);
      }
      return null;
    }
  }

  /**
   * A Hadoop KeyProvider that lets us test the interaction
   * with the Hadoop code.
   * Must only be used in unit tests!
   */
  static class TestKeyProvider extends KeyProvider {
    // map from key name to metadata
    private final Map<String, TestMetadata> keyMetdata = new HashMap<>();
    // map from key version name to material
    private final Map<String, KeyVersion> keyVersions = new HashMap<>();

    public TestKeyProvider(Configuration conf) {
      super(conf);
    }

    @Override
    public KeyVersion getKeyVersion(String name) {
      return keyVersions.get(name);
    }

    @Override
    public List<String> getKeys() {
      return new ArrayList<>(keyMetdata.keySet());
    }

    @Override
    public List<KeyVersion> getKeyVersions(String name) {
      List<KeyVersion> result = new ArrayList<>();
      Metadata meta = getMetadata(name);
      for(int v=0; v < meta.getVersions(); ++v) {
        String versionName = buildVersionName(name, v);
        KeyVersion material = keyVersions.get(versionName);
        if (material != null) {
          result.add(material);
        }
      }
      return result;
    }

    @Override
    public Metadata getMetadata(String name)  {
      return keyMetdata.get(name);
    }

    @Override
    public KeyVersion createKey(String name, byte[] bytes, Options options) {
      String versionName = buildVersionName(name, 0);
      keyMetdata.put(name, new TestMetadata(options.getCipher(),
          options.getBitLength(), 1));
      KeyVersion result = new KMSClientProvider.KMSKeyVersion(name, versionName, bytes);
      keyVersions.put(versionName, result);
      return result;
    }

    @Override
    public void deleteKey(String name) {
      throw new UnsupportedOperationException("Can't delete keys");
    }

    @Override
    public KeyVersion rollNewVersion(String name, byte[] bytes) {
      TestMetadata key = keyMetdata.get(name);
      String versionName = buildVersionName(name, key.addVersion());
      KeyVersion result = new KMSClientProvider.KMSKeyVersion(name, versionName,
          bytes);
      keyVersions.put(versionName, result);
      return result;
    }

    @Override
    public void flush() {
      // Nothing
    }

    static class TestMetadata extends KeyProvider.Metadata {

      protected TestMetadata(String cipher, int bitLength, int versions) {
        super(cipher, bitLength, null, null, null, versions);
      }

      public int addVersion() {
        return super.addVersion();
      }
    }
  }
}
