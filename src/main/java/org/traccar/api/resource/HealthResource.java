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
package org.traccar.api.resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.BaseResource;
import org.traccar.api.http.ApiReply;
import org.traccar.api.http.ApiResource;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.StatisticsManager;
import org.traccar.model.Server;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;

public class HealthResource extends BaseResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(HealthResource.class);

    @Inject
    private Config config;

    @Inject
    private StatisticsManager statisticsManager;

    @Inject
    private Storage storage;

    private static long messageLastTotal;
    private static long messageLastCheck;

    public ApiReply health() {
        try {
            checkMessages();
            checkDatabase();
            return ApiReply.ok("OK");
        } catch (Exception e) {
            LOGGER.warn("Health check failed", e);
            return ApiReply.status(500);
        }
    }

    private void checkMessages() {
        synchronized (HealthResource.class) {
            long messageCurrentTotal = statisticsManager.messageStoredCount();
            long messageCurrentCheck = messageCurrentTotal - messageLastTotal;
            long messagePreviousCheck = messageLastCheck;
            messageLastTotal = messageCurrentTotal;
            messageLastCheck = messageCurrentCheck;
            double dropThreshold = config.getDouble(Keys.WEB_HEALTH_CHECK_DROP_THRESHOLD);
            if (dropThreshold > 0 && messagePreviousCheck > 0 && messageCurrentCheck > 0) {
                double ratio = messageCurrentCheck / (double) messagePreviousCheck;
                if (ratio < dropThreshold) {
                    throw new IllegalStateException("Message health check failed with ratio " + ratio);
                }
            }
        }
    }

    private void checkDatabase() throws StorageException {
        storage.getObject(Server.class, new Request(new Columns.All()));
    }

    public static void routes(ApiResource<HealthResource> routes) {
        routes.get("", (resource, call) -> resource.health()).permitAll();
    }

}
