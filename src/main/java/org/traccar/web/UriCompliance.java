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

import io.helidon.webserver.http.Filter;
import io.helidon.webserver.http.FilterChain;
import io.helidon.webserver.http.RoutingRequest;
import io.helidon.webserver.http.RoutingResponse;

import java.util.Locale;

/**
 * Refuses request paths the web server treats as ambiguous or suspicious with 400: empty segments, encoded dot
 * segments, encoded slashes, encoded control characters and dot segments that climb above the root.
 */
public class UriCompliance implements Filter {

    @Override
    public void filter(FilterChain chain, RoutingRequest request, RoutingResponse response) {
        if (isAmbiguous(request.prologue().uriPath().rawPath())) {
            ErrorPage.send(request, response, 400, "Ambiguous URI");
        } else {
            chain.proceed();
        }
    }

    static boolean isAmbiguous(String rawPath) {
        String lower = rawPath.toLowerCase(Locale.ROOT);
        if (rawPath.contains("//") || lower.contains("%2f") || lower.contains("%5c")) {
            return true;
        }
        for (int i = lower.indexOf('%'); i >= 0 && i + 2 < lower.length(); i = lower.indexOf('%', i + 1)) {
            int high = Character.digit(lower.charAt(i + 1), 16);
            int low = Character.digit(lower.charAt(i + 2), 16);
            if (high >= 0 && low >= 0 && (high < 2 || high * 16 + low == 0x7f)) {
                return true;
            }
        }
        int depth = 0;
        String[] segments = lower.split("/", -1);
        for (int i = 1; i < segments.length; i++) {
            String segment = segments[i];
            String decoded = segment.replace("%2e", ".");
            if (!decoded.equals(segment) && (decoded.equals(".") || decoded.equals(".."))) {
                return true;
            }
            if (segment.equals("..")) {
                depth--;
                if (depth < 0) {
                    return true;
                }
            } else if (!segment.equals(".") && !(segment.isEmpty() && i == segments.length - 1)) {
                depth++;
            }
        }
        return false;
    }

    /**
     * True when the raw path holds a literal "." or ".." segment.
     */
    public static boolean hasDotSegment(String rawPath) {
        for (String segment : rawPath.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

}
