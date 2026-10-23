/*
 * Copyright 2025 - 2026 Anton Tananaev (anton@traccar.org)
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
import io.helidon.http.HeaderNames;
import io.helidon.http.HttpPrologue;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import io.modelcontextprotocol.common.McpTransportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.security.LoginService;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The MCP endpoint at {@link McpServerHolder#PATH}: Bearer token authentication for the endpoint and everything
 * below it, then the streamable HTTP transport for the endpoint itself. Paths below the endpoint continue to the
 * next route once authenticated.
 */
public class McpService implements HttpService {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpService.class);

    public static final String ATTRIBUTE_USER_ID = "userId";
    public static final String ATTRIBUTE_AUTHORIZATION = "authorization";

    private static final String ALLOW = "GET, HEAD, POST, DELETE, TRACE, OPTIONS";

    private final McpTransportProvider transport;
    private final LoginService loginService;

    public McpService(McpServerHolder holder, LoginService loginService) {
        this.transport = holder.getTransport();
        this.loginService = loginService;
    }

    @Override
    public void routing(HttpRules rules) {
        rules.any(this::handle);
    }

    private void handle(ServerRequest request, ServerResponse response) {
        String path = request.path().absolute().path();
        if (!path.equals(McpServerHolder.PATH) && !path.startsWith(McpServerHolder.PATH + "/")) {
            response.next();
            return;
        }
        McpTransportContext context = authenticate(request, response);
        if (context == null) {
            return;
        }
        if (!path.equals(McpServerHolder.PATH)) {
            response.next();
            return;
        }
        switch (request.prologue().method().text()) {
            case "GET", "HEAD" -> transport.doGet(request, response, context);
            case "POST" -> transport.doPost(request, response, context);
            case "DELETE" -> transport.doDelete(request, response, context);
            case "OPTIONS" -> response.header("Allow", ALLOW).send();
            case "TRACE" -> trace(request, response);
            case "PUT" -> McpErrorPage.send(request, response, 405, "HTTP method PUT is not supported by this URL");
            default -> McpErrorPage.send(request, response, 501, "Method not implemented");
        }
    }

    private McpTransportContext authenticate(ServerRequest request, ServerResponse response) {
        String authorization = request.headers().first(HeaderNames.AUTHORIZATION).orElse(null);
        if (authorization == null || authorization.isBlank()) {
            unauthorized(response, "Missing Authorization header");
            return null;
        }
        String[] parts = authorization.split(" ", 2);
        if (parts.length != 2 || !"bearer".equals(parts[0].toLowerCase(Locale.ROOT)) || parts[1].isBlank()) {
            unauthorized(response, "Authorization header must be a Bearer token");
            return null;
        }
        try {
            var loginResult = loginService.login(parts[1].trim());
            if (loginResult == null || loginResult.getUser() == null) {
                unauthorized(response, "Invalid access token");
                return null;
            }
            return McpServerHolder.createTransportContext(loginResult.getUser().getId(), authorization);
        } catch (Exception e) {
            LOGGER.warn("MCP token authentication failed", e);
            unauthorized(response, "Invalid access token");
            return null;
        }
    }

    private static void unauthorized(ServerResponse response, String message) {
        response.status(401);
        response.header("WWW-Authenticate", "Bearer error=\"invalid_token\"");
        response.header("Content-Type", "application/json");
        response.send(("{\"error\":\"unauthorized\",\"error_description\":\"" + message + "\"}")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void trace(ServerRequest request, ServerResponse response) {
        HttpPrologue prologue = request.prologue();
        StringBuilder buffer = new StringBuilder("TRACE ").append(prologue.uriPath().rawPath())
                .append(' ').append(prologue.protocol()).append('/').append(prologue.protocolVersion());
        for (Header header : request.headers()) {
            for (String value : header.allValues()) {
                buffer.append("\r\n").append(header.name()).append(": ").append(value);
            }
        }
        buffer.append("\r\n");
        response.header("Content-Type", "message/http");
        response.send(buffer.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

}
