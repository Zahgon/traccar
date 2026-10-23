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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import javax.sql.DataSource;

/**
 * Keeps web sessions in the database (web.persistSession), so a session survives a restart of the service.
 */
final class SessionRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionRepository.class);

    private static final String TABLE = "web_sessions";

    private final DataSource dataSource;

    SessionRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE
                    + " (id VARCHAR(128) NOT NULL PRIMARY KEY, attributes VARCHAR(4000) NOT NULL)");
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot prepare the session table", e);
        }
    }

    Map<String, Object> load(String id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT attributes FROM " + TABLE + " WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? decode(result.getString(1)) : null;
            }
        } catch (SQLException e) {
            LOGGER.warn("Session load error", e);
            return null;
        }
    }

    void save(String id, Map<String, Object> attributes) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + TABLE + " SET attributes = ? WHERE id = ?")) {
                update.setString(1, encode(attributes));
                update.setString(2, id);
                if (update.executeUpdate() > 0) {
                    return;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + TABLE + " (id, attributes) VALUES (?, ?)")) {
                insert.setString(1, id);
                insert.setString(2, encode(attributes));
                insert.executeUpdate();
            }
        } catch (SQLException e) {
            LOGGER.warn("Session save error", e);
        }
    }

    void delete(String id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM " + TABLE + " WHERE id = ?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            LOGGER.warn("Session delete error", e);
        }
    }

    private static String encode(Map<String, Object> attributes) {
        StringJoiner joiner = new StringJoiner("&");
        attributes.forEach((name, value) -> {
            String typed = switch (value) {
                case Long number -> "L" + number;
                case Date date -> "D" + date.getTime();
                case Boolean flag -> "B" + flag;
                default -> "S" + value;
            };
            joiner.add(URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(typed, StandardCharsets.UTF_8));
        });
        return joiner.toString();
    }

    private static Map<String, Object> decode(String encoded) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (String pair : encoded.split("&")) {
            int index = pair.indexOf('=');
            if (index <= 0) {
                continue;
            }
            String name = URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8);
            String typed = URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8);
            String text = typed.substring(1);
            attributes.put(name, switch (typed.charAt(0)) {
                case 'L' -> Long.valueOf(text);
                case 'D' -> new Date(Long.parseLong(text));
                case 'B' -> Boolean.valueOf(text);
                default -> text;
            });
        }
        return attributes;
    }

}
