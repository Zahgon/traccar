package org.traccar.speedlimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.helper.http.HttpJsonClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class OverpassSpeedLimitProviderTest {

    private final HttpJsonClient client = new HttpJsonClient(new ObjectMapper(), 0, 0);

    @Disabled
    @Test
    public void testOverpass() throws Exception {
        var config = new Config();
        SpeedLimitProvider provider = new OverpassSpeedLimitProvider(config, client, "http://8.8.8.8/api/interpreter");

        provider.getSpeedLimit(34.74767, -82.48098, new SpeedLimitProvider.SpeedLimitProviderCallback() {
            @Override
            public void onSuccess(double speedLimit) {
                assertEquals(52.1, speedLimit, 0.1);
            }

            @Override
            public void onFailure(Throwable e) {
                fail();
            }
        });

        Thread.sleep(Long.MAX_VALUE);
    }

}
