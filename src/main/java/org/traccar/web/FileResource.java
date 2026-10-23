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

import io.helidon.http.HeaderNames;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * Serves files below one root directory the way the web server always has: directory redirects and welcome files,
 * no directory listing, Last-Modified and If-Modified-Since, byte ranges and HEAD without a body.
 */
final class FileResource {

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path root;
    private final String cacheControl;
    private final boolean welcome;

    FileResource(Path root, String cacheControl, boolean welcome) {
        this.root = root.toAbsolutePath().normalize();
        this.cacheControl = cacheControl;
        this.welcome = welcome;
    }

    /**
     * Answers every method but GET and HEAD the way the file servlet did; returns false for GET and HEAD.
     */
    static boolean answerOtherMethods(ServerRequest request, ServerResponse response) {
        switch (request.prologue().method().text()) {
            case "GET", "HEAD" -> {
                return false;
            }
            case "OPTIONS" -> {
                response.header("Allow", "GET, HEAD, OPTIONS");
                response.contentLength(0);
                response.send();
            }
            case "POST" -> ErrorPage.send(request, response, 405, "HTTP method POST is not supported by this URL");
            case "PUT", "DELETE" -> empty(response, 405);
            default -> empty(response, 501);
        }
        return true;
    }

    private static void empty(ServerResponse response, int status) {
        response.status(status);
        response.header("Cache-Control", ErrorPage.CACHE_CONTROL);
        response.contentLength(0);
        response.send();
    }

    /**
     * The regular file a request path names, or null; never a path outside the root.
     */
    Path file(String relative) {
        Path target = resolve(relative);
        return target != null && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) ? target : null;
    }

    private Path resolve(String relative) {
        Path target = root.resolve(relative.startsWith("/") ? relative.substring(1) : relative).normalize();
        return target.startsWith(root) ? target : null;
    }

    /**
     * Answers a GET or HEAD for a path below the root. The body transform (which may be null) rewrites a complete
     * file answer before it is sent.
     */
    void serve(ServerRequest request, ServerResponse response, String relative, String requestPath,
               UnaryOperator<byte[]> transform) throws IOException {
        Path target = resolve(relative);
        if (target == null) {
            ErrorPage.send(request, response, 404);
            return;
        }
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!relative.endsWith("/")) {
                String query = request.query().rawValue();
                response.status(302);
                response.header("Location", requestPath + "/" + (query.isEmpty() ? "" : "?" + query));
                response.send();
                return;
            }
            Path index = target.resolve("index.html");
            if (welcome && Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)) {
                send(request, response, index, transform);
            } else {
                ErrorPage.send(request, response, 403);
            }
            return;
        }
        if (relative.endsWith("/") || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            ErrorPage.send(request, response, 404);
            return;
        }
        send(request, response, target, transform);
    }

    void send(ServerRequest request, ServerResponse response, Path file, UnaryOperator<byte[]> transform)
            throws IOException {
        Instant modified = Files.getLastModifiedTime(file).toInstant();
        long modifiedSeconds = modified.getEpochSecond();
        long length = Files.size(file);
        boolean head = request.prologue().method().text().equals("HEAD");

        String since = request.headers().first(HeaderNames.IF_MODIFIED_SINCE).orElse(null);
        if (since != null) {
            try {
                if (ZonedDateTime.parse(since, HTTP_DATE).toEpochSecond() >= modifiedSeconds) {
                    response.status(304);
                    response.header("Accept-Ranges", "bytes");
                    response.contentLength(0);
                    response.send();
                    return;
                }
            } catch (DateTimeParseException e) {
                // an unparsable date is ignored, as if the header were absent
            }
        }

        String type = MimeTypes.forName(file.getFileName().toString());
        List<long[]> ranges = ranges(request.headers().first(HeaderNames.RANGE).orElse(null), length);
        if (ranges != null && ranges.isEmpty()) {
            response.status(416);
            response.header("Content-Range", "bytes */" + length);
            response.header("Cache-Control", ErrorPage.CACHE_CONTROL);
            response.header("Content-Type", ErrorPage.CONTENT_TYPE);
            response.contentLength(0);
            response.send();
            return;
        }

        response.header("Last-Modified", HTTP_DATE.format(modified));
        if (type != null) {
            response.header("Content-Type", type);
        }
        response.header("Accept-Ranges", "bytes");
        if (cacheControl != null) {
            response.header("Cache-Control", cacheControl);
        }
        if (ranges != null && ranges.size() == 1) {
            long[] range = ranges.get(0);
            response.status(206);
            response.header("Content-Range", "bytes " + range[0] + "-" + range[1] + "/" + length);
            byte[] data = Arrays.copyOfRange(Files.readAllBytes(file), (int) range[0], (int) range[1] + 1);
            finish(response, head, data, data.length);
            return;
        }
        if (ranges != null) {
            byte[] data = multipart(Files.readAllBytes(file), ranges, type, length, response);
            response.status(206);
            finish(response, head, data, data.length);
            return;
        }
        if (head) {
            finish(response, true, null, length);
            return;
        }
        byte[] data = Files.readAllBytes(file);
        if (transform != null) {
            data = transform.apply(data);
        }
        finish(response, false, data, data.length);
    }

    private static byte[] multipart(
            byte[] content, List<long[]> ranges, String type, long length, ServerResponse response) {
        String boundary = new BigInteger(130, RANDOM).toString(36);
        response.header("Content-Type", "multipart/byteranges; boundary=" + boundary);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (long[] range : ranges) {
            String part = "--" + boundary + "\r\n" + (type != null ? "Content-Type: " + type + "\r\n" : "")
                    + "Content-Range: bytes " + range[0] + "-" + range[1] + "/" + length + "\r\n\r\n";
            body.writeBytes(part.getBytes(StandardCharsets.ISO_8859_1));
            body.write(content, (int) range[0], (int) (range[1] - range[0] + 1));
            body.writeBytes("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
        return body.toByteArray();
    }

    private static void finish(ServerResponse response, boolean head, byte[] data, long length) {
        if (head) {
            response.contentLength(length);
            response.send();
        } else {
            response.send(data);
        }
    }

    /**
     * The inclusive byte ranges of a Range header; null to send the whole file, an empty list when no range can be
     * satisfied.
     */
    private static List<long[]> ranges(String header, long length) {
        if (header == null || !header.startsWith("bytes=")) {
            return null;
        }
        List<long[]> ranges = new ArrayList<>();
        for (String spec : header.substring("bytes=".length()).split(",")) {
            spec = spec.trim();
            int dash = spec.indexOf('-');
            if (dash < 0) {
                return null;
            }
            try {
                long first;
                long last;
                if (dash == 0) {
                    first = Math.max(0, length - Long.parseLong(spec.substring(1)));
                    last = length - 1;
                } else {
                    first = Long.parseLong(spec.substring(0, dash));
                    last = dash == spec.length() - 1
                            ? length - 1 : Math.min(length - 1, Long.parseLong(spec.substring(dash + 1)));
                }
                if (first < length && first <= last) {
                    ranges.add(new long[] {first, last});
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return ranges;
    }

}
