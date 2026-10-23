/*
 * Copyright 2015 - 2026 Anton Tananaev (anton@traccar.org)
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

import com.nimbusds.oauth2.sdk.ParseException;
import org.traccar.api.BaseResource;
import org.traccar.api.http.ApiException;
import org.traccar.api.http.ApiReply;
import org.traccar.api.http.ApiResource;
import org.traccar.api.security.CodeRequiredException;
import org.traccar.api.security.LoginResult;
import org.traccar.api.security.LoginService;
import org.traccar.api.signature.TokenManager;
import org.traccar.database.OpenIdProvider;
import org.traccar.helper.LogAction;
import org.traccar.helper.SessionHelper;
import org.traccar.model.RevokedToken;
import org.traccar.model.User;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;
import org.traccar.web.WebSession;

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Date;

public class SessionResource extends BaseResource {

    @Inject
    private LoginService loginService;

    @Inject
    @Nullable
    private OpenIdProvider openIdProvider;

    @Inject
    private TokenManager tokenManager;

    @Inject
    private LogAction actionLogger;

    public User get(String token) throws StorageException, IOException, GeneralSecurityException {

        if (token != null) {
            LoginResult loginResult;
            try {
                loginResult = loginService.login(token);
            } catch (SecurityException | GeneralSecurityException e) {
                throw new ApiException(401);
            }
            if (loginResult != null) {
                User user = loginResult.getUser();
                SessionHelper.userLogin(actionLogger, request, user, loginResult.getExpiration());
                return user;
            }
        }

        WebSession session = request.getSession(false);
        Long userId = session != null ? (Long) session.getAttribute(SessionHelper.USER_ID_KEY) : null;
        if (userId != null && SessionHelper.isSessionOriginValid(request)) {
            User user = permissionsService.getUser(userId);
            if (user != null) {
                return user;
            }
        }

        throw new ApiException(404);
    }

    public User get(long userId) throws StorageException {
        permissionsService.checkUser(getUserId(), userId);
        User user = storage.getObject(User.class, new Request(
                new Columns.All(), new Condition.Equals("id", userId)));
        SessionHelper.userLogin(actionLogger, request, user, getUserPrincipal().getExpiration());
        return user;
    }

    public User add(
            String email,
            String password,
            Integer code) throws StorageException {
        LoginResult loginResult;
        try {
            loginResult = loginService.login(email, password, code);
        } catch (CodeRequiredException e) {
            throw new ApiException(401).header("WWW-Authenticate", "TOTP");
        }
        if (loginResult != null) {
            User user = loginResult.getUser();
            SessionHelper.userLogin(actionLogger, request, user, null);
            return user;
        } else {
            actionLogger.failedLogin(request);
            throw new ApiException(401);
        }
    }

    public ApiReply remove() {
        actionLogger.logout(request, getUserId());
        request.getSession().removeAttribute(SessionHelper.USER_ID_KEY);
        return ApiReply.noContent();
    }

    public String requestToken(
            Date expiration) throws StorageException, GeneralSecurityException, IOException {
        Date currentExpiration = getUserPrincipal().getExpiration();
        if (currentExpiration != null && (expiration == null || currentExpiration.before(expiration))) {
            expiration = currentExpiration;
        }
        String token = tokenManager.generateToken(getUserId(), expiration);
        TokenManager.TokenData data = tokenManager.decodeToken(token);
        actionLogger.token(request, getUserId(), data.getId());
        return token;
    }

    public ApiReply revokeToken(
            String token) throws StorageException, GeneralSecurityException, IOException {
        TokenManager.TokenData data = tokenManager.decodeToken(token);
        RevokedToken revokedToken = new RevokedToken();
        revokedToken.setId(data.getId());
        storage.addObject(revokedToken, new Request(new Columns.Include("id")));
        return ApiReply.noContent();
    }

    public ApiReply openIdAuth() {
        if (openIdProvider == null) {
            throw new UnsupportedOperationException("OpenID not enabled");
        }
        return ApiReply.seeOther(openIdProvider.createAuthUri());
    }

    public ApiReply requestToken() throws IOException, StorageException, ParseException, GeneralSecurityException {
        if (openIdProvider == null) {
            throw new UnsupportedOperationException("OpenID not enabled");
        }
        return ApiReply.seeOther(openIdProvider.handleCallback(request.getQueryString(), request));
    }

    public static void routes(ApiResource<SessionResource> routes) {
        routes.get("", (resource, call) -> resource.get(call.query("token"))).permitAll();
        routes.get("{id}", (resource, call) -> resource.get(call.pathLong("id")));
        routes.post("", (resource, call) -> resource.add(
                call.form("email"), call.form("password"), call.formInteger("code"))).permitAll();
        routes.delete("", (resource, call) -> resource.remove());
        routes.post("token", (resource, call) -> resource.requestToken(call.formDate("expiration")));
        routes.post("token/revoke", (resource, call) -> resource.revokeToken(call.form("token")));
        routes.get("openid/auth", (resource, call) -> resource.openIdAuth()).permitAll();
        routes.get("openid/callback", (resource, call) -> resource.requestToken()).permitAll();
    }

}
