# 1.1.1 配色验证

日期：2026-10-26。

- assembleDebug 与 lintDebug 成功，日志见 build.txt、lint.txt。
- Android 16 独立只读模拟器真实截图：home-light.png、home-dark.png、settings-light.png、settings-dark.png；已逐张查看。
- 主按钮文字对比度：浅色蓝紫 5.92:1、浅色青绿 4.86:1，深色蓝紫 7.19:1、深色青绿 7.42:1。
- 修改范围：MainActivity.kt 配色与功能色、InterfaceSurfaces.kt 彩色图标底与容器边、SettingsContent.kt 分组图标配色、版本号；未改后台执行或网络逻辑。
- 此次为样式调整，没有重复运行 1.1.0 的单元/设备测试。

交付：releases/声屿-1.1.1-测试包.apk
SHA-256：1f9e3a4ad2c8b96dd99a6c13c386c1fee14ef093d109d08b8ab3dae0c1cca1de

沿用本机调试签名，与本次 1.1.0 测试包兼容；仍与历史 1.0.2 报告签名不同，不能直接覆盖旧版真实手机安装。
