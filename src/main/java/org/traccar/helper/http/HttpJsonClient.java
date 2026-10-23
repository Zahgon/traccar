/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.helper.http;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Outbound HTTP client used by geocoders, geolocation, forwarders, notificators and other integrations. It is a thin
 * layer over the JDK HTTP client with JSON support through the shared Jackson mapper.
 */
public class HttpJsonClient {

    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final Duration readTimeout;

    private final int connectTimeout;

    public HttpJsonClient(ObjectMapper objectMapper, int connectTimeout, int readTimeout) {
        this(objectMapper, connectTimeout, readTimeout, null, null, null);
    }

    private HttpJsonClient(
            ObjectMapper objectMapper, int connectTimeout, int readTimeout,
            URI proxy, String username, String password) {
        this.objectMapper = objectMapper;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout > 0 ? Duration.ofMillis(readTimeout) : null;
        HttpClient.Builder builder = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL);
        if (connectTimeout > 0) {
            builder.connectTimeout(Duration.ofMillis(connectTimeout));
        }
        if (proxy != null) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort())));
            if (username != null) {
                char[] secret = password != null ? password.toCharArray() : new char[0];
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return getRequestorType() == RequestorType.PROXY
                                ? new PasswordAuthentication(username, secret) : null;
                    }
                });
            }
        }
        this.client = builder.build();
    }

    /**
     * Returns a client that sends every request through the given HTTP proxy.
     */
    public HttpJsonClient withProxy(String proxyUri, String username, String password) {
        int readMillis = readTimeout != null ? (int) readTimeout.toMillis() : 0;
        return new HttpJsonClient(
                objectMapper, connectTimeout, readMillis, URI.create(proxyUri), username, password);
    }

    public HttpTarget target(String url) {
        return new HttpTarget(this, url);
    }

    HttpClient client() {
        return client;
    }

    ObjectMapper objectMapper() {
        return objectMapper;
    }

    Duration readTimeout() {
        return readTimeout;
    }

}
