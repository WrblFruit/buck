/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.rules;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.HashingInputStream;
import com.google.common.io.ByteStreams;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;

import org.apache.commons.compress.utils.BoundedInputStream;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

import okio.BufferedSink;

public class HttpArtifactCache implements ArtifactCache {

  /**
   * If the user is offline, then we do not want to print every connection failure that occurs.
   * However, in practice, it appears that some connection failures can be intermittent, so we
   * should print enough to provide a signal of how flaky the connection is.
   */
  private static final Logger LOGGER = Logger.get(HttpArtifactCache.class);
  private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

  private final String name;
  private final OkHttpClient fetchClient;
  private final OkHttpClient storeClient;
  private final URL url;
  private final boolean doStore;
  private final ProjectFilesystem projectFilesystem;
  private final BuckEventBus buckEventBus;
  private final HashFunction hashFunction;

  private final Set<String> seenErrors = Sets.newConcurrentHashSet();

  public HttpArtifactCache(
      String name,
      OkHttpClient fetchClient,
      OkHttpClient storeClient,
      URL url,
      boolean doStore,
      ProjectFilesystem projectFilesystem,
      BuckEventBus buckEventBus,
      HashFunction hashFunction) {
    this.name = name;
    this.fetchClient = fetchClient;
    this.storeClient = storeClient;
    this.url = url;
    this.doStore = doStore;
    this.projectFilesystem = projectFilesystem;
    this.buckEventBus = buckEventBus;
    this.hashFunction = hashFunction;
  }

  private Request.Builder createRequestBuilder(String key) throws IOException {
    return new Request.Builder()
        .url(new URL(url, "artifact/key/" + key));
  }

  protected Response fetchCall(Request request) throws IOException {
    return fetchClient.newCall(request).execute();
  }

  public CacheResult fetchImpl(RuleKey ruleKey, File file) throws IOException {
    Request request =
        createRequestBuilder(ruleKey.toString())
            .get()
            .build();
    Response response = fetchCall(request);

    if (response.code() == HttpURLConnection.HTTP_NOT_FOUND) {
      LOGGER.info("fetch(%s, %s): cache miss", url, ruleKey);
      return CacheResult.miss();
    }

    if (response.code() != HttpURLConnection.HTTP_OK) {
      String msg = String.format("unexpected response: %d", response.code());
      reportFailure("fetch(%s, %s): %s", url, ruleKey, msg);
      return CacheResult.error(name, msg);
    }

    // The hash code shipped with the artifact to/from the cache.
    HashCode expectedHashCode, actualHashCode;

    // Setup a temporary file, which sits next to the destination, to write to and
    // make sure all parent dirs exist.
    Path path = file.toPath();
    projectFilesystem.createParentDirs(path);
    Path temp = projectFilesystem.createTempFile(
        path.getParent(),
        path.getFileName().toString(),
        ".tmp");

    // Open the stream to server just long enough to read the hash code and artifact.
    try (DataInputStream input = new DataInputStream(response.body().byteStream())) {

      // First, extract the size of the file data portion, which we put in the beginning of
      // the artifact.
      long length = input.readLong();

      // Now, write the remaining response data to the temp file, while grabbing the hash.
      try (BoundedInputStream boundedInput = new BoundedInputStream(input, length);
           HashingInputStream hashingInput = new HashingInputStream(hashFunction, boundedInput);
           OutputStream output = projectFilesystem.newFileOutputStream(temp)) {
        ByteStreams.copy(hashingInput, output);
        actualHashCode = hashingInput.hash();
      }

      // Lastly, extract the hash code from the end of the request data.
      byte[] hashCodeBytes = new byte[hashFunction.bits() / Byte.SIZE];
      ByteStreams.readFully(input, hashCodeBytes);
      expectedHashCode = HashCode.fromBytes(hashCodeBytes);

      // We should be at the end of output -- verify this.  Also, we could just try to read a
      // single byte here, instead of all remaining input, but some network stack implementations
      // require that we exhaust the input stream before the connection can be reusable.
      try (OutputStream output = ByteStreams.nullOutputStream()) {
        if (ByteStreams.copy(input, output) != 0) {
          String msg = "unexpected end of input";
          reportFailure("fetch(%s, %s): %s", url, ruleKey, msg);
          return CacheResult.error(name, msg);
        }
      }
    }

    // Now form the checksum on the file we got and compare it to the checksum form the
    // the HTTP header.  If it's incorrect, log this and return a miss.
    if (!expectedHashCode.equals(actualHashCode)) {
      String msg = "artifact had invalid checksum";
      reportFailure("fetch(%s, %s): %s", url, ruleKey, msg);
      projectFilesystem.deleteFileAtPath(temp);
      return CacheResult.error(name, msg);
    }

    // Finally, move the temp file into it's final place.
    projectFilesystem.move(temp, path, StandardCopyOption.REPLACE_EXISTING);

    LOGGER.info("fetch(%s, %s): cache hit", url, ruleKey);
    return CacheResult.hit(name);
  }

