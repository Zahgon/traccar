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
package org.traccar.api.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.helidon.http.HeaderNames;
import io.helidon.webserver.http.ServerRequest;
import org.traccar.helper.DateUtil;
import org.traccar.web.SessionStore;
import org.traccar.web.WebRequest;
import org.traccar.web.WebSession;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * One API request as the resources see it: typed path, query and form parameters with the conversion rules the REST
 * API has always applied, the JSON entity, and the cookie session.
 */
public class ApiCall implements WebRequest {

    public static final String FORM_REQUIRED =
            "The @FormParam is utilized when the content type of the request entity is not "
            + "application/x-www-form-urlencoded";

    private final ServerRequest request;
    private final ObjectMapper objectMapper;
    private final SessionStore sessionStore;
    private Map<String, String> pathParameters = Map.of();
    private final Map<String, List<String>> queryParameters;
    private byte[] body;
    private Map<String, List<String>> formParameters;
    private WebSession session;
    private WebSession createdSession;
    private boolean sessionResolved;

    public ApiCall(ServerRequest request, ObjectMapper objectMapper, SessionStore sessionStore) {
        this.request = request;
        this.objectMapper = objectMapper;
        this.sessionStore = sessionStore;
        this.queryParameters = decode(request.query().rawValue());
    }

    void pathParameters(Map<String, String> parameters) {
        this.pathParameters = parameters;
    }

    static Map<String, List<String>> decode(String encoded) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return result;
        }
        for (String pair : encoded.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int index = pair.indexOf('=');
            String name = index >= 0 ? pair.substring(0, index) : pair;
            String value = index >= 0 ? pair.substring(index + 1) : "";
            result.computeIfAbsent(URLDecoder.decode(name, StandardCharsets.UTF_8), k -> new ArrayList<>())
                    .add(URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return result;
    }

    // ---------------------------------------------------------------- WebRequest

    @Override
    public String getHeader(String name) {
        return request.headers().first(HeaderNames.create(name)).orElse(null);
    }

    @Override
    public String getRemoteAddr() {
        SocketAddress address = request.remotePeer().address();
        if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        return request.remotePeer().host();
    }

    @Override
    public String getQueryString() {
        String query = request.query().rawValue();
        return query == null || query.isEmpty() ? null : query;
    }

    @Override
    public WebSession getSession(boolean create) {
        if (!sessionResolved) {
            sessionResolved = true;
            session = sessionStore.find(cookie(SessionStore.COOKIE_NAME));
        }
        if (session != null && !session.isValid()) {
            session = null;
        }
        if (session == null && create) {
            session = sessionStore.create();
            createdSession = session;
        }
        return session;
    }

    public WebSession getCreatedSession() {
        return createdSession != null && createdSession.isValid() ? createdSession : null;
    }

    private String cookie(String name) {
        for (String header : request.headers().all(HeaderNames.COOKIE, List::of)) {
            for (String part : header.split(";")) {
                String trimmed = part.trim();
                int index = trimmed.indexOf('=');
                if (index > 0 && trimmed.substring(0, index).equals(name)) {
                    String value = trimmed.substring(index + 1);
                    if (sessionStore.find(value) != null) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- parameters

    private static <T> T convert(String value, Function<String, T> parser, int failureStatus) {
        try {
            return parser.apply(value);
        } catch (RuntimeException e) {
            throw new ApiException(failureStatus);
        }
    }

    private static String first(Map<String, List<String>> parameters, String name) {
        List<String> values = parameters.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public String path(String name) {
        return pathParameters.get(name);
    }

    public long pathLong(String name) {
        return convert(pathParameters.get(name), Long::valueOf, 404);
    }

    public int pathInt(String name) {
        return convert(pathParameters.get(name), Integer::valueOf, 404);
    }

    public String query(String name) {
        return first(queryParameters, name);
    }

    public long queryLong(String name) {
        String value = query(name);
        return blank(value) ? 0 : convert(value, Long::valueOf, 404);
    }

    public int queryInt(String name) {
        String value = query(name);
        return blank(value) ? 0 : convert(value, Integer::valueOf, 404);
    }

    public double queryDouble(String name) {
        String value = query(name);
        return blank(value) ? 0 : convert(value, Double::valueOf, 404);
    }

    public boolean queryBoolean(String name) {
        String value = query(name);
        return !blank(value) && Boolean.parseBoolean(value);
    }

    public Date queryDate(String name) {
        String value = query(name);
        return value == null ? null : convert(value, DateUtil::parseDate, 404);
    }

    public List<String> queryList(String name) {
        return List.copyOf(queryParameters.getOrDefault(name, List.of()));
    }

    public List<Long> queryLongList(String name) {
        List<Long> result = new ArrayList<>();
        for (String value : queryParameters.getOrDefault(name, List.of())) {
            result.add(convert(value, Long::valueOf, 404));
        }
        return result;
    }

    private Map<String, List<String>> form() {
        if (formParameters == null) {
            String type = getHeader("Content-Type");
            if (type == null || !type.toLowerCase().startsWith("application/x-www-form-urlencoded")) {
                throw new IllegalStateException(FORM_REQUIRED);
            }
            formParameters = decode(new String(body(), StandardCharsets.UTF_8));
        }
        return formParameters;
    }

    public String form(String name) {
        return first(form(), name);
    }

    public long formLong(String name) {
        String value = form(name);
        return blank(value) ? 0 : convert(value, Long::valueOf, 400);
    }

    public Integer formInteger(String name) {
        String value = form(name);
        return value == null ? null : convert(value, Integer::valueOf, 400);
    }

    public Date formDate(String name) {
        String value = form(name);
        return value == null ? null : convert(value, DateUtil::parseDate, 400);
    }

    // ---------------------------------------------------------------- entity

    public byte[] body() {
        if (body == null) {
            body = request.content().hasEntity() ? request.content().as(byte[].class) : new byte[0];
        }
        return body;
    }

    public <T> T json(Class<T> type) throws ApiJsonException {
        byte[] data = body();
        if (data.length == 0) {
            return null;
        }
        try {
            return objectMapper.readValue(data, type);
        } catch (JsonProcessingException e) {
            throw new ApiJsonException(e);
        } catch (IOException e) {
            throw new ApiException(e);
        }
    }

    public <T> T json(TypeReference<T> type) throws ApiJsonException {
        byte[] data = body();
        if (data.length == 0) {
            return null;
        }
        try {
            return objectMapper.readValue(data, type);
        } catch (JsonProcessingException e) {
            throw new ApiJsonException(e);
        } catch (IOException e) {
            throw new ApiException(e);
        }
    }

    public File file() throws IOException {
        File file = File.createTempFile("traccar-upload", ".tmp");
        file.deleteOnExit();
        Files.write(file.toPath(), body());
        return file;
    }

}
