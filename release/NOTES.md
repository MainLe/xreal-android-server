# XREAL Android Server 1.0.1

首次 GitHub Release，版本 `1.0.1-handoff` / versionCode 11 / `dev.xreal.server`。

- 使用 D8 Release 编译；明确关闭 Android `debuggable`。
- 保留客户端启动前释放 USB 控制通道，以及返回 Server 后恢复控制的生命周期逻辑。
- 校验通过：保留 6,381 个原运行库类、15 个原生库，无新增重复类；APK 签名和 16KiB ZIP 对齐检查通过。
- 沿用已有本地开发签名证书，与原 Server 安装签名一致。不是应用商店专用签名。

此次为 Release 打包验证，没有进行新的手机实测。Server 复用指定版本的 XREAL 闭源运行库，不包含该原生引擎的源码重建。

下载 `XrealServer-v1.0.1-release.apk`；用 `SHA256SUMS` 校验下载内容。Android 稳屏客户端在独立仓库发布。
