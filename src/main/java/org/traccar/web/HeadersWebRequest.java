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
import io.helidon.http.Headers;

import java.util.List;

/**
 * A request seen outside the API dispatcher (media files, the WebSocket upgrade): headers, peer address and query,
 * with the session its cookie names. It never creates a session.
 */
public class HeadersWebRequest implements WebRequest {

    private final Headers headers;
    private final String remoteAddress;
    private final String query;
    private final SessionStore sessionStore;

    public HeadersWebRequest(Headers headers, String remoteAddress, String query, SessionStore sessionStore) {
        this.headers = headers;
        this.remoteAddress = remoteAddress;
        this.query = query == null || query.isEmpty() ? null : query;
        this.sessionStore = sessionStore;
    }

    @Override
    public String getHeader(String name) {
        return headers.first(HeaderNames.create(name)).orElse(null);
    }

    @Override
    public String getRemoteAddr() {
        return remoteAddress;
    }

    @Override
    public String getQueryString() {
        return query;
    }

    @Override
    public WebSession getSession(boolean create) {
        for (String header : headers.all(HeaderNames.COOKIE, List::of)) {
            for (String part : header.split(";")) {
                String trimmed = part.trim();
                int index = trimmed.indexOf('=');
                if (index > 0 && trimmed.substring(0, index).equals(SessionStore.COOKIE_NAME)) {
                    WebSession session = sessionStore.find(trimmed.substring(index + 1));
                    if (session != null) {
                        return session;
                    }
                }
            }
        }
        return null;
    }

}
