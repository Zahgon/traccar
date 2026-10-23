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
package org.traccar.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.helidon.http.HeaderNames;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import org.traccar.api.security.LoginService;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.session.ConnectionManager;
import org.traccar.storage.Storage;
import org.traccar.web.ErrorPage;
import org.traccar.web.SessionStore;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Creates one {@link AsyncSocket} per WebSocket upgrade on /api/socket and enforces the web.timeout idle limit.
 */
@Singleton
public class AsyncSocketEndpoint {

    public static final String PATH = "/api/socket";

    private final ObjectMapper objectMapper;
    private final ConnectionManager connectionManager;
    private final Storage storage;
    private final LoginService loginService;
    private final SessionStore sessionStore;
    private final long idleTimeout;
    private final Set<AsyncSocket> sockets = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService idleChecker;

    @Inject
    public AsyncSocketEndpoint(
            Config config, ObjectMapper objectMapper, ConnectionManager connectionManager, Storage storage,
            LoginService loginService, SessionStore sessionStore) {
        this.objectMapper = objectMapper;
        this.connectionManager = connectionManager;
        this.storage = storage;
        this.loginService = loginService;
        this.sessionStore = sessionStore;
        this.idleTimeout = config.getLong(Keys.WEB_TIMEOUT);
    }

    public AsyncSocket create() {
        return new AsyncSocket(
                objectMapper, connectionManager, storage, loginService, sessionStore, idleTimeout, sockets);
    }

    /**
     * Answers a request on the socket path that did not become a WebSocket: a refused upgrade (no valid token or
     * session) gets an empty 200, a plain request what a WebSocket-only path answers for its method.
     */
    public static void refuse(ServerRequest request, ServerResponse response) {
        String method = request.prologue().method().text();
        if (request.headers().contains(HeaderNames.UPGRADE)) {
            response.status(200);
            response.contentLength(0);
            response.send();
            return;
        }
        switch (method) {
            case "GET", "POST", "HEAD" ->
                    ErrorPage.send(request, response, 405, "HTTP method " + method + " is not supported by this URL");
            case "OPTIONS" -> {
                response.header("Allow", "TRACE, OPTIONS");
                response.contentLength(0);
                response.send();
            }
            case "PUT", "DELETE" -> {
                response.status(405);
                response.header("Cache-Control", ErrorPage.CACHE_CONTROL);
                response.contentLength(0);
                response.send();
            }
            default -> {
                response.status(501);
                response.header("Cache-Control", ErrorPage.CACHE_CONTROL);
                response.contentLength(0);
                response.send();
            }
        }
    }

    public synchronized void start() {
        if (idleChecker == null && idleTimeout > 0) {
            idleChecker = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "websocket-idle");
                thread.setDaemon(true);
                return thread;
            });
            long period = Math.max(100, Math.min(1000, idleTimeout / 4));
            idleChecker.scheduleAtFixedRate(this::checkIdle, period, period, TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void stop() {
        if (idleChecker != null) {
            idleChecker.shutdownNow();
            idleChecker = null;
        }
    }

    private void checkIdle() {
        long now = System.currentTimeMillis();
        sockets.forEach(socket -> socket.closeIfIdle(now));
    }

}
