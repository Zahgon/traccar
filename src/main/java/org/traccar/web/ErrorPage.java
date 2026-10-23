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
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import org.traccar.api.http.ApiException;

import java.nio.charset.StandardCharsets;

/**
 * The HTML error page the web server answers with when no handler writes a body of its own.
 */
public final class ErrorPage {

    public static final String CONTENT_TYPE = "text/html;charset=iso-8859-1";
    public static final String CACHE_CONTROL = "must-revalidate,no-cache,no-store";

    private ErrorPage() {
    }

    public static String uri(ServerRequest request) {
        String host = request.headers().first(HeaderNames.HOST).orElse(request.localPeer().host());
        String query = request.query().rawValue();
        return "http://" + host + request.path().absolute().rawPath() + (query.isEmpty() ? "" : "?" + query);
    }

    public static byte[] render(int status, String message, String uri) {
        String reason = message != null ? message : ApiException.reason(status);
        String page = "<html>\n<head>\n<meta http-equiv=\"Content-Type\" content=\"text/html;charset=ISO-8859-1\"/>\n"
                + "<title>Error " + status + " " + escape(reason) + "</title>\n</head>\n<body>\n<h2>HTTP ERROR "
                + status + " " + escape(reason) + "</h2>\n<table>\n<tr><th>URI:</th><td>" + escape(uri)
                + "</td></tr>\n<tr><th>STATUS:</th><td>" + status + "</td></tr>\n<tr><th>MESSAGE:</th><td>"
                + escape(reason) + "</td></tr>\n</table>\n\n</body>\n</html>\n";
        return page.getBytes(StandardCharsets.ISO_8859_1);
    }

    public static void send(ServerRequest request, ServerResponse response, int status) {
        send(request, response, status, null);
    }

    public static void send(ServerRequest request, ServerResponse response, int status, String message) {
        byte[] body = render(status, message, uri(request));
        response.status(status);
        response.header("Cache-Control", CACHE_CONTROL);
        response.header("Content-Type", CONTENT_TYPE);
        if (request.prologue().method().text().equals("HEAD")) {
            response.contentLength(body.length);
            response.send();
        } else {
            response.send(body);
        }
    }

    private static String escape(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '&' -> builder.append("&amp;");
                case '<' -> builder.append("&lt;");
                case '>' -> builder.append("&gt;");
                case '"' -> builder.append("&quot;");
                case '\'' -> builder.append("&#39;");
                default -> builder.append(c);
            }
        }
        return builder.toString();
    }

}
