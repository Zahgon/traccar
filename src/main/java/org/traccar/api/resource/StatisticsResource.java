/*
 * Copyright 2016 - 2022 Anton Tananaev (anton@traccar.org)
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

import org.traccar.api.BaseResource;
import org.traccar.api.http.ApiResource;
import org.traccar.model.Statistics;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.util.Date;
import java.util.stream.Stream;

public class StatisticsResource extends BaseResource {
    public Stream<Statistics> get(
            Date from, Date to) throws StorageException {
        permissionsService.checkAdmin(getUserId());
        return storage.getObjectsStream(Statistics.class, new Request(
                new Columns.All(),
                new Condition.Between("captureTime", from, to),
                new Order("captureTime")));
    }

    public static void routes(ApiResource<StatisticsResource> routes) {
        routes.get("", (resource, call) -> resource.get(call.queryDate("from"), call.queryDate("to")));
    }

}
