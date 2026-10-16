package com.kukuqi.tvbox.osc.update;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateRouteSelectorTest {
    private static final List<String> ROUTES = List.of("https://slow.example/app.apk", "https://fast.example/app.apk", "https://failed.example/app.apk");
    private Response response(Request request, int status, String range, String body) {
        Response.Builder builder = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("fixture").body(ResponseBody.create(MediaType.parse("application/octet-stream"), body));
        if (range != null) builder.header("Content-Range", range);
        return builder.build();
    }
    @Test public void fastestValidSampleIsFirstAndOtherRoutesRemainFallbacks() throws Exception {
        CountDownLatch fastStarted = new CountDownLatch(1);
        List<String> selected = UpdateRouteSelector.select(ROUTES, 3, request -> {
            if (request.url().host().equals("failed.example")) throw new IOException("offline");
            if (request.url().host().equals("slow.example")) {
                try { fastStarted.await(2, TimeUnit.SECONDS); Thread.sleep(1000); }
                catch (InterruptedException cancelled) { throw new IOException("cancelled", cancelled); }
            } else fastStarted.countDown();
            assertEquals("identity", request.header("Accept-Encoding"));
            assertEquals("bytes=0-2", request.header("Range"));
            return response(request, 206, "bytes 0-2/3", "abc");
        }, () -> false);
        assertEquals(List.of(ROUTES.get(1), ROUTES.get(0), ROUTES.get(2)), selected);
    }
    @Test public void htmlAndWrongRangeNeverWin() throws Exception {
        List<String> selected = UpdateRouteSelector.select(ROUTES, 3, request -> {
            if (request.url().host().equals("slow.example")) return response(request, 200, null, "html");
            if (request.url().host().equals("fast.example")) return response(request, 206, "bytes 0-2/999", "abc");
            return response(request, 206, "bytes 0-2/3", "abc");
        }, () -> false);
        assertEquals(ROUTES.get(2), selected.get(0));
    }
    @Test public void allFailedProbesPreserveFallbackOrder() throws Exception {
        assertEquals(ROUTES, UpdateRouteSelector.select(ROUTES, 3, request -> { throw new IOException("offline"); }, () -> false));
    }
    @Test public void cancelledSelectionDoesNotReadAnyBody() throws Exception {
        assertEquals(ROUTES, UpdateRouteSelector.select(ROUTES, 3, request -> { fail("cancelled request executed"); return null; }, () -> true));
    }
}
