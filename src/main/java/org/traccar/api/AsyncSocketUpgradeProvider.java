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
package org.traccar.api;

import io.helidon.common.Weight;
import io.helidon.http.Headers;
import io.helidon.webserver.ProtocolConfigs;
import io.helidon.webserver.http1.spi.Http1Upgrader;
import io.helidon.webserver.websocket.WsConfig;
import io.helidon.webserver.websocket.WsUpgradeProvider;
import io.helidon.webserver.websocket.WsUpgrader;

/**
 * WebSocket upgrades without the server's Origin check: the push endpoint applies the session origin rule itself,
 * and a token or an origin-less request may come from any page.
 */
@Weight(200)
public class AsyncSocketUpgradeProvider extends WsUpgradeProvider {

    @Override
    public Http1Upgrader create(WsConfig config, ProtocolConfigs configs) {
        return new AnyOriginUpgrader(config);
    }

    private static final class AnyOriginUpgrader extends WsUpgrader {

        private AnyOriginUpgrader(WsConfig config) {
            super(config);
        }

        @Override
        protected void validateOrigin(Headers headers, String origin) {
        }

    }

}
