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

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * A buffered HTTP response.
 */
public final class HttpReply implements AutoCloseable {

    private final int status;
    private final byte[] body;
    private final ObjectMapper objectMapper;

    HttpReply(int status, byte[] body, ObjectMapper objectMapper) {
        this.status = status;
        this.body = body;
        this.objectMapper = objectMapper;
    }

    public int getStatus() {
        return status;
    }

    public boolean isSuccessful() {
        return status / 100 == 2;
    }

    public <T> T readEntity(Class<T> type) {
        return read(type, body, objectMapper);
    }

    @SuppressWarnings("unchecked")
    static <T> T read(Class<T> type, byte[] body, ObjectMapper objectMapper) {
        if (type == String.class) {
            return (T) new String(body, StandardCharsets.UTF_8);
        }
        if (type == byte[].class) {
            return (T) body;
        }
        if (JsonObject.class.isAssignableFrom(type)) {
            try (JsonReader reader = Json.createReader(new ByteArrayInputStream(body))) {
                return (T) reader.readObject();
            }
        }
        try {
            return objectMapper.readValue(body, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
    }

}
