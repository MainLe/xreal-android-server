# APK 内部接口与实现关系

完整 JNI Java 声明清单（240 项）：`evidence/jni-api-inventory.json`。ELF 依赖、导出、相关字符串和对齐信息：`evidence/apk-audit.json`。这里的“接口实现”区分已恢复的 Java 控制流、JNI 桥接和仍为二进制的 C++ 引擎。

| 层/用途 | Java 入口 | 实现路径与数据流 |
|---|---|---|
| 插拔与设备识别 | GlassesInitSetting、GlassesTypeUtil、UsbAttachDetachHelper | UsbManager 枚举 VID/PID、USB attach/detach 广播；Air 2 Ultra 返回类型 5。DisplayManager 监听显示设备变化。 |
| USB 授权 | GlassesManager、AirGlasses、UsbPermissionHelper、UsbPermissionReceiver | 检查 Camera 运行时权限，申请对应 UsbDevice 访问权，经 BroadcastReceiver 回调结果；随后才启动 SDK。 |
| 服务初始化 | NRServiceControl.startSdk、Startup | `System.loadLibrary("nr_service")`；`nativeInitService(Context)`、设置模式/前台状态、`nativeStartService()`、`nativeGlassesInit()`。失败会重试一次，成功设置原生库路径。 |
| 服务工作模式 | SdkMode | TINY_CLIENT=0、DEFAULT_FULL_CLIENT=1、QVR_CLIENT=2。不是 Android 版本值，也不是 3DoF/6DoF 值。 |
| IPC 发现 | ai.nreal.framework.net.binder.Acceptor | Manifest action `ai.nreal.nrsdk.network.type.server`，meta-data `com.xreal.nrsdk.network.type=server`。ai.nreal 类继承 com.xreal 桥，维持旧协议类名。 |
| IPC 连接 | IConnectorOrAcceptor、Connection、IConnection | Binder 握手传 serverName 和 connId；nativePreNewConnection / nativePostNewConnection 接入原生连接；Connection 转发 byte[] 与文件描述符。并非仅实现一个同名 Service 即可替换二进制协议。 |
| 原生运行库定位 | Startup.nativeSetNativeLibraryPath、nr_loader、assets/data/nr_api.dat | 服务从 BaseDexClassLoader.findLibrary 获取库路径；提供客户端运行库路径。nr_api.dat 中有 DEX 接口类，供 Java/JNI 桥接使用。 |
| 显示器识别 | DisplayModel、GlassesInitSetting.checkHdmi/checkDisplayInfo | 原版先要求名称带 HDMI，再检查 EDID；本机在第一步即误判。新实现直接检查 EDID 与受限名称回退。 |
| 输出模式 | NRServiceControl.set2D3D → Control JNI | 非 Ethernet 眼镜调用 nativeSet2D3DMode；常量 1=2D1080、3=3D1080/60Hz、4=3D1080/72Hz、2=3D540/120Hz。Ethernet 眼镜走 DP EDID/input mode 路径，不能混用。 |
| 显示/表面 | com.xreal.sdk.display.GlassesDisplay | Java SurfaceView/SurfaceHolder 生命周期转给 nativeOnControllerSufaceCreate/Destroy；nativeRunOnGlassesDisplay 由原生运行时参与显示。新界面另用公共 Presentation 做独立外屏测试，此测试本身无姿态锁定。 |
| 眼镜控制 | Control、NRServiceControl | JNI 调用 libnr_service，控制亮度、显示开关、临时 2D/3D、休眠、传感器属性；底层 USB 协议由 libnr_glasses_api / libnr_libusb 等处理。列举 API 不等于所有型号均实现。 |
| 姿态/跟踪 | libnr_api、libnr_service、tracking libraries、Unity XREALXRPlugin | 传感器获取、时间同步、标定、滤波、预测及渲染相关核心逻辑主要在二进制库。Java 启动 Service 不能替代这些算法。Unity 客户端使用 TrackingType.MODE_3DOF（1）或 MODE_6DOF（0）；服务工作模式数值与之无关。 |
| 空间扩展 | nr_hand_tracking、nr_image_tracking、nr_meshing、nr_spatial_anchor | 原生手部/图像/网格/锚点模块。Server 1.0 使用完整运行模式，综合调试客户端通过 XR Hands、ARFoundation 和 XREAL 扩展接口按需启动；运行状态与实际追踪结果分别报告。 |
| 固件更新 | OtaManager、libota-lib、assets/nr_ota_default | 与基础服务启动不同；精简包不带 OTA 固件数据且不调用更新流程。 |

关键类代码在 `decompiled/sources/com/xreal/` 与 `decompiled/sources/ai/nreal/`。JNI 函数名可以确定桥接目标和参数声明，但不能单靠导出符号把闭源原生算法的全部实现恢复出来。

## 综合 Demo 的公开接口

| 功能 | 接口 |
|---|---|
| 追踪模式 | `XREALPlugin.SwitchTrackingTypeAsync`、`GetTrackingType`，`InputDevices` 的 Head position / rotation / trackingState，`ARSession.state` / `notTrackingReason` |
| 锚点 | `ARAnchorManager`、`ARAnchor`，`XREALAnchorManagerExtension` 的质量、保存、列举、加载、重映射、删除及地图目录接口 |
| 手部 | `XREALPlugin.SetInputSource` / `IsHandTrackingSupported`，`XRHandSubsystem.leftHand/rightHand`、`XRHandJoint.TryGetPose` |
| 环境 | `ARPlaneManager`、`ARMeshManager`、`ARTrackedImageManager` 及已编译 `XRReferenceImageLibrary` |
| RGB | `XREALPlugin.IsHMDFeatureSupported(RGB_CAMERA)`、`ARCameraManager.TryAcquireLatestCpuImage`（及时 Dispose） |

地图由客户端私有目录持久化；Demo 不声称具备跨应用地图共享或云锚点服务。捏合/张掌/握拳使用真实关节的几何规则，不冒充原生手势分类器标签。
