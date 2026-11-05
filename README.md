
# XREAL Android Server

Android 原生 Server 工程，当前版本 **1.0.1-handoff**（versionCode 11，包名 `dev.xreal.server`）。管理 XREAL 运行库、眼镜显示模式和 XR 客户端启动时的 USB 控制权交接。

本仓库只包含 Server 源码。Android 稳屏客户端独立位于 [xreal-android-stable-screen](https://github.com/MainLe/xreal-android-stable-screen)；Windows 3DoF 应用位于 [xreal-windows-3DOF](https://github.com/MainLe/xreal-windows-3DOF)。

## 构建

需要 Windows、Python 3、JDK 17、Android SDK platform 36 和 build-tools 35.0.0，以及 jadx 1.5.6 的 `jadx-1.5.6-all.jar`。脚本通过 Java/d8/aapt2 构建，不使用 Gradle，也不需要 Unity。

1. 将自行取得的 `ControlGlasses-3.1.0.20251118115716-release.apk` 放到仓库根目录。构建脚本会校验 SHA-256：`49c9484ebd34e495984b219bb73988a366c03339f7251a9fe9a4702f58f5c76e`。
2. 设置下列环境变量，然后构建：

```powershell
$env:XREAL_ANDROID_SDK = 'C:\Android\Sdk'
$env:XREAL_JDK = 'C:\Program Files\Java\jdk-17'
$env:XREAL_JADX_JAR = 'C:\tools\jadx\lib\jadx-1.5.6-all.jar'
python ServerAppResearch/build_server.py --release
python ServerAppResearch/verify_build.py --release
```

Release 输出：`Build/Android/XrealServer-v1.0.1-release.apk`。使用优化的 D8 Release 编译，生成非 debuggable 清单。省略 `--release` 可构建原来的调试版本。

签名复用本地 `ServerAppResearch/build/development.p12`；没有该文件时生成本地开发证书。发布时使用原工作区已有证书，以保持安装更新兼容。签名密钥不进入仓库；首次在其他电脑构建的证书不保证与已有安装相同。

成品见 [Release v1.0.1](https://github.com/MainLe/xreal-android-server/releases/tag/v1.0.1)，下载后可用同页 `SHA256SUMS` 校验。构建脚本限制各 Java 工具的堆和 GC 并发，降低编译/签名启动的内存需求。

## 实现与验证范围

Java UI、生命周期管理及显示适配在 `ServerAppResearch/server-app`。构建会复用指定输入 APK 中的闭源 DEX、资源和 ARM64 运行库；本仓库不是 XREAL 原生跟踪引擎的源码重建。输入 APK、第三方工具和签名密钥均需本地提供，不包含在 Git 历史中。

1.0.1 在 Xiaomi 15 / Air 2 Ultra 上已有首次客户端启动、眼镜重连、停止/恢复验证记录。详见 [USB 控制权交接说明](ServerAppResearch/SERVER-HANDOFF.md) 和 [服务接口映射](ServerAppResearch/API-MAP.md)。文档中引用的原始设备日志和截图未公开。

`verify_build.py` 校验运行库类、原生库、资源保留和打包一致性；它不替代实机测试。现有成品的字节数与 SHA-256 见 [artifact.json](release/artifact.json)。APK 作为 Release 附件管理，不写入源码分支。
