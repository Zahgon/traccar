/*
 * Copyright 2025 Anton Tananaev (anton@traccar.org)
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
package org.traccar.command;

import org.traccar.config.Keys;
import org.traccar.helper.http.HttpBody;
import org.traccar.helper.http.HttpJsonClient;
import org.traccar.helper.http.HttpReply;
import org.traccar.helper.http.HttpTarget;
import org.traccar.model.Command;
import org.traccar.model.Device;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Singleton
public class FindHubCommandSender implements CommandSender {

    private final HttpJsonClient client;

    @Inject
    public FindHubCommandSender(HttpJsonClient client) throws IOException {
        this.client = client;
    }

    @Override
    public Collection<String> getSupportedCommands() {
        return List.of(
                Command.TYPE_POSITION_SINGLE,
                Command.TYPE_POSITION_PERIODIC,
                Command.TYPE_POSITION_STOP);
    }

    @Override
    public void sendCommand(Device device, Command command) throws Exception {
        String url = device.getString(Keys.COMMAND_FIND_HUB_URL.getKey());
        String key = device.getString(Keys.COMMAND_FIND_HUB_KEY.getKey());
        if (url == null || key == null) {
            throw new RuntimeException("Missing device URL or API key");
        }

        String commandType = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])")
                .matcher(command.getType()).replaceAll("-").toLowerCase(Locale.ROOT);

        HttpTarget target = client.target(url)
                .path("devices")
                .path(device.getUniqueId())
                .path(commandType);

        if (Command.TYPE_POSITION_PERIODIC.equals(command.getType())) {
            int interval = command.getInteger(Command.KEY_FREQUENCY);
            target = target.queryParam("interval", interval);
        }

        try (HttpReply response = target.request(HttpBody.JSON)
                .header("Authorization", "Bearer " + key).post(null)) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("HTTP code " + response.getStatus());
            }
        }
    }

}
