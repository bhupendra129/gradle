/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.caching.http.internal;

import com.google.common.collect.ImmutableSet;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.protocol.HTTP;
import org.gradle.api.UncheckedIOException;
import org.gradle.caching.BuildCacheException;
import org.gradle.caching.BuildCacheKey;
import org.gradle.caching.internal.NextGenBuildCacheService;
import org.gradle.internal.file.BufferProvider;
import org.gradle.internal.resource.transport.http.HttpClientHelper;
import org.gradle.internal.resource.transport.http.HttpClientResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Set;

/**
 * Build cache implementation that delegates to a service accessible via HTTP.
 */
public class NextGenHttpBuildCacheService extends AbstractHttpBuildCacheService implements NextGenBuildCacheService {
    private static final Logger LOGGER = LoggerFactory.getLogger(NextGenHttpBuildCacheService.class);
    static final String BUILD_CACHE_CONTENT_TYPE = "application/vnd.gradle.build-cache-artifact.v1";

    private static final Set<Integer> FATAL_HTTP_ERROR_CODES = ImmutableSet.of(
        HttpStatus.SC_USE_PROXY,
        HttpStatus.SC_BAD_REQUEST,
        HttpStatus.SC_UNAUTHORIZED, HttpStatus.SC_FORBIDDEN, HttpStatus.SC_PROXY_AUTHENTICATION_REQUIRED,
        HttpStatus.SC_METHOD_NOT_ALLOWED,
        HttpStatus.SC_NOT_ACCEPTABLE, HttpStatus.SC_LENGTH_REQUIRED, HttpStatus.SC_UNSUPPORTED_MEDIA_TYPE, HttpStatus.SC_EXPECTATION_FAILED,
        426, // Upgrade required
        HttpStatus.SC_HTTP_VERSION_NOT_SUPPORTED,
        511 // network authentication required
    );

    private final BufferProvider bufferProvider;

    public NextGenHttpBuildCacheService(
        URI url,
        boolean useExpectContinue,
        HttpClientHelper httpClientHelper,
        HttpBuildCacheRequestCustomizer requestCustomizer,
        BufferProvider bufferProvider
    ) {
        super(url, useExpectContinue, httpClientHelper, requestCustomizer);
        this.bufferProvider = bufferProvider;
    }

    @Override
    public boolean load(BuildCacheKey key, EntryReader reader) throws BuildCacheException {
        final URI uri = root.resolve("./" + key.getHashCode());
        HttpGet httpGet = new HttpGet(uri);
        httpGet.addHeader(HttpHeaders.ACCEPT, BUILD_CACHE_CONTENT_TYPE + ", */*");
        requestCustomizer.customize(httpGet);

        try (HttpClientResponse response = httpClientHelper.performHttpRequest(httpGet)) {
            StatusLine statusLine = response.getStatusLine();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Response for GET {}: {}", safeUri(uri), statusLine);
            }
            int statusCode = statusLine.getStatusCode();
            if (isHttpSuccess(statusCode)) {
                reader.readFrom(response.getContent());
                return true;
            } else if (statusCode == HttpStatus.SC_NOT_FOUND) {
                return false;
            } else {
                String defaultMessage = String.format("Loading entry from '%s' response status %d: %s", safeUri(uri), statusCode, statusLine.getReasonPhrase());
                return throwHttpStatusCodeException(statusCode, defaultMessage);
            }
        } catch (IOException e) {
            throw wrap(e);
        }
    }

    @Override
    public void store(BuildCacheKey key, EntryWriter writer) throws BuildCacheException {
        final URI uri = root.resolve(key.getHashCode());
        HttpPut httpPut = new HttpPut(uri);
        if (useExpectContinue) {
            httpPut.setHeader(HTTP.EXPECT_DIRECTIVE, HTTP.EXPECT_CONTINUE);
        }
        httpPut.addHeader(HttpHeaders.CONTENT_TYPE, BUILD_CACHE_CONTENT_TYPE);
        requestCustomizer.customize(httpPut);

        httpPut.setEntity(new AbstractHttpEntity() {
            @Override
            public boolean isRepeatable() {
                return true;
            }

            @Override
            public long getContentLength() {
                return writer.getSize();
            }

            @Override
            public InputStream getContent() throws IOException {
                return writer.openStream();
            }

            @Override
            public void writeTo(OutputStream output) throws IOException {
                try (InputStream input = writer.openStream()) {
                    IOUtils.copyLarge(input, output, bufferProvider.getBuffer());
                }
            }

            @Override
            public boolean isStreaming() {
                return false;
            }
        });
        try (HttpClientResponse response = httpClientHelper.performHttpRequest(httpPut)) {
            StatusLine statusLine = response.getStatusLine();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Response for PUT {}: {}", safeUri(uri), statusLine);
            }
            int statusCode = statusLine.getStatusCode();
            if (!isHttpSuccess(statusCode)) {
                String defaultMessage = String.format("Storing entry at '%s' response status %d: %s", safeUri(uri), statusCode, statusLine.getReasonPhrase());
                throwHttpStatusCodeException(statusCode, defaultMessage);
            }
        } catch (ClientProtocolException e) {
            throw wrap(e.getCause());
        } catch (IOException e) {
            throw wrap(e);
        }
    }

    private static BuildCacheException wrap(Throwable e) {
        if (e instanceof Error) {
            throw (Error) e;
        }

        throw new BuildCacheException(e.getMessage(), e);
    }

    private static boolean throwHttpStatusCodeException(int statusCode, String message) {
        if (FATAL_HTTP_ERROR_CODES.contains(statusCode)) {
            throw new UncheckedIOException(message);
        } else {
            throw new BuildCacheException(message);
        }
    }
}
