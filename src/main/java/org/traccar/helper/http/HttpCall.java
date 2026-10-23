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

import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * A request under construction: target URL plus headers, sent synchronously or asynchronously.
 */
public final class HttpCall {

    private final HttpJsonClient client;
    private final String url;
    private final Map<String, String> headers = new LinkedHashMap<>();

    HttpCall(HttpJsonClient client, String url) {
        this.client = client;
        this.url = url;
    }

    public HttpCall header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    private HttpRequest build(String method, HttpBody body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
        if (client.readTimeout() != null) {
            builder.timeout(client.readTimeout());
        }
        headers.forEach(builder::header);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            String content;
            try {
                content = body.serialize()
                        ? client.objectMapper().writeValueAsString(body.value()) : String.valueOf(body.value());
            } catch (JsonProcessingException e) {
                throw new UncheckedIOException(e);
            }
            if (!headers.containsKey("Content-Type")) {
                builder.header("Content-Type", body.mediaType());
            }
            builder.method(method, HttpRequest.BodyPublishers.ofString(content, StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    private HttpReply send(String method, HttpBody body) {
        try {
            HttpResponse<byte[]> response = client.client().send(
                    build(method, body), HttpResponse.BodyHandlers.ofByteArray());
            return new HttpReply(response.statusCode(), response.body(), client.objectMapper());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    CompletableFuture<HttpReply> sendAsync(String method, HttpBody body) {
        return client.client().sendAsync(build(method, body), HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> new HttpReply(response.statusCode(), response.body(), client.objectMapper()));
    }

    static <T> T convert(HttpReply reply, Class<T> type) {
        if (type == HttpReply.class) {
            return type.cast(reply);
        }
        if (!reply.isSuccessful()) {
            throw new HttpStatusException(reply.getStatus(), reply.readEntity(String.class));
        }
        return reply.readEntity(type);
    }

    static Class<?> callbackType(HttpCallback<?> callback) {
        for (Type type : callback.getClass().getGenericInterfaces()) {
            if (type instanceof ParameterizedType parameterized
                    && parameterized.getRawType() == HttpCallback.class) {
                Type argument = parameterized.getActualTypeArguments()[0];
                if (argument instanceof Class<?> clazz) {
                    return clazz;
                }
                if (argument instanceof ParameterizedType nested && nested.getRawType() instanceof Class<?> raw) {
                    return raw;
                }
            }
        }
        return HttpReply.class;
    }

    public HttpReply get() {
        return send("GET", null);
    }

    public <T> T get(Class<T> type) {
        return convert(get(), type);
    }

    public HttpReply post(HttpBody body) {
        return send("POST", body);
    }

    public <T> T post(HttpBody body, Class<T> type) {
        return convert(post(body), type);
    }

    public HttpAsync async() {
        return new HttpAsync(this);
    }

}
