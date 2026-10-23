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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.HashMap;

@Singleton
public class McpServerHolder implements AutoCloseable {

    public static final String PATH = "/api/mcp";

    private static final McpSchema.ToolAnnotations READ_ONLY_ANNOTATIONS = new McpSchema.ToolAnnotations(
            null, true, false, true, false, null);

    private final McpApiInvoker apiInvoker;

    private final McpTransportProvider transport;
    private final McpAsyncServer server;

    @Inject
    public McpServerHolder(ObjectMapper objectMapper, McpToolRegistry toolRegistry, McpApiInvoker apiInvoker) {

        this.apiInvoker = apiInvoker;

        transport = new McpTransportProvider(new JacksonMcpJsonMapper(objectMapper));

        var capabilities = McpSchema.ServerCapabilities.builder()
                .tools(true)
                .resources(false, true)
                .prompts(true)
                .build();

        server = McpServer.async(transport)
                .serverInfo("traccar-mcp", "1.0.0")
                .capabilities(capabilities)
                .tools(toolRegistry.getTools().stream().map(this::createApiTool).toList())
                .build();
    }

    static McpTransportContext createTransportContext(long userId, String authorization) {
        var contextData = new HashMap<String, Object>();
        contextData.put(McpService.ATTRIBUTE_USER_ID, userId);
        contextData.put(McpService.ATTRIBUTE_AUTHORIZATION, authorization);
        return McpTransportContext.create(contextData);
    }

    private McpServerFeatures.AsyncToolSpecification createApiTool(McpToolRegistry.McpApiTool tool) {

        var toolSchema = McpSchema.Tool.builder(tool.name(), tool.inputSchema())
                .title(tool.title())
                .annotations(READ_ONLY_ANNOTATIONS)
                .build();

        return McpServerFeatures.AsyncToolSpecification.builder()
                .tool(toolSchema)
                .callHandler((context, request) -> {
                    try {
                        String authorization =
                                (String) context.transportContext().get(McpService.ATTRIBUTE_AUTHORIZATION);
                        String body = apiInvoker.get(tool.path(), request.arguments(), authorization);
                        return Mono.just(McpSchema.CallToolResult.builder().addTextContent(body).build());
                    } catch (RuntimeException e) {
                        return Mono.just(McpSchema.CallToolResult.builder()
                                .addTextContent(e.getMessage())
                                .isError(true)
                                .build());
                    }
                })
                .build();
    }

    McpTransportProvider getTransport() {
        return transport;
    }

    @Override
    public void close() throws Exception {
        server.close();
    }

}
