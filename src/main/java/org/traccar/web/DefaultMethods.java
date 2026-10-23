/*
 * Copyright 2025 Anton Tananaev (anton@traccar.org)
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
package org.traccar.web;

import io.helidon.http.Header;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The answers the web handlers outside the API have always given to the methods they do not implement: OPTIONS
 * lists the allowed methods, TRACE echoes the request head, POST without support is a 405 page, PUT and DELETE are
 * an empty 405 and any other method an empty 501.
 */
final class DefaultMethods {

    static final String POST_NOT_SUPPORTED = "HTTP method POST is not supported by this URL";

    private DefaultMethods() {
    }

    /**
     * Answers every method the handler does not implement; returns false for GET and HEAD, and for POST when the
     * allowed list contains it.
     */
    static boolean answer(ServerRequest request, ServerResponse response, String allow) {
        switch (request.prologue().method().text()) {
            case "GET", "HEAD" -> {
                return false;
            }
            case "POST" -> {
                if (allow.contains("POST")) {
                    return false;
                }
                ErrorPage.send(request, response, 405, POST_NOT_SUPPORTED);
            }
            case "OPTIONS" -> {
                response.header("Allow", allow);
                response.contentLength(0);
                response.send();
            }
            case "TRACE" -> trace(request, response);
            case "PUT", "DELETE" -> empty(response, 405);
            default -> empty(response, 501);
        }
        return true;
    }

    private static void empty(ServerResponse response, int status) {
        response.status(status);
        response.header("Cache-Control", ErrorPage.CACHE_CONTROL);
        response.contentLength(0);
        response.send();
    }

    private static void trace(ServerRequest request, ServerResponse response) {
        StringBuilder buffer = new StringBuilder("TRACE ")
                .append(request.path().absolute().rawPath()).append(' ')
                .append(request.prologue().protocol()).append('/').append(request.prologue().protocolVersion());
        Set<String> names = new LinkedHashSet<>();
        for (Header header : request.headers()) {
            names.add(header.name());
        }
        for (String name : names) {
            for (Header header : request.headers()) {
                if (header.name().equals(name)) {
                    for (String value : header.allValues()) {
                        buffer.append("\r\n").append(name).append(": ").append(value);
                    }
                }
            }
        }
        buffer.append("\r\n");
        byte[] body = buffer.toString().getBytes(StandardCharsets.ISO_8859_1);
        response.header("Content-Type", "message/http");
        response.send(body);
    }

}
