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
package org.traccar.api.resource;

import org.traccar.api.BaseResource;
import org.traccar.api.http.ApiException;
import org.traccar.api.http.ApiReply;
import org.traccar.api.http.ApiResource;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Device;
import org.traccar.model.Geofence;
import org.traccar.model.Position;
import org.traccar.model.UserRestrictions;
import org.traccar.reports.CsvExportProvider;
import org.traccar.reports.GpxExportProvider;
import org.traccar.reports.KmlExportProvider;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.xml.stream.XMLStreamException;

public class PositionResource extends BaseResource {

    @Inject
    private KmlExportProvider kmlExportProvider;

    @Inject
    private CsvExportProvider csvExportProvider;

    @Inject
    private GpxExportProvider gpxExportProvider;

    @Inject
    private Config config;

    public Stream<Position> getJson(
            long deviceId, List<Long> positionIds,
            long geofenceId, Date from, Date to)
            throws StorageException {
        if (!positionIds.isEmpty()) {
            permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
            var positions = new ArrayList<Position>();
            for (long positionId : positionIds) {
                Position position = storage.getObject(Position.class, new Request(
                        new Columns.All(), new Condition.Equals("id", positionId)));
                permissionsService.checkPermission(Device.class, getUserId(), position.getDeviceId());
                positions.add(position);
            }
            return positions.stream();
        } else if (deviceId > 0) {
            permissionsService.checkPermission(Device.class, getUserId(), deviceId);
            if (from != null && to != null) {
                permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);

                Geofence geofence = geofenceId == 0 ? null : storage.getObject(Geofence.class, new Request(
                        new Columns.All(), new Condition.Equals("id", geofenceId)));

                return PositionUtil.getPositionsStream(
                        storage, deviceId, from, to, config.getInteger(Keys.REPORT_MAX_POSITIONS))
                        .filter(position -> geofence == null || geofence.containsPosition(position));
            } else {
                return storage.getObjectsStream(Position.class, new Request(
                        new Columns.All(), new Condition.LatestPositions(deviceId)));
            }
        } else {
            return PositionUtil.getLatestPositions(storage, getUserId()).stream();
        }
    }

    public ApiReply removeById(long positionId) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getReadonly);

        Request request = new Request(new Columns.All(), new Condition.Equals("id", positionId));
        Position position = storage.getObject(Position.class, request);
        if (position == null) {
            return ApiReply.status(404);
        }

        permissionsService.checkPermission(Device.class, getUserId(), position.getDeviceId());

        storage.removeObject(Position.class, request);
        return ApiReply.status(204);
    }

    public ApiReply remove(
            long deviceId,
            Date from, Date to) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getReadonly);

        var conditions = new LinkedList<Condition>();
        conditions.add(new Condition.Equals("deviceId", deviceId));
        conditions.add(new Condition.Between("fixTime", from, to));
        storage.removeObject(Position.class, new Request(Condition.merge(conditions)));

        return ApiReply.status(204);
    }

    public ApiReply getKml(
            String extension,
            long deviceId, long geofenceId,
            Date from, Date to) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        ApiReply.Body stream = output -> {
            try {
                if (extension.equals("kmz")) {
                    try (ZipOutputStream zipStream = new ZipOutputStream(output)) {
                        zipStream.putNextEntry(new ZipEntry("doc.kml"));
                        kmlExportProvider.generate(zipStream, deviceId, geofenceId, from, to);
                        zipStream.closeEntry();
                    }
                } else {
                    kmlExportProvider.generate(output, deviceId, geofenceId, from, to);
                }
            } catch (XMLStreamException | StorageException e) {
                throw new ApiException(e);
            }
        };
        String mediaType = extension.equals("kmz")
                ? "application/vnd.google-earth.kmz"
                : "application/vnd.google-earth.kml+xml";
        return ApiReply.ok(stream, mediaType)
                .header("Content-Disposition", "attachment; filename=positions." + extension);
    }

    public ApiReply getCsv(
            long deviceId, long geofenceId,
            Date from, Date to) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        ApiReply.Body stream = output -> {
            try {
                csvExportProvider.generate(output, getUserId(), deviceId, geofenceId, from, to);
            } catch (StorageException e) {
                throw new ApiException(e);
            }
        };
        return ApiReply.ok(stream)
                .header("Content-Disposition", "attachment; filename=positions.csv");
    }

    public ApiReply getGpx(
            long deviceId, long geofenceId,
            Date from, Date to) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        ApiReply.Body stream = output -> {
            try {
                gpxExportProvider.generate(output, deviceId, geofenceId, from, to);
            } catch (XMLStreamException | StorageException e) {
                throw new ApiException(e);
            }
        };
        return ApiReply.ok(stream)
                .header("Content-Disposition", "attachment; filename=positions.gpx");
    }

    public static void routes(ApiResource<PositionResource> routes) {
        routes.get("", (resource, call) -> resource.getJson(
                call.queryLong("deviceId"), call.queryLongList("id"), call.queryLong("geofenceId"),
                call.queryDate("from"), call.queryDate("to")));
        routes.delete("{id}", (resource, call) -> resource.removeById(call.pathLong("id")));
        routes.delete("", (resource, call) -> resource.remove(
                call.queryLong("deviceId"), call.queryDate("from"), call.queryDate("to")));
        routes.get("{extension:kml|kmz}", (resource, call) -> resource.getKml(
                call.path("extension"), call.queryLong("deviceId"), call.queryLong("geofenceId"),
                call.queryDate("from"), call.queryDate("to")));
        routes.get("csv", (resource, call) -> resource.getCsv(
                call.queryLong("deviceId"), call.queryLong("geofenceId"),
                call.queryDate("from"), call.queryDate("to"))).produces("text/csv");
        routes.get("gpx", (resource, call) -> resource.getGpx(
                call.queryLong("deviceId"), call.queryLong("geofenceId"),
                call.queryDate("from"), call.queryDate("to"))).produces("application/gpx+xml");
    }

}
