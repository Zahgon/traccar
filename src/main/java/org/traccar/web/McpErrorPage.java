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
package org.traccar.web;

import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import org.traccar.api.http.ApiException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The plain error answer of the MCP endpoint (a status with no handler body): an HTML, JSON or text page chosen by
 * the Accept header for GET, HEAD and POST, and an empty body for any other method or an unmatched Accept header.
 */
public final class McpErrorPage {

    private McpErrorPage() {
    }

    private record Accepted(String type, double quality, int specificity, int order) {
    }

    public static void send(ServerRequest request, ServerResponse response, int status) {
        send(request, response, status, null);
    }

    public static void send(ServerRequest request, ServerResponse response, int status, String message) {
        String method = request.prologue().method().text();
        response.status(Status.create(status, reason(status)));
        response.header("Cache-Control", ErrorPage.CACHE_CONTROL);
        byte[] body = null;
        if (method.equals("GET") || method.equals("HEAD") || method.equals("POST")) {
            String reason = message != null ? message : reason(status);
            String uri = uri(request);
            for (String type : accepted(request.headers().first(HeaderNames.ACCEPT).orElse(null))) {
                if (type.equals("text/html") || type.equals("text/*") || type.equals("*/*")) {
                    response.header("Content-Type", ErrorPage.CONTENT_TYPE);
                    body = ErrorPage.render(status, reason, uri);
                } else if (type.equals("text/json") || type.equals("application/json")) {
                    response.header("Content-Type", "application/json");
                    body = ("{\n\"message\":\"" + json(reason) + "\",\n\"url\":\"" + json(uri)
                            + "\",\n\"status\":\"" + status + "\"\n}").getBytes(StandardCharsets.UTF_8);
                } else if (type.equals("text/plain")) {
                    response.header("Content-Type", "text/plain;charset=iso-8859-1");
                    body = ("HTTP ERROR " + status + " " + reason + "\nURI: " + uri + "\nSTATUS: " + status
                            + "\nMESSAGE: " + reason + "\n").getBytes(StandardCharsets.ISO_8859_1);
                }
                if (body != null) {
                    break;
                }
            }
        }
        if (body == null) {
            response.send();
        } else if (method.equals("HEAD")) {
            response.contentLength(body.length);
            response.send();
        } else {
            response.send(body);
        }
    }

    private static String uri(ServerRequest request) {
        String host = request.headers().first(HeaderNames.HOST).orElse(request.localPeer().host());
        String query = request.query().rawValue();
        return "http://" + host + request.path().absolute().rawPath() + (query.isEmpty() ? "" : "?" + query);
    }

    private static String reason(int status) {
        return switch (status) {
            case 413 -> "Payload Too Large";
            case 501 -> "Not Implemented";
            default -> ApiException.reason(status);
        };
    }

    private static List<String> accepted(String header) {
        if (header == null) {
            return List.of("text/html");
        }
        List<Accepted> ranges = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] fields = part.split(";");
            String type = fields[0].trim().toLowerCase(Locale.ROOT);
            if (type.isEmpty()) {
                continue;
            }
            double quality = 1.0;
            for (int i = 1; i < fields.length; i++) {
                String field = fields[i].trim();
                if (field.startsWith("q=")) {
                    try {
                        quality = Double.parseDouble(field.substring(2).trim());
                    } catch (NumberFormatException e) {
                        quality = 0.0;
                    }
                }
            }
            if (quality > 0.0) {
                int specificity = type.equals("*/*") ? 0 : type.endsWith("/*") ? 1 : 2;
                ranges.add(new Accepted(type, quality, specificity, ranges.size()));
            }
        }
        ranges.sort(Comparator.comparingDouble(Accepted::quality).reversed()
                .thenComparing(Comparator.comparingInt(Accepted::specificity).reversed())
                .thenComparingInt(Accepted::order));
        return ranges.stream().map(Accepted::type).toList();
    }

    private static String json(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

}
