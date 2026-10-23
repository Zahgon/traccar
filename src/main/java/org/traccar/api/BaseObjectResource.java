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
package org.traccar.api;

import org.traccar.api.http.ApiReply;
import org.traccar.api.http.ApiResource;
import org.traccar.api.security.ServiceAccountUser;
import org.traccar.helper.LogAction;
import org.traccar.model.BaseModel;
import org.traccar.model.Group;
import org.traccar.model.ObjectOperation;
import org.traccar.model.Permission;
import org.traccar.model.User;
import org.traccar.session.ConnectionManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import java.util.Map;
import java.util.stream.Collectors;

public abstract class BaseObjectResource<T extends BaseModel> extends BaseResource {

    @Inject
    private CacheManager cacheManager;

    @Inject
    private ConnectionManager connectionManager;

    @Inject
    private LogAction actionLogger;

    protected final Class<T> baseClass;

    public BaseObjectResource(Class<T> baseClass) {
        this.baseClass = baseClass;
    }

    public static <M extends BaseModel, R extends BaseObjectResource<M>> void objectRoutes(ApiResource<R> routes) {
        routes.get("{id}", (resource, call) -> resource.getSingle(call.pathLong("id")));
        routes.post("", (resource, call) -> resource.add(call.json(resource.baseClass)));
        routes.put("{id}", (resource, call) -> resource.update(call.json(resource.baseClass)));
        routes.delete("{id}", (resource, call) -> resource.remove(call.pathLong("id")));
    }

    public ApiReply getSingle(long id) throws StorageException {
        permissionsService.checkPermission(baseClass, getUserId(), id);
        T entity = storage.getObject(baseClass, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
        if (entity != null) {
            return ApiReply.ok(entity);
        } else {
            return ApiReply.status(404);
        }
    }

    public ApiReply add(T entity) throws Exception {
        permissionsService.checkEdit(getUserId(), entity, true, false);

        if (entity instanceof Group group) {
            checkGroupHierarchy(group, true);
        }

        entity.setId(storage.addObject(entity, new Request(new Columns.Exclude("id"))));
        actionLogger.create(request, getUserId(), entity);

        if (getUserId() != ServiceAccountUser.ID) {
            storage.addPermission(new Permission(User.class, getUserId(), baseClass, entity.getId()));
            cacheManager.invalidatePermission(true, User.class, getUserId(), baseClass, entity.getId(), true);
            connectionManager.invalidatePermission(true, User.class, getUserId(), baseClass, entity.getId(), true);
            actionLogger.link(request, getUserId(), User.class, getUserId(), baseClass, entity.getId());
        }

        return ApiReply.ok(entity);
    }

    public ApiReply update(T entity) throws Exception {
        permissionsService.checkPermission(baseClass, getUserId(), entity.getId());

        boolean skipReadonly = false;
        if (entity instanceof User after) {
            User before = storage.getObject(User.class, new Request(
                    new Columns.All(), new Condition.Equals("id", entity.getId())));
            permissionsService.checkUserUpdate(getUserId(), before, after);
            skipReadonly = permissionsService.getUser(getUserId())
                    .compare(after, "notificationTokens", "termsAccepted");
        }

        permissionsService.checkEdit(getUserId(), entity, false, skipReadonly);

        if (entity instanceof Group group) {
            checkGroupHierarchy(group, false);
        }

        storage.updateObject(entity, new Request(
                new Columns.Exclude("id"),
                new Condition.Equals("id", entity.getId())));
        if (entity instanceof User user) {
            if (user.getHashedPassword() != null) {
                storage.updateObject(entity, new Request(
                        new Columns.Include("hashedPassword", "salt"),
                        new Condition.Equals("id", entity.getId())));
            }
        }
        cacheManager.invalidateObject(true, entity.getClass(), entity.getId(), ObjectOperation.UPDATE);
        actionLogger.edit(request, getUserId(), entity);

        return ApiReply.ok(entity);
    }

    void checkGroupHierarchy(Group group, boolean addition) throws StorageException {
        if (group.getGroupId() > 0) {
            Map<Long, Long> parents;
            try (var groups = storage.getObjectsStream(
                    Group.class, new Request(new Columns.Include("id", "groupId")))) {
                parents = groups.collect(Collectors.toMap(Group::getId, Group::getGroupId));
            }
            parents.put(addition ? 0L : group.getId(), group.getGroupId());
            for (long parentId : parents.values()) {
                int depth = 1;
                while (parentId > 0) {
                    if (++depth > Storage.MAX_GROUP_DEPTH) {
                        throw new IllegalArgumentException("Maximum group depth exceeded");
                    }
                    parentId = parents.getOrDefault(parentId, 0L);
                }
            }
        }
    }

    public ApiReply remove(long id) throws Exception {
        permissionsService.checkPermission(baseClass, getUserId(), id);
        permissionsService.checkEdit(getUserId(), baseClass, false, false);

        storage.removeObject(baseClass, new Request(new Condition.Equals("id", id)));
        cacheManager.invalidateObject(true, baseClass, id, ObjectOperation.DELETE);

        actionLogger.remove(request, getUserId(), baseClass, id);

        return ApiReply.noContent();
    }

}
