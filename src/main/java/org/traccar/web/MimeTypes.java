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

import java.util.Locale;
import java.util.Map;

/**
 * Content types of the files the web server serves, by file extension.
 */
final class MimeTypes {

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("css", "text/css"),
            Map.entry("csv", "text/csv"),
            Map.entry("gif", "image/gif"),
            Map.entry("gz", "application/gzip"),
            Map.entry("htm", "text/html"),
            Map.entry("html", "text/html"),
            Map.entry("ico", "image/vnd.microsoft.icon"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("js", "text/javascript"),
            Map.entry("json", "application/json"),
            Map.entry("mjs", "text/javascript"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("ogg", "application/ogg"),
            Map.entry("otf", "font/otf"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("png", "image/png"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("svgz", "image/svg+xml"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("txt", "text/plain"),
            Map.entry("wasm", "application/wasm"),
            Map.entry("webp", "image/webp"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("xml", "application/xml"),
            Map.entry("zip", "application/zip"));

    private MimeTypes() {
    }

    static String forName(String name) {
        int index = name.lastIndexOf('.');
        return index < 0 ? null : TYPES.get(name.substring(index + 1).toLowerCase(Locale.ROOT));
    }

}
