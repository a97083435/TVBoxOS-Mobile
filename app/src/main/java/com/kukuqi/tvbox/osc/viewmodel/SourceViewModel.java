package com.kukuqi.tvbox.osc.viewmodel;

import android.text.TextUtils;

import android.util.Base64;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.blankj.utilcode.util.ToastUtils;
import com.github.catvod.crawler.Spider;
import com.kukuqi.tvbox.osc.api.ApiConfig;
import com.kukuqi.tvbox.osc.base.App;
import com.kukuqi.tvbox.osc.bean.AbsJson;
import com.kukuqi.tvbox.osc.bean.AbsSortJson;
import com.kukuqi.tvbox.osc.bean.AbsSortXml;
import com.kukuqi.tvbox.osc.bean.AbsXml;
import com.kukuqi.tvbox.osc.bean.Movie;
import com.kukuqi.tvbox.osc.bean.MovieSort;
import com.kukuqi.tvbox.osc.bean.SourceBean;
import com.kukuqi.tvbox.osc.event.RefreshEvent;
import com.kukuqi.tvbox.osc.util.DefaultConfig;
import com.kukuqi.tvbox.osc.util.HawkConfig;
import com.kukuqi.tvbox.osc.util.LOG;
import com.kukuqi.tvbox.osc.util.thunder.Thunder;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class SourceViewModel extends ViewModel {
    public MutableLiveData<AbsSortXml> sortResult;
    public MutableLiveData<AbsXml> listResult;
    public MutableLiveData<AbsXml> searchResult;
    public MutableLiveData<AbsXml> quickSearchResult;
    public MutableLiveData<AbsXml> detailResult;
    public MutableLiveData<JSONObject> playResult;

    public SourceViewModel() {
        sortResult = new MutableLiveData<>();
        listResult = new MutableLiveData<>();
        searchResult = new MutableLiveData<>();
        quickSearchResult = new MutableLiveData<>();
        detailResult = new MutableLiveData<>();
        playResult = new MutableLiveData<>();
    }

    public static final ExecutorService spThreadPool = Executors.newSingleThreadExecutor();

    // homeContent
    public void getSort(String sourceKey) {
        spThreadPool.execute(() -> {
            try {
                com.kukuqi.tvbox.engine.bean.Result result = com.kukuqi.tvbox.engine.api.SiteApi.homeContent(com.kukuqi.tvbox.osc.core.CoreBridge.site(sourceKey));
                AbsSortXml categories = sortJson(sortResult, result.toString());
                if (categories != null && Hawk.get(HawkConfig.HOME_REC, 1) == 1) {
                    AbsXml videos = json(null, result.toString(), sourceKey);
                    if (videos != null && videos.movie != null) categories.videoList = videos.movie.videoList;
                }
                sortResult.postValue(categories);
            } catch(Throwable e) { android.util.Log.e("TVboxCore", "home", e); sortResult.postValue(null); }
        });
    }
    // categoryContent
    public void getList(MovieSort.SortData sortData, int page) {
        spThreadPool.execute(() -> {
            try { json(listResult, com.kukuqi.tvbox.engine.api.SiteApi.categoryContent(ApiConfig.get().getHomeSourceBean().getKey(), sortData.id, String.valueOf(page), true, sortData.filterSelect).toString(), ApiConfig.get().getHomeSourceBean().getKey()); }
            catch(Throwable e) { android.util.Log.e("TVboxCore", "category", e); listResult.postValue(null); }
        });
    }

    interface HomeRecCallback {
        void done(List<Movie.Video> videos);
    }
//    homeVideoContent
    void getHomeRecList(SourceBean sourceBean, ArrayList<String> ids, HomeRecCallback callback) {
        spThreadPool.execute(() -> {
            try {
                com.kukuqi.tvbox.engine.bean.Result result = sourceBean.getType() == 0 || sourceBean.getType() == 1
                        ? com.kukuqi.tvbox.engine.api.SiteApi.detailContent(sourceBean.getKey(), TextUtils.join(",", ids))
                        : com.kukuqi.tvbox.engine.api.SiteApi.homeContent(com.kukuqi.tvbox.osc.core.CoreBridge.site(sourceBean.getKey()));
                AbsXml data = json(null, result.toString(), sourceBean.getKey());
                callback.done(data == null || data.movie == null ? null : data.movie.videoList);
            } catch (Throwable failure) {
                android.util.Log.e("TVboxCore", "home recommendation", failure);
                callback.done(null);
            }
        });
    }
    // detailContent
    public void getDetail(String sourceKey, String id) {
        if (com.kukuqi.tvbox.osc.ui.dialog.VideoLinkDialog.SOURCE.equals(sourceKey)) {
            Movie.Video video = new Movie.Video();
            video.id = id; video.name = "链接播放"; video.sourceKey = sourceKey; video.pic = "";
            video.urlBean = new Movie.Video.UrlBean();
            Movie.Video.UrlBean.UrlInfo line = new Movie.Video.UrlBean.UrlInfo();
            line.flag = "链接";
            line.beanList = java.util.Collections.singletonList(new Movie.Video.UrlBean.UrlInfo.InfoBean("播放", id));
            video.urlBean.infoList = java.util.Collections.singletonList(line);
            AbsXml result = new AbsXml(); result.movie = new Movie(); result.movie.videoList = java.util.Collections.singletonList(video);
            detailResult.postValue(result);
            return;
        }
        spThreadPool.execute(() -> {
            try {
                com.kukuqi.tvbox.engine.bean.Result core = com.kukuqi.tvbox.engine.api.SiteApi.detailContent(sourceKey,id);
                JsonObject document = JsonParser.parseString(core.toString()).getAsJsonObject();
                JsonArray list = document.getAsJsonArray("list");
                for (int i = 0; list != null && i < list.size() && i < core.getList().size(); i++) {
                    com.kukuqi.tvbox.engine.bean.Vod vod = core.getList().get(i);
                    if (vod.getFlags().isEmpty()) continue;
                    ArrayList<String> flags = new ArrayList<>(), urls = new ArrayList<>();
                    for (com.kukuqi.tvbox.engine.bean.Flag flag : vod.getFlags()) {
                        flags.add(flag.getFlag());
                        ArrayList<String> episodes = new ArrayList<>();
                        for (com.kukuqi.tvbox.engine.bean.Episode episode : flag.getEpisodes()) episodes.add(episode.getName()+"$"+episode.getUrl());
                        urls.add(TextUtils.join("#",episodes));
                    }
                    list.get(i).getAsJsonObject().addProperty("vod_play_from",TextUtils.join("$$$",flags));
                    list.get(i).getAsJsonObject().addProperty("vod_play_url",TextUtils.join("$$$",urls));
                }
                json(detailResult,document.toString(),sourceKey);
            }
            catch(Throwable e) { android.util.Log.e("TVboxCore", "detail", e); detailResult.postValue(null); }
        });
    }
    // searchContent
    public void getSearch(String sourceKey, String wd) {
        spThreadPool.execute(() -> {
            try { json(searchResult, com.kukuqi.tvbox.engine.api.SiteApi.searchContent(com.kukuqi.tvbox.osc.core.CoreBridge.site(sourceKey),wd,false,"1").toString(),sourceKey); }
            catch(Throwable e) { json(searchResult,"",sourceKey); }
        });
    }
    // searchContent
    public void getQuickSearch(String sourceKey, String wd) {
        spThreadPool.execute(() -> {
            try { json(quickSearchResult, com.kukuqi.tvbox.engine.api.SiteApi.searchContent(com.kukuqi.tvbox.osc.core.CoreBridge.site(sourceKey),wd,true,"1").toString(),sourceKey); }
            catch(Throwable e) { json(quickSearchResult,"",sourceKey); }
        });
    }
    // playerContent
    public void getPlay(String sourceKey, String playFlag, String progressKey, String url, String subtitleKey) {
        spThreadPool.execute(() -> {
            try {
                com.kukuqi.tvbox.engine.bean.Result value = com.kukuqi.tvbox.osc.core.CoreBridge.player(sourceKey,playFlag,url);
                JSONObject result = new JSONObject(value.toString());
                // Keep the complete core response, including named quality URLs and DRM/subtitles.
                result.put("coreResult", new JSONObject(value.toString()));
                result.put("url", value.getUrl().v());
                result.put("key", url); result.put("siteKey", sourceKey);
                result.put("proKey",progressKey); result.put("subtKey",subtitleKey);
                if (!result.has("flag")) result.put("flag",playFlag);
                playResult.postValue(result);
            } catch(Throwable e) { android.util.Log.e("TVboxCore","player",e); playResult.postValue(null); }
        });
    }

    private MovieSort.SortFilter getSortFilter(JsonObject obj) {
        String key = obj.get("key").getAsString();
        String name = obj.get("name").getAsString();
        JsonArray kv = obj.getAsJsonArray("value");
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (JsonElement ele : kv) {
            JsonObject ele_obj = ele.getAsJsonObject();
            String values_key=ele_obj.has("n")?ele_obj.get("n").getAsString():"";
            String values_value=ele_obj.has("v")?ele_obj.get("v").getAsString():"";
            values.put(values_key, values_value);
        }
        MovieSort.SortFilter filter = new MovieSort.SortFilter();
        filter.key = key;
        filter.name = name;
        filter.values = values;
        return filter;
    }

    private AbsSortXml sortJson(MutableLiveData<AbsSortXml> result, String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            AbsSortJson sortJson = new Gson().fromJson(obj, new TypeToken<AbsSortJson>() {
            }.getType());
            AbsSortXml data = sortJson.toAbsSortXml();
            try {
                if (obj.has("filters")) {
                    LinkedHashMap<String, ArrayList<MovieSort.SortFilter>> sortFilters = new LinkedHashMap<>();
                    JsonObject filters = obj.getAsJsonObject("filters");
                    for (String key : filters.keySet()) {
                        ArrayList<MovieSort.SortFilter> sortFilter = new ArrayList<>();
                        JsonElement one = filters.get(key);
                        if (one.isJsonObject()) {
                            sortFilter.add(getSortFilter(one.getAsJsonObject()));
                        } else {
                            for (JsonElement ele : one.getAsJsonArray()) {
                                sortFilter.add(getSortFilter(ele.getAsJsonObject()));
                            }
                        }
                        sortFilters.put(key, sortFilter);
                    }
                    for (MovieSort.SortData sort : data.classes.sortList) {
                        if (sortFilters.containsKey(sort.id) && sortFilters.get(sort.id) != null) {
                            sort.filters = sortFilters.get(sort.id);
                        }
                    }
                }
            } catch (Throwable th) {

            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private AbsSortXml sortXml(MutableLiveData<AbsSortXml> result, String xml) {
        try {
            XStream xstream = new XStream(new DomDriver());//创建Xstram对象
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsSortXml.class);
            xstream.ignoreUnknownElements();
            AbsSortXml data = (AbsSortXml) xstream.fromXML(xml);
            for (MovieSort.SortData sort : data.classes.sortList) {
                if (sort.filters == null) {
                    sort.filters = new ArrayList<>();
                }
            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private void absXml(AbsXml data, String sourceKey) {
        if (data.movie != null && data.movie.videoList != null) {
            for (Movie.Video video : data.movie.videoList) {
                if (video.urlBean != null && video.urlBean.infoList != null) {
                    for (Movie.Video.UrlBean.UrlInfo urlInfo : video.urlBean.infoList) {
                        String[] str = null;
                        if (urlInfo.urls.contains("#")) {
                            str = urlInfo.urls.split("#");
                        } else {
                            str = new String[]{urlInfo.urls};
                        }
                        List<Movie.Video.UrlBean.UrlInfo.InfoBean> infoBeanList = new ArrayList<>();
                        for (String s : str) {
                            String entry = s.trim();
                            if (entry.isEmpty()) continue;
                            String[] ss = entry.split("\\$", 2);
                            String number = String.valueOf(infoBeanList.size() + 1);
                            String name = ss.length == 2 && !ss[0].trim().isEmpty() ? ss[0].trim() : number;
                            String url = ss.length == 2 ? ss[1].trim() : entry;
                            if (!url.isEmpty()) infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(name, url));
                        }
                        urlInfo.beanList = infoBeanList;
                    }
                }
                video.sourceKey = sourceKey;
            }
        }
    }

    public void checkThunder(AbsXml data, int index) {
        if (index == 0) detailResult.postValue(data);
    }


    private AbsXml xml(MutableLiveData<AbsXml> result, String xml, String sourceKey) {
        try {
            XStream xstream = new XStream(new DomDriver());//创建Xstram对象
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsXml.class);
            xstream.ignoreUnknownElements();
            if (xml.contains("<year></year>")) {
                xml = xml.replace("<year></year>", "<year>0</year>");
            }
            if (xml.contains("<state></state>")) {
                xml = xml.replace("<state></state>", "<state>0</state>");
            }
            AbsXml data = (AbsXml) xstream.fromXML(xml);
            absXml(data, sourceKey);
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data));
            } else if (quickSearchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, data));
            } else if (result != null) {
                if (result == detailResult) {
                    checkThunder(data,0);
                } else {
                    result.postValue(data);
                }
            }
            return data;
        } catch (Exception e) {
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, null));
            } else if (quickSearchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, null));
            } else if (result != null) {
                result.postValue(null);
            }
            return null;
        }
    }

    private AbsXml json(MutableLiveData<AbsXml> result, String json, String sourceKey) {
        try {
            // 测试数据
            /*json = "{\n" +
                    "\t\"list\": [{\n" +
                    "\t\t\"vod_id\": \"137133\",\n" +
                    "\t\t\"vod_name\": \"磁力测试\",\n" +
                    "\t\t\"vod_pic\": \"https:/img9.doubanio.com/view/photo/s_ratio_poster/public/p2656327176.webp\",\n" +
                    "\t\t\"type_name\": \"剧情 / 爱情 / 古装\",\n" +
                    "\t\t\"vod_year\": \"2022\",\n" +
                    "\t\t\"vod_area\": \"中国大陆\",\n" +
                    "\t\t\"vod_remarks\": \"40集全\",\n" +
                    "\t\t\"vod_actor\": \"刘亦菲\",\n" +
                    "\t\t\"vod_director\": \"杨阳\",\n" +
                    "\t\t\"vod_content\": \"　　在钱塘开茶铺的赵盼儿（刘亦菲 饰）惊闻未婚夫、新科探花欧阳旭（徐海乔 饰）要另娶当朝高官之女，不甘命运的她誓要上京讨个公道。在途中她遇到了出自权门但生性正直的皇城司指挥顾千帆（陈晓 饰），并卷入江南一场大案，两人不打不相识从而结缘。赵盼儿凭借智慧解救了被骗婚而惨遭虐待的“江南第一琵琶高手”宋引章（林允 饰）与被苛刻家人逼得离家出走的豪爽厨娘孙三娘（柳岩 饰），三位姐妹从此结伴同行，终抵汴京，见识世间繁华。为了不被另攀高枝的欧阳旭从东京赶走，赵盼儿与宋引章、孙三娘一起历经艰辛，将小小茶坊一步步发展为汴京最大的酒楼，揭露了负心人的真面目，收获了各自的真挚感情和人生感悟，也为无数平凡女子推开了一扇平等救赎之门。\",\n" +
                    "\t\t\"vod_play_from\": \"磁力测试\",\n" +
                    "\t\t\"vod_play_url\": \"0$magnet:?xt=urn:btih:9e9358b946c427962533472efdd2efd9e9e38c67&dn=%e9%98%b3%e5%85%89%e7%94%b5%e5%bd%b1www.ygdy8.com.%e7%83%ad%e8%a1%80.2022.BD.1080P.%e9%9f%a9%e8%af%ad%e4%b8%ad%e8%8b%b1%e5%8f%8c%e5%ad%97.mkv&tr=udp%3a%2f%2ftracker.opentrackr.org%3a1337%2fannounce&tr=udp%3a%2f%2fexodus.desync.com%3a6969%2fannounce\"\n" +
                    "\t}]\n" +
                    "}";*/
            AbsJson absJson = new Gson().fromJson(json, new TypeToken<AbsJson>() {
            }.getType());
            AbsXml data = absJson.toAbsXml();
            absXml(data, sourceKey);
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data));
            } else if (quickSearchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, data));
            } else if (result != null) {
                if (result == detailResult) {
                    checkThunder(data,0);
                } else {
                    result.postValue(data);
                }
            }
            return data;
        } catch (Exception e) {
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, null));
            } else if (quickSearchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, null));
            } else if (result != null) {
                result.postValue(null);
            }
            return null;
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
    }
}
