package com.kukuqi.tvbox.osc;

import android.test.InstrumentationTestCase;
import com.kukuqi.tvbox.engine.api.SiteApi;
import com.kukuqi.tvbox.engine.api.config.VodConfig;
import com.kukuqi.tvbox.engine.api.loader.BaseLoader;
import com.kukuqi.tvbox.engine.bean.Parse;
import com.kukuqi.tvbox.engine.bean.Result;
import com.kukuqi.tvbox.engine.impl.ParseCallback;
import com.kukuqi.tvbox.engine.player.parse.ParseJob;
import com.google.gson.JsonParser;
import fi.iki.elonen.NanoHTTPD;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercises upstream loaders and parsing on Android, beyond presentation-model conversion. */
public class CoreEngineRegressionTest extends InstrumentationTestCase {
    private NanoHTTPD server;
    private String base;
    @Override protected void setUp() throws Exception {
        super.setUp();
        server = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                if (session.getUri().endsWith(".js")) return newFixedLengthResponse(Response.Status.OK, "text/javascript",
                    "export default {init: async()=>{}, home: async()=>JSON.stringify({class:[{type_id:'1',type_name:'JS分类'}]}), homeVod: async()=>JSON.stringify({list:[{vod_id:'js1',vod_name:'"+(session.getUri().equals("/second.js")?"替换影片":"JS影片")+"'}]}), play: async(flag,id)=>JSON.stringify({parse:0,url:id,header:{'X-Core':'js'}}), destroy: async()=>{}};");
                if (session.getUri().endsWith(".py")) return newFixedLengthResponse(Response.Status.OK, "text/plain",
                    "from base.spider import Spider as Base\nclass Spider(Base):\n def init(self,extend=''): pass\n def homeContent(self,filter): return {'class':[{'type_id':'1','type_name':'Python分类'}]}\n def homeVideoContent(self): return {'list':[{'vod_id':'py1','vod_name':'Python影片'}]}\n def playerContent(self,flag,id,vipFlags): return {'parse':0,'url':id,'header':{'X-Core':'py'}}\n def destroy(self): pass\n");
                if (session.getUri().equals("/parse")) return newFixedLengthResponse(Response.Status.OK,"application/json", "{\"url\":\""+base+"/video.mp4?fixture=upstream-parser-test\",\"header\":{\"X-Resolved\":\"yes\"}}");
                if (session.getUri().equals("/video.mp4")) {
                    try { java.io.File file=new java.io.File("/sdcard/Download/tvbox-regression.mp4"); return newFixedLengthResponse(Response.Status.OK,"video/mp4",new java.io.FileInputStream(file),file.length()); }
                    catch(Exception failure) {return newFixedLengthResponse(Response.Status.NOT_FOUND,"text/plain","missing media fixture");}
                }
                return newFixedLengthResponse(Response.Status.OK,"text/html", "<html><video autoplay muted src='"+base+"/video.mp4?fixture=upstream-web-parser-test'></video></html>");
            }
        };
        server.start(); base="http://127.0.0.1:"+server.getListeningPort();
    }
    @Override protected void tearDown() throws Exception { server.stop(); super.tearDown(); }
    private void configure(String language) {
        VodConfig.get().accept(base+"/config", language, JsonParser.parseString("{\"sites\":[{\"key\":\""+language+"\",\"name\":\""+language+"\",\"type\":3,\"api\":\""+base+"/fixture."+language+"\"}]}").getAsJsonObject());
    }
    public void testJavascriptLoaderHomeAndPlayer() throws Exception { verifyLoader("js", "JS影片"); }
    public void testPythonLoaderHomeAndPlayer() throws Exception { verifyLoader("py", "Python影片"); }
    private void verifyLoader(String language, String title) throws Exception {
        configure(language);
        Result home=SiteApi.homeContent(VodConfig.get().getSite(language));
        assertEquals(1,home.getTypes().size()); assertEquals(title, home.getList().get(0).getName());
        Result player=SiteApi.playerContent(language,"line",base+"/video.mp4");
        assertFalse(player.needParse()); assertEquals(base+"/video.mp4",player.getUrl().v());
        assertEquals(language,player.getHeader().get("X-Core"));
    }
    public void testJsonParsingPreservesHeaders() throws Exception {
        Result result=Result.objectFrom("{\"url\":\"https://example.org/watch\",\"header\":{\"X-Input\":\"yes\"}}");
        Parse parse=Parse.get(1,base+"/parse?url=");
        CountDownLatch done=new CountDownLatch(1); String[] url={null}; Map[] headers={null};
        ParseJob job=ParseJob.create(new ParseCallback(){
            @Override public void onParseSuccess(Map<String,String> value,String resolved,String from){ headers[0]=value;url[0]=resolved;done.countDown(); }
            @Override public void onParseError(){done.countDown();}
        }).start(result,parse);
        try {assertTrue(done.await(20,TimeUnit.SECONDS));assertEquals(base+"/video.mp4?fixture=upstream-parser-test",url[0]);assertEquals("yes",headers[0].get("X-Resolved"));}
        finally {job.stop();}
    }
    public void testConfigReloadReplacesSitesAndParses() {
        VodConfig.get().accept(base+"/a","old",JsonParser.parseString("{\"sites\":[{\"key\":\"old\"}],\"parses\":[{\"name\":\"old\",\"type\":1,\"url\":\"https://example.org/\"}]}").getAsJsonObject());
        VodConfig.get().accept(base+"/b","new",JsonParser.parseString("{\"sites\":[{\"key\":\"new\"}]}").getAsJsonObject());
        assertTrue(VodConfig.get().getSite("old").isEmpty()); assertTrue(VodConfig.get().getParses().isEmpty()); assertEquals("new",VodConfig.get().getHome().getKey());
    }
    public void testReloadSameSiteKeyReplacesCachedJavascriptSpider() throws Exception {
        configure("js");assertEquals("JS影片",SiteApi.homeContent(VodConfig.get().getSite("js")).getList().get(0).getName());
        VodConfig.get().accept(base+"/config","js",JsonParser.parseString("{\"sites\":[{\"key\":\"js\",\"name\":\"js\",\"type\":3,\"api\":\""+base+"/second.js\"}]}").getAsJsonObject());
        assertEquals("替换影片",SiteApi.homeContent(VodConfig.get().getSite("js")).getList().get(0).getName());
    }
    public void testWebParserSniffsActualVideoRequest() throws Exception {
        assertParsed(Parse.get(0,base+"/html?url="),base+"/video.mp4?fixture=upstream-web-parser-test");
    }
    public void testAggregateParserUsesCoreJsonLines() throws Exception {
        VodConfig.get().accept(base+"/config","",JsonParser.parseString("{\"parses\":[{\"name\":\"fixture\",\"type\":1,\"url\":\""+base+"/parse?url=\"}]}").getAsJsonObject());
        assertParsed(Parse.get(4,""),base+"/video.mp4?fixture=upstream-parser-test");
    }
    public void testJarJsonAndMixParsersUseUpstreamAbi() throws Exception {
        java.io.File jar=new java.io.File(getInstrumentation().getTargetContext().getCacheDir(),"fixture-parser.jar");
        if(jar.exists()){jar.setWritable(true);assertTrue(jar.delete());}
        try(java.io.InputStream input=getInstrumentation().getContext().getAssets().open("fixture-parser.jar");java.io.OutputStream output=new java.io.FileOutputStream(jar)) {
            byte[] bytes=new byte[4096];int count;while((count=input.read(bytes))!=-1)output.write(bytes,0,count);
        }
        VodConfig.get().accept(base+"/config","",JsonParser.parseString("{\"parses\":[{\"name\":\"fixture\",\"type\":1,\"url\":\""+base+"/parse?url=\"}]}").getAsJsonObject());
        BaseLoader.get().parseJar(android.net.Uri.fromFile(jar).toString(),true);
        assertParsed(Parse.get(2,"Fixture"),base+"/video.mp4?json=1");
        assertParsed(Parse.get(3,"Fixture"),base+"/video.mp4?mix="+VodConfig.get().getParses().size()+"&flag=line");
    }
    private void assertParsed(Parse parse,String expected) throws Exception {
        Result result=Result.objectFrom("{\"url\":\""+base+"\",\"flag\":\"line\",\"header\":{\"X-Input\":\"yes\"}}");
        CountDownLatch done=new CountDownLatch(1);String[] resolved={null};
        ParseJob job=ParseJob.create(new ParseCallback(){
            @Override public void onParseSuccess(Map<String,String> value,String url,String from){resolved[0]=url;done.countDown();}
            @Override public void onParseError(){done.countDown();}
        }).start(result,parse);
        try {assertTrue("Core parser callback timed out",done.await(25,TimeUnit.SECONDS));assertEquals(expected,resolved[0]);}
        finally {getInstrumentation().runOnMainSync(job::stop);}
    }
    public void testExoPlaybackSeekPauseAndSpeed() throws Exception { verifyPlayback(2); }
    public void testMpvPlaybackSeekPauseAndSpeed() throws Exception { verifyPlayback(1); }
    public void testExoAudioTracksAndExternalSubtitles() throws Exception { verifyPlayback(2,true); }
    public void testMpvAudioTracksAndExternalSubtitles() throws Exception { verifyPlayback(1,true); }
    private void verifyPlayback(int backend) throws Exception {
        verifyPlayback(backend,false);
    }
    private void verifyPlayback(int backend,boolean tracks) throws Exception {
        int old=com.orhanobut.hawk.Hawk.get(com.kukuqi.tvbox.osc.util.HawkConfig.PLAY_TYPE,2);
        com.kukuqi.tvbox.osc.ui.activity.LocalPlayActivity[] activity={null};
        com.kukuqi.tvbox.osc.player.MyVideoView[] video={null};
        String filePath=tracks?"/sdcard/Download/core-tracks.mkv":"/sdcard/Download/tvbox-regression.mp4";
        onMain(()->{
            com.kukuqi.tvbox.osc.base.App.getInstance().isNormalStart=true;
            com.orhanobut.hawk.Hawk.put(com.kukuqi.tvbox.osc.util.HawkConfig.PLAY_TYPE,backend);
            com.blankj.utilcode.util.SPUtils.getInstance(com.kukuqi.tvbox.osc.constant.CacheConst.VIDEO_PROGRESS_SP).put(filePath,0L);
        });
        try {
            activity[0]=(com.kukuqi.tvbox.osc.ui.activity.LocalPlayActivity)getInstrumentation().startActivitySync(new android.content.Intent(getInstrumentation().getTargetContext(),com.kukuqi.tvbox.osc.ui.activity.LocalPlayActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("position",0).putExtra("videoList","[{\"path\":\""+filePath+"\",\"displayName\":\"核心播放测试\"}]"));
            video[0]=activity[0].findViewById(R.id.player);
            await(()->video[0].isPlaying() && video[0].getCurrentPosition()>200);
            onMain(()->{
                com.kukuqi.tvbox.osc.player.CorePlayer player=(com.kukuqi.tvbox.osc.player.CorePlayer)video[0].getMediaPlayer();
                assertEquals(backend==1?com.kukuqi.tvbox.engine.setting.PlayerSetting.ENGINE_MPV:com.kukuqi.tvbox.engine.setting.PlayerSetting.ENGINE_EXO,player.manager().getEngine());
                assertFalse(player.getTrackInfo().getAudio().isEmpty());
                video[0].setSpeed(1.5f); assertEquals(1.5f,video[0].getSpeed(),0.01f);
                video[0].seekTo(5000);
            });
            await(()->video[0].getCurrentPosition()>=4900);
            onMain(()->video[0].pause()); await(()->!video[0].isPlaying());
            long[] position={0}; onMain(()->position[0]=video[0].getCurrentPosition());
            Thread.sleep(500);
            onMain(()->{assertTrue("Paused position: "+position[0]+" -> "+video[0].getCurrentPosition(),Math.abs(video[0].getCurrentPosition()-position[0])<300);video[0].resume();});
            await(()->video[0].isPlaying()&&video[0].getCurrentPosition()>position[0]+100);
            if(tracks) verifyTracks(video[0],activity[0],backend);
            android.graphics.Bitmap screenshot=getInstrumentation().getUiAutomation().takeScreenshot();
            try(java.io.FileOutputStream output=new java.io.FileOutputStream(new java.io.File(getInstrumentation().getTargetContext().getExternalFilesDir(null),"core-"+(tracks?"tracks":"engine")+"-"+backend+".png"))){screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);} finally{screenshot.recycle();}
        } finally {onMain(()->{if(activity[0]!=null)activity[0].finish();com.orhanobut.hawk.Hawk.put(com.kukuqi.tvbox.osc.util.HawkConfig.PLAY_TYPE,old);});}
    }
    private void verifyTracks(com.kukuqi.tvbox.osc.player.MyVideoView video,android.app.Activity activity,int backend) throws Exception {
        com.kukuqi.tvbox.osc.player.CorePlayer player=(com.kukuqi.tvbox.osc.player.CorePlayer)video.getMediaPlayer();
        await(()->player.getTrackInfo().getAudio().size()==2 && !player.getTrackInfo().getSubtitle().isEmpty());
        com.kukuqi.tvbox.osc.player.TrackInfoBean[] selected={null};
        onMain(()->{video.pause();selected[0]=player.getTrackInfo().getAudio().get(1);player.selectTrack(selected[0]);});
        await(()->player.getTrackInfo().getAudio().stream().anyMatch(item->item.trackGroupId==selected[0].trackGroupId && item.trackId==selected[0].trackId && item.selected));
        onMain(()->assertFalse("Switching tracks resumed a paused video",video.isPlaying()));
        com.kukuqi.tvbox.osc.subtitle.widget.SimpleSubtitleView subtitles=activity.findViewById(R.id.subtitle_view);
        java.util.concurrent.atomic.AtomicBoolean externalCue=new java.util.concurrent.atomic.AtomicBoolean();
        onMain(()->{
            player.setCueListener(group->{for(androidx.media3.common.text.Cue cue:group.cues)if(cue.text!=null&&cue.text.toString().contains("EXTERNAL CORE"))externalCue.set(true);});
            subtitles.setVisibility(android.view.View.VISIBLE);
            subtitles.setSubtitlePath("/sdcard/Download/core-external.srt");
            video.seekTo(1000);video.resume();
        });
        await(()->player.getTrackInfo().getSubtitle().size()>=2);
        onMain(()->{
            subtitles.setSubtitleDelay(500);assertEquals(500L,player.manager().getTextOffsetMs());
            subtitles.setVisibility(android.view.View.GONE);
            if(backend==1)assertTrue(player.player().getTrackSelectionParameters().disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT));
            else assertFalse("Hidden subtitles still render",player.useRenderedSubtitles());
            subtitles.setVisibility(android.view.View.VISIBLE);
            if(backend==1)assertFalse(player.player().getTrackSelectionParameters().disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT));
        });
        if(backend==2) {
            await(()->player.player().getCurrentCues().cues.stream().anyMatch(cue->cue.text!=null&&cue.text.toString().contains("EXTERNAL CORE")));
            onMain(()->assertTrue("Core subtitle renderer is disabled",player.useRenderedSubtitles()));
        }
        Thread.sleep(300);
    }
    private interface Check {boolean ready();}
    private void await(Check check) throws Exception {
        for(int i=0;i<200;i++){boolean[] ready={false};onMain(()->ready[0]=check.ready());if(ready[0])return;Thread.sleep(100);}
        fail("Core playback did not reach expected state");
    }
    private void onMain(Runnable action) { Throwable[] error={null}; getInstrumentation().runOnMainSync(()->{try{action.run();}catch(Throwable failure){error[0]=failure;}}); if(error[0]!=null)throw new AssertionError(error[0]); }
}
