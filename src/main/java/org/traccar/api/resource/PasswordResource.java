/*
 * Copyright 2021 - 2026 Anton Tananaev (anton@traccar.org)
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
package org.traccar.api.resource;

import org.traccar.api.BaseResource;
import org.traccar.api.http.ApiReply;
import org.traccar.api.http.ApiResource;
import org.traccar.api.signature.TokenManager;
import org.traccar.mail.MailManager;
import org.traccar.model.User;
import org.traccar.notification.TextTemplateFormatter;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

public class PasswordResource extends BaseResource {

    @Inject
    private MailManager mailManager;

    @Inject
    private TokenManager tokenManager;

    @Inject
    private TextTemplateFormatter textTemplateFormatter;

    public ApiReply reset(String email)
            throws StorageException, ExecutionException, InterruptedException, GeneralSecurityException, IOException {

        User user = storage.getObject(User.class, new Request(
                new Columns.All(), new Condition.Equals("LOWER(email)", email.toLowerCase(Locale.ROOT))));
        if (user != null) {
            var velocityContext = textTemplateFormatter.prepareContext(permissionsService.getServer(), user);
            var fullMessage = textTemplateFormatter.formatMessage(velocityContext, "passwordReset", false);
            mailManager.sendMessage(user, true, fullMessage.subject(), fullMessage.body()).get();
        }
        return ApiReply.ok();
    }

    public ApiReply update(
            String token, String password)
            throws StorageException, GeneralSecurityException, IOException {

        long userId = tokenManager.verifyToken(token).getUserId();
        User user = storage.getObject(User.class, new Request(
                new Columns.All(), new Condition.Equals("id", userId)));
        if (user != null) {
            user.setPassword(password);
            storage.updateObject(user, new Request(
                    new Columns.Include("hashedPassword", "salt"),
                    new Condition.Equals("id", userId)));
            return ApiReply.ok();
        }
        return ApiReply.status(404);
    }

    public static void routes(ApiResource<PasswordResource> routes) {
        routes.post("reset", (resource, call) -> resource.reset(call.form("email"))).permitAll();
        routes.post("update", (resource, call) -> resource.update(
                call.form("token"), call.form("password"))).permitAll();
    }

}
