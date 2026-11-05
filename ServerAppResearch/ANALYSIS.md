# Control Glasses 分析与精简服务工程

设备：小米 15（24129PN74C / dada），Android 17 / API 37，HyperOS OS4.0.0.23.XOCCNXM，Air 2 Ultra。

## 本机已确认的失败原因

2026-11-01 20:22 的官方应用日志显示：

1. USB 相机权限、眼镜 USB 权限通过，识别 glassesType=5。
2. `NRServiceControl` 调用 `Startup.nativeInitService → nativeStartService → nativeGlassesInit`。
3. `nativeGlassesInit` 返回 true；20:22:28.397 打印 `startSdk: success`。
4. `XREALManager.checkDisplay` 每 500ms 检查外接显示器，最多 8 次。
5. 系统确实有显示器 id=2，名称 `Air 2 Ultra`，分辨率 1920×1080，FLAG_PRESENTATION。
6. `DisplayModel._isHdmi` 仅判断名称包含 `HDMI`（另有针对 Build.PRODUCT=eva 的分支），因此把该显示器过滤掉。
7. 20:22:32.921，检查超时，状态设为 `sdkFailed`，UI 显示 SDK 启动失败。

证据：`evidence/controlglasses-device.log`；对应反编译位置：

- `decompiled/sources/com/xreal/glassesdisplayplugevent/display/DisplayModel.java`
- `decompiled/sources/com/xreal/glassescontrol/manager/XREALManager.java`，`checkDisplay()`
- `decompiled/sources/com/xreal/glasses/servicesdk/NRServiceControl.java`，启动回调与 `sdkSuccessConfig()`

这是本次设备组合的实测根因，不能据此声称所有 Android 16/17 问题均为同一个原因。

## 原生兼容检查

输入 APK SHA-256：`49c9484ebd34e495984b219bb73988a366c03339f7251a9fe9a4702f58f5c76e`。

- APK 包名 `com.xreal.glassescontrol`，版本 `3.1.0.20251118115716`。
- compileSdk=33、targetSdk=33、minSdk=28、仅 arm64-v8a。
- 15 个 ELF 共享库中 13 个 LOAD 段未满足 16 KB 对齐。
- **本机 getconf PAGE_SIZE=4096**，且核心库已实际加载并启动，不能把 16 KB 对齐当作本次根因。
- 只重打包 ZIP 或修改 ELF 的 alignment 数字无法替代原生代码的 16 KB 重编译。
- 日志中有 optional external sensor algorithm 加载失败和部分 OTA 命令 unsupported；后续 SDK 初始化仍成功，不能据此认定它们是此次 UI 失败根因。
- Android 16/17 存在适用于全部应用的变化，也存在仅适用于 targetSdk=36/37 的变化。原应用 targetSdk=33，需逐条判断适用范围。

参考：

- https://developer.android.com/about/versions/16/behavior-changes-all
- https://developer.android.com/about/versions/17/behavior-changes-17
- https://developer.android.com/guide/practices/page-sizes

## 实现与边界

`server-app/` 是新建的最小 Android 入口，包名 `dev.xreal.server`。它复用用户提供 APK 中的 DEX / 原生运行库；这是基于二进制依赖构建的服务应用，不是把 XREAL 的 C++ 引擎恢复成源码。

修复 `DisplayModel` 保持类名和公开方法兼容，优先使用 Android 的 DeviceProductInfo（MRG/NRL EDID），其次使用严格的 XREAL/眼镜名称匹配，并排除主屏。不再要求显示器名字带 HDMI。若产品信息为空，正确处理而不是解引用 null。

新界面负责运行时权限、USB 授权、SDK 启停、临时 2D/3D 模式切换、外屏测试和启动现有 HelloMR。通过原 Binder Acceptor 提供 SDK 的服务发现协议。服务本身不负责把任意 Android 应用变成 3DoF 场景；姿态采样、预测、双眼渲染和世界锁定由 SDK 客户端完成，必须另行验证。

没有打包 firmware 更新数据，没有接入 OTA 操作，也没有重编译或修改原生库。13 个原生库的 16 KB 限制仍在。当前初版保持 targetSdk=33 以隔离实测显示检测缺陷；compileSdk=36，不把“提高 targetSdk”当作兼容修复。

## 本地构建

运行 `python ServerAppResearch/build_server.py`。

默认工具链为 `D:/FilesLibrary/SDK` 和 `C:/Program Files/Java/jdk-17`，可用 `XREAL_ANDROID_SDK` / `XREAL_JDK` 覆盖。JADX 1.5.6（官方 skylot/jadx 发布）提供 dexlib2，用于删除原 DEX 中旧的 DisplayModel；新实现通过 javac / D8 编译。aapt2 编译新 Manifest，复用原 APK 中的二进制运行时和资源，再 zipalign、签署本地开发证书、验签。

原 APK 保留。新 APK 不使用官方签名，不能覆盖安装官方应用。开发证书仅用于本地调试，位于 build/development.p12；口令 android。

输出：`build/XrealMinimalServer-debug.apk`，构建信息在 `build/build-info.json`。

JADX 对原 APK 报告 68 个反编译错误，反编译输出仅作审计证据；构建并未尝试把全部反编译 Java 代码当作原始源码编译。

