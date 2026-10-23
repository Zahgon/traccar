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

import org.traccar.api.BaseResource;
import org.traccar.api.http.ApiResource;
import org.traccar.model.Action;
import org.traccar.model.User;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.util.Date;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AuditResource extends BaseResource {
    public Stream<Action> get(
            Date from, Date to) throws StorageException {
        permissionsService.checkAdmin(getUserId());

        Map<Long, String> userEmailMap;
        try (var stream = storage.getObjectsStream(User.class, new Request(new Columns.All()))) {
            userEmailMap = stream
                    .filter(user -> user.getEmail() != null)
                    .collect(Collectors.toMap(User::getId, User::getEmail));
        }

        return storage.getObjectsStream(Action.class, new Request(
                        new Columns.All(),
                        new Condition.Between("actionTime", from, to),
                        new Order("actionTime")))
                .map(action -> {
                    action.setUserEmail(userEmailMap.get(action.getUserId()));
                    return action;
                });
    }

    public static void routes(ApiResource<AuditResource> routes) {
        routes.get("", (resource, call) -> resource.get(call.queryDate("from"), call.queryDate("to")));
    }

}
