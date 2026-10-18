package com.kukuqi.tvbox.osc;

import android.test.InstrumentationTestCase;
import androidx.media3.common.*;
import androidx.media3.common.text.CueGroup;
import com.kukuqi.tvbox.engine.api.config.VodConfig;
import com.kukuqi.tvbox.engine.bean.*;
import com.kukuqi.tvbox.engine.player.exo.ExoPlayerEngine;
import com.kukuqi.tvbox.engine.player.media.PlaySpec;
import com.kukuqi.tvbox.engine.player.parse.PreloadResolver;
import com.kukuqi.tvbox.engine.player.subtitle.AssSubtitleDecoder;
import com.kukuqi.tvbox.engine.setting.PlayerSetting;
import com.kukuqi.tvbox.engine.setting.SubtitleSetting;
import com.google.gson.JsonParser;
import fi.iki.elonen.NanoHTTPD;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class AdvancedPlaybackRegressionTest extends InstrumentationTestCase {
    private NanoHTTPD server;
    private String base;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicInteger licenses = new AtomicInteger();
    @Override protected void setUp() throws Exception {
        super.setUp();
        server = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession request) {
                if (request.getUri().equals("/license")) {
                    licenses.incrementAndGet();
                    if (!"fixture".equals(request.getHeaders().get("x-license"))) return newFixedLengthResponse(Response.Status.FORBIDDEN,"text/plain","missing license authorization");
                    return newFixedLengthResponse(Response.Status.OK,"application/json","{\"keys\":[{\"kty\":\"oct\",\"kid\":\"ESIzRFVmd4iZAKq7zN3u_w\",\"k\":\"ABEiM0RVZneImaq7zN3u_w\"}],\"type\":\"temporary\"}");
                }
                if (request.getUri().equals("/slow")) {
                    calls.incrementAndGet();
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                }
                if (request.getUri().equals("/bad")) return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "unavailable");
                if (request.getUri().equals("/parse")) {
                    calls.incrementAndGet();
                    if (!"source".equals(request.getHeaders().get("x-source")) || !"parser".equals(request.getHeaders().get("referer")))
                        return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "missing inherited headers");
                }
                return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"url\":\""+base+"/resolved.mp4\",\"header\":{\"X-Resolved\":\"yes\",\"Referer\":\"resolved\"}}");
            }
        };
        server.start(); base = "http://127.0.0.1:" + server.getListeningPort();
        VodConfig.get().accept(base, "test", JsonParser.parseString("{\"flags\":[\"vip\"],\"parses\":[{\"name\":\"json\",\"type\":1,\"url\":\""+base+"/parse?url=\"}]}").getAsJsonObject());
    }
    @Override protected void tearDown() throws Exception { server.stop(); super.tearDown(); }
    private Result request(String prefix) { return Result.fromJson("{\"parse\":1,\"flag\":\"vip\",\"url\":\"https://example.org/watch/2\",\"playUrl\":\""+prefix+"\",\"header\":{\"X-Source\":\"source\",\"referer\":\"original\"}}"); }
    private Parse parser(String path) { Parse parser = Parse.get(1, base+path+"?url="); parser.getExt().setHeader(Map.of("Referer", "parser")); return parser; }
    public void testJsonPreloadPreservesHeadersAndDoesNotMutateOriginal() throws Exception {
        Result original = request("");
        Result result = PreloadResolver.resolve(original, parser("/parse"), List.of());
        assertNotNull(result); assertEquals(base+"/resolved.mp4", result.getUrl().v());
        assertFalse(result.needParse()); assertEquals("yes", result.getHeader().get("X-Resolved"));
        assertEquals("resolved", result.getHeader().get("Referer")); assertEquals("source", result.getHeader().get("X-Source"));
        assertTrue(original.needParse()); assertEquals("original", original.getHeader().get("referer"));
    }
    public void testAutomaticPreloadFallsBackOnlyThroughJsonLines() throws Exception {
        Result result = PreloadResolver.resolve(request(""), Parse.god(), List.of(Parse.get(0,base+"/web"),parser("/bad"),parser("/parse")));
        assertNotNull(result); assertEquals(1,calls.get());
        assertNull(PreloadResolver.resolve(request(""),Parse.get(0,base+"/web"),List.of()));
        assertNull(PreloadResolver.resolve(request(""),Parse.get(3,"script"),List.of()));
    }
    public void testJsonPreloadCancellationReturnsPromptly() throws Exception {
        Thread worker = new Thread(() -> { try { PreloadResolver.resolve(request(""),parser("/slow"),List.of()); } catch (Exception expected) {} });
        worker.start(); long end=System.currentTimeMillis()+5000;
        while(calls.get()==0 && System.currentTimeMillis()<end) Thread.sleep(20);
        assertEquals(1,calls.get()); worker.interrupt(); worker.join(1500); assertFalse("Cancelled JSON request kept the resolver occupied",worker.isAlive());
    }
    public void testDirectPrefixAndDrmPreloadPolicy() throws Exception {
        Result direct=Result.fromJson("{\"parse\":0,\"url\":\"video.mp4\",\"playUrl\":\""+base+"/\"}");
        assertEquals(base+"/video.mp4",PreloadResolver.resolve(direct,null,List.of()).getRealUrl());
        direct.setDrm(Drm.create(base+"/license","clearkey",Map.of(),true));
        assertNull(PreloadResolver.resolve(direct,null,List.of()));
    }
    public void testExoActuallyRendersTwoSubtitleTracksAndClearsOnSwitch() throws Exception {
        java.io.File first = write("dual-en.srt","1\n00:00:00,000 --> 00:00:15,000\nPRIMARY ENGLISH\n");
        java.io.File second = write("dual-zh.srt","1\n00:00:00,000 --> 00:00:15,000\nSECONDARY CHINESE\n");
        ExoPlayerEngine[] engine={null}; AtomicBoolean primary=new AtomicBoolean(), secondary=new AtomicBoolean();
        int old = SubtitleSetting.getSecondaryMode();
        try {
            main(() -> {
                SubtitleSetting.putSecondaryMode(SubtitleSetting.SECONDARY_MODE_OFF);
                engine[0]=new ExoPlayerEngine(1,new Player.Listener(){
                    @Override public void onPlayerError(PlaybackException error){failure.set(error);}
                    @Override public void onCues(CueGroup group){if(group.cues.stream().anyMatch(c->c.text!=null&&c.text.toString().contains("PRIMARY")))primary.set(true);}
                });
                engine[0].setSecondarySubtitleOutput(group->{if(group.cues.stream().anyMatch(c->c.text!=null&&c.text.toString().contains("SECONDARY")))secondary.set(true);});
                Result result=media(); result.setSubs(List.of(sub(first,"en",C.SELECTION_FLAG_DEFAULT),sub(second,"und",C.SELECTION_FLAG_AUTOSELECT)));
                engine[0].start(PlaySpec.from(result,"dual",MediaMetadata.EMPTY),0);
            });
            await(()->primary.get()&&!engine[0].getSecondarySubtitleState().secondaryCandidates().isEmpty());
            main(()->engine[0].setSecondarySubtitleSelection(engine[0].getSecondarySubtitleState().secondaryCandidates().get(0)));
            await(secondary::get);
            main(()->{
                assertNotNull(engine[0].getSecondarySubtitleState().explicitSelection());
                engine[0].getPlayer().seekTo(1000);engine[0].getPlayer().pause();
                engine[0].setSecondarySubtitleSelection(null); assertNull(engine[0].getSecondarySubtitleState().explicitSelection());
                engine[0].start(PlaySpec.from(media(),"new",MediaMetadata.EMPTY),0);
                assertNull(engine[0].getSecondarySubtitleState().explicitSelection());
            });
        } finally {main(()->{if(engine[0]!=null)engine[0].release();SubtitleSetting.putSecondaryMode(old);});}
    }
    public void testExoAssRendersAnimatedBitmapCues() throws Exception { verifyAss(false); }
    public void testExoEmbeddedAssRendersAnimatedBitmapCues() throws Exception { verifyAss(true); }
    public void testClearKeyLicenseHeadersAndEncryptedPlayback() throws Exception {
        ExoPlayerEngine[] engine={null}; android.graphics.SurfaceTexture[] texture={null}; android.view.Surface[] surface={null};
        AtomicBoolean frame=new AtomicBoolean();
        try {
            main(()->{
                texture[0]=new android.graphics.SurfaceTexture(0); surface[0]=new android.view.Surface(texture[0]);
                engine[0]=new ExoPlayerEngine(1,new Player.Listener(){
                    @Override public void onPlayerError(PlaybackException error){failure.set(error);}
                    @Override public void onRenderedFirstFrame(){frame.set(true);}
                });
                engine[0].getPlayer().setVideoSurface(surface[0]);
                Result result=Result.fromJson("{\"url\":\"file:///sdcard/TVboxOSC-regression/clearkey.mp4\"}");
                result.setDrm(Drm.create(base+"/license","clearkey",Map.of("X-License","fixture"),true));
                engine[0].start(PlaySpec.from(result,"encrypted",MediaMetadata.EMPTY),0);
            });
            await(()->frame.get()&&engine[0].getPlayer().getCurrentPosition()>500);
            assertTrue("Player never requested the ClearKey license",licenses.get()>0);
        } finally {main(()->{if(engine[0]!=null)engine[0].release();if(surface[0]!=null)surface[0].release();if(texture[0]!=null)texture[0].release();});}
    }
    private void verifyAss(boolean embedded) throws Exception {
        ExoPlayerEngine[] engine={null}; AtomicInteger bitmaps=new AtomicInteger(); AtomicReference<Float> first=new AtomicReference<>(); AtomicBoolean moved=new AtomicBoolean();
        boolean old=PlayerSetting.isLibass();
        try {
            java.io.File subtitle = new java.io.File("/sdcard/TVboxOSC-regression/core-effects.ass"); assertTrue(subtitle.exists());
            main(()->{
                assertTrue("Existing player library does not expose libass",AssSubtitleDecoder.isAvailable());
                PlayerSetting.putLibass(true);
                engine[0]=new ExoPlayerEngine(1,new Player.Listener(){
                    @Override public void onPlayerError(PlaybackException error){failure.set(error);}
                    @Override public void onCues(CueGroup group){for(var cue:group.cues)if(cue.bitmap!=null){bitmaps.incrementAndGet();Float initial=first.get();if(initial==null)first.set(cue.position);else if(Math.abs(initial-cue.position)>.02)moved.set(true);}}
                });
                Result result=embedded?Result.fromJson("{\"url\":\"file:///sdcard/TVboxOSC-regression/core-effects.mkv\"}"):media();
                if(!embedded)result.setSubs(List.of(sub(subtitle,"en",C.SELECTION_FLAG_DEFAULT)));
                engine[0].start(PlaySpec.from(result,"ass",MediaMetadata.EMPTY),0);
            });
            try { await(()->bitmaps.get()>5&&moved.get()); }
            catch (AssertionError unavailable) { throw new AssertionError("ASS " + (embedded ? "embedded" : "external")
                    + ": bitmap cues=" + bitmaps.get() + ", first position=" + first.get() + ", moved=" + moved.get(), unavailable); }
        } finally {main(()->{if(engine[0]!=null)engine[0].release();PlayerSetting.putLibass(old);});}
    }
    private Result media(){return Result.fromJson("{\"url\":\"file:///sdcard/TVboxOSC-regression/tvbox-regression.mp4\"}");}
    private Sub sub(java.io.File file,String language,int flags){Sub sub=Sub.from(file.getName(),android.net.Uri.fromFile(file).toString(),language,file.getName().endsWith(".ass")?MimeTypes.TEXT_SSA:MimeTypes.APPLICATION_SUBRIP);sub.setFlag(flags);return sub;}
    private java.io.File write(String name,String text)throws Exception{java.io.File file=new java.io.File(getInstrumentation().getTargetContext().getFilesDir(),name);try(var out=new java.io.FileOutputStream(file)){out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}return file;}
    private interface Check{boolean ready();}
    private void await(Check check)throws Exception{for(int i=0;i<200;i++){if(failure.get()!=null)throw new AssertionError(failure.get());boolean[] ready={false};main(()->ready[0]=check.ready());if(ready[0])return;Thread.sleep(100);}fail("Advanced playback did not reach expected state");}
    private void main(Runnable action){Throwable[] error={null};getInstrumentation().runOnMainSync(()->{try{action.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new AssertionError(error[0]);}
}
