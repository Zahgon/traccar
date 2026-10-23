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
package org.traccar.api.resource;

import io.netty.buffer.ByteBuf;
import org.traccar.api.BaseResource;
import org.traccar.api.http.ApiReply;
import org.traccar.api.http.ApiResource;
import org.traccar.media.VideoStreamManager;
import org.traccar.model.Device;
import org.traccar.storage.StorageException;

import jakarta.inject.Inject;

public class VideoStreamResource extends BaseResource {

    @Inject
    private VideoStreamManager streamManager;

    public ApiReply playlist(
            long deviceId,
            int channel) throws StorageException {

        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        return ApiReply.ok(streamManager.getPlaylist(deviceId, channel), "application/vnd.apple.mpegurl");
    }

    public ApiReply segment(
            long deviceId,
            int channel,
            int index) throws StorageException {

        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        ByteBuf data = streamManager.getSegment(deviceId, channel, index);
        ApiReply.Body stream = output -> data.getBytes(data.readerIndex(), output, data.readableBytes());
        return ApiReply.ok(stream, "video/mp2t");
    }

    public static void routes(ApiResource<VideoStreamResource> routes) {
        routes.get("{deviceId}/{channel}/live.m3u8", (resource, call) -> resource.playlist(
                call.pathLong("deviceId"), call.pathInt("channel")));
        routes.get("{deviceId}/{channel}/{index}.ts", (resource, call) -> resource.segment(
                call.pathLong("deviceId"), call.pathInt("channel"), call.pathInt("index")));
    }

}
