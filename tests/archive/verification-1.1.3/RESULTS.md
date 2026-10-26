# 1.1.3 预测式返回验证

日期：2026-10-26。versionCode=7 / versionName=1.1.3。

## 本次纠正

之前只做了固定水平位移与缩放，且历史/设置在预测式包装外又有 AnimatedVisibility 退场，转写页则在路由置空时直接卸载。这些路径没有统一取消和提交生命周期，不能凭“最终返回成功”宣称符合完整转场要求。

本次保留 Compose，用公开手势进度与触点信息实现一套转场状态。拖动时页面缩小并浮起，横纵位移受边距约束，取消回弹，提交后完成连续退场才移除页面；普通返回按钮走同一退出生命周期。删除原来的重复退出包装。系统仍负责主页返回桌面的窗口级预览。

参考：
- 用户提供的 PREDICTIVE_BACK_GESTURE_GUIDE.md。
- https://api.flutter.dev/flutter/material/PredictiveBackPageTransitionsBuilder-class.html
- https://developer.android.com/design/ui/mobile/guides/patterns/predictive-back
- https://developer.android.com/develop/ui/compose/system/predictive-back

指南指定的 Flutter 组件对应一类页面转场，并非只能在 Flutter 使用。此处独立实现其浮动页面交互机制，不引入 Flutter，也不声称与某个未提供录屏的 ChatGPT 版本逐像素一致。

## 验证

- assembleDebug、testDebugUnitTest、lintDebug 成功；lint 0 errors，14 warnings。
- 单元测试 47 项通过（原 43 项 + 4 项返回几何测试）。新增检查初始状态、双边边距、纵向位移方向、缩放边界。
- 独立 Android 16 / API 36 模拟器注入真实手势，验证左侧斜向拖动并取消、右侧斜向拖动并提交、转写返回历史、历史/设置按钮返回主页。
- 取消前后设置页内容区域（排除系统栏）像素完全一致。
- 检查了提交动画的连续抽帧，确认退场连续完成，没有叠加第二段退出。
- 主页返回桌面可见真实桌面壁纸；取消后仍为首页。
- 返回优先级：键盘 → API Key 弹窗 → 转写页面 → 历史。测试只聚焦空输入框，没有输入/保存 Key。

证据：
- gesture-check.txt、keyboard-check.txt：路径断言。
- predictive-return.mp4：31 秒完整交互录屏。
- predictive-preview.mp4：包含手势预览与退场的 6 秒片段。
- left-preview-down.png、right-preview-up.png：两侧斜向拖动。
- settings-start.png、cancelled.png：取消前后。
- commit-sequence.png：提交阶段连续抽帧。
- back-to-desktop-preview.png：系统桌面预览。
- build.txt、lint.txt、unit-tests.txt：构建与测试记录。

主要文件：InterfaceSurfaces.kt、PredictiveMotion.kt、MainActivity.kt、styles.xml、PredictiveMotionTest.kt。转写引擎和后台服务未改。

## 交付与范围

APK：releases/声屿-1.1.3-测试包.apk
SHA-256：9243aa19b8d3b323ca853771260dcca7320a7ecdda313bc39546e1231781ce46

未在厂商实体手机或 Android 13/14 上复核；模拟器可证明本次转场与生命周期行为，不能替代对应设备的最终手感验收。沿用本机调试签名，与历史 1.0.2 签名不同的升级限制仍在。
