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
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP streamable HTTP transport on Helidon: the session registry the SDK talks to, GET (the session's listening SSE
 * stream) and DELETE (end of a session). POST is handled by {@link McpPostHandler}.
 */
public class McpTransportProvider implements McpStreamableServerTransportProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpTransportProvider.class);

    public static final String APPLICATION_JSON = "application/json";
    public static final String TEXT_EVENT_STREAM = "text/event-stream";

    private final McpJsonMapper jsonMapper;
    private final ConcurrentHashMap<String, McpStreamableServerSession> sessions = new ConcurrentHashMap<>();
    private McpStreamableServerSession.Factory sessionFactory;
    private final McpPostHandler postHandler = new McpPostHandler(this);
    private volatile boolean closing;

    public McpTransportProvider(McpJsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    McpStreamableServerSession session(String sessionId) {
        return sessions.get(sessionId);
    }

    McpStreamableServerSession.McpStreamableServerSessionInit startSession(McpSchema.InitializeRequest request) {
        McpStreamableServerSession.McpStreamableServerSessionInit init = sessionFactory.startSession(request);
        sessions.put(init.session().getId(), init.session());
        return init;
    }

    McpJsonMapper jsonMapper() {
        return jsonMapper;
    }

    void removeSession(String sessionId) {
        sessions.remove(sessionId);
    }

    @Override
    public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        if (sessions.isEmpty()) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> sessions.values().parallelStream().forEach(session -> {
            try {
                session.sendNotification(method, params).block();
            } catch (Exception e) {
                LOGGER.info("Failed to send message to session {}: {}", session.getId(), e.getMessage());
            }
        }));
    }

    @Override
    public Mono<Void> notifyClient(String sessionId, String method, Object params) {
        return Mono.defer(() -> {
            McpStreamableServerSession session = sessions.get(sessionId);
            return session != null ? session.sendNotification(method, params) : Mono.empty();
        });
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(() -> {
            closing = true;
            sessions.values().parallelStream().forEach(session -> {
                try {
                    session.closeGracefully().block();
                } catch (Exception e) {
                    LOGGER.warn("Failed to close session {}: {}", session.getId(), e.getMessage());
                }
            });
            sessions.clear();
        });
    }

    boolean rejectWhenClosing(ServerRequest request, ServerResponse response) {
        if (closing) {
            McpErrorPage.send(request, response, 503, "Server is shutting down");
        }
        return closing;
    }

    static List<String> acceptErrors(ServerRequest request, boolean json) {
        List<String> errors = new ArrayList<>();
        String accept = header(request, "Accept");
        if (accept == null || !accept.contains(TEXT_EVENT_STREAM)) {
            errors.add("text/event-stream required in Accept header");
        }
        if (json && (accept == null || !accept.contains(APPLICATION_JSON))) {
            errors.add("application/json required in Accept header");
        }
        return errors;
    }

    static String header(ServerRequest request, String name) {
        return request.headers().first(HeaderNames.create(name)).orElse(null);
    }

    void doGet(ServerRequest request, ServerResponse response, McpTransportContext context) {
        if (rejectWhenClosing(request, response)) {
            return;
        }
        List<String> errors = acceptErrors(request, false);
        String sessionId = header(request, HttpHeaders.MCP_SESSION_ID);
        if (sessionId == null || sessionId.isBlank()) {
            errors.add("Session ID required in mcp-session-id header");
        }
        if (!errors.isEmpty()) {
            responseError(request, response, 400, McpError.builder(McpSchema.ErrorCodes.METHOD_NOT_FOUND)
                    .message(String.join("; ", errors)).build());
            return;
        }
        McpStreamableServerSession session = sessions.get(sessionId);
        if (session == null) {
            McpErrorPage.send(request, response, 404);
            return;
        }
        McpSseStream stream = openStream(request, response, sessionId);
        String lastEventId = header(request, HttpHeaders.LAST_EVENT_ID);
        if (lastEventId != null) {
            try {
                session.replay(lastEventId).contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context))
                        .toIterable().forEach(message -> stream.sendMessage(message)
                                .contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context)).block());
            } catch (Exception e) {
                LOGGER.error("Failed to replay messages: {}", e.getMessage());
                stream.close();
            }
            stream.await();
        } else {
            McpStreamableServerSession.McpStreamableServerSessionStream listeningStream =
                    session.listeningStream(stream);
            try {
                stream.await();
            } finally {
                listeningStream.close();
            }
        }
    }

    void doPost(ServerRequest request, ServerResponse response, McpTransportContext context) {
        postHandler.handle(request, response, context);
    }

    void doDelete(ServerRequest request, ServerResponse response, McpTransportContext context) {
        if (rejectWhenClosing(request, response)) {
            return;
        }
        String sessionId = header(request, HttpHeaders.MCP_SESSION_ID);
        if (sessionId == null) {
            responseError(request, response, 400, McpError.builder(McpSchema.ErrorCodes.METHOD_NOT_FOUND)
                    .message("Session ID required in mcp-session-id header").build());
            return;
        }
        McpStreamableServerSession session = sessions.get(sessionId);
        if (session == null) {
            McpErrorPage.send(request, response, 404);
            return;
        }
        try {
            session.delete().contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context)).block();
            sessions.remove(sessionId);
            response.status(200).send();
        } catch (Exception e) {
            LOGGER.error("Failed to delete session {}: {}", sessionId, e.getMessage());
            responseError(request, response, 500, McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
                    .message(e.getMessage()).build());
        }
    }

    McpSseStream openStream(ServerRequest request, ServerResponse response, String sessionId) {
        response.status(200);
        response.header("Content-Type", TEXT_EVENT_STREAM + ";charset=utf-8");
        response.header("Cache-Control", "no-cache");
        return new McpSseStream(this, sessionId, response.outputStream());
    }

    void responseError(ServerRequest request, ServerResponse response, int status, McpError error) {
        McpJsonResponse.error(request, response, status, jsonMapper, error);
    }

}
