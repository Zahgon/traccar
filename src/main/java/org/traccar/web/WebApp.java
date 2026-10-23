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
import io.helidon.webserver.http.Handler;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import org.traccar.api.security.PermissionsService;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Server;
import org.traccar.storage.StorageException;

import jakarta.inject.Provider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.UnaryOperator;

/**
 * The web app outside /api: files from web.path, files in web.override taking precedence (and served directly under
 * /override), the index page for app routes requested as HTML, and the server's title, description and primary
 * colour filled into HTML pages and the web manifest.
 */
public class WebApp implements Handler {

    private static final String OVERRIDE_PREFIX = "/override";

    private final Provider<PermissionsService> permissionsServiceProvider;
    private final FileResource base;
    private final FileResource override;
    private final ClientProxy clientProxy;

    public WebApp(Config config, Provider<PermissionsService> permissionsServiceProvider, ClientProxy clientProxy)
            throws IOException {
        this.permissionsServiceProvider = permissionsServiceProvider;
        this.clientProxy = clientProxy;
        String cacheControl = config.getString(Keys.WEB_CACHE_CONTROL);
        Path baseReal = Paths.get(config.getString(Keys.WEB_PATH)).toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path overridePath = Paths.get(config.getString(Keys.WEB_OVERRIDE));
        Files.createDirectories(overridePath);
        Path overrideReal = overridePath.toRealPath(LinkOption.NOFOLLOW_LINKS);
        base = new FileResource(baseReal, cacheControl, true);
        override = new FileResource(overrideReal, cacheControl, false);
    }

    @Override
    public void handle(ServerRequest request, ServerResponse response) throws IOException {
        String path = request.path().path();
        if (path.isEmpty()) {
            path = "/";
        }
        String method = request.prologue().method().text();
        boolean read = method.equals("GET") || method.equals("HEAD");
        if (read && serveShell(request, response, path)) {
            return;
        }
        if (clientProxy != null && (path.equals(ClientProxy.PATH) || path.startsWith(ClientProxy.PATH + "/"))) {
            clientProxy.handle(request, response);
        } else if (!FileResource.answerOtherMethods(request, response)) {
            serve(request, response, path);
        }
    }

    /**
     * Serves an override file or, for an HTML request of an application route, the index page; false otherwise.
     */
    private boolean serveShell(ServerRequest request, ServerResponse response, String path) throws IOException {
        if (!path.startsWith("/api") && !path.startsWith("/console")) {
            if (override.file(path) != null) {
                override.serve(request, response, path, path, transform(path));
                return true;
            }
            String accept = request.headers().first(HeaderNames.ACCEPT).orElse(null);
            boolean acceptHtml = accept != null && accept.contains("text/html");
            String last = path.endsWith("/") ? "" : path.substring(path.lastIndexOf('/') + 1);
            if (acceptHtml && (path.endsWith("/") || !last.contains("."))) {
                base.serve(request, response, "/index.html", "/index.html", this::fillTemplate);
                return true;
            }
        }
        return false;
    }

    private void serve(ServerRequest request, ServerResponse response, String path) throws IOException {
        if (path.equals(OVERRIDE_PREFIX) || path.startsWith(OVERRIDE_PREFIX + "/")) {
            String relative = path.substring(OVERRIDE_PREFIX.length());
            override.serve(request, response, relative, path, transform(path));
        } else {
            base.serve(request, response, path, path, transform(path));
        }
    }

    private UnaryOperator<byte[]> transform(String path) {
        String type = MimeTypes.forName(path.endsWith("/") ? "index.html" : path);
        boolean html = type != null && type.contains("text/html");
        return html || path.endsWith("manifest.webmanifest") ? this::fillTemplate : null;
    }

    private byte[] fillTemplate(byte[] data) {
        Server server;
        try {
            server = permissionsServiceProvider.get().getServer();
        } catch (StorageException e) {
            throw new IllegalStateException("Server attributes unavailable", e);
        }
        String title = server.getString("title", "Traccar");
        String description = server.getString("description", "Traccar GPS Tracking System");
        String colorPrimary = server.getString("colorPrimary", "#1a237e");
        return new String(data, StandardCharsets.UTF_8)
                .replace("${title}", title)
                .replace("${description}", description)
                .replace("${colorPrimary}", colorPrimary)
                .getBytes(StandardCharsets.UTF_8);
    }

}