  @Override
  public CacheResult fetch(RuleKey ruleKey, File output) throws InterruptedException {
    try {
      return fetchImpl(ruleKey, output);
    } catch (IOException e) {
      String msg = String.format("%s: %s", e.getClass().getName(), e.getMessage());
      reportFailure(e, "fetch(%s, %s): %s", url, ruleKey, msg);
      return CacheResult.error(name, msg);
    }
  }

  protected Response storeCall(Request request) throws IOException {
    return storeClient.newCall(request).execute();
  }

  public void storeImpl(RuleKey ruleKey, final File file) throws IOException {
    Request request = createRequestBuilder(ruleKey.toString())
        .put(
            new RequestBody() {
              @Override
              public MediaType contentType() {
                return OCTET_STREAM;
              }

              @Override
              public long contentLength() throws IOException {
                return
                    Long.SIZE / Byte.SIZE +
                    projectFilesystem.getFileSize(file.toPath()) +
                    hashFunction.bits() / Byte.SIZE;
              }

              @Override
              public void writeTo(BufferedSink sink) throws IOException {
                try (DataOutputStream output = new DataOutputStream(sink.outputStream());
                     InputStream input = projectFilesystem.newFileInputStream(file.toPath());
                     HashingInputStream hasher = new HashingInputStream(hashFunction, input)) {
                  output.writeLong(projectFilesystem.getFileSize(file.toPath()));
                  ByteStreams.copy(hasher, output);
                  output.write(hasher.hash().asBytes());
                }
              }
            })
        .build();

    Response response = storeCall(request);

    if (response.code() != HttpURLConnection.HTTP_ACCEPTED) {
      reportFailure("store(%s, %s): unexpected response: %d", url, ruleKey, response.code());
    }
  }

  @Override
  public void store(RuleKey ruleKey, File output) throws InterruptedException {
    if (!isStoreSupported()) {
      return;
    }
    try {
      storeImpl(ruleKey, output);
    } catch (IOException e) {
      reportFailure(
          e,
          "store(%s, %s): %s: %s",
          url,
          ruleKey,
          e.getClass().getName(),
          e.getMessage());
    }
  }

  private void reportFailure(Exception exception, String format, Object... args) {
    LOGGER.warn(exception, format, args);
    reportFailureToEvenBus(format, args);
  }

  private void reportFailure(String format, Object... args) {
    LOGGER.warn(format, args);
    reportFailureToEvenBus(format, args);
  }

  private void reportFailureToEvenBus(String format, Object... args) {
    if (seenErrors.add(format)) {
      buckEventBus.post(ConsoleEvent.warning(format, args));
    }
  }

  @Override
  public boolean isStoreSupported() {
    return doStore;
  }

  @Override
  public void close() {}

}
