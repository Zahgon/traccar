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

import io.helidon.http.HeaderName;
import io.helidon.http.HeaderNames;
import io.helidon.webserver.http.Handler;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Set;

/**
 * Forwards /client-proxy/* and POST / to the local OsmAnd protocol port and answers what that port answers.
 */
public class ClientProxy implements Handler {

    public static final String PATH = "/client-proxy";

    private static final Set<String> SKIPPED_HEADERS = Set.of(
            "connection", "content-length", "expect", "host", "keep-alive", "proxy-connection", "te",
            "trailer", "transfer-encoding", "upgrade");

    private final int port;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    public ClientProxy(int port) {
        this.port = port;
    }

    @Override
    public void handle(ServerRequest request, ServerResponse response) throws IOException {
        String path = request.path().rawPath().substring(PATH.length());
        forward(request, response, path.isEmpty() ? "/" : path);
    }

    public void forwardRoot(ServerRequest request, ServerResponse response) throws IOException {
        forward(request, response, "/");
    }

    private void forward(ServerRequest request, ServerResponse response, String path) throws IOException {
        String query = request.query().rawValue();
        URI target = URI.create("http://localhost:" + port + path + (query.isEmpty() ? "" : "?" + query));
        byte[] body = request.content().hasEntity() ? request.content().inputStream().readAllBytes() : new byte[0];
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .method(request.prologue().method().text(), body.length > 0
                        ? HttpRequest.BodyPublishers.ofByteArray(body) : HttpRequest.BodyPublishers.noBody());
        request.headers().forEach(header -> {
            if (!SKIPPED_HEADERS.contains(header.name().toLowerCase(Locale.ROOT))) {
                header.allValues().forEach(value -> builder.header(header.name(), value));
            }
        });
        HttpResponse<byte[]> reply;
        try {
            reply = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Client proxy interrupted", e);
        } catch (IOException e) {
            ErrorPage.send(request, response, 502);
            return;
        }
        response.status(reply.statusCode());
        reply.headers().map().forEach((name, values) -> {
            if (!SKIPPED_HEADERS.contains(name.toLowerCase(Locale.ROOT)) && !name.startsWith(":")) {
                HeaderName headerName = HeaderNames.create(name);
                values.forEach(value -> response.headers().add(headerName, value));
            }
        });
        response.send(reply.body());
    }

}
