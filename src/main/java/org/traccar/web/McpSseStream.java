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

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One SSE response stream of an MCP session. The request handler thread waits in {@link #await()} until the stream
 * is closed, then the response ends.
 */
public class McpSseStream implements McpStreamableServerTransport {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpSseStream.class);

    private final McpTransportProvider provider;
    private final String sessionId;
    private final OutputStream output;
    private final ReentrantLock lock = new ReentrantLock();
    private final CountDownLatch closedLatch = new CountDownLatch(1);
    private volatile boolean closed;

    public McpSseStream(McpTransportProvider provider, String sessionId, OutputStream output) {
        this.provider = provider;
        this.sessionId = sessionId;
        this.output = output;
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return sendMessage(message, null);
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String messageId) {
        return Mono.fromRunnable(() -> {
            if (closed) {
                return;
            }
            lock.lock();
            try {
                if (closed) {
                    return;
                }
                String data = provider.jsonMapper().writeValueAsString(message);
                String event = "id: " + (messageId != null ? messageId : sessionId) + "\n"
                        + "event: message\n" + "data: " + data + "\n\n";
                output.write(event.getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (Exception e) {
                LOGGER.error("Failed to send message to session {}: {}", sessionId, e.getMessage());
                provider.removeSession(sessionId);
                closeLocked();
            } finally {
                lock.unlock();
            }
        });
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
        return provider.jsonMapper().convertValue(data, typeRef);
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(this::close);
    }

    @Override
    public void close() {
        lock.lock();
        try {
            closeLocked();
        } finally {
            lock.unlock();
        }
    }

    private void closeLocked() {
        if (!closed) {
            closed = true;
            closedLatch.countDown();
        }
    }

    /**
     * Blocks the request thread until the stream is closed, then finishes the response.
     */
    public void await() {
        try {
            closedLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        lock.lock();
        try {
            output.close();
        } catch (IOException e) {
            LOGGER.debug("SSE stream for session {} ended: {}", sessionId, e.getMessage());
        } finally {
            lock.unlock();
        }
    }

}
