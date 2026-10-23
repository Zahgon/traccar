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
package org.traccar.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import org.traccar.api.security.OidcSessionManager;
import org.traccar.config.Config;
import org.traccar.helper.WebHelper;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The OpenID and OAuth discovery documents under /.well-known.
 */
@Singleton
public class WellKnownService implements HttpService {

    public static final String PATH = "/.well-known";

    private static final String ALLOW = "GET, HEAD, TRACE, OPTIONS";

    private final Config config;
    private final ObjectMapper objectMapper;

    @Inject
    public WellKnownService(Config config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @Override
    public void routing(HttpRules rules) {
        rules.any(this::handle);
    }

    private void handle(ServerRequest request, ServerResponse response) throws IOException {
        String path = request.path().absolute().path();
        if (!path.startsWith(PATH + "/")) {
            response.next();
            return;
        }
        if (DefaultMethods.answer(request, response, ALLOW)) {
            return;
        }
        Map<String, Object> payload = switch (path.substring(PATH.length())) {
            case "/openid-configuration", "/openid-configuration/api/oidc" -> openIdConfiguration();
            case "/oauth-authorization-server", "/oauth-authorization-server/api/oidc" ->
                    authorizationServerConfiguration();
            case "/oauth-protected-resource", "/oauth-protected-resource/api/mcp" ->
                    protectedResourceConfiguration();
            default -> null;
        };
        if (payload == null) {
            ErrorPage.send(request, response, 404);
            return;
        }
        byte[] body = objectMapper.writeValueAsBytes(payload);
        response.header("Content-Type", "application/json");
        if (request.prologue().method().text().equals("HEAD")) {
            response.contentLength(body.length);
            response.send();
        } else {
            response.send(body);
        }
    }

    private String issuer() {
        return WebHelper.retrieveWebUrl(config) + "/api/oidc";
    }

    private String mcpResource() {
        return WebHelper.retrieveWebUrl(config) + "/api/mcp";
    }

    private Map<String, Object> openIdConfiguration() {
        String issuer = issuer();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("issuer", issuer);
        payload.put("authorization_endpoint", issuer + "/authorize");
        payload.put("token_endpoint", issuer + "/token");
        payload.put("userinfo_endpoint", issuer + "/userinfo");
        payload.put("jwks_uri", issuer + "/jwks");
        payload.put("subject_types_supported", List.of("public"));
        payload.put("response_types_supported", List.of("code"));
        payload.put("grant_types_supported", List.of("authorization_code"));
        payload.put("scopes_supported", List.of("openid", "profile", "email"));
        payload.put("id_token_signing_alg_values_supported", List.of(OidcSessionManager.ID_TOKEN_ALGORITHM));
        payload.put("code_challenge_methods_supported", List.of("S256", "plain"));
        payload.put("token_endpoint_auth_methods_supported",
                List.of("client_secret_basic", "client_secret_post", "none"));
        return payload;
    }

    private Map<String, Object> authorizationServerConfiguration() {
        String issuer = issuer();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("issuer", issuer);
        payload.put("authorization_endpoint", issuer + "/authorize");
        payload.put("token_endpoint", issuer + "/token");
        payload.put("jwks_uri", issuer + "/jwks");
        payload.put("response_types_supported", List.of("code"));
        payload.put("grant_types_supported", List.of("authorization_code"));
        payload.put("scopes_supported", List.of("openid", "profile", "email"));
        payload.put("code_challenge_methods_supported", List.of("S256", "plain"));
        payload.put("token_endpoint_auth_methods_supported",
                List.of("client_secret_basic", "client_secret_post", "none"));
        return payload;
    }

    private Map<String, Object> protectedResourceConfiguration() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("issuer", issuer());
        payload.put("authorization_servers", List.of(issuer()));
        payload.put("jwks_uri", issuer() + "/jwks");
        payload.put("scopes_supported", List.of("openid", "profile", "email"));
        payload.put("resource", mcpResource());
        return payload;
    }

}
