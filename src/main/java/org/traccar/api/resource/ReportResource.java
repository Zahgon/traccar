/*
 * Copyright 2016 - 2026 Anton Tananaev (anton@traccar.org)
 * Copyright 2016 - 2018 Andrey Kunitsyn (andrey@traccar.org)
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

import org.traccar.api.SimpleObjectResource;
import org.traccar.api.http.ApiException;
import org.traccar.api.http.ApiReply;
import org.traccar.api.http.ApiResource;
import org.traccar.helper.LogAction;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.Report;
import org.traccar.model.UserRestrictions;
import org.traccar.reports.CombinedReportProvider;
import org.traccar.reports.DevicesReportProvider;
import org.traccar.reports.EventsReportProvider;
import org.traccar.reports.GeofenceReportProvider;
import org.traccar.reports.RouteReportProvider;
import org.traccar.reports.StopsReportProvider;
import org.traccar.reports.SummaryReportProvider;
import org.traccar.reports.TripsReportProvider;
import org.traccar.reports.common.ReportExecutor;
import org.traccar.reports.common.ReportMailer;
import org.traccar.reports.model.CombinedReportItem;
import org.traccar.reports.model.GeofenceReportItem;
import org.traccar.reports.model.StopReportItem;
import org.traccar.reports.model.SummaryReportItem;
import org.traccar.reports.model.TripReportItem;
import org.traccar.storage.StorageException;

import jakarta.inject.Inject;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

public class ReportResource extends SimpleObjectResource<Report> {

    public static final String EXCEL = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Inject
    private CombinedReportProvider combinedReportProvider;

    @Inject
    private EventsReportProvider eventsReportProvider;

    @Inject
    private GeofenceReportProvider geofenceReportProvider;

    @Inject
    private RouteReportProvider routeReportProvider;

    @Inject
    private StopsReportProvider stopsReportProvider;

    @Inject
    private SummaryReportProvider summaryReportProvider;

    @Inject
    private TripsReportProvider tripsReportProvider;

    @Inject
    private DevicesReportProvider devicesReportProvider;

    @Inject
    private ReportMailer reportMailer;

    @Inject
    private LogAction actionLogger;

    public ReportResource() {
        super(Report.class, "description", List.of("description"));
    }

    private ApiReply executeReport(long userId, boolean mail, ReportExecutor executor) {
        if (mail) {
            reportMailer.sendAsync(userId, executor);
            return ApiReply.noContent();
        } else {
            ApiReply.Body stream = output -> {
                try {
                    executor.execute(output);
                } catch (StorageException e) {
                    throw new ApiException(e);
                }
            };
            return ApiReply.ok(stream)
                    .header("Content-Disposition", "attachment; filename=report.xlsx");
        }
    }

    public Collection<CombinedReportItem> getCombined(
            List<Long> deviceIds,
            List<Long> groupIds,
            Date from,
            Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        actionLogger.report(request, getUserId(), false, "combined", from, to, deviceIds, groupIds);
        return combinedReportProvider.getObjects(getUserId(), deviceIds, groupIds, from, to);
    }

    public Stream<Position> getRoute(
            List<Long> deviceIds,
            List<Long> groupIds,
            Date from,
            Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        actionLogger.report(request, getUserId(), false, "route", from, to, deviceIds, groupIds);
        return routeReportProvider.getObjects(getUserId(), deviceIds, groupIds, from, to);
    }

    public ApiReply getRouteExcel(
            List<Long> deviceIds,
            List<Long> groupIds,
            Date from,
            Date to,
            boolean mail) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), mail, stream -> {
            actionLogger.report(request, getUserId(), false, "route", from, to, deviceIds, groupIds);
            routeReportProvider.getExcel(stream, getUserId(), deviceIds, groupIds, from, to);
        });
    }

    public ApiReply getRouteExcel(
            List<Long> deviceIds,
            final List<Long> groupIds,
            Date from,
            Date to,
            String type) throws StorageException {
        return getRouteExcel(deviceIds, groupIds, from, to, type.equals("mail"));
    }

    public Stream<Event> getEvents(
            List<Long> deviceIds,
            List<Long> groupIds,
            List<String> types,
            List<String> alarms,
            Date from,
            Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        actionLogger.report(request, getUserId(), false, "events", from, to, deviceIds, groupIds);
        return eventsReportProvider.getObjects(getUserId(), deviceIds, groupIds, types, alarms, from, to);
    }

    public ApiReply getEventsExcel(
            List<Long> deviceIds,
            List<Long> groupIds,
            List<String> types,
            List<String> alarms,
            Date from,
            Date to,
            boolean mail) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), mail, stream -> {
            actionLogger.report(request, getUserId(), false, "events", from, to, deviceIds, groupIds);
            eventsReportProvider.getExcel(stream, getUserId(), deviceIds, groupIds, types, alarms, from, to);
        });
    }

    public ApiReply getEventsExcel(
            List<Long> deviceIds,
            List<Long> groupIds,
            List<String> types,
            List<String> alarms,
            Date from,
            Date to,
            String type) throws StorageException {
        return getEventsExcel(deviceIds, groupIds, types, alarms, from, to, type.equals("mail"));
    }

    public Collection<GeofenceReportItem> getGeofences(
            List<Long> deviceIds,
            List<Long> groupIds,
            List<Long> geofenceIds,
            Date from,
            Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        actionLogger.report(request, getUserId(), false, "geofences", from, to, deviceIds, groupIds);
        return geofenceReportProvider.getObjects(getUserId(), deviceIds, groupIds, geofenceIds, from, to);
    }

    public Collection<SummaryReportItem> getSummary(
            List<Long> deviceIds,
            List<Long> groupIds,
            Date from,
            Date to,
            boolean daily) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        actionLogger.report(request, getUserId(), false, "summary", from, to, deviceIds, groupIds);
        return summaryReportProvider.getObjects(getUserId(), deviceIds, groupIds, from, to, daily);
    }

    public ApiReply getSummaryExcel(
            List<Long> deviceIds,
            List<Long> groupIds,
            Date from,
            Date to,
            boolean daily,
            boolean mail) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), mail, stream -> {
            actionLogger.report(request, getUserId(), false, "summary", from, to, deviceIds, groupIds);
            summaryReportProvider.getExcel(stream, getUserId(), deviceIds, groupIds, from, to, daily);
        });
    }

    public ApiReply getSummaryExcel(
            List<Long> deviceIds,
            List<Long> groupIds,
            Date from,
            Date to,
            boolean daily,
            String type) throws StorageException {
        return getSummaryExcel(deviceIds, groupIds, from, to, daily, type.equals("mail"));
    }

    public Collection<TripReportItem> getTrips(
            List<Long> deviceIds,
            List<Long> groupIds,
            Date from,
            Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        actionLogger.report(request, getUserId(), false, "trips", from, to, deviceIds, groupIds);
        return tripsReportProvider.getObjects(getUserId(), deviceIds, groupIds, from, to);
    }

    public ApiReply getTripsExcel(
            List<Long> deviceIds,
            List<Long> groupIds,
            Date from,
            Date to,
            boolean mail) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), mail, stream -> {
            actionLogger.report(request, getUserId(), false, "trips", from, to, deviceIds, groupIds);
            tripsReportProvider.getExcel(stream, getUserId(), deviceIds, groupIds, from, to);
        });
    }

    public ApiReply getTripsExcel(
            List<Long> deviceIds,
            List<Long> groupIds,
            Date from,
            Date to,
            String type) throws StorageException {
        return getTripsExcel(deviceIds, groupIds, from, to, type.equals("mail"));
    }

    public Collection<StopReportItem> getStops(
            List<Long> deviceIds,
            List<Long> groupIds,
            Date from,
            Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        actionLogger.report(request, getUserId(), false, "stops", from, to, deviceIds, groupIds);
        return stopsReportProvider.getObjects(getUserId(), deviceIds, groupIds, from, to);
    }

    public ApiReply getStopsExcel(
            List<Long> deviceIds,
            List<Long> groupIds,
            Date from,
            Date to,
            boolean mail) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), mail, stream -> {
            actionLogger.report(request, getUserId(), false, "stops", from, to, deviceIds, groupIds);
            stopsReportProvider.getExcel(stream, getUserId(), deviceIds, groupIds, from, to);
        });
    }

    public ApiReply getStopsExcel(
            List<Long> deviceIds,
            List<Long> groupIds,
            Date from,
            Date to,
            String type) throws StorageException {
        return getStopsExcel(deviceIds, groupIds, from, to, type.equals("mail"));
    }

    public ApiReply getDevicesExcel(
            String type) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), type.equals("mail"), stream -> {
            devicesReportProvider.getExcel(stream, getUserId());
        });
    }

    public static void routes(ApiResource<ReportResource> routes) {
        listRoutes(routes);
        routes.get("combined", (resource, call) -> resource.getCombined(
                call.queryLongList("deviceId"), call.queryLongList("groupId"),
                call.queryDate("from"), call.queryDate("to")));
        routes.get("route", (resource, call) -> resource.getRoute(
                call.queryLongList("deviceId"), call.queryLongList("groupId"),
                call.queryDate("from"), call.queryDate("to")));
        routes.get("route", (resource, call) -> resource.getRouteExcel(
                call.queryLongList("deviceId"), call.queryLongList("groupId"),
                call.queryDate("from"), call.queryDate("to"), call.queryBoolean("mail"))).produces(EXCEL);
        routes.get("route/{type:xlsx|mail}", (resource, call) -> resource.getRouteExcel(
                call.queryLongList("deviceId"), call.queryLongList("groupId"),
                call.queryDate("from"), call.queryDate("to"), call.path("type"))).produces(EXCEL);
        routes.get("events", (resource, call) -> resource.getEvents(
                call.queryLongList("deviceId"), call.queryLongList("groupId"),
                call.queryList("type"), call.queryList("alarm"),
                call.queryDate("from"), call.queryDate("to")));
        routes.get("events", (resource, call) -> resource.getEventsExcel(
                call.queryLongList("deviceId"), call.queryLongList("groupId"),
                call.queryList("type"), call.queryList("alarm"),
                call.queryDate("from"), call.queryDate("to"), call.queryBoolean("mail"))).produces(EXCEL);
        routes.get("events/{type:xlsx|mail}", (resource, call) -> resource.getEventsExcel(
                call.queryLongList("deviceId"), call.queryLongList("groupId"),
                call.queryList("type"), call.queryList("alarm"),
                call.queryDate("from"), call.queryDate("to"), call.path("type"))).produces(EXCEL);
        routes.get("geofences", (resource, call) -> resource.getGeofences(
                call.queryLongList("deviceId"), call.queryLongList("groupId"), call.queryLongList("geofenceId"),
                call.queryDate("from"), call.queryDate("to")));
        routes.get("summary", (resource, call) -> resource.getSummary(
                call.queryLongList("deviceId"), call.queryLongList("groupId"),
                call.queryDate("from"), call.queryDate("to"), call.queryBoolean("daily")));
        routes.get("summary", (resource, call) -> resource.getSummaryExcel(
                call.queryLongList("deviceId"), call.queryLongList("groupId"),
                call.queryDate("from"), call.queryDate("to"), call.queryBoolean("daily"),
                call.queryBoolean("mail"))).produces(EXCEL);
        routes.get("summary/{type:xlsx|mail}", (resource, call) -> resource.getSummaryExcel(
                call.queryLongList("deviceId"), call.queryLongList("groupId"),
                call.queryDate("from"), call.queryDate("to"), call.queryBoolean("daily"),
                call.path("type"))).produces(EXCEL);
        routes.get("trips", (resource, call) -> resource.getTrips(
                call.queryLongList("deviceId"), call.queryLongList("groupId"),
                call.queryDate("from"), call.queryDate("to")));
        routes.get("trips", (resource, call) -> resource.getTripsExcel(
                call.queryLongList("deviceId"), call.queryLongList("groupId"),
                call.queryDate("from"), call.queryDate("to"), call.queryBoolean("mail"))).produces(EXCEL);
        routes.get("trips/{type:xlsx|mail}", (resource, call) -> resource.getTripsExcel(
                call.queryLongList("deviceId"), call.queryLongList("groupId"),
                call.queryDate("from"), call.queryDate("to"), call.path("type"))).produces(EXCEL);
        routes.get("stops", (resource, call) -> resource.getStops(
                call.queryLongList("deviceId"), call.queryLongList("groupId"),
                call.queryDate("from"), call.queryDate("to")));
        routes.get("stops", (resource, call) -> resource.getStopsExcel(
                call.queryLongList("deviceId"), call.queryLongList("groupId"),
                call.queryDate("from"), call.queryDate("to"), call.queryBoolean("mail"))).produces(EXCEL);
        routes.get("stops/{type:xlsx|mail}", (resource, call) -> resource.getStopsExcel(
                call.queryLongList("deviceId"), call.queryLongList("groupId"),
                call.queryDate("from"), call.queryDate("to"), call.path("type"))).produces(EXCEL);
        routes.get("devices/{type:xlsx|mail}", (resource, call) -> resource.getDevicesExcel(
                call.path("type"))).produces(EXCEL);
    }

}
