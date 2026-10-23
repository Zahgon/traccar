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

import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A JSON answer of the MCP transport, streamed (chunked) like the original transport wrote it.
 */
public final class McpJsonResponse {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpJsonResponse.class);

    private McpJsonResponse() {
    }

    public static void error(
            ServerRequest request, ServerResponse response, int status, McpJsonMapper jsonMapper, McpError error) {
        String json;
        try {
            json = jsonMapper.writeValueAsString(error);
        } catch (IOException e) {
            LOGGER.error("Failed to send error response: {}", e.getMessage());
            McpErrorPage.send(request, response, 500, "Error processing message");
            return;
        }
        write(request, response, status, json);
    }

    public static void write(ServerRequest request, ServerResponse response, int status, String json) {
        response.status(status);
        response.header("Content-Type", McpTransportProvider.APPLICATION_JSON + ";charset=utf-8");
        if (request.prologue().method().text().equals("HEAD")) {
            response.header("Transfer-Encoding", "chunked");
            response.send();
            return;
        }
        try (OutputStream output = response.outputStream()) {
            output.write(json.getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException e) {
            LOGGER.warn("Failed to write MCP response: {}", e.getMessage());
        }
    }

}
