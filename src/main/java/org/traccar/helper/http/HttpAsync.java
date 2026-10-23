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
package org.traccar.helper.http;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Asynchronous variant of a request; the callback type argument selects how the entity is read.
 */
public final class HttpAsync {

    private final HttpCall call;

    HttpAsync(HttpCall call) {
        this.call = call;
    }

    private static <T> CompletableFuture<T> deliver(
            CompletableFuture<HttpReply> future, HttpCallback<T> callback) {
        @SuppressWarnings("unchecked")
        Class<T> type = (Class<T>) HttpCall.callbackType(callback);
        return future.thenApply(reply -> HttpCall.convert(reply, type)).whenComplete((result, error) -> {
            if (error != null) {
                callback.failed(error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error);
            } else {
                try {
                    callback.completed(result);
                } catch (RuntimeException e) {
                    callback.failed(e);
                }
            }
        });
    }

    public <T> CompletableFuture<T> get(HttpCallback<T> callback) {
        return deliver(call.sendAsync("GET", null), callback);
    }

    public <T> CompletableFuture<T> post(HttpBody body, HttpCallback<T> callback) {
        return deliver(call.sendAsync("POST", body), callback);
    }

    public CompletableFuture<HttpReply> post(HttpBody body) {
        return call.sendAsync("POST", body);
    }

}
