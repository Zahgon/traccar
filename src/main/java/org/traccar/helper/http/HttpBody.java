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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A request entity: either an object serialized as JSON, or literal text with a media type.
 */
public final class HttpBody {

    public static final String JSON = "application/json";
    public static final String XML = "application/xml";
    public static final String FORM = "application/x-www-form-urlencoded";

    private final Object value;
    private final String mediaType;
    private final boolean serialize;

    private HttpBody(Object value, String mediaType, boolean serialize) {
        this.value = value;
        this.mediaType = mediaType;
        this.serialize = serialize;
    }

    public static HttpBody json(Object value) {
        return new HttpBody(value, JSON, true);
    }

    public static HttpBody text(String value, String mediaType) {
        return new HttpBody(value, mediaType, false);
    }

    public static HttpBody form(Map<String, String> parameters) {
        Map<String, String> copy = new LinkedHashMap<>(parameters);
        String encoded = copy.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return new HttpBody(encoded, FORM, false);
    }

    Object value() {
        return value;
    }

    String mediaType() {
        return mediaType;
    }

    boolean serialize() {
        return serialize;
    }

}
