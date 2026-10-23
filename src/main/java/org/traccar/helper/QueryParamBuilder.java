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
package org.traccar.helper;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Appends query parameters to a URI with the rules the REST API redirects have always used: names and values are
 * percent-encoded except unreserved characters, a space becomes '+', valid percent escapes are kept, and text in
 * braces is a URI template, so a template variable in a value fails the build because it has no value.
 */
public final class QueryParamBuilder {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final String scheme;
    private final String authority;
    private final String path;
    private final StringBuilder query;
    private final String fragment;

    public QueryParamBuilder(URI uri) {
        scheme = uri.getScheme();
        authority = uri.getRawAuthority();
        path = uri.getRawPath();
        query = new StringBuilder(uri.getRawQuery() != null ? uri.getRawQuery() : "");
        fragment = uri.getRawFragment();
    }

    public QueryParamBuilder queryParam(String name, String value) {
        if (query.length() > 0) {
            query.append('&');
        }
        query.append(encode(name)).append('=').append(encode(value));
        return this;
    }

    public URI build() {
        StringBuilder result = new StringBuilder();
        if (scheme != null) {
            result.append(scheme).append(':');
        }
        if (authority != null) {
            result.append("//").append(authority);
        }
        if (path != null) {
            result.append(resolve(path));
        }
        if (!query.isEmpty()) {
            result.append('?').append(resolve(query.toString()));
        }
        if (fragment != null) {
            result.append('#').append(resolve(fragment));
        }
        try {
            return new URI(result.toString());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static boolean unreserved(int c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                || c == '-' || c == '.' || c == '_' || c == '~';
    }

    private static boolean hex(char c) {
        return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
    }

    static String encode(String text) {
        StringBuilder result = new StringBuilder();
        boolean insideTemplate = false;
        int i = 0;
        while (i < text.length()) {
            int c = text.codePointAt(i);
            int width = Character.charCount(c);
            boolean leavingTemplate = false;
            if (c == '{') {
                insideTemplate = true;
            } else if (c == '}') {
                insideTemplate = false;
                leavingTemplate = true;
            }
            if (insideTemplate || leavingTemplate || unreserved(c)) {
                result.appendCodePoint(c);
            } else if (c == '%' && i + 2 < text.length() && hex(text.charAt(i + 1)) && hex(text.charAt(i + 2))) {
                result.append(text, i, i + 3);
                width = 3;
            } else if (c == ' ') {
                result.append('+');
            } else {
                for (byte b : new String(Character.toChars(c)).getBytes(StandardCharsets.UTF_8)) {
                    result.append('%').append(HEX[(b >> 4) & 0x0F]).append(HEX[b & 0x0F]);
                }
            }
            i += width;
        }
        return result.toString();
    }

    /**
     * Resolves the template variables of one URI component; none has a value, so any variable is an error.
     */
    private static String resolve(String template) {
        if (template.indexOf('{') < 0) {
            return template;
        }
        String variable = new TemplateParser(template).firstVariable();
        if (variable != null) {
            throw new IllegalArgumentException("The template variable '" + variable + "' has no value");
        }
        return template.replaceAll("\\{[^}]*}", "");
    }

    private static final class TemplateParser {

        private final String template;
        private int position = -1;

        TemplateParser(String template) {
            this.template = template;
        }

        private char next() {
            position += 1;
            if (position >= template.length()) {
                throw new NoSuchElementException();
            }
            return template.charAt(position);
        }

        private char skipWhitespace() {
            char c;
            do {
                c = next();
            } while (Character.isWhitespace(c));
            return c;
        }

        /**
         * The first plain template variable, or null when every variable is a query or matrix expansion.
         */
        String firstVariable() {
            String first = null;
            try {
                while (position + 1 < template.length()) {
                    if (next() == '{') {
                        String name = parseName();
                        if (first == null && name != null) {
                            first = name;
                        }
                    }
                }
            } catch (NoSuchElementException e) {
                throw new IllegalArgumentException("Invalid syntax in the template \"" + template
                        + "\". Check if a path parameter is terminated with a \"}\".", e);
            }
            return first;
        }

        private IllegalArgumentException afterName(char c) {
            return new IllegalArgumentException("Illegal character \"" + c + "\" at position " + position
                    + " is not allowed after a name in a path template \"" + template + "\".");
        }

        private String parseName() {
            char c = skipWhitespace();
            boolean plain = true;
            if (c == '?' || c == ';') {
                plain = false;
                c = next();
            }
            if (!Character.isLetterOrDigit(c) && c != '_') {
                throw new IllegalArgumentException("Illegal character \"" + c + "\" at position " + position
                        + " is not allowed as a start of a name in a path template \"" + template + "\".");
            }
            StringBuilder name = new StringBuilder().append(c);
            String regex = "";
            while (true) {
                c = next();
                if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == ',' && !plain) {
                    name.append(c);
                } else if (c == ':' && plain) {
                    regex = parseRegex();
                    break;
                } else if (c == '}') {
                    break;
                } else if (c == ' ') {
                    c = skipWhitespace();
                    if (c == ':' && plain) {
                        regex = parseRegex();
                        break;
                    } else if (c != '}') {
                        throw afterName(c);
                    }
                    break;
                } else {
                    throw afterName(c);
                }
            }
            if (!regex.isEmpty()) {
                try {
                    Pattern.compile(regex);
                } catch (PatternSyntaxException e) {
                    throw new IllegalArgumentException("Invalid syntax for the expression \"" + regex
                            + "\" associated with the name \"" + name + "\" in the template \"" + template + "\".", e);
                }
            }
            return plain ? name.toString() : null;
        }

        private String parseRegex() {
            StringBuilder regex = new StringBuilder();
            int depth = 1;
            while (true) {
                char c = next();
                if (c == '{') {
                    depth += 1;
                } else if (c == '}') {
                    depth -= 1;
                    if (depth == 0) {
                        break;
                    }
                }
                regex.append(c);
            }
            return regex.toString().trim();
        }

    }

}
