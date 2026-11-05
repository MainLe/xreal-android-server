package dev.xreal.server;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.xreal.glassesdisplayplugevent.display.DisplayModel;
import java.io.*;

public final class MainActivity extends Activity implements DisplayManager.DisplayListener,RuntimeController.Listener {
    private RuntimeController runtime;
    private DisplayManager displays;
    private final Handler main=new Handler();
    private LinearLayout content;
    private TextView state,logView;
    private int tab;
    private Presentation test;
    private boolean launching,handoffPending,permissionStartPending;
    private int launchToken;
    private long deadline;
    private String launchPackage,launchPage;
    private static final String FEATURE_DEMO="dev.xreal.server.features";
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        runtime=RuntimeController.get(this);runtime.add(this);
        displays=getSystemService(DisplayManager.class);displays.registerDisplayListener(this,main);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(20,24,20,12);
        TextView title=new TextView(this);title.setText("XREAL Server 1.0.1 · 空间调试控制台");title.setTextSize(23);root.addView(title);
        state=new TextView(this);state.setTextSize(15);root.addView(state);
        LinearLayout tabs=new LinearLayout(this);root.addView(tabs);
        String[] titles={"服务","设备","调试 Demo","日志"};
        for(int i=0;i<titles.length;i++){final int index=i;Button b=new Button(this);b.setText(titles[i]);tabs.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v->{tab=index;render();});}
        ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(1);scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        render();changed();
    }
    private void text(String value){TextView t=new TextView(this);t.setText(value);t.setTextSize(15);t.setPadding(4,12,4,12);content.addView(t);}
    private void button(String label,Runnable action){Button b=new Button(this);b.setText(label);content.addView(b);b.setOnClickListener(v->{try{action.run();}catch(Throwable e){runtime.note(label+"："+e);}});}
    private void action(String name,Class<?>[] types,Object... values){runtime.invoke(name,types,values,(v,e)->{if(e!=null)Toast.makeText(this,e.toString(),Toast.LENGTH_LONG).show();});}
    private void render(){
        content.removeAllViews();logView=null;
        if(tab==0){
            text("完整原生运行库：6DoF、空间锚点、手部跟踪、平面、网格、图像跟踪。功能由 XR 客户端按需启动；READY 表示服务就绪，不代表每项能力已在手机上通过测试。");
            button("启动完整服务",this::startService);
            button("停止眼镜服务",()->runtime.stop("用户请求停止"));
            button("刷新服务 / 设备状态",()->{runtime.refreshInfo();changed();});
            text("保持服务运行后，到“调试 Demo”启动综合测试。请顺序测试各能力，避免同时运行多个 XR 客户端。");
        }else if(tab==1){
            text(runtime.usbSummary()+"\n"+displaySummary()+"\n"+runtime.deviceInfo);
            button("重新查询硬件信息",()->{runtime.refreshInfo();main.postDelayed(this::render,1200);});
            text("亮度等级（先查询设备支持的最大值）");
            SeekBar brightness=new SeekBar(this);brightness.setMax(0);content.addView(brightness);
            runtime.invoke("getBrightnessLevelNumber",new Class[]{},new Object[]{},(v,e)->{if(e==null&&v instanceof Number){int n=((Number)v).intValue();if(n>0)brightness.setMax(n-1);}});
            brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int p,boolean u){}public void onStopTrackingTouch(SeekBar s){action("setBrightness",new Class[]{int.class},s.getProgress());}});
            button("立体 3840×1080 / 60Hz",()->mode(3));button("普通 1920×1080",()->mode(1));
            button("显示开启",()->action("setDisplayState",new Class[]{int.class},1));button("显示关闭",()->action("setDisplayState",new Class[]{int.class},0));
            button("独立外屏测试",this::displayTest);
        }else if(tab==2){
            text("综合 Demo：跟踪、锚点持久化、双手骨架/捏合、平面/网格、图像与相机诊断。Air 2 Ultra 的灰度跟踪相机不等同于 RGB 摄像头；不支持项会显示真实状态。");
            button("6DoF / 世界锁定",()->launch(FEATURE_DEMO,"tracking"));
            button("空间锚点 / 保存 / 重定位",()->launch(FEATURE_DEMO,"anchors"));
            button("手部骨架 / 手势交互",()->launch(FEATURE_DEMO,"hands"));
            button("平面 / 网格 / 图像 / 相机",()->launch(FEATURE_DEMO,"environment"));
            button("原 3DoF 对照测试",()->launch("dev.xreal.server.posecheck","tracking"));
            button("HelloMR",()->launch("com.xreal.hellomr.demo","tracking"));
        }else{
            button("导出诊断 JSON",()->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"xreal-server-report.json");startActivityForResult(i,20);});
            text("报告含本应用的系统/API、USB、固件、客户端数量、打包模块和操作日志。");
            logView=new TextView(this);logView.setTextSize(12);content.addView(logView);
        }
        changed();
    }
    @Override public void changed(){if(isFinishing())return;state.setText(runtime.state+" · "+runtime.detail+"\n运行客户端 "+runtime.runningClients+" / 暂停 "+runtime.pausedClients);if(logView!=null)logView.setText(runtime.logs());}
    private void startService(){
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){permissionStartPending=true;requestPermissions(new String[]{Manifest.permission.CAMERA},10);return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED){permissionStartPending=true;requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},11);return;}
        runtime.start();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(permissionStartPending){permissionStartPending=false;if(request==11||(results.length>0&&results[0]==0))runtime.start();else runtime.note("相机授权未通过；Ultra 的 USB 视频接口需要该权限");}}
    private void closeTest(){if(test!=null){test.dismiss();test=null;}}
    private void mode(int mode){cancelLaunch();closeTest();action("set2D3D",new Class[]{int.class},mode);}
    private String displaySummary(){StringBuilder s=new StringBuilder();for(Display d:displays.getDisplays()){DisplayModel model=new DisplayModel(d);Display.Mode m=d.getMode();s.append(d.getDisplayId()).append(" '").append(d.getName()).append("' ").append(m.getPhysicalWidth()).append('x').append(m.getPhysicalHeight()).append(" @").append(m.getRefreshRate()).append(" type=").append(model.getNrealDisplay()).append('\n');}return s.toString();}
    private boolean stereoReady(){for(Display d:displays.getDisplays())if(new DisplayModel(d).getNrealDisplay()>=0&&d.getMode().getPhysicalWidth()==3840&&d.getMode().getPhysicalHeight()==1080)return true;return false;}
    private void launch(String pkg,String page){
        if(runtime.state!=RuntimeController.State.READY){runtime.note("请先启动服务");return;}if(launching)return;
        if(getPackageManager().getLaunchIntentForPackage(pkg)==null){runtime.note("尚未安装 "+pkg);return;}
        closeTest();launchPackage=pkg;launchPage=page;launching=true;if(stereoReady()){openClient();return;}
        runtime.invoke("set2D3D",new Class[]{int.class},new Object[]{3},(value,error)->{if(!launching)return;if(error!=null||!Boolean.TRUE.equals(value)){cancelLaunch();runtime.note("立体切换失败，未启动客户端");return;}deadline=SystemClock.uptimeMillis()+15000;main.post(waitStereo);});
    }
    private final Runnable waitStereo=new Runnable(){public void run(){if(!launching)return;if(runtime.state!=RuntimeController.State.READY){cancelLaunch();return;}if(stereoReady()){openClient();return;}if(SystemClock.uptimeMillis()>=deadline){cancelLaunch();runtime.note("等待 3840×1080 超时："+displaySummary());return;}main.postDelayed(this,250);}};
    private void openClient(){
        if(handoffPending||!launching)return;handoffPending=true;main.removeCallbacks(waitStereo);
        final int token=launchToken;String pkg=launchPackage,page=launchPage;
        runtime.prepareClient((value,error)->{
            if(token!=launchToken||!launching||isFinishing()||isDestroyed())return;
            cancelLaunch();
            if(error!=null){runtime.note("设备交接失败，未启动客户端："+error);runtime.setUiForeground(true);return;}
            Intent intent=getPackageManager().getLaunchIntentForPackage(pkg);
            if(intent==null){runtime.note("客户端入口已不可用："+pkg);runtime.setUiForeground(true);return;}
            try{intent.putExtra("xreal.debug.page",page);startActivity(intent);runtime.note("控制通道已交接，启动客户端 "+pkg+" / "+page);}
            catch(RuntimeException failure){runtime.note("客户端启动失败："+failure);runtime.setUiForeground(true);}
        });
    }
    private void cancelLaunch(){launchToken++;launching=false;handoffPending=false;main.removeCallbacks(waitStereo);}
    private void displayTest(){if(test!=null){closeTest();return;}for(Display d:displays.getDisplays())if(new DisplayModel(d).getNrealDisplay()>=0){test=new Presentation(this,d);test.setContentView(new View(test.getContext()){
        final Paint p=new Paint(3);
        @Override protected void onDraw(Canvas c){c.drawColor(Color.BLACK);p.setColor(Color.GREEN);p.setStrokeWidth(3);int eyes=getWidth()>2500?2:1;for(int e=0;e<eyes;e++){int w=getWidth()/eyes,x=e*w;for(int j=0;j<=8;j++){c.drawLine(x+j*w/8f,0,x+j*w/8f,getHeight(),p);c.drawLine(x,j*getHeight()/8f,x+w,j*getHeight()/8f,p);}p.setTextSize(40);c.drawText("XREAL DISPLAY TEST",x+50,getHeight()/2f,p);}}
    });test.show();return;}runtime.note("无已识别的 XREAL 外接显示器");}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==20&&result==RESULT_OK&&data!=null&&data.getData()!=null){String report=runtime.report();new Thread(()->{try(OutputStream stream=getContentResolver().openOutputStream(data.getData())){if(stream==null)throw new IOException("无法打开文档");stream.write(report.getBytes("UTF-8"));runtime.note("报告已导出");}catch(Exception e){runtime.note("导出失败："+e);}},"ReportExport").start();}}
    @Override public void onDisplayAdded(int id){runtime.note("显示器已连接 "+id);}
    @Override public void onDisplayChanged(int id){if(launching)main.post(waitStereo);}
    @Override public void onDisplayRemoved(int id){if(test!=null&&test.getDisplay().getDisplayId()==id)closeTest();runtime.note("显示器已断开 "+id);}
    @Override protected void onResume(){super.onResume();runtime.setUiForeground(true);}
    @Override protected void onPause(){runtime.setUiForeground(false);super.onPause();}
    @Override protected void onDestroy(){cancelLaunch();closeTest();runtime.remove(this);displays.unregisterDisplayListener(this);super.onDestroy();}
}
