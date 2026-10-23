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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An HTTP failure with a status code, the replacement for the REST framework's application exception. The default
 * message is the "HTTP code reason" text the REST API has always answered with.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final Map<String, String> headers = new LinkedHashMap<>();

    public ApiException(int status) {
        super(defaultMessage(status));
        this.status = status;
    }

    public ApiException(Throwable cause) {
        super(defaultMessage(500), cause);
        this.status = 500;
    }

    public static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 303 -> "See Other";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 406 -> "Not Acceptable";
            case 409 -> "Conflict";
            case 415 -> "Unsupported Media Type";
            case 500 -> "Internal Server Error";
            case 503 -> "Service Unavailable";
            default -> "Unknown";
        };
    }

    private static String defaultMessage(int status) {
        return "HTTP " + status + " " + reason(status);
    }

    public ApiException header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public int getStatus() {
        return status;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

}
