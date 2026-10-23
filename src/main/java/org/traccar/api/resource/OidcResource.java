/*
 * Copyright 2025 Anton Tananaev (anton@traccar.org)
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

import com.nimbusds.jose.JOSEException;
import org.traccar.api.BaseResource;
import org.traccar.api.http.ApiException;
import org.traccar.api.http.ApiReply;
import org.traccar.api.http.ApiResource;
import org.traccar.api.security.OidcSessionManager;
import org.traccar.api.security.OidcSessionManager.AuthorizationCode;
import org.traccar.api.signature.TokenManager;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.QueryParamBuilder;
import org.traccar.model.User;
import org.traccar.storage.StorageException;

import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class OidcResource extends BaseResource {

    private record ClientConfig(String secret, Set<URI> redirectUris) {}

    @Inject
    private Config config;

    @Inject
    private TokenManager tokenManager;

    @Inject
    private OidcSessionManager sessionManager;

    public ApiReply authorize(
            String clientId,
            String redirectUri,
            String state,
            String scope,
            String responseType,
            String codeChallenge,
            String codeChallengeMethod,
            String nonce) {

        ClientConfig client = getClients().get(clientId);
        if (client == null) {
            throw new ApiException(401);
        }

        URI target = URI.create(redirectUri);
        if (!client.redirectUris().contains(target)) {
            throw new ApiException(400);
        }

        String code = sessionManager.issueCode(
                getUserId(), clientId, target, scope, nonce, codeChallenge, codeChallengeMethod);

        QueryParamBuilder redirectBuilder = new QueryParamBuilder(target).queryParam("code", code);
        if (state != null) {
            redirectBuilder.queryParam("state", state);
        }

        return ApiReply.seeOther(redirectBuilder.build());
    }

    public ApiReply token(
            String grantType,
            String code,
            String redirectUri,
            String clientId,
            String clientSecret,
            String codeVerifier,
            String authorization)
            throws StorageException, IOException, GeneralSecurityException, JOSEException {

        if (authorization != null && authorization.startsWith("Basic ")) {
            String[] credentials = new String(Base64.getDecoder().decode(
                    authorization.substring("Basic ".length())), StandardCharsets.UTF_8).split(":");
            clientId = credentials[0];
            clientSecret = credentials[1];
        }

        ClientConfig client = getClients().get(clientId);
        if (client == null || !client.secret().equals(clientSecret)) {
            throw new ApiException(401);
        }

        AuthorizationCode authCode = sessionManager.consumeCode(
                code, clientId, redirectUri != null ? URI.create(redirectUri) : null, codeVerifier);
        if (authCode == null) {
            throw new ApiException(400);
        }

        String token = tokenManager.generateToken(authCode.userId());
        TokenManager.TokenData tokenData = tokenManager.decodeToken(token);
        long expiresIn = Math.max(0, (tokenData.getExpiration().getTime() - System.currentTimeMillis()) / 1000);
        Set<String> scopes = sessionManager.parseScopes(authCode.scope());
        User user = permissionsService.getUser(authCode.userId());
        String idToken = sessionManager.generateIdToken(authCode, clientId, tokenData, scopes, user);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", token);
        response.put("token_type", "Bearer");
        response.put("expires_in", expiresIn);
        response.put("id_token", idToken);
        response.put("scope", authCode.scope());
        return ApiReply.ok(response);
    }

    public Map<String, Object> userInfo() throws StorageException {
        User user = permissionsService.getUser(getUserId());
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("sub", String.valueOf(user.getId()));
        profile.put("name", user.getName());
        profile.put("email", user.getEmail());
        return profile;
    }

    public Map<String, Object> jwks() throws GeneralSecurityException, StorageException, JOSEException {
        return sessionManager.getJwks();
    }

    private Map<String, ClientConfig> getClients() {
        String value = config.getString(Keys.OPENID_CLIENTS);
        if (value == null || value.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, ClientConfig> clients = new LinkedHashMap<>();
        for (String entry : value.split(",")) {
            String[] values = entry.split(":", 3);
            clients.put(values[0], new ClientConfig(values[1], Arrays.stream(values[2].split("\\|"))
                    .map(URI::create)
                    .collect(Collectors.toSet())));
        }
        return clients;
    }

    public static void routes(ApiResource<OidcResource> routes) {
        routes.get("authorize", (resource, call) -> resource.authorize(
                call.query("client_id"), call.query("redirect_uri"), call.query("state"), call.query("scope"),
                call.query("response_type"), call.query("code_challenge"), call.query("code_challenge_method"),
                call.query("nonce")));
        routes.post("token", (resource, call) -> resource.token(
                call.form("grant_type"), call.form("code"), call.form("redirect_uri"), call.form("client_id"),
                call.form("client_secret"), call.form("code_verifier"), call.getHeader("Authorization")))
                .permitAll();
        routes.get("userinfo", (resource, call) -> resource.userInfo());
        routes.get("jwks", (resource, call) -> resource.jwks()).permitAll();
    }

}
