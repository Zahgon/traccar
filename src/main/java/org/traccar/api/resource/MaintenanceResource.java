/*
 * Copyright 2018 - 2026 Anton Tananaev (anton@traccar.org)
 * Copyright 2018 Andrey Kunitsyn (andrey@traccar.org)
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
import org.traccar.api.http.ApiResource;
import org.traccar.model.Maintenance;

import java.util.List;

public class MaintenanceResource extends ExtendedObjectResource<Maintenance> {

    public MaintenanceResource() {
        super(Maintenance.class, "name", List.of("name"));
    }

    public static void routes(ApiResource<MaintenanceResource> routes) {
        listRoutes(routes);
    }

}
