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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side cookie session, the replacement for the servlet container session.
 */
public final class WebSession {

    private final String id;
    private final SessionStore store;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private volatile long lastAccess;
    private volatile boolean valid = true;

    WebSession(String id, SessionStore store) {
        this.id = id;
        this.store = store;
        this.lastAccess = System.currentTimeMillis();
    }

    public String getId() {
        return id;
    }

    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    public void setAttribute(String name, Object value) {
        if (value == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, value);
        }
        store.save(this);
    }

    public void removeAttribute(String name) {
        attributes.remove(name);
        store.save(this);
    }

    void restore(String name, Object value) {
        attributes.put(name, value);
    }

    Map<String, Object> getAttributes() {
        return Map.copyOf(attributes);
    }

    public void invalidate() {
        valid = false;
        attributes.clear();
        store.remove(id);
    }

    public boolean isValid() {
        return valid;
    }

    long getLastAccess() {
        return lastAccess;
    }

    void touch() {
        lastAccess = System.currentTimeMillis();
    }

}
