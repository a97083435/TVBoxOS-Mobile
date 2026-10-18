package com.kukuqi.tvbox.engine.dlna;

import com.kukuqi.tvbox.engine.App;
import com.kukuqi.tvbox.engine.R;
import com.kukuqi.tvbox.engine.bean.CastVideo;
import com.kukuqi.tvbox.engine.bean.Device;
import com.kukuqi.tvbox.engine.utils.Notify;

import org.jupnp.controlpoint.ControlPoint;
import org.jupnp.model.action.ActionInvocation;
import org.jupnp.model.message.UpnpResponse;
import org.jupnp.model.meta.RemoteService;
import org.jupnp.support.avtransport.callback.Play;
import org.jupnp.support.avtransport.callback.Seek;
import org.jupnp.support.avtransport.callback.SetAVTransportURI;
import org.jupnp.support.contentdirectory.DIDLParser;
import org.jupnp.support.model.DIDLContent;
import org.jupnp.support.model.DIDLObject;
import org.jupnp.support.model.ProtocolInfo;
import org.jupnp.support.model.Res;
import org.jupnp.support.model.SeekMode;
import org.jupnp.support.model.item.VideoItem;

import java.util.Locale;

public record DLNACast(CastVideo video, Runnable runnable) {

    public void cast(Device item) {
        DLNACastManager mgr = DLNACastManager.get();
        ControlPoint control = mgr.getControlPoint();
        RemoteService service = mgr.findAVTransport(item);
        if (service != null && control != null) {
            control.execute(uriAction(control, service));
        } else {
            App.post(() -> Notify.show(R.string.core_device_offline));
        }
    }

    private String buildMetaData() {
        try {
            DIDLContent content = new DIDLContent();
            VideoItem item = new VideoItem("0", "-1", video.name(), "", new Res(new ProtocolInfo("http-get:*:" + contentType(video.url()) + ":*"), 0L, video.url()));
            if (!video.headers().isEmpty()) item.addProperty(new DIDLObject.Property.DC.DESCRIPTION(App.gson().toJson(video.headers())));
            content.addItem(item);
            return new DIDLParser().generate(content);
        } catch (Exception e) {
            return "";
        }
    }

    private static String contentType(String url) {
        String path = android.net.Uri.parse(url).getPath();
        if (path == null) return "video/*";
        path = path.toLowerCase(Locale.ROOT);
        if (path.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (path.endsWith(".mpd")) return "application/dash+xml";
        if (path.endsWith(".mp4") || path.endsWith(".m4v")) return "video/mp4";
        if (path.endsWith(".mkv")) return "video/x-matroska";
        if (path.endsWith(".ts") || path.endsWith(".m2ts")) return "video/mp2t";
        if (path.endsWith(".mp3")) return "audio/mpeg";
        return "video/*";
    }

    private SetAVTransportURI uriAction(ControlPoint control, RemoteService service) {
        return new SetAVTransportURI(service, video.url(), buildMetaData()) {
            @Override
            public void success(ActionInvocation i) {
                control.execute(playAction(control, service));
            }

            @Override
            public void failure(ActionInvocation i, UpnpResponse r, String msg) {
                App.post(() -> Notify.show(msg));
            }
        };
    }

    private Play playAction(ControlPoint control, RemoteService service) {
        return new Play(service) {
            @Override
            public void success(ActionInvocation i) {
                if (video.position() > 0) control.execute(seekAction(service));
                App.post(runnable);
            }

            @Override
            public void failure(ActionInvocation i, UpnpResponse r, String msg) {
                App.post(() -> Notify.show(msg));
            }
        };
    }

    private Seek seekAction(RemoteService service) {
        return new Seek(service, SeekMode.REL_TIME, formatMs(video.position())) {
            @Override
            public void success(ActionInvocation i) {
            }

            @Override
            public void failure(ActionInvocation i, UpnpResponse r, String m) {
            }
        };
    }

    private String formatMs(long ms) {
        if (ms <= 0) return "00:00:00";
        long s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }
}
