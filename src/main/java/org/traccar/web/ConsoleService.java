/*
 * Copyright 2015 - 2025 Anton Tananaev (anton@traccar.org)
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
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import org.h2.server.web.ConnectionInfo;
import org.h2.server.web.PageParser;
import org.h2.server.web.WebServer;
import org.h2.util.NetworkConnectionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * The H2 web console under /console, driving H2's own console application the way its servlet adapter does: the
 * request parameters and the jsessionid select the console session, the console application picks the page, and
 * the page is answered with its content type, cache headers and template expansion.
 */
public class ConsoleService implements HttpService {

    public static final String PATH = "/console";

    private static final Logger LOGGER = LoggerFactory.getLogger(ConsoleService.class);

    private static final String ALLOW = "GET, HEAD, POST, TRACE, OPTIONS";
    private static final String FORM = "application/x-www-form-urlencoded";

    private final WebServer server;
    private final Method getSession;
    private final Method getStartDateTime;
    private final Method getFile;
    private final Constructor<?> appConstructor;
    private final Method setSession;
    private final Method processRequest;
    private final Method appSession;
    private final Method getMimeType;
    private final Method getCache;
    private final Field sessionMap;

    public ConsoleService(Config config) {
        try {
            Class<?> appClass = Class.forName("org.h2.server.web.WebApp");
            Class<?> sessionClass = Class.forName("org.h2.server.web.WebSession");
            getSession = accessible(WebServer.class.getDeclaredMethod("getSession", String.class));
            getStartDateTime = accessible(WebServer.class.getDeclaredMethod("getStartDateTime"));
            getFile = accessible(WebServer.class.getDeclaredMethod("getFile", String.class));
            appConstructor = appClass.getDeclaredConstructor(WebServer.class);
            appConstructor.setAccessible(true);
            setSession = accessible(appClass.getDeclaredMethod("setSession", sessionClass, Properties.class));
            processRequest = accessible(appClass.getDeclaredMethod(
                    "processRequest", String.class, NetworkConnectionInfo.class));
            appSession = accessible(appClass.getDeclaredMethod("getSession"));
            getMimeType = accessible(appClass.getDeclaredMethod("getMimeType"));
            getCache = accessible(appClass.getDeclaredMethod("getCache"));
            sessionMap = sessionClass.getDeclaredField("map");
            sessionMap.setAccessible(true);

            server = new WebServer();
            accessible(WebServer.class.getDeclaredMethod("setAllowChunked", boolean.class)).invoke(server, false);
            server.init();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("H2 console unavailable", e);
        }

        try {
            ConnectionInfo connectionInfo = new ConnectionInfo("Traccar|"
                    + config.getString(Keys.DATABASE_DRIVER) + "|"
                    + config.getString(Keys.DATABASE_URL) + "|"
                    + config.getString(Keys.DATABASE_USER));
            accessible(WebServer.class.getDeclaredMethod("updateSetting", ConnectionInfo.class))
                    .invoke(server, connectionInfo);
            accessible(WebServer.class.getDeclaredMethod("setAllowOthers", boolean.class)).invoke(server, true);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            LOGGER.warn("Console reflection error", e);
        }
    }

    private static Method accessible(Method method) {
        method.setAccessible(true);
        return method;
    }

    public void stop() {
        server.stop();
    }

    @Override
    public void routing(HttpRules rules) {
        rules.any(this::handle);
    }

