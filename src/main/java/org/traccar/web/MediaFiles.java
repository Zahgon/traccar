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

import io.helidon.webserver.http.Handler;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import org.traccar.api.security.LoginService;
import org.traccar.api.security.PermissionsService;
import org.traccar.database.StatisticsManager;
import org.traccar.model.Device;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Provider;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Device media under /api/media/{uniqueId}/{file} for a session user with access to the device, always sandboxed
 * by Content-Security-Policy.
 */
public class MediaFiles implements Handler {

    public static final String PATH = "/api/media";

    private final Storage storage;
    private final StatisticsManager statisticsManager;
    private final LoginService loginService;
    private final Provider<PermissionsService> permissionsServiceProvider;
    private final SessionStore sessionStore;
    private final FileResource files;

    public MediaFiles(
            Path mediaPath, Storage storage, StatisticsManager statisticsManager, LoginService loginService,
            Provider<PermissionsService> permissionsServiceProvider, SessionStore sessionStore) {
        this.storage = storage;
        this.statisticsManager = statisticsManager;
        this.loginService = loginService;
        this.permissionsServiceProvider = permissionsServiceProvider;
        this.sessionStore = sessionStore;
        this.files = new FileResource(mediaPath, null, false);
    }

    @Override
    public void handle(ServerRequest request, ServerResponse response) throws IOException {
        response.header("Content-Security-Policy", "sandbox");
        String path = request.path().path();
        String relative = path.length() > PATH.length() ? path.substring(PATH.length()) : null;
        try {
            WebRequest webRequest = new HeadersWebRequest(
                    request.headers(), request.remotePeer().host(), request.query().rawValue(), sessionStore);
            var loginResult = loginService.login(webRequest);
            if (loginResult == null || loginResult.getUser() == null) {
                ErrorPage.send(request, response, 401);
                return;
            }
            long userId = loginResult.getUser().getId();
            statisticsManager.registerRequest(userId);
            String[] parts = relative != null ? relative.split("/") : null;
            if (parts != null && parts.length >= 2) {
                Device device = storage.getObject(Device.class, new Request(
                        new Columns.All(), new Condition.Equals("uniqueId", parts[1])));
                if (device != null) {
                    permissionsServiceProvider.get().checkPermission(Device.class, userId, device.getId());
                    if (!FileResource.answerOtherMethods(request, response)) {
                        files.serve(request, response, relative, path, null);
                    }
                    return;
                }
            }
            ErrorPage.send(request, response, 403);
        } catch (SecurityException | StorageException e) {
            response.status(403);
            response.contentLength(0);
            response.send();
        }
    }

}
