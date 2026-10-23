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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Reads an MCP request body as text in the charset its Content-Type names (UTF-8 when it names none).
 */
public final class McpRequestBody {

    private McpRequestBody() {
    }

    public static class TooLargeException extends IOException {
        public TooLargeException(String message) {
            super(message);
        }
    }

    public static String read(ServerRequest request, int maxSize) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = request.content().inputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer, 0, buffer.length)) != -1) {
                total += count;
                if (total > maxSize) {
                    throw new TooLargeException(
                            "Request body exceeds the maximum allowed size of " + maxSize + " bytes");
                }
                bytes.write(buffer, 0, count);
            }
        }
        String charset = charset(request.headers().first(HeaderNames.CONTENT_TYPE).orElse(null));
        return bytes.toString(charset != null ? charset : StandardCharsets.UTF_8.name());
    }

    private static String charset(String contentType) {
        if (contentType == null) {
            return null;
        }
        String[] parts = contentType.split(";");
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].trim();
            int index = part.indexOf('=');
            if (index > 0 && part.substring(0, index).trim().toLowerCase(Locale.ROOT).equals("charset")) {
                String value = part.substring(index + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return null;
    }

}