    private Object call(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    private void handle(ServerRequest request, ServerResponse response) throws IOException {
        String path = request.path().absolute().path();
        if (!path.equals(PATH) && !path.startsWith(PATH + "/")) {
            response.next();
            return;
        }
        if (DefaultMethods.answer(request, response, ALLOW)) {
            return;
        }
        if (path.equals(PATH)) {
            response.status(302);
            response.header("Location", request.path().absolute().rawPath() + "/");
            response.contentLength(0);
            response.send();
            return;
        }
        String file = path.substring(PATH.length() + 1);
        if (!allowed(request)) {
            file = "notAllowed.jsp";
        } else if (file.isEmpty()) {
            file = "index.do";
        }

        Properties attributes = new Properties();
        parameters(request).forEach(attributes::put);
        String sessionId = attributes.getProperty("jsessionid");
        Object session = sessionId != null ? call(getSession, server, sessionId) : null;
        Object app;
        try {
            app = appConstructor.newInstance(server);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        call(setSession, app, session, attributes);

        String ifModifiedSince = request.headers().first(HeaderNames.IF_MODIFIED_SINCE).orElse(null);
        file = (String) call(processRequest, app, file, connectionInfo(request));
        session = call(appSession, app);
        String mimeType = (String) call(getMimeType, app);
        boolean cache = (Boolean) call(getCache, app);
        String startDateTime = (String) call(getStartDateTime, server);

        if (cache && startDateTime.equals(ifModifiedSince)) {
            response.status(304);
            response.contentLength(0);
            response.send();
            return;
        }
        byte[] bytes = (byte[]) call(getFile, server, file);
        if (bytes == null) {
            ErrorPage.send(request, response, 404);
            return;
        }
        if (session != null && file.endsWith(".jsp")) {
            String page = new String(bytes, StandardCharsets.UTF_8);
            page = PageParser.parse(page, sessionMap(session));
            bytes = page.getBytes(StandardCharsets.UTF_8);
        }
        response.header("Content-Type", mimeType);
        if (cache) {
            response.header("Cache-Control", "max-age=10");
            response.header("Last-Modified", startDateTime);
        } else {
            response.header("Cache-Control", "no-cache");
        }
        if (request.prologue().method().text().equals("HEAD")) {
            response.contentLength(0);
            response.send();
        } else {
            response.send(bytes);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sessionMap(Object session) {
        try {
            return (Map<String, Object>) sessionMap.get(session);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean allowed(ServerRequest request) {
        if (server.getAllowOthers()) {
            return true;
        }
        try {
            return InetAddress.getByName(remoteAddress(request)).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static String remoteAddress(ServerRequest request) {
        SocketAddress address = request.remotePeer().address();
        if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        return request.remotePeer().host();
    }

    private static NetworkConnectionInfo connectionInfo(ServerRequest request) throws UnknownHostException {
        String host = request.headers().first(HeaderNames.HOST).orElse(request.localPeer().host());
        int port = 80;
        int index = host.lastIndexOf(':');
        if (index > host.lastIndexOf(']')) {
            port = Integer.parseInt(host.substring(index + 1));
            host = host.substring(0, index);
        }
        StringBuilder url = new StringBuilder("http://").append(host);
        if (port != 80) {
            url.append(':').append(port);
        }
        return new NetworkConnectionInfo(url.toString(), remoteAddress(request), request.remotePeer().port());
    }

    /**
     * The first value of every query parameter, then of every form parameter of a form POST.
     */
    private static Map<String, String> parameters(ServerRequest request) {
        Map<String, String> result = new LinkedHashMap<>();
        decode(request.query().rawValue(), result);
        String type = request.headers().first(HeaderNames.CONTENT_TYPE).orElse("");
        if (request.prologue().method().text().equals("POST")
                && type.toLowerCase(Locale.ROOT).startsWith(FORM) && request.content().hasEntity()) {
            decode(new String(request.content().as(byte[].class), StandardCharsets.UTF_8), result);
        }
        return result;
    }

    private static void decode(String encoded, Map<String, String> result) {
        if (encoded == null || encoded.isEmpty()) {
            return;
        }
        for (String pair : encoded.split("&")) {
            if (!pair.isEmpty()) {
                int index = pair.indexOf('=');
                String name = decodeComponent(index >= 0 ? pair.substring(0, index) : pair);
                String value = decodeComponent(index >= 0 ? pair.substring(index + 1) : "");
                result.putIfAbsent(name, value);
            }
        }
    }

    private static String decodeComponent(String text) {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return text;
        }
    }

}
