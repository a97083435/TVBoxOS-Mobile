package com.kukuqi.tvbox.osc;

import android.test.InstrumentationTestCase;
import com.kukuqi.tvbox.engine.bean.CastVideo;
import com.kukuqi.tvbox.engine.bean.Device;
import com.kukuqi.tvbox.engine.dlna.DLNACast;
import com.kukuqi.tvbox.engine.dlna.DLNACastManager;
import fi.iki.elonen.NanoHTTPD;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.jupnp.android.AndroidUpnpService;
import org.jupnp.model.meta.*;
import org.jupnp.model.types.UDN;
import org.jupnp.protocol.RetrieveRemoteDescriptors;

/** Exercises descriptor hydration and real HTTP SOAP actions against a local renderer fixture. */
public class DlnaTransportRegressionTest extends InstrumentationTestCase {
    public void testCastLoadsMetadataPlaysAndSeeksOverSoap() throws Exception {
        String uuid="tvbox-dlna-fixture-310";
        List<String> actions=Collections.synchronizedList(new ArrayList<>());
        List<String> bodies=Collections.synchronizedList(new ArrayList<>());
        CountDownLatch seek=new CountDownLatch(1), playing=new CountDownLatch(1);
        NanoHTTPD fixture=new NanoHTTPD("127.0.0.1",0){
            @Override public Response serve(IHTTPSession request){
                if(request.getUri().equals("/device.xml"))return newFixedLengthResponse(Response.Status.OK,"text/xml",device(uuid));
                if(request.getUri().equals("/transport.xml"))return newFixedLengthResponse(Response.Status.OK,"text/xml",scpd());
                String action=request.getHeaders().getOrDefault("soapaction","").replace("\"","");action=action.substring(action.lastIndexOf('#')+1);
                Map<String,String> form=new HashMap<>();try{request.parseBody(form);}catch(Exception e){return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,"text/plain",e.toString());}
                actions.add(action);bodies.add(form.getOrDefault("postData",""));if(action.equals("Seek"))seek.countDown();
                return newFixedLengthResponse(Response.Status.OK,"text/xml; charset=utf-8","<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:"+action+"Response xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"/></s:Body></s:Envelope>");
            }
        };
        DLNACastManager manager=DLNACastManager.get(); org.jupnp.UpnpService[] service={null};
        CountDownLatch bound=new CountDownLatch(1);
        android.content.Context context=getInstrumentation().getTargetContext();
        android.content.ServiceConnection connection=new android.content.ServiceConnection(){
            @Override public void onServiceConnected(android.content.ComponentName name,android.os.IBinder binder){service[0]=((AndroidUpnpService)binder).get();bound.countDown();}
            @Override public void onServiceDisconnected(android.content.ComponentName name){}
        };
        boolean[] attached={false};
        fixture.start();
        try {
            main(()->{attached[0]=context.bindService(new android.content.Intent(context,com.kukuqi.tvbox.engine.service.DLNACastService.class),connection,android.content.Context.BIND_AUTO_CREATE);manager.init(context);});
            assertTrue(bound.await(15,TimeUnit.SECONDS));
            assertNotNull("DLNA service failed to bind",service[0]);
            RemoteDevice remote=new RemoteDevice(new RemoteDeviceIdentity(new UDN(uuid),1800,new URL("http://127.0.0.1:"+fixture.getListeningPort()+"/device.xml"),null,InetAddress.getByName("127.0.0.1")));
            new RetrieveRemoteDescriptors(service[0],remote).run();
            var hydrated=service[0].getRegistry().getRemoteDevice(new UDN(uuid),true);
            assertNotNull("Renderer descriptor/SCPD could not be loaded",hydrated);
            Device device=Device.get(hydrated);
            new DLNACast(new CastVideo("A & B <sample>","https://example.org/movie.m3u8?token=fixture",125000,Map.of()),playing::countDown).cast(device);
            assertTrue("Set URI / Play callback missing",playing.await(15,TimeUnit.SECONDS));
            assertTrue("Resume seek missing",seek.await(10,TimeUnit.SECONDS));
            assertEquals(List.of("SetAVTransportURI","Play","Seek"),actions);
            assertTrue(bodies.get(0).contains("application/vnd.apple.mpegurl"));
            assertTrue(bodies.get(0).contains("example.org/movie.m3u8?token=fixture"));
            assertTrue(bodies.get(2).contains("00:02:05"));
        } finally {
            if(service[0]!=null)service[0].getRegistry().removeDevice(new UDN(uuid));
            main(()->{manager.release(context);if(attached[0])context.unbindService(connection);});fixture.stop();
        }
    }
    private static String device(String uuid){return "<?xml version=\"1.0\"?><root xmlns=\"urn:schemas-upnp-org:device-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>Local test renderer</friendlyName><manufacturer>TVboxOSC</manufacturer><modelName>Fixture</modelName><UDN>uuid:"+uuid+"</UDN><serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><SCPDURL>/transport.xml</SCPDURL><controlURL>/control</controlURL><eventSubURL>/event</eventSubURL></service></serviceList></device></root>";}
    private static String scpd(){return "<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>"
        +action("SetAVTransportURI",new String[]{"InstanceID","CurrentURI","CurrentURIMetaData"},new String[]{"InstanceID","URI","Metadata"})
        +action("Play",new String[]{"InstanceID","Speed"},new String[]{"InstanceID","Speed"})
        +action("Seek",new String[]{"InstanceID","Unit","Target"},new String[]{"InstanceID","Unit","Target"})
        +"</actionList><serviceStateTable>"+variable("InstanceID","ui4")+variable("URI","uri")+variable("Metadata","string")+variable("Speed","string")+variable("Unit","string")+variable("Target","string")+"</serviceStateTable></scpd>";}
    private static String action(String name,String[] args,String[] vars){StringBuilder result=new StringBuilder("<action><name>"+name+"</name><argumentList>");for(int i=0;i<args.length;i++)result.append("<argument><name>").append(args[i]).append("</name><direction>in</direction><relatedStateVariable>").append(vars[i]).append("</relatedStateVariable></argument>");return result+"</argumentList></action>";}
    private static String variable(String name,String type){return "<stateVariable sendEvents=\"no\"><name>"+name+"</name><dataType>"+type+"</dataType></stateVariable>";}
    private void main(Runnable action){Throwable[] error={null};getInstrumentation().runOnMainSync(()->{try{action.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new AssertionError(error[0]);}
}
