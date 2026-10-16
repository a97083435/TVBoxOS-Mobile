package com.kukuqi.tvbox.osc.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class RuntimeTransferTest {
    private static final String URL = "https://github.com/kukuqi666/TVboxOSC/releases/download/v3.0.5/module.apk";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final List<Request> requests = new ArrayList<>();

    private RuntimeTransfer transfer(int status, String body, String range) {
        return new RuntimeTransfer(new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request(); requests.add(request);
            Response.Builder response = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(status).message("fixture").body(ResponseBody.create(MediaType.parse("application/octet-stream"), body));
            if (range != null) response.header("Content-Range", range);
            return response.build();
        }).build());
    }

    @Test public void publicReleasesUseTwoMirrorsThenOfficial() {
        assertEquals(List.of(RuntimeTransfer.PRIMARY + URL, RuntimeTransfer.SECONDARY + URL, URL), RuntimeTransfer.routes(URL, 0));
    }
    @Test public void officialModeAndManualMirrorsRespectPreference() {
        assertEquals(List.of(URL), RuntimeTransfer.routes(URL, 1));
        assertEquals(List.of(RuntimeTransfer.PRIMARY + URL, URL), RuntimeTransfer.routes(URL, 2));
        assertEquals(List.of(RuntimeTransfer.SECONDARY + URL, URL), RuntimeTransfer.routes(URL, 3));
    }
    @Test public void catalogUsesTheSameRoutesAndDoesNotDoublePrefix() {
        String catalog = "https://github.com/kukuqi666/TVboxOSC/releases/latest/download/catalog.json";
        assertEquals(RuntimeTransfer.routes(catalog, 0), RuntimeTransfer.routes(RuntimeTransfer.PRIMARY + catalog, 0));
    }
    @Test public void privateCustomAndCredentialedUrlsNeverGoToPublicMirrors() {
        for (String url : List.of("https://private.example/catalog.json?token=private", URL + "?token=private",
                URL.replace("github.com", "user:password@github.com"), URL.replace("github.com", "github.com.evil.example"),
                URL.replace("kukuqi666/TVboxOSC", "private/another-repo"), "http://127.0.0.1/catalog.json")) {
            assertEquals(List.of(url), RuntimeTransfer.routes(url, 0));
        }
    }
    @Test public void normalDownloadRequestsUncompressedBytes() throws Exception {
        File part = temporary.newFile();
        transfer(200, "abcdef", null).download(URL, part, 6, value -> {});
        assertEquals("abcdef", Files.readString(part.toPath()));
        assertEquals("identity", requests.get(0).header("Accept-Encoding"));
        assertNull(requests.get(0).header("Range"));
    }
    @Test public void resumedDownloadAppendsOnlyMatchingRange() throws Exception {
        File part = temporary.newFile(); Files.writeString(part.toPath(), "abc");
        transfer(206, "def", "bytes 3-5/6").download(URL, part, 6, value -> {});
        assertEquals("bytes=3-", requests.get(0).header("Range"));
        assertEquals("abcdef", Files.readString(part.toPath()));
    }
    @Test public void serverIgnoringRangeRestartsInsteadOfAppending() throws Exception {
        File part = temporary.newFile(); Files.writeString(part.toPath(), "bad");
        transfer(200, "abcdef", null).download(URL, part, 6, value -> {});
        assertEquals("abcdef", Files.readString(part.toPath()));
    }
    @Test public void invalidRangeNeverChangesPartialData() throws Exception {
        for (String range : List.of("bytes 0-2/6", "bytes 3-5/7", "bytes 3-6/6", "invalid")) {
            File part = temporary.newFile(); Files.writeString(part.toPath(), "abc");
            try { transfer(206, "def", range).download(URL, part, 6, value -> {}); fail("Invalid range accepted"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("范围")); }
            assertEquals("abc", Files.readString(part.toPath()));
        }
    }
    @Test public void incompleteDownloadRetainsBytesForNextAttempt() throws Exception {
        File part = temporary.newFile();
        try { transfer(200, "abc", null).download(URL, part, 6, value -> {}); fail("Partial file accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("不完整")); }
        assertEquals("abc", Files.readString(part.toPath()));
        transfer(206, "def", "bytes 3-5/6").download(URL, part, 6, value -> {});
        assertEquals("abcdef", Files.readString(part.toPath()));
    }
    @Test public void oversizedResponseAndHttpErrorAreRejected() throws Exception {
        File part = temporary.newFile();
        try { transfer(200, "abcdefg", null).download(URL, part, 6, value -> {}); fail("Oversized file accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("长度")); }
        try { transfer(503, "error", null).download(URL, part, 6, value -> {}); fail("HTTP error accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("503")); }
        assertEquals(0, part.length());
    }
    @Test public void completePartialFileIsReturnedForVerificationWithoutAnotherRequest() throws Exception {
        File part = temporary.newFile(); Files.writeString(part.toPath(), "abcdef");
        transfer(503, "error", null).download(URL, part, 6, value -> {});
        assertTrue(requests.isEmpty());
    }
    @Test public void staleOversizedPartialFileRestarts() throws Exception {
        File part = temporary.newFile(); Files.writeString(part.toPath(), "old-version");
        transfer(200, "abcdef", null).download(URL, part, 6, value -> {});
        assertEquals("abcdef", Files.readString(part.toPath()));
        assertNull(requests.get(0).header("Range"));
    }
}
