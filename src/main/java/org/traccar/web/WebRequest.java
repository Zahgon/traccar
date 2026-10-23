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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * The parts of an HTTP request that services outside the web layer read: headers, the peer address,
 * the raw query and the cookie session.
 */
public interface WebRequest {

    String getHeader(String name);

    String getRemoteAddr();

    String getQueryString();

    WebSession getSession(boolean create);

    default WebSession getSession() {
        return getSession(true);
    }

    default String getParameter(String name) {
        String query = getQueryString();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int index = pair.indexOf('=');
            String key = URLDecoder.decode(index >= 0 ? pair.substring(0, index) : pair, StandardCharsets.UTF_8);
            if (key.equals(name)) {
                return index >= 0 ? URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8) : "";
            }
        }
        return null;
    }

}
