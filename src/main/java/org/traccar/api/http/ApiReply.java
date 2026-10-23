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
package org.traccar.api.http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A resource method result: status, optional entity, optional media type and extra headers.
 */
public final class ApiReply {

    /**
     * An entity written straight to the response stream.
     */
    @FunctionalInterface
    public interface Body {
        void write(OutputStream output) throws IOException;
    }

    private final int status;
    private Object entity;
    private String mediaType;
    private final Map<String, String> headers = new LinkedHashMap<>();

    private ApiReply(int status) {
        this.status = status;
    }

    public static ApiReply status(int status) {
        return new ApiReply(status);
    }

    public static ApiReply ok() {
        return new ApiReply(200);
    }

    public static ApiReply ok(Object entity) {
        return new ApiReply(200).entity(entity);
    }

    public static ApiReply ok(Object entity, String mediaType) {
        return new ApiReply(200).entity(entity).type(mediaType);
    }

    public static ApiReply accepted(Object entity) {
        return new ApiReply(202).entity(entity);
    }

    public static ApiReply noContent() {
        return new ApiReply(204);
    }

    public static ApiReply seeOther(URI location) {
        return new ApiReply(303).header("Location", location.toString());
    }

    public ApiReply entity(Object entity) {
        this.entity = entity;
        return this;
    }

    public ApiReply type(String mediaType) {
        this.mediaType = mediaType;
        return this;
    }

    public ApiReply header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public int getStatus() {
        return status;
    }

    public Object getEntity() {
        return entity;
    }

    public String getMediaType() {
        return mediaType;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

}
