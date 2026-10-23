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

import org.traccar.api.BaseResource;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The explicit route table of one resource class: its base path, default media types and one entry per HTTP method
 * and sub-path template.
 *
 * @param <R> resource type, instantiated per request
 */
public final class ApiResource<R extends BaseResource> {

    public static final String JSON = "application/json";
    public static final String FORM = "application/x-www-form-urlencoded";
    public static final String TEXT = "text/plain";
    public static final String ANY = "*/*";

    /**
     * Handles one request on a fresh resource instance.
     */
    @FunctionalInterface
    public interface Handler<R> {
        Object handle(R resource, ApiCall call) throws Exception;
    }

    /**
     * A compiled path template.
     */
    public static final class Template {

        private final String text;
        private final Pattern pattern;
        private final List<String> names = new ArrayList<>();
        private final int literals;
        private final int customPatterns;

        Template(String text, String suffix) {
            this.text = text;
            StringBuilder regex = new StringBuilder();
            int literalCount = 0;
            int custom = 0;
            int index = 0;
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == '{') {
                    int end = text.indexOf('}', index);
                    String variable = text.substring(index + 1, end);
                    int colon = variable.indexOf(':');
                    if (colon >= 0) {
                        names.add(variable.substring(0, colon).trim());
                        regex.append('(').append(variable.substring(colon + 1).trim()).append(')');
                        custom += 1;
                    } else {
                        names.add(variable.trim());
                        regex.append("([^/]+)");
                    }
                    index = end + 1;
                } else {
                    regex.append(Pattern.quote(String.valueOf(c)));
                    literalCount += 1;
                    index += 1;
                }
            }
            this.literals = literalCount;
            this.customPatterns = custom;
            String prefix = text.isEmpty() ? "" : "/";
            this.pattern = Pattern.compile(prefix + regex + suffix);
        }

        Matcher match(String path) {
            Matcher matcher = pattern.matcher(path);
            return matcher.matches() ? matcher : null;
        }

        List<String> names() {
            return names;
        }

        String text() {
            return text;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Template template && template.text.equals(text);
        }

        @Override
        public int hashCode() {
            return text.hashCode();
        }

        int compareSpecificity(Template other) {
            if (literals != other.literals) {
                return Integer.compare(other.literals, literals);
            }
            if (names.size() != other.names.size()) {
                return Integer.compare(other.names.size(), names.size());
            }
            return Integer.compare(other.customPatterns, customPatterns);
        }

    }

    /**
     * One resource method.
     */
    public static final class Route<R> {

        private final String method;
        private final Template template;
        private final Handler<R> handler;
        private List<String> consumes;
        private List<String> produces;
        private boolean permitAll;

        Route(String method, Template template, Handler<R> handler, List<String> consumes, List<String> produces) {
            this.method = method;
            this.template = template;
            this.handler = handler;
            this.consumes = consumes;
            this.produces = produces;
        }

        public Route<R> consumes(String... types) {
            consumes = List.of(types);
            return this;
        }

        public Route<R> produces(String... types) {
            produces = List.of(types);
            return this;
        }

        public Route<R> permitAll() {
            permitAll = true;
            return this;
        }

        public String method() {
            return method;
        }

        public Template template() {
            return template;
        }

        public Handler<R> handler() {
            return handler;
        }

        public List<String> consumes() {
            return consumes;
        }

        public List<String> produces() {
            return produces;
        }

        public boolean isPermitAll() {
            return permitAll;
        }

    }

    private final Template base;
    private final Class<R> type;
    private final List<String> consumes;
    private final List<String> produces;
    private final List<Route<R>> routes = new ArrayList<>();

    public ApiResource(String path, Class<R> type, List<String> consumes, List<String> produces) {
        this.base = new Template(path, "(/.*)?");
        this.type = type;
        this.consumes = consumes;
        this.produces = produces;
    }

    public Template base() {
        return base;
    }

    public Class<R> type() {
        return type;
    }

    public List<Route<R>> routes() {
        return routes;
    }

    public Route<R> find(String method, String path) {
        return routes.stream()
                .filter(route -> route.method.equals(method) && route.template.text().equals(path))
                .findFirst().orElseThrow();
    }

    public Route<R> route(String method, String path, Handler<R> handler) {
        Route<R> route = new Route<>(method, new Template(path, "(/)?"), handler, consumes, produces);
        routes.add(route);
        return route;
    }

    public Route<R> get(String path, Handler<R> handler) {
        return route("GET", path, handler);
    }

    public Route<R> post(String path, Handler<R> handler) {
        return route("POST", path, handler);
    }

    public Route<R> put(String path, Handler<R> handler) {
        return route("PUT", path, handler);
    }

    public Route<R> delete(String path, Handler<R> handler) {
        return route("DELETE", path, handler);
    }

}
