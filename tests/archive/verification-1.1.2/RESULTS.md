# 1.1.2 顶部导航验证

日期：2026-10-26。版本 1.1.2 / 6。

移除侧栏及其状态和手势；主页右上角仅保留历史、设置图标。历史变为完整列表页面，保留所有音频操作。已选视频卡片提供更换文件按钮。

验证：
- assembleDebug、lintDebug 成功；0 errors，14 warnings。
- 独立 Android 16 模拟器中使用合成 WAV，实际点击和 UI 层级断言通过，见 navigation-check.txt。
- 主页无旧侧栏菜单入口；历史列表能显示音频并进入转写；真实边缘返回手势依次回到历史与主页；设置可直接进入和返回主页。
- 截图与 UI 层级在本目录；已查看 home.png 与 history.png。
- 本次不改后台转写引擎，不重复其已有测试。

主要修改：MainActivity.kt（导航与历史页面）、InterfaceSurfaces.kt（仅顶层页面接收返回手势）、SettingsContent.kt 与 app/build.gradle.kts（版本号）。

交付：releases/声屿-1.1.2-测试包.apk
SHA-256：45c1af079c868b7b1a8ac3769b5d28d720fd81f334f5a42881125be5148062a3

沿用本机调试签名，可覆盖本次 1.1.x 测试版本；与历史 1.0.2 报告签名不同的限制仍在。
