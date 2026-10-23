/*
 * Copyright 2017 - 2026 Anton Tananaev (anton@traccar.org)
 * Copyright 2017 - 2018 Andrey Kunitsyn (andrey@traccar.org)
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

import org.traccar.api.ExtendedObjectResource;
import org.traccar.api.http.ApiReply;
import org.traccar.api.http.ApiResource;
import org.traccar.handler.ComputedAttributesProvider;
import org.traccar.model.Attribute;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import java.util.List;

public class AttributeResource extends ExtendedObjectResource<Attribute> {

    @Inject
    private CacheManager cacheManager;

    @Inject
    private ComputedAttributesProvider computedAttributesProvider;

    public AttributeResource() {
        super(Attribute.class, "description", List.of("description"));
    }

    public ApiReply test(long deviceId, Attribute entity) throws Exception {
        permissionsService.checkAdmin(getUserId());
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);

        Position position = storage.getObject(Position.class, new Request(
                new Columns.All(),
                new Condition.LatestPositions(deviceId)));

        var key = new Object();
        try {
            cacheManager.addDevice(position.getDeviceId(), key);
            Object result = computedAttributesProvider.compute(entity, position);
            if (result != null) {
                return switch (entity.getType()) {
                    case "number", "boolean" -> ApiReply.ok(result);
                    default -> ApiReply.ok(result.toString());
                };
            } else {
                return ApiReply.noContent();
            }
        } finally {
            cacheManager.removeDevice(position.getDeviceId(), key);
        }
    }

    public ApiReply add(Attribute entity) throws Exception {
        permissionsService.checkAdmin(getUserId());
        return super.add(entity);
    }

    public ApiReply update(Attribute entity) throws Exception {
        permissionsService.checkAdmin(getUserId());
        return super.update(entity);
    }

    public ApiReply remove(long id) throws Exception {
        permissionsService.checkAdmin(getUserId());
        return super.remove(id);
    }

    public static void routes(ApiResource<AttributeResource> routes) {
        listRoutes(routes);
        routes.post("test", (resource, call) -> resource.test(call.queryLong("deviceId"), call.json(Attribute.class)));
    }

}
