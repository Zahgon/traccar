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
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * POST on the MCP endpoint: initialize answers JSON with a new session id, notifications and responses answer 202,
 * requests answer an SSE stream that ends with the response.
 */
public class McpPostHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpPostHandler.class);

    private static final int REQUEST_MAX_SIZE = 16 * 1024 * 1024;

    private final McpTransportProvider provider;

    public McpPostHandler(McpTransportProvider provider) {
        this.provider = provider;
    }

    void handle(ServerRequest request, ServerResponse response, McpTransportContext context) {
        if (provider.rejectWhenClosing(request, response)) {
            return;
        }
        if (request.headers().contentLength().orElse(-1) > REQUEST_MAX_SIZE) {
            response.header("Connection", "close");
            McpErrorPage.send(request, response, 413);
            return;
        }
        List<String> errors = McpTransportProvider.acceptErrors(request, true);
        try {
            String body = McpRequestBody.read(request, REQUEST_MAX_SIZE);
            McpSchema.JSONRPCMessage message =
                    McpSchema.deserializeJsonRpcMessage(provider.jsonMapper(), body);
            if (message instanceof McpSchema.JSONRPCRequest jsonrpcRequest
                    && jsonrpcRequest.method().equals(McpSchema.METHOD_INITIALIZE)) {
                initialize(request, response, jsonrpcRequest, errors);
                return;
            }
            String sessionId = McpTransportProvider.header(request, HttpHeaders.MCP_SESSION_ID);
            if (sessionId == null || sessionId.isBlank()) {
                errors.add("Session ID required in mcp-session-id header");
            }
            if (!errors.isEmpty()) {
                error(request, response, 400, McpSchema.ErrorCodes.METHOD_NOT_FOUND, String.join("; ", errors));
                return;
            }
            McpStreamableServerSession session = provider.session(sessionId);
            if (session == null) {
                error(request, response, 404, McpSchema.ErrorCodes.INTERNAL_ERROR, "Session not found: " + sessionId);
                return;
            }
            if (message instanceof McpSchema.JSONRPCResponse jsonrpcResponse) {
                session.accept(jsonrpcResponse).contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context))
                        .block();
                response.status(202).send();
            } else if (message instanceof McpSchema.JSONRPCNotification jsonrpcNotification) {
                session.accept(jsonrpcNotification).contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context))
                        .block();
                response.status(202).send();
            } else if (message instanceof McpSchema.JSONRPCRequest jsonrpcRequest) {
                McpSseStream stream = provider.openStream(request, response, sessionId);
                try {
                    session.responseStream(jsonrpcRequest, stream)
                            .contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context)).block();
                } catch (Exception e) {
                    LOGGER.error("Failed to handle request stream: {}", e.getMessage());
                    stream.close();
                }
                stream.await();
            } else {
                error(request, response, 500, McpSchema.ErrorCodes.INVALID_REQUEST, "Unknown message type");
            }
        } catch (McpRequestBody.TooLargeException e) {
            McpErrorPage.send(request, response, 413);
        } catch (IllegalArgumentException | IOException e) {
            LOGGER.error("Failed to deserialize message: {}", e.getMessage());
            error(request, response, 400, McpSchema.ErrorCodes.INVALID_REQUEST,
                    "Invalid message format: " + e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Error handling message: {}", e.getMessage());
            error(request, response, 500, McpSchema.ErrorCodes.INTERNAL_ERROR,
                    "Error processing message: " + e.getMessage());
        }
    }

    private void initialize(
            ServerRequest request, ServerResponse response, McpSchema.JSONRPCRequest jsonrpcRequest,
            List<String> errors) {
        if (!errors.isEmpty()) {
            error(request, response, 400, McpSchema.ErrorCodes.METHOD_NOT_FOUND, String.join("; ", errors));
            return;
        }
        McpSchema.InitializeRequest initializeRequest = provider.jsonMapper().convertValue(
                jsonrpcRequest.params(), new TypeRef<McpSchema.InitializeRequest>() {
                });
        McpStreamableServerSession.McpStreamableServerSessionInit init = provider.startSession(initializeRequest);
        String json;
        try {
            McpSchema.InitializeResult initResult = init.initResult().block();
            json = provider.jsonMapper().writeValueAsString(
                    McpSchema.JSONRPCResponse.result(jsonrpcRequest.id(), initResult));
        } catch (Exception e) {
            LOGGER.error("Failed to initialize session: {}", e.getMessage());
            error(request, response, 500, McpSchema.ErrorCodes.INTERNAL_ERROR,
                    "Failed to initialize session: " + e.getMessage());
            return;
        }
        response.header(HttpHeaders.MCP_SESSION_ID, init.session().getId());
        McpJsonResponse.write(request, response, 200, json);
    }

    private void error(ServerRequest request, ServerResponse response, int status, int code, String message) {
        provider.responseError(request, response, status, McpError.builder(code).message(message).build());
    }

}
