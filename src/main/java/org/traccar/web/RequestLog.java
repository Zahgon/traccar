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
import io.helidon.webserver.http.Filter;
import io.helidon.webserver.http.FilterChain;
import io.helidon.webserver.http.RoutingRequest;
import io.helidon.webserver.http.RoutingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NCSA style request log (web.requestLog.path), one line per request. A file name containing yyyy_mm_dd rolls over
 * daily, and on each roll-over files of the same pattern older than web.requestLog.retainDays are deleted.
 */
public class RequestLog implements Filter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestLog.class);

    private static final String DATE_TOKEN = "yyyy_mm_dd";
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy_MM_dd");
    private static final DateTimeFormatter LINE_DATE =
            DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.getDefault()).withZone(ZoneOffset.UTC);

    private final Path pattern;
    private final int retainDays;
    private LocalDate currentDay;
    private Writer writer;

    public RequestLog(String path, int retainDays) {
        this.pattern = Path.of(path).toAbsolutePath();
        this.retainDays = retainDays;
    }

    @Override
    public void filter(FilterChain chain, RoutingRequest request, RoutingResponse response) {
        Instant time = Instant.now();
        AtomicLong written = new AtomicLong();
        response.streamFilter(stream -> new FilterOutputStream(stream) {
            @Override
            public void write(int b) throws IOException {
                out.write(b);
                written.incrementAndGet();
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
                written.addAndGet(len);
            }
        });
        try {
            chain.proceed();
        } finally {
            long bytes = written.get();
            if (bytes == 0) {
                bytes = response.headers().contentLength().orElse(0L);
            }
            String query = request.query().rawValue();
            String host = request.headers().first(HeaderNames.HOST).orElse(request.localPeer().host());
            write(String.format("%s - - [%s] \"%s http://%s%s%s %s\" %d %d",
                    request.remotePeer().host(), LINE_DATE.format(time), request.prologue().method().text(),
                    host, request.prologue().uriPath().rawPath(), query.isEmpty() ? "" : "?" + query,
                    request.prologue().rawProtocol(), response.status().code(), bytes));
        }
    }

    private synchronized void write(String line) {
        try {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            if (writer == null || !today.equals(currentDay)) {
                boolean rolled = writer != null;
                close();
                currentDay = today;
                Path file = fileFor(today);
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (rolled) {
                    removeOldFiles(today);
                }
            }
            writer.write(line);
            writer.write(System.lineSeparator());
            writer.flush();
        } catch (IOException e) {
            LOGGER.warn("Request log write error", e);
        }
    }

    private Path fileFor(LocalDate day) {
        String name = pattern.getFileName().toString();
        return pattern.resolveSibling(name.replace(DATE_TOKEN, FILE_DATE.format(day)));
    }

    private void removeOldFiles(LocalDate today) throws IOException {
        String name = pattern.getFileName().toString();
        int index = name.indexOf(DATE_TOKEN);
        if (index < 0 || retainDays <= 0) {
            return;
        }
        String prefix = name.substring(0, index);
        String suffix = name.substring(index + DATE_TOKEN.length());
        LocalDate oldest = today.minusDays(retainDays);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(pattern.getParent())) {
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                if (fileName.length() == name.length() && fileName.startsWith(prefix) && fileName.endsWith(suffix)) {
                    String date = fileName.substring(index, index + DATE_TOKEN.length());
                    try {
                        if (LocalDate.parse(date, FILE_DATE).isBefore(oldest)) {
                            Files.deleteIfExists(file);
                        }
                    } catch (DateTimeParseException e) {
                        LOGGER.debug("Skipping request log file {}", fileName, e);
                    }
                }
            }
        }
    }

    public synchronized void close() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                LOGGER.warn("Request log close error", e);
            }
            writer = null;
        }
    }

}