## 验证状态

- [x] 设备真实 Android/API/页大小已读取。
- [x] 官方错误已实机复现并定位到名称过滤后的显示检查超时。
- [x] 新 APK 已构建，通过签名与 ZIP 对齐检查。
- [x] 二进制包装验证：原 6381 项类定义均保留，原有 2 个 LogControl 重复类保持原状，无新增重复类；DisplayModel 仅替换一次；15 个 .so 的 SHA-256 均未改变；resources.arsc 未压缩；固件更新资产未打包。
- [x] 新包在实机完成 USB 权限和服务启动（10510 / dev.xreal.server，SDK start result=true）。
- [x] 3840×1080 立体输出确认（20:48:15 mode=3 result=true，20:48:17 枚举到 3840×1080）。
- [ ] HelloMR 实际绑定新服务，验证眼镜姿态与 3DoF 悬停。

后续设备验证结果将补充在此文件；未完成的项目不代表已支持。

### 安装待确认

无线 ADB 已配对成功。ADB 安装两次返回 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`；即使用户开启 USB 安装后系统仍拒绝。没有修改或绕过系统安装策略。

已将完整的已验签 APK（121502287 字节）复制到手机 `/sdcard/Download/XrealMinimalServer-debug.apk`，等待用户通过手机文件管理器打开系统安装界面完成安装。原 Control Glasses 没有卸载或清除数据。

### 手动安装后的实机结果与 v0.3

用户已完成安装。新服务可初始化、USB 已授权、外屏画面测试正常。HelloMR 的 Binder 握手实际进入新服务进程的 Acceptor，NativeAPI 创建和启动完成。最初黑屏时外屏仍是 1920×1080，初版启动按钮没有自动切换立体模式；手动设为 3 后系统枚举到 3840×1080，用户确认出现悬停画面，但停在 Unity 启动图，持续渲染尚未通过。

日志证据：`evidence/server-hellomr-black-screen.log`、`evidence/hellomr-stereo-freeze.log`。

还观察到：HelloMR 多次因 TMP_Settings/defaultFontAsset 缺失抛 NullReferenceException；有启动阶段 NativeRendering.FrameWait 失败日志；系统保留了一次 dev.xreal.server 主线程 ANR。不能把后两者的全部因果关系等同于 TMP 缺失。

v0.3 的新入口启动客户端前先请求 3D 模式，并等待物理显示模式为 3840×1080（最多 15 秒），失败时明确报告而不进入黑屏客户端。SDK 启动、停止和模式切换均移到串行原生工作线程，Activity 重建会读取已有 SDK 启动状态。

项目已导入其随包 TMP Essential Resources，避免缺失字体设置。另建独立 `dev.xreal.server.posecheck` 客户端：仅基本方块与摄像机、显式 MODE_3DOF、不使用 TMP 场景 UI，输出每秒渲染帧率、isTracked 和眼镜旋转心跳，用来隔离复杂示例场景与跟踪/渲染运行时。其稳态实机结果仍待验证。

3DoF 诊断 APK 已成功构建并通过 apksigner 验证：`../Build/Android/XREAL-Server3DoF.apk`（130143043 字节），包名 `dev.xreal.server.posecheck`，可与 HelloMR 并存。构建期间临时使用 3DoF / InputSource.None / SupportMultiResume=false；构建后主项目 XREALSettings 已恢复原 6DoF 配置、原应用 ID 和产品名。新场景为 `Assets/ServerDiagnostics/Server3DoF.unity`，姿态心跳脚本为 `Assets/ServerDiagnostics/Runtime/ServerPoseTelemetry.cs`。

Server 更新文件为 `build/XrealMinimalServer-v0.3-debug.apk`。v0.2 是中间包，后续验证使用 v0.3。安装后，先“启动眼镜服务”，然后“启动纯 3DoF 诊断场景”；此按钮会自动准备立体显示模式。检查中央方块是否持续旋转、转头时静态标记是否固定、至少一分钟是否仍可响应，并结合每秒 `[ServerPoseCheck] heartbeat` 区分内容更新与仅姿态重投影。

### Server 1.0 / 空间能力扩展

用户随后反馈调试功能已正常，并要求 6DoF、空间锚点、手势和综合 Demo。已新增可编辑的完整服务控制台、进程级运行库所有者、独立 Unity 空间调试客户端与手机原生操作面板。服务工作线程使用 HandlerThread，以满足原版 DisplayObserverHelper 的 Looper 依赖，同时避免原生初始化阻塞主界面。回调带启动代次检查，重试前释放上次原生状态。

空间客户端接入真实 XR / ARFoundation / XR Hands：6DoF 位姿与失追原因、锚点持久化闭环、双手关节/骨架及几何手势、平面/网格/图像与 RGB 能力诊断。修复 SDK 保存/删除锚点异常没有完成 Task 的缺陷。具体操作与验收步骤见 `../Build/Android/空间功能调试说明.md`。

新增空间功能的状态为“代码与构建检查完成，待本机硬件验收”；不能把前一版基础功能反馈或新 APK 验签通过等同于所有新增模块实机通过。继续复用官方闭源原生库，没有宣称恢复其 C++ 源码。
