package com.kukuqi.tvbox.osc.core;

import com.kukuqi.tvbox.engine.App;
import com.kukuqi.tvbox.engine.api.LiveApi;
import com.kukuqi.tvbox.engine.api.config.LiveConfig;
import com.kukuqi.tvbox.engine.api.parser.LiveParser;
import com.kukuqi.tvbox.engine.bean.*;
import com.google.gson.*;
import com.kukuqi.tvbox.osc.util.ConfigCompat;
import com.kukuqi.tvbox.osc.util.LiveConfigCompat;
import com.kukuqi.tvbox.osc.util.SourceDescriptor;
import java.util.*;

/** Upstream live execution with the existing OSC presentation models. */
public final class LiveCoreBridge {
    private LiveCoreBridge() { }
    public static synchronized JsonArray parse(String url, String content) throws Exception {
        List<Live> sources = new ArrayList<>();
        String text = content.trim();
        if (text.startsWith("{") || text.startsWith("[")) {
            SourceDescriptor source = new SourceDescriptor(url, content);
            JsonObject document = source.content.isJsonObject() ? source.content.getAsJsonObject().deepCopy() : new JsonObject();
            JsonArray lives = source.lives().deepCopy();
            if (lives.size() == 0) throw new IllegalArgumentException("这个源未提供直播，请选择其他直播源");
            if (lives.size() > 0 && lives.get(0).isJsonObject()
                    && (lives.get(0).getAsJsonObject().has("channels") || lives.get(0).getAsJsonObject().has("channel"))) {
                JsonObject inline = new JsonObject(); inline.addProperty("name","直播");
                JsonArray inlineGroups = upstreamGroups(LiveConfigCompat.groups(lives,url));
                lives = new JsonArray();
                if (inlineGroups.size() > 0) { inline.add("groups", inlineGroups); lives.add(inline); }
                for (String playlist : source.playlists()) {
                    JsonObject external = new JsonObject();
                    external.addProperty("name", "直播 " + (lives.size()+1));
                    external.addProperty("url", decodePlaylist(playlist));
                    lives.add(external);
                }
            }
            for (JsonElement entry : lives) {
                if (!entry.isJsonObject()) continue;
                JsonObject item = entry.getAsJsonObject();
                for (String key : new String[]{"url", "epg"}) {
                    String path = SourceDescriptor.string(item, key);
                    if (!path.isEmpty()) item.addProperty(key, SourceDescriptor.resolve(url, path));
                }
                if (SourceDescriptor.string(item,"name").isEmpty()) item.addProperty("name", "直播 " + sources.size() + " " + SourceDescriptor.string(item,"url"));
                if (item.has("groups")) item.add("groups",upstreamGroups(LiveConfigCompat.groups(item.getAsJsonArray("groups"),url)));
            }
            document.add("lives",lives);
            ConfigCompat.normalize(document,url);
            LiveConfig.get().clear().config(Config.find(url,1)).parse(document);
            sources.addAll(LiveConfig.get().getLives());
        } else {
            Live live = new Live("直播",url);
            LiveParser.text(live,content);
            sources.add(live);
        }
        String preferred = com.orhanobut.hawk.Hawk.get("live_playlist_url","");
        sources.sort(Comparator.comparing(live -> !live.getUrl().equals(preferred)));
        Exception last = null;
        for (Live live : sources) {
            try {
                LiveApi.parse(live);
                JsonArray groups = toHost(live);
                if (groups.size() > 0) return groups;
            } catch (Exception failure) { last = failure; }
        }
        if (last != null) throw last;
        throw new IllegalArgumentException("这个源没有可用的直播频道");
    }
    private static String decodePlaylist(String value) {
        if (!value.startsWith("proxy://")) return value;
        try {
            String query = value.substring(value.indexOf("ext=")+4).split("&",2)[0];
            String ext = android.net.Uri.decode(query);
            return ext.contains("://") ? ext : new String(android.util.Base64.decode(ext,android.util.Base64.DEFAULT | android.util.Base64.URL_SAFE), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception failure) { return value; }
    }
    private static JsonArray upstreamGroups(JsonArray host) {
        JsonArray result = new JsonArray();
        for (JsonElement element : host) {
            JsonObject group = element.getAsJsonObject().deepCopy();
            group.addProperty("name",group.get("group").getAsString()); group.remove("group");
            JsonArray channels = group.remove("channels").getAsJsonArray();
            for (JsonElement entry : channels) {
                JsonObject channel = entry.getAsJsonObject();
                channel.addProperty("tvgId",SourceDescriptor.string(channel,"tvg-id"));
                channel.addProperty("tvgName",SourceDescriptor.string(channel,"tvg-name"));
                if (!channel.has("epg") && group.has("epg")) channel.add("epg",group.get("epg"));
            }
            group.add("channel",channels); result.add(group);
        }
        return result;
    }
    private static JsonArray toHost(Live live) {
        JsonArray groups = new JsonArray();
        for (Group group : live.getGroups()) {
            if (group.isKeep() || group.isEmpty()) continue;
            JsonObject output = new JsonObject();
            output.addProperty("group",group.getName()+(group.getPass().isEmpty()?"":"_"+group.getPass()));
            output.addProperty("epg",live.getEpg());
            JsonArray channels = new JsonArray();
            for (Channel channel : group.getChannel()) {
                channel.live(live);
                JsonObject item = new JsonObject();
                item.addProperty("name",channel.getName()); item.add("urls",App.gson().toJsonTree(channel.getUrls()));
                item.addProperty("tvg-id",channel.getTvgId()); item.addProperty("tvg-name",channel.getTvgName());
                item.addProperty("logo",channel.getLogo()); item.addProperty("epg",channel.getEpg());
                item.add("header",App.gson().toJsonTree(channel.getHeaders()));
                JsonObject core = item.deepCopy();
                core.addProperty("tvgId",channel.getTvgId()); core.addProperty("tvgName",channel.getTvgName());
                core.addProperty("click",channel.getClick()); core.addProperty("format",channel.getFormat());
                core.addProperty("parse",channel.getParse());
                core.add("drm",App.gson().toJsonTree(channel.getDrm()));
                core.add("catchup",App.gson().toJsonTree(channel.getCatchup()));
                item.add("core",core); channels.add(item);
            }
            output.add("channels",channels); groups.add(output);
        }
        return groups;
    }
    public static Channel channel(com.kukuqi.tvbox.osc.bean.LiveChannelItem item) {
        Channel channel = item.getCoreChannel();
        if (channel == null) {
            channel = Channel.create(item.getChannelName());
            channel.setUrls(item.getChannelUrls()); channel.setHeader(item.getHeaders());
            channel.setTvgId(item.getTvgId()); channel.setTvgName(item.getTvgName()); channel.setEpg(item.getEpgUrl());
        }
        channel.setIndex(item.getSourceIndex());
        return channel;
    }
}
