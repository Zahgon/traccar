/*
 * Copyright 2018 - 2023 Anton Tananaev (anton@traccar.org)
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
package org.traccar.sms;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.DataConverter;
import org.traccar.helper.WebHelper;
import org.traccar.helper.http.HttpBody;
import org.traccar.helper.http.HttpCall;
import org.traccar.helper.http.HttpJsonClient;
import org.traccar.notification.MessageException;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public class HttpSmsClient implements SmsManager {

    private final HttpJsonClient client;
    private final String url;
    private final String authorizationHeader;
    private final String authorization;
    private final String template;
    private final String mediaType;

    public HttpSmsClient(Config config, HttpJsonClient client) {
        this.client = client;
        url = config.getString(Keys.SMS_HTTP_URL);
        authorizationHeader = config.getString(Keys.SMS_HTTP_AUTHORIZATION_HEADER);
        if (config.hasKey(Keys.SMS_HTTP_AUTHORIZATION)) {
            authorization = config.getString(Keys.SMS_HTTP_AUTHORIZATION);
        } else {
            String user = config.getString(Keys.SMS_HTTP_USER);
            String password = config.getString(Keys.SMS_HTTP_PASSWORD);
            if (user != null && password != null) {
                authorization = "Basic "
                        + DataConverter.printBase64((user + ":" + password).getBytes(StandardCharsets.UTF_8));
            } else {
                authorization = null;
            }
        }
        template = config.getString(Keys.SMS_HTTP_TEMPLATE).trim();
        if (template.charAt(0) == '<') {
            mediaType = HttpBody.XML;
        } else if (template.charAt(0) == '{' || template.charAt(0) == '[') {
            mediaType = HttpBody.JSON;
        } else {
            mediaType = HttpBody.FORM;
        }
    }

    private String prepareValue(String value) throws UnsupportedEncodingException {
        if (mediaType == HttpBody.FORM) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
        if (mediaType == HttpBody.JSON) {
            return new String(JsonStringEncoder.getInstance().quoteAsString(value));
        }
        return value;
    }

    private String preparePayload(String phone, String message) {
        try {
            return template
                    .replace("{phone}", prepareValue(phone))
                    .replace("{message}", prepareValue(message));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private HttpCall getRequestBuilder() {
        HttpCall builder = client.target(url).request();
        if (authorization != null) {
            builder = builder.header(authorizationHeader, authorization);
        }
        return builder;
    }

    @Override
    public CompletableFuture<Void> sendMessage(String phone, String message, boolean command) {
        HttpBody entity = HttpBody.text(
                preparePayload(phone, message), mediaType + ";charset=" + StandardCharsets.UTF_8.name());
        return WebHelper.post(getRequestBuilder(), entity, response -> {
            if (response.getStatus() / 100 != 2) {
                throw new MessageException(response.readEntity(String.class));
            }
        });
    }

}
