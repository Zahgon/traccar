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

import org.traccar.config.Config;
import org.traccar.config.Keys;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

/**
 * In-memory store of cookie sessions keyed by the JSESSIONID cookie value.
 */
@Singleton
public class SessionStore {

    public static final String COOKIE_NAME = "JSESSIONID";

    private static final DateTimeFormatter EXPIRES_FORMAT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

    private final Map<String, WebSession> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final AtomicLong counter = new AtomicLong();
    private final int timeoutSeconds;
    private final String sameSite;
    private final boolean secure;
    private final SessionRepository repository;

    @Inject
    public SessionStore(Config config, Provider<DataSource> dataSourceProvider) {
        repository = config.getBoolean(Keys.WEB_PERSIST_SESSION)
                ? new SessionRepository(dataSourceProvider.get()) : null;
        timeoutSeconds = config.getInteger(Keys.WEB_SESSION_TIMEOUT);
        String configured = config.getString(Keys.WEB_SAME_SITE_COOKIE);
        String value = null;
        boolean none = false;
        if (configured != null) {
            switch (configured.toLowerCase(Locale.ROOT)) {
                case "lax" -> value = "Lax";
                case "strict" -> value = "Strict";
                case "none" -> {
                    value = "None";
                    none = true;
                }
                default -> {
                }
            }
        }
        sameSite = value;
        secure = none;
    }

    public WebSession find(String id) {
        if (id == null) {
            return null;
        }
        WebSession session = sessions.get(id);
        if (session == null && repository != null) {
            session = load(id);
        }
        if (session == null) {
            return null;
        }
        if (timeoutSeconds > 0
                && System.currentTimeMillis() - session.getLastAccess() > timeoutSeconds * 1000L) {
            session.invalidate();
            return null;
        }
        session.touch();
        return session;
    }

    public WebSession create() {
        String id = "node0" + new BigInteger(130, random).toString(36).toLowerCase(Locale.ROOT)
                + counter.incrementAndGet() + ".node0";
        WebSession session = new WebSession(id, this);
        sessions.put(id, session);
        return session;
    }

    private WebSession load(String id) {
        Map<String, Object> attributes = repository.load(id);
        if (attributes == null) {
            return null;
        }
        WebSession session = new WebSession(id, this);
        attributes.forEach(session::restore);
        WebSession existing = sessions.putIfAbsent(id, session);
        return existing != null ? existing : session;
    }

    void remove(String id) {
        sessions.remove(id);
        if (repository != null) {
            repository.delete(id);
        }
    }

    void save(WebSession session) {
        if (repository != null && session.isValid()) {
            repository.save(session.getId(), session.getAttributes());
        }
    }

    public String cookie(WebSession session) {
        StringBuilder cookie = new StringBuilder(COOKIE_NAME).append('=').append(session.getId()).append("; Path=/");
        if (timeoutSeconds > 0) {
            cookie.append("; Expires=").append(EXPIRES_FORMAT.format(Instant.now().plusSeconds(timeoutSeconds)));
            cookie.append("; Max-Age=").append(timeoutSeconds);
        }
        if (secure) {
            cookie.append("; Secure");
        }
        cookie.append("; HttpOnly");
        if (sameSite != null) {
            cookie.append("; SameSite=").append(sameSite);
        }
        return cookie.toString();
    }

}
