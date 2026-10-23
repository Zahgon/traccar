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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.http.HttpJsonClient;
import org.traccar.helper.http.HttpReply;
import org.traccar.helper.http.HttpTarget;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Runs a read-only REST API call on behalf of an MCP tool by sending it to this server's own API port.
 */
@Singleton
public class McpApiInvoker {

    private final HttpJsonClient client;
    private final String baseUrl;

    @Inject
    public McpApiInvoker(Config config, ObjectMapper objectMapper) {
        this.client = new HttpJsonClient(objectMapper, 0, 0);
        this.baseUrl = "http://" + loopbackHost(config.getString(Keys.WEB_ADDRESS)) + ":"
                + config.getInteger(Keys.WEB_PORT) + "/api";
    }

    private static String loopbackHost(String address) {
        if (address == null) {
            return "127.0.0.1";
        }
        try {
            InetAddress inetAddress = InetAddress.getByName(address);
            if (inetAddress.isAnyLocalAddress()) {
                return "127.0.0.1";
            }
            String host = inetAddress.getHostAddress();
            return host.contains(":") ? "[" + host + "]" : host;
        } catch (UnknownHostException e) {
            return address;
        }
    }

    public String get(String path, Map<String, Object> arguments, String authorization) {
        HttpTarget target = client.target(baseUrl).path(path);
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            List<?> values = entry.getValue() instanceof List<?> list
                    ? list : Collections.singletonList(entry.getValue());
            if (values.contains(null)) {
                throw new IllegalArgumentException("One or more of query value parameters are null.");
            }
            target = target.queryParam(entry.getKey(), values.toArray());
        }
        HttpReply reply = target.request("application/json").header("Authorization", authorization).get();
        String body = reply.readEntity(String.class);
        if (!reply.isSuccessful()) {
            throw new RuntimeException("Request failed with status " + reply.getStatus() + ": " + body);
        }
        return body;
    }

}
