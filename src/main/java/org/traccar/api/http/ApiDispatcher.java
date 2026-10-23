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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Injector;
import io.helidon.http.HeaderNames;
import io.helidon.http.Method;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.BaseResource;
import org.traccar.api.security.LoginResult;
import org.traccar.api.security.LoginService;
import org.traccar.api.security.UserPrincipal;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.StatisticsManager;
import org.traccar.model.User;
import org.traccar.storage.StorageException;
import org.traccar.web.SessionStore;
import org.traccar.web.UriCompliance;
import org.traccar.web.WebSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.stream.Stream;

/**
 * Helidon SE service that serves the REST API under /api from the explicit route tables of the resources. It keeps
 * the request semantics clients rely on: path selection by template specificity, 404 for unmatched paths and
 * unconvertible path or query parameters, 405 with Allow, automatic HEAD and OPTIONS, 415 and 406 negotiation,
 * authentication, error bodies and CORS headers.
 */
public class ApiDispatcher implements HttpService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiDispatcher.class);

    private static final String HEADERS_ALL = "origin, content-type, accept, authorization";
    private static final String METHODS_ALL = "GET, POST, PUT, DELETE, OPTIONS";
    private static final String ORIGIN_ALL = "*";

    private final Injector injector;
    private final ObjectMapper objectMapper;
    private final SessionStore sessionStore;
    private final LoginService loginService;
    private final StatisticsManager statisticsManager;
    private final String allowedOrigin;
    private final boolean showStackTraces;
    private final List<ApiResource<?>> resources;

    public ApiDispatcher(Injector injector, Config config, List<ApiResource<?>> resources) {
        this.injector = injector;
        this.objectMapper = injector.getInstance(ObjectMapper.class);
        this.sessionStore = injector.getInstance(SessionStore.class);
        this.loginService = injector.getInstance(LoginService.class);
        this.statisticsManager = injector.getInstance(StatisticsManager.class);
        this.allowedOrigin = config.getString(Keys.WEB_ORIGIN);
        this.showStackTraces = config.getBoolean(Keys.WEB_SHOW_STACK_TRACES);
        this.resources = resources;
    }

    @Override
    public void routing(HttpRules rules) {
        rules.any(this::handle);
    }

    private record Outcome(int status, Map<String, String> headers, String type, byte[] body) {
    }

    private record Selection<R extends BaseResource>(
            ApiResource<R> resource, ApiResource.Route<R> route, Map<String, String> parameters) {
    }

    private void handle(ServerRequest request, ServerResponse response) {
        String method = request.prologue().method().text();
        String path = request.path().absolute().path();
        String remainder = path.startsWith("/api") ? path.substring(4) : path;
        ApiCall call = new ApiCall(request, objectMapper, sessionStore);
        Outcome outcome;
        try {
            outcome = UriCompliance.hasDotSegment(request.prologue().uriPath().rawPath())
                    ? error(new ApiException(404), null) : dispatch(method, remainder, request, call);
        } catch (RuntimeException e) {
            LOGGER.warn("API dispatch error", e);
            outcome = failure(e, null);
        }
        write(request, response, call, method, outcome);
    }

    // ---------------------------------------------------------------- selection

    private ApiResource<?> selectResource(String path) {
        ApiResource<?> best = null;
        for (ApiResource<?> resource : resources) {
            if (resource.base().match(path) != null
                    && (best == null || resource.base().compareSpecificity(best.base()) < 0)) {
                best = resource;
            }
        }
        return best;
    }

    private Outcome dispatch(String method, String path, ServerRequest request, ApiCall rootCall) {
        ApiResource<?> resource = path.isEmpty() || path.equals("/") ? null : selectResource(path);
        if (resource == null) {
            return error(new ApiException(404), null);
        }
        return dispatch(resource, method, path, request, rootCall);
    }

    private <R extends BaseResource> Outcome dispatch(
            ApiResource<R> resource, String method, String path, ServerRequest request, ApiCall rootCall) {

        Matcher baseMatcher = resource.base().match(path);
        String rest = baseMatcher.group(baseMatcher.groupCount()) != null
                ? baseMatcher.group(baseMatcher.groupCount()) : "";

        Map<ApiResource.Template, Matcher> matched = new LinkedHashMap<>();
        for (ApiResource.Route<R> route : resource.routes()) {
            if (!matched.containsKey(route.template())) {
                Matcher matcher = route.template().match(rest);
                if (matcher != null) {
                    matched.put(route.template(), matcher);
                }
            }
        }
        if (matched.isEmpty()) {
            return error(new ApiException(404), null);
        }
        List<ApiResource.Template> templates = new ArrayList<>(matched.keySet());
        templates.sort(ApiResource.Template::compareSpecificity);

        if (method.equals("OPTIONS")) {
            Set<String> allowed = allowedMethods(resource, templates.get(0), true);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Allow", String.join(",", allowed));
            return new Outcome(200, headers, ApiResource.TEXT,
                    String.join(", ", allowed).getBytes(StandardCharsets.UTF_8));
        }

        String effective = method.equals("HEAD") ? "GET" : method;
        List<ApiResource.Route<R>> candidates = null;
        ApiResource.Template template = null;
        for (ApiResource.Template current : templates) {
            List<ApiResource.Route<R>> routes = resource.routes().stream()
                    .filter(route -> route.template().equals(current) && route.method().equals(effective))
                    .toList();
            if (!routes.isEmpty()) {
                candidates = routes;
                template = current;
                break;
            }
        }
        if (candidates == null) {
            ApiException exception = new ApiException(405);
            exception.header("Allow", String.join(",", allowedMethods(resource, templates.get(0), false)));
            return error(exception, null);
        }

        String contentType = mediaType(rootCall.getHeader("Content-Type"));
        if (contentType != null) {
            candidates = candidates.stream()
                    .filter(route -> route.consumes().stream().anyMatch(type -> compatible(type, contentType)))
                    .toList();
            if (candidates.isEmpty()) {
                return error(new ApiException(415), null);
            }
        }
        ApiResource.Route<R> route = negotiate(candidates, rootCall.getHeader("Accept"));
        if (route == null) {
            return error(new ApiException(406), null);
        }

        Matcher matcher = matched.get(template);
        Map<String, String> parameters = new LinkedHashMap<>();
        for (int i = 0; i < template.names().size(); i++) {
            parameters.put(template.names().get(i),
                    java.net.URLDecoder.decode(matcher.group(i + 1).replace("+", "%2B"), StandardCharsets.UTF_8));
        }
        return invoke(new Selection<>(resource, route, parameters), request, rootCall);
    }

    private static <R extends BaseResource> Set<String> allowedMethods(
            ApiResource<R> resource, ApiResource.Template template, boolean automatic) {
        Set<String> methods = new LinkedHashSet<>();
        for (ApiResource.Route<R> route : resource.routes()) {
            if (route.template().equals(template)) {
                methods.add(route.method());
            }
        }
        Set<String> result = new LinkedHashSet<>();
        if (automatic && methods.contains("GET")) {
            result.add("HEAD");
        }
        result.addAll(methods);
        result.add("OPTIONS");
        return result;
    }

    private static String mediaType(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        int index = header.indexOf(';');
        return (index >= 0 ? header.substring(0, index) : header).trim().toLowerCase(Locale.ROOT);
    }

    private static boolean compatible(String a, String b) {
        String[] left = a.split("/", 2);
        String[] right = b.split("/", 2);
        if (left.length < 2 || right.length < 2) {
            return a.equals("*") || b.equals("*");
        }
        boolean typeMatch = left[0].equals("*") || right[0].equals("*") || left[0].equals(right[0]);
        boolean subtypeMatch = left[1].equals("*") || right[1].equals("*") || left[1].equals(right[1]);
        return typeMatch && subtypeMatch;
    }

    private record Range(String type, double quality, int specificity) {
    }

    private static List<Range> accepted(String header) {
        List<Range> ranges = new ArrayList<>();
        if (header == null || header.isBlank()) {
            ranges.add(new Range("*/*", 1, 0));
            return ranges;
        }
        for (String part : header.split(",")) {
            String[] pieces = part.split(";");
            String type = pieces[0].trim().toLowerCase(Locale.ROOT);
            if (type.isEmpty()) {
                continue;
            }
            if (type.equals("*")) {
                type = "*/*";
            }
            double quality = 1;
            for (int i = 1; i < pieces.length; i++) {
                String parameter = pieces[i].trim();
                if (parameter.startsWith("q=")) {
                    try {
                        quality = Double.parseDouble(parameter.substring(2));
                    } catch (NumberFormatException e) {
                        quality = 1;
                    }
                }
            }
            int specificity = type.equals("*/*") ? 0 : type.endsWith("/*") ? 1 : 2;
            ranges.add(new Range(type, quality, specificity));
        }
        return ranges;
    }

    private static <R extends BaseResource> ApiResource.Route<R> negotiate(
            List<ApiResource.Route<R>> candidates, String accept) {
        List<Range> ranges = accepted(accept);
        ApiResource.Route<R> best = null;
        double bestQuality = -1;
        int bestSpecificity = -1;
        for (ApiResource.Route<R> route : candidates) {
            for (String produced : route.produces()) {
                for (Range range : ranges) {
                    if (compatible(range.type(), produced)) {
                        if (range.quality() > bestQuality
                                || range.quality() == bestQuality && range.specificity() >= bestSpecificity) {
                            best = route;
                            bestQuality = range.quality();
                            bestSpecificity = range.specificity();
                        }
                    }
                }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- invocation

    private UserPrincipal authenticate(ApiCall call) {
        UserPrincipal principal = null;
        try {
            String authHeader = call.getHeader("Authorization");
            if (authHeader != null) {
                try {
                    String[] auth = authHeader.split(" ");
                    LoginResult loginResult = loginService.login(auth[0], auth[1]);
                    if (loginResult != null && loginResult.getUser() != null) {
                        User user = loginResult.getUser();
                        statisticsManager.registerRequest(user.getId());
                        principal = new UserPrincipal(user.getId(), loginResult.getExpiration());
                    }
                } catch (StorageException | GeneralSecurityException | IOException e) {
                    throw new ApiException(e);
                }
            } else {
                LoginResult loginResult = loginService.login(call);
                if (loginResult != null && loginResult.getUser() != null) {
                    User user = loginResult.getUser();
                    statisticsManager.registerRequest(user.getId());
                    principal = new UserPrincipal(user.getId(), loginResult.getExpiration());
                }
            }
        } catch (SecurityException | StorageException e) {
            LOGGER.warn("Authentication error", e);
        }
        return principal;
    }

    private <R extends BaseResource> Outcome invoke(Selection<R> selection, ServerRequest request, ApiCall rootCall) {
        ApiResource.Route<R> route = selection.route();
        String produced = route.produces().get(0);
        ApiCall call = rootCall;
        call.pathParameters(selection.parameters());
        try {
            UserPrincipal principal = authenticate(call);
            if (principal == null && !route.isPermitAll()) {
                ApiException exception = new ApiException(401);
                String accept = call.getHeader("Accept");
                if (accept != null && accept.contains("text/html")) {
                    exception.header("WWW-Authenticate", "Basic realm=\"api\"");
                }
                throw exception;
            }
            R resource = injector.getInstance(selection.resource().type());
            resource.bind(call, principal);
            Object result = route.handler().handle(resource, call);
            return success(result, produced);
        } catch (Exception e) {
            return failure(e, produced);
        }
    }

    // ---------------------------------------------------------------- responses

    private Outcome success(Object result, String produced) throws IOException {
        ApiReply reply = result instanceof ApiReply apiReply ? apiReply
                : result == null ? ApiReply.noContent() : ApiReply.ok(result);
        String type = reply.getMediaType() != null ? reply.getMediaType() : produced;
        Object entity = reply.getEntity();
        if (entity == null) {
            if (reply.getStatus() >= 400) {
                return new Outcome(reply.getStatus(), reply.getHeaders(), null, null);
            }
            return new Outcome(reply.getStatus(), reply.getHeaders(), null, new byte[0]);
        }
        return new Outcome(reply.getStatus(), reply.getHeaders(), type, serialize(entity));
    }

    private byte[] serialize(Object entity) throws IOException {
        if (entity instanceof byte[] bytes) {
            return bytes;
        } else if (entity instanceof String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        } else if (entity instanceof ApiReply.Body body) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            body.write(output);
            return output.toByteArray();
        } else if (entity instanceof Stream<?> stream) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (stream; var generator = objectMapper.createGenerator(output)) {
                generator.writeStartArray();
                for (var iterator = stream.iterator(); iterator.hasNext();) {
                    objectMapper.writeValue(generator, iterator.next());
                }
                generator.writeEndArray();
            }
            return output.toByteArray();
        } else {
            return objectMapper.writeValueAsBytes(entity);
        }
    }

    private String describe(Throwable throwable) {
        if (showStackTraces) {
            StringWriter writer = new StringWriter();
            throwable.printStackTrace(new PrintWriter(writer));
            return writer.toString();
        }
        return throwable.getMessage();
    }

    private Outcome error(ApiException exception, String produced) {
        return new Outcome(exception.getStatus(), exception.getHeaders(), produced,
                describe(exception).getBytes(StandardCharsets.UTF_8));
    }

    private Outcome failure(Throwable throwable, String produced) {
        while ((throwable instanceof CompletionException || throwable instanceof ExecutionException)
                && throwable.getCause() != null) {
            throwable = throwable.getCause();
        }
        if (throwable instanceof ApiJsonException jsonException) {
            return new Outcome(400, Map.of(), ApiResource.TEXT,
                    jsonException.getMessage().getBytes(StandardCharsets.UTF_8));
        }
        if (throwable instanceof ApiException apiException) {
            return error(apiException, produced);
        }
        String message = describe(throwable);
        if (message == null) {
            return new Outcome(400, Map.of(), null, null);
        }
        return new Outcome(400, Map.of(), produced, message.getBytes(StandardCharsets.UTF_8));
    }

    private void write(ServerRequest request, ServerResponse response, ApiCall call, String method, Outcome outcome) {
        response.status(outcome.status());
        outcome.headers().forEach(response::header);

        String origin = request.headers().first(HeaderNames.ORIGIN).orElse(null);
        response.header("Access-Control-Allow-Headers", HEADERS_ALL);
        response.header("Access-Control-Allow-Credentials", "true");
        response.header("Access-Control-Allow-Methods", METHODS_ALL);
        if (origin == null) {
            response.header("Access-Control-Allow-Origin", ORIGIN_ALL);
        } else if (allowedOrigin != null && (allowedOrigin.equals(ORIGIN_ALL) || allowedOrigin.contains(origin))) {
            response.header("Access-Control-Allow-Origin", origin);
        }

        WebSession created = call.getCreatedSession();
        if (created != null) {
            response.header("Set-Cookie", sessionStore.cookie(created));
            response.header("Expires", "Thu, 01 Jan 1970 00:00:00 GMT");
        }

        byte[] body = outcome.body();
        String type = outcome.type();
        if (body == null) {
            body = errorPage(outcome.status(), request.path().path());
            type = "text/html;charset=iso-8859-1";
            response.header("Cache-Control", "must-revalidate,no-cache,no-store");
        }
        if (type != null && outcome.status() != 204) {
            response.header("Content-Type", type);
        }
        if (outcome.status() == 204) {
            response.send();
        } else if (method.equals(Method.HEAD.text())) {
            response.contentLength(body.length);
            response.send();
        } else {
            response.send(body);
        }
    }

    private static byte[] errorPage(int status, String uri) {
        String reason = ApiException.reason(status);
        String page = "<html>\n<head>\n<meta http-equiv=\"Content-Type\" content=\"text/html;charset=ISO-8859-1\"/>\n"
                + "<title>Error " + status + " " + reason + "</title>\n</head>\n<body>\n<h2>HTTP ERROR " + status + " "
                + reason + "</h2>\n<table>\n<tr><th>URI:</th><td>" + uri.replace("<", "&lt;") + "</td></tr>\n"
                + "<tr><th>STATUS:</th><td>" + status + "</td></tr>\n<tr><th>MESSAGE:</th><td>" + reason
                + "</td></tr>\n</table>\n</body>\n</html>\n";
        return page.getBytes(StandardCharsets.ISO_8859_1);
    }

}
