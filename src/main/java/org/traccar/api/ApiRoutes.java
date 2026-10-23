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

import org.traccar.api.http.ApiResource;
import org.traccar.api.resource.AttributeResource;
import org.traccar.api.resource.AuditResource;
import org.traccar.api.resource.CalendarResource;
import org.traccar.api.resource.CommandResource;
import org.traccar.api.resource.DeviceResource;
import org.traccar.api.resource.DriverResource;
import org.traccar.api.resource.EventResource;
import org.traccar.api.resource.GeofenceResource;
import org.traccar.api.resource.GroupResource;
import org.traccar.api.resource.HealthResource;
import org.traccar.api.resource.MaintenanceResource;
import org.traccar.api.resource.NotificationResource;
import org.traccar.api.resource.OidcResource;
import org.traccar.api.resource.OrderResource;
import org.traccar.api.resource.PasswordResource;
import org.traccar.api.resource.PermissionsResource;
import org.traccar.api.resource.PositionResource;
import org.traccar.api.resource.ReportResource;
import org.traccar.api.resource.ServerResource;
import org.traccar.api.resource.SessionResource;
import org.traccar.api.resource.ShareResource;
import org.traccar.api.resource.StatisticsResource;
import org.traccar.api.resource.UserResource;
import org.traccar.api.resource.VideoStreamResource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The complete REST API route inventory, one explicit table per resource class.
 */
public final class ApiRoutes {

    private ApiRoutes() {
    }

    private static <R extends BaseResource> void add(
            List<ApiResource<?>> list, String path, Class<R> type, String consumes, String produces,
            Consumer<ApiResource<R>> routes) {
        ApiResource<R> resource = new ApiResource<>(path, type, List.of(consumes), List.of(produces));
        routes.accept(resource);
        list.add(resource);
    }

    public static List<ApiResource<?>> create() {
        String json = ApiResource.JSON;
        List<ApiResource<?>> list = new ArrayList<>();
        add(list, "attributes/computed", AttributeResource.class, json, json, AttributeResource::routes);
        add(list, "audit", AuditResource.class, json, json, AuditResource::routes);
        add(list, "calendars", CalendarResource.class, json, json, CalendarResource::routes);
        add(list, "commands", CommandResource.class, json, json, CommandResource::routes);
        add(list, "devices", DeviceResource.class, json, json, DeviceResource::routes);
        add(list, "drivers", DriverResource.class, json, json, DriverResource::routes);
        add(list, "events", EventResource.class, json, json, EventResource::routes);
        add(list, "geofences", GeofenceResource.class, json, json, GeofenceResource::routes);
        add(list, "groups", GroupResource.class, json, json, GroupResource::routes);
        add(list, "health", HealthResource.class, ApiResource.TEXT, ApiResource.TEXT, HealthResource::routes);
        add(list, "maintenance", MaintenanceResource.class, json, json, MaintenanceResource::routes);
        add(list, "notifications", NotificationResource.class, json, json, NotificationResource::routes);
        add(list, "oidc", OidcResource.class, ApiResource.FORM, json, OidcResource::routes);
        add(list, "orders", OrderResource.class, json, json, OrderResource::routes);
        add(list, "password", PasswordResource.class, ApiResource.FORM, json, PasswordResource::routes);
        add(list, "permissions", PermissionsResource.class, json, json, PermissionsResource::routes);
        add(list, "positions", PositionResource.class, json, json, PositionResource::routes);
        add(list, "reports", ReportResource.class, json, json, ReportResource::routes);
        add(list, "server", ServerResource.class, json, json, ServerResource::routes);
        add(list, "session", SessionResource.class, ApiResource.FORM, json, SessionResource::routes);
        add(list, "share", ShareResource.class, json, json, ShareResource::routes);
        add(list, "statistics", StatisticsResource.class, json, json, StatisticsResource::routes);
        add(list, "stream", VideoStreamResource.class, ApiResource.ANY, ApiResource.ANY, VideoStreamResource::routes);
        add(list, "users", UserResource.class, json, json, UserResource::routes);
        return list;
    }

}
