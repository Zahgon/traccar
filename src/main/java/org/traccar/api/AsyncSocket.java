/*
 * Copyright 2015 - 2025 Anton Tananaev (anton@traccar.org)
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
package org.traccar.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.helidon.http.Headers;
import io.helidon.http.HttpPrologue;
import io.helidon.websocket.WsCloseCodes;
import io.helidon.websocket.WsListener;
import io.helidon.websocket.WsSession;
import io.helidon.websocket.WsUpgradeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.security.LoginService;
import org.traccar.helper.SessionHelper;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.LogRecord;
import org.traccar.model.Position;
import org.traccar.session.ConnectionManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.web.HeadersWebRequest;
import org.traccar.web.SessionStore;
import org.traccar.web.WebSession;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The /api/socket push channel of one client: the user's latest positions on open, then every device, position,
 * event and (on request) log update the connection manager reports for that user.
 */
public class AsyncSocket implements WsListener, ConnectionManager.UpdateListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncSocket.class);

    private static final String KEY_DEVICES = "devices";
    private static final String KEY_POSITIONS = "positions";
    private static final String KEY_EVENTS = "events";
    private static final String KEY_LOGS = "logs";

    private final ObjectMapper objectMapper;
    private final ConnectionManager connectionManager;
    private final Storage storage;
    private final LoginService loginService;
    private final SessionStore sessionStore;
    private final long idleTimeout;
    private final Set<AsyncSocket> openSockets;

    private Long userId;
    private volatile boolean includeLogs;
    private volatile WsSession session;
    private volatile long lastActivity;

    public AsyncSocket(
            ObjectMapper objectMapper, ConnectionManager connectionManager, Storage storage,
            LoginService loginService, SessionStore sessionStore, long idleTimeout, Set<AsyncSocket> openSockets) {
        this.objectMapper = objectMapper;
        this.connectionManager = connectionManager;
        this.storage = storage;
        this.loginService = loginService;
        this.sessionStore = sessionStore;
        this.idleTimeout = idleTimeout;
        this.openSockets = openSockets;
    }

    @Override
    public Optional<Headers> onHttpUpgrade(HttpPrologue prologue, Headers headers) throws WsUpgradeException {
        HeadersWebRequest request = new HeadersWebRequest(headers, null, prologue.query().rawValue(), sessionStore);
        List<String> tokens = prologue.query().all("token", List::of);
        if (!tokens.isEmpty()) {
            try {
                userId = loginService.login(tokens.get(0)).getUser().getId();
            } catch (StorageException | GeneralSecurityException | IOException e) {
                throw new WsUpgradeException("Token login failed", e);
            }
        } else if (SessionHelper.isSessionOriginValid(request)) {
            WebSession webSession = request.getSession(false);
            userId = (Long) webSession.getAttribute(SessionHelper.USER_ID_KEY);
        }
        if (userId == null) {
            throw new WsUpgradeException("Not authenticated");
        }
        return Optional.empty();
    }

    @Override
    public void onOpen(WsSession session) {
        this.session = session;
        lastActivity = System.currentTimeMillis();
        openSockets.add(this);
        try {
            Map<String, Collection<?>> data = new HashMap<>();
            data.put(KEY_POSITIONS, PositionUtil.getLatestPositions(storage, userId));
            sendData(data);
            connectionManager.addListener(userId, this);
        } catch (StorageException e) {
            LOGGER.warn("Socket positions error", e);
            session.close(WsCloseCodes.UNEXPECTED_CONDITION, "Storage error");
        }
    }

    @Override
    public void onClose(WsSession session, int status, String reason) {
        connectionManager.removeListener(userId, this);
        openSockets.remove(this);
        this.session = null;
    }

    @Override
    public void onMessage(WsSession session, String text, boolean last) {
        lastActivity = System.currentTimeMillis();
        try {
            JsonNode json = objectMapper.readTree(text);
            if (json.hasNonNull("logs")) {
                includeLogs = json.get("logs").asBoolean();
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("Socket JSON parsing error", e);
        }
    }

    @Override
    public void onError(WsSession session, Throwable cause) {
        LOGGER.warn("WebSocket error", cause);
    }

    /**
     * Closes the connection when nothing was sent or received for longer than the configured web.timeout.
     */
    void closeIfIdle(long now) {
        WsSession current = session;
        if (current != null && now - lastActivity > idleTimeout) {
            current.close(WsCloseCodes.NORMAL_CLOSE, "Idle timeout");
        }
    }

    @Override
    public void onKeepalive() {
        sendData(new HashMap<>());
    }

    @Override
    public void onUpdateDevice(Device device) {
        sendData(Map.of(KEY_DEVICES, List.of(device)));
    }

    @Override
    public void onUpdatePosition(Position position) {
        sendData(Map.of(KEY_POSITIONS, List.of(position)));
    }

    @Override
    public void onUpdateEvent(Event event) {
        sendData(Map.of(KEY_EVENTS, List.of(event)));
    }

    @Override
    public void onUpdateLog(LogRecord record) {
        if (includeLogs) {
            sendData(Map.of(KEY_LOGS, List.of(record)));
        }
    }

    private void sendData(Map<String, Collection<?>> data) {
        WsSession current = session;
        if (current != null) {
            try {
                String text = objectMapper.writeValueAsString(data);
                synchronized (this) {
                    current.send(text, true);
                }
                lastActivity = System.currentTimeMillis();
            } catch (JsonProcessingException e) {
                LOGGER.warn("Socket JSON formatting error", e);
            } catch (RuntimeException e) {
                LOGGER.warn("Socket send error", e);
            }
        }
    }

}
