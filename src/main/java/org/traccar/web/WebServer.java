/*
 * Copyright 2012 - 2026 Anton Tananaev (anton@traccar.org)
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

import com.google.inject.Injector;
import io.helidon.webserver.WebServerConfig;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.websocket.WsRouting;
import org.traccar.BaseProtocol;
import org.traccar.LifecycleObject;
import org.traccar.api.ApiRoutes;
import org.traccar.api.AsyncSocketEndpoint;
import org.traccar.api.http.ApiDispatcher;
import org.traccar.api.security.LoginService;
import org.traccar.api.security.PermissionsService;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.StatisticsManager;
import org.traccar.protocol.OsmAndProtocol;
import org.traccar.storage.Storage;

import java.io.IOException;
import java.nio.file.Path;

public class WebServer implements LifecycleObject {

    private final io.helidon.webserver.WebServer server;
    private final AsyncSocketEndpoint socketEndpoint;
    private final RequestLog requestLog;
    private final ConsoleService consoleService;
    private McpServerHolder mcpServerHolder;

    public WebServer(Injector injector, Config config) throws IOException {
        String address = config.getString(Keys.WEB_ADDRESS);
        int port = config.getInteger(Keys.WEB_PORT);
        SessionStore sessionStore = injector.getInstance(SessionStore.class);
        socketEndpoint = injector.getInstance(AsyncSocketEndpoint.class);
        requestLog = config.hasKey(Keys.WEB_REQUEST_LOG_PATH)
                ? new RequestLog(config.getString(Keys.WEB_REQUEST_LOG_PATH),
                        config.getInteger(Keys.WEB_REQUEST_LOG_RETAIN_DAYS))
                : null;

        HttpRouting.Builder routing = HttpRouting.builder();
        if (requestLog != null) {
            routing.addFilter(requestLog);
        }
        routing.addFilter(new UriCompliance());
        routing.any(AsyncSocketEndpoint.PATH, AsyncSocketEndpoint::refuse);
        routing.register(WellKnownService.PATH, injector.getInstance(WellKnownService.class));

        int osmandPort = config.getInteger(
                Keys.PROTOCOL_PORT.withPrefix(BaseProtocol.nameFromClass(OsmAndProtocol.class)));
        ClientProxy proxy = osmandPort > 0 ? new ClientProxy(osmandPort) : null;
        if (proxy != null) {
            routing.post("/", proxy::forwardRoot);
        }

        String mediaPath = config.getString(Keys.MEDIA_PATH);
        if (mediaPath != null) {
            MediaFiles media = new MediaFiles(
                    Path.of(mediaPath), injector.getInstance(Storage.class),
                    injector.getInstance(StatisticsManager.class), injector.getInstance(LoginService.class),
                    injector.getProvider(PermissionsService.class), sessionStore);
            routing.any(MediaFiles.PATH, media);
            routing.any(MediaFiles.PATH + "/*", media);
        }

        if (config.getBoolean(Keys.WEB_MCP_ENABLE)) {
            mcpServerHolder = injector.getInstance(McpServerHolder.class);
            routing.register(McpServerHolder.PATH,
                    new McpService(mcpServerHolder, injector.getInstance(LoginService.class)));
        }
        routing.register("/api", new ApiDispatcher(injector, config, ApiRoutes.create()));
        consoleService = config.getBoolean(Keys.WEB_CONSOLE) ? new ConsoleService(config) : null;
        if (consoleService != null) {
            routing.register(ConsoleService.PATH, consoleService);
        }
        routing.any(new WebApp(config, injector.getProvider(PermissionsService.class), proxy));

        WebServerConfig.Builder builder = io.helidon.webserver.WebServer.builder()
                .port(port)
                .addRouting(routing)
                .addRouting(WsRouting.builder().endpoint(AsyncSocketEndpoint.PATH, socketEndpoint::create));
        if (address != null) {
            builder.host(address);
        }
        server = builder.build();
    }

    @Override
    public void start() {
        socketEndpoint.start();
        server.start();
    }

    @Override
    public void stop() throws Exception {
        if (mcpServerHolder != null) {
            mcpServerHolder.close();
        }
        server.stop();
        socketEndpoint.stop();
        if (consoleService != null) {
            consoleService.stop();
        }
        if (requestLog != null) {
            requestLog.close();
        }
    }

}
