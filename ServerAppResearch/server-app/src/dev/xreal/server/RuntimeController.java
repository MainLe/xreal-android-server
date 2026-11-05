package dev.xreal.server;

import android.app.PendingIntent;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import android.util.Log;
import com.xreal.glasses.servicesdk.AbsNRCallback;
import java.lang.reflect.Proxy;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Process-wide ownership of USB authorization and native runtime; UI is only an observer. */
public final class RuntimeController {
    public enum State { IDLE, USB_PERMISSION, STARTING, READY, STOPPING, FAILED }
    public interface Listener { void changed(); }
    public interface Result { void done(Object value, Throwable error); }
    private static RuntimeController instance;
    public static synchronized RuntimeController get(Context c){if(instance==null)instance=new RuntimeController(c.getApplicationContext());return instance;}
    private static final String USB_ACTION="dev.xreal.server.USB_PERMISSION";
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Executor worker;
    private final Set<Listener> listeners=new CopyOnWriteArraySet<>();
    private final ArrayDeque<String> log=new ArrayDeque<>();
    private final UsbManager usb;
    private Object control,startupCallback;
    private boolean attemptedNativeStart;
    private volatile boolean uiForeground,controlPaused,handoffRequested;
    private volatile int generation;
    public volatile State state=State.IDLE;
    public volatile String detail="等待连接眼镜",deviceInfo="尚未查询";
    public volatile int runningClients,pausedClients;
    private RuntimeController(Context context){
        this.context=context;usb=(UsbManager)context.getSystemService(Context.USB_SERVICE);
        // Vendor DisplayObserverHelper registers a listener with a null Handler and needs a Looper.
        HandlerThread runtimeThread=new HandlerThread("XrealRuntime");runtimeThread.start();
        Handler runtimeHandler=new Handler(runtimeThread.getLooper());worker=runtimeHandler::post;
        IntentFilter f=new IntentFilter(USB_ACTION);f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
            if(USB_ACTION.equals(i.getAction())){if(state!=State.USB_PERMISSION)return;if(!i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false))fail("USB 授权被拒绝，可重新启动重试",null);else authorizeNext();}
            else if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction())){UsbDevice d=i.getParcelableExtra(UsbManager.EXTRA_DEVICE);if(d!=null&&isXreal(d)&&state!=State.IDLE)stop("眼镜已断开，释放运行库");}
            else note("检测到 USB 连接，请点击启动服务");
        }};
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);else context.registerReceiver(receiver,f);
        worker.execute(()->{try{
            Class<?> s=Class.forName("com.xreal.glassesdisplayplugevent.GlassesInitSetting");s.getMethod("init",Context.class).invoke(s.getMethod("getInstance").invoke(null),context);
            AbsNRCallback cb=new AbsNRCallback(){
                @Override public void onClientCountChanged(int running,int paused){runningClients=running;pausedClients=paused;note("客户端运行="+running+" 暂停="+paused);if(running==0&&paused==0&&uiForeground&&!handoffRequested)scheduleControlOwnership();}
                @Override public void onServiceMethodError(int code){note("SDK 错误码="+code);}
                @Override public void onGlassesAction(int type,int p1,int p2,long time){if(type==32)note("眼镜事件 "+type+" / "+p1);}
            };
            control=Class.forName("com.xreal.glasses.servicesdk.NRServiceControl").getConstructor(Context.class,AbsNRCallback.class).newInstance(context,cb);
            note("XREAL 3.1 完整运行库已加载；空间能力由客户端按需启用");
        }catch(Throwable t){fail("运行库加载失败",t);}});
    }
    private boolean isXreal(UsbDevice d){return d.getVendorId()==13080||(d.getVendorId()==21315&&d.getProductId()==512);}
    public List<UsbDevice> devices(){List<UsbDevice> a=new ArrayList<>();if(usb!=null)for(UsbDevice d:usb.getDeviceList().values())if(isXreal(d))a.add(d);return a;}
    public String usbSummary(){StringBuilder s=new StringBuilder();for(UsbDevice d:devices())s.append("VID ").append(d.getVendorId()).append(" / PID ").append(d.getProductId()).append(" / 授权 ").append(usb.hasPermission(d)).append('\n');return s.length()==0?"未检测到 XREAL USB 设备":s.toString();}
    public void add(Listener l){listeners.add(l);}public void remove(Listener l){listeners.remove(l);}
    public synchronized String logs(){return String.join("\n",log);}
    public void note(String message){Log.i("XrealServer",message);synchronized(this){log.addLast(new java.text.SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date())+" "+message);while(log.size()>300)log.removeFirst();}main.post(()->{for(Listener l:listeners)l.changed();});}
    private void status(State next,String message){state=next;detail=message;note(next+": "+message);}
    private Throwable unwrap(Throwable t){while(t.getCause()!=null)t=t.getCause();return t;}
    private void fail(String message,Throwable t){if(t!=null){t=unwrap(t);Log.e("XrealServer",message,t);message+="："+t;}status(State.FAILED,message);}
    public void start(){
        if(state==State.USB_PERMISSION||state==State.STARTING||state==State.READY||state==State.STOPPING){note("当前状态 "+state+"，忽略重复启动");return;}
        if(devices().isEmpty()){fail("请连接 XREAL 眼镜",null);return;}generation++;status(State.USB_PERMISSION,"检查 USB 授权");authorizeNext();
    }
    private void authorizeNext(){
        for(UsbDevice d:devices())if(!usb.hasPermission(d)){
            PendingIntent p=PendingIntent.getBroadcast(context,0,new Intent(USB_ACTION).setPackage(context.getPackageName()),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
            try{usb.requestPermission(d,p);}catch(Throwable t){fail("请求 USB 权限失败",t);}return;
        }
        if(devices().isEmpty()){fail("授权期间眼镜已断开",null);return;}
        final int token=generation;status(State.STARTING,"初始化完整 SDK 服务");
        main.postDelayed(()->{if(token==generation&&state==State.STARTING)stop("SDK 初始化超过 30 秒，释放运行库");},30000);
        worker.execute(()->{if(token!=generation)return;try{
            if(control==null)throw new IllegalStateException("运行库未就绪");
            if(attemptedNativeStart){control.getClass().getMethod("stopSdk").invoke(control);attemptedNativeStart=false;}
            Class<?> t=Class.forName("com.xreal.glasses.servicesdk.NRServiceControl$OnStartSDKCallback");
            startupCallback=Proxy.newProxyInstance(context.getClassLoader(),new Class[]{t},(proxy,m,args)->{
                if(m.getName().equals("onStartSDKResult")){boolean ok=Boolean.TRUE.equals(args[0]);main.post(()->{if(token!=generation||state!=State.STARTING)return;if(ok){controlPaused=false;status(State.READY,"空间运行库就绪，等待 XR 客户端");setUiForeground(uiForeground);refreshInfo();}else fail("SDK 初始化返回失败",null);});}
                if(m.getName().equals("toString"))return "XrealStartupCallback";return null;
            });
            attemptedNativeStart=true;control.getClass().getMethod("startSdk",int.class,t).invoke(control,1,startupCallback);
        }catch(Throwable t){main.post(()->{if(token==generation&&state==State.STARTING)fail("SDK 启动异常",t);});}});
    }
    public void stop(String reason){if(state==State.STOPPING)return;generation++;status(State.STOPPING,reason);worker.execute(()->{try{if(control!=null&&attemptedNativeStart){try{setControlPaused(true);}catch(Throwable pauseError){note("停止前暂停控制失败："+unwrap(pauseError));}control.getClass().getMethod("stopSdk").invoke(control);}attemptedNativeStart=false;controlPaused=false;runningClients=0;pausedClients=0;status(State.IDLE,"服务已停止");}catch(Throwable t){fail("停止服务异常",t);}});}
    /** Same native ownership transition as the official app's activity pause/resume callbacks. */
    private boolean setControlPaused(boolean paused)throws Exception{
        if(controlPaused==paused)return true;
        String method=paused?"onNRPause":"onNRResume";
        boolean ok=Boolean.TRUE.equals(control.getClass().getMethod(method).invoke(control));
        note(method+" = "+ok);if(ok)controlPaused=paused;return ok;
    }
    public void setUiForeground(boolean foreground){
        uiForeground=foreground;if(foreground)handoffRequested=false;scheduleControlOwnership();
    }
    private void scheduleControlOwnership(){
        if(state!=State.READY)return;int token=generation;
        worker.execute(()->{if(token!=generation||state!=State.READY)return;try{
            if(!uiForeground||handoffRequested)setControlPaused(true);
            else if(runningClients==0&&pausedClients==0)setControlPaused(false);
        }catch(Throwable t){note("前后台设备交接失败："+unwrap(t));}});
    }
    public void prepareClient(Result callback){
        if(state!=State.READY){main.post(()->callback.done(null,new IllegalStateException("服务未就绪")));return;}
        handoffRequested=true;
        int token=generation;boolean[] delivered={false};
        main.postDelayed(()->{if(!delivered[0]){delivered[0]=true;note("设备交接超过 8 秒，未启动客户端");callback.done(null,new IllegalStateException("设备交接超时"));}},8000);
        worker.execute(()->{Object value=null;Throwable error=null;try{
            if(token!=generation||state!=State.READY)throw new IllegalStateException("服务状态已改变");
            if(!setControlPaused(true))throw new IllegalStateException("原生服务未释放控制通道");
            value=Boolean.TRUE;
        }catch(Throwable t){error=unwrap(t);note("客户端交接失败："+error);}
            Object result=value;Throwable failure=error;main.post(()->{if(delivered[0])return;delivered[0]=true;if(token!=generation||state!=State.READY)callback.done(null,new IllegalStateException("服务状态已改变"));else callback.done(result,failure);});
        });
    }
    public void invoke(String name,Class<?>[] types,Object[] args,Result callback){
        if(state!=State.READY){if(callback!=null)main.post(()->callback.done(null,new IllegalStateException("服务未就绪")));return;}
        int token=generation;worker.execute(()->{try{if(token!=generation||state!=State.READY)throw new IllegalStateException("服务状态已改变");Object v=control.getClass().getMethod(name,types).invoke(control,args);note(name+" = "+v);if(callback!=null)main.post(()->callback.done(v,null));}catch(Throwable t){Throwable e=unwrap(t);note(name+" 失败："+e);if(callback!=null)main.post(()->callback.done(null,e));}});
    }
    public void refreshInfo(){if(state!=State.READY)return;int token=generation;worker.execute(()->{if(token!=generation||state!=State.READY)return;StringBuilder s=new StringBuilder();for(String n:new String[]{"getGlassesID","getGlassesFw","getMcuFw","getDpFw","getDspFw","get2D3D","getBrightness","getBrightnessLevelNumber","getSupportedDisplayMode","getPSensorIsWearing"}){if(token!=generation)return;try{s.append(n).append(": ").append(control.getClass().getMethod(n).invoke(control)).append('\n');}catch(Throwable t){s.append(n).append(": unavailable\n");}}deviceInfo=s.toString();note("设备状态已刷新");});}
    public String report(){JSONObject r=new JSONObject();try{r.put("serverVersion","1.0.1");r.put("vendorRuntime","3.1.0.20251118115716");r.put("model",Build.MODEL);r.put("android",Build.VERSION.RELEASE);r.put("api",Build.VERSION.SDK_INT);r.put("state",state.toString());r.put("detail",detail);r.put("uiForeground",uiForeground);r.put("controlPaused",controlPaused);r.put("runningClients",runningClients);r.put("pausedClients",pausedClients);r.put("usb",usbSummary());r.put("deviceInfo",deviceInfo);r.put("events",logs());try(InputStream in=context.getAssets().open("runtime-inventory.json")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);r.put("packagedModules",new JSONObject(out.toString("UTF-8")));}}catch(Exception e){note("报告生成失败："+e);}return r.toString();}
}
