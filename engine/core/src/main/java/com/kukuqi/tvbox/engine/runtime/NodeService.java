package com.kukuqi.tvbox.engine.runtime;

import android.app.Service;
import android.content.Intent;
import android.os.*;

/** A bound service keeps V8 and its native libraries out of the UI process. */
public final class NodeService extends Service {
    private HandlerThread worker;
    private Messenger messenger;
    private String[] endpoint;
    @Override public void onCreate() {
        super.onCreate(); worker = new HandlerThread("node-control"); worker.start();
        messenger = new Messenger(new Handler(worker.getLooper(), message -> {
            Bundle data = new Bundle();
            try {
                if (endpoint == null) endpoint = (String[]) RuntimeModules.call(RuntimeModules.create(this, RuntimeModule.NODEJS), "start", new Class<?>[0]);
                data.putStringArray("endpoint", endpoint);
            } catch (Throwable failure) { data.putString("error", failure.getMessage()); }
            Message reply = Message.obtain(null, 1); reply.setData(data);
            try { message.replyTo.send(reply); } catch (RemoteException ignored) { }
            return true;
        }));
    }
    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }
    @Override public void onDestroy() {
        worker.quitSafely(); super.onDestroy();
        // Node cannot safely be initialized a second time inside the same process.
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
