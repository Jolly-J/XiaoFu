# 1.1.4 三层滑移渐隐转场验收

日期：2026-10-26。版本 1.1.4 / versionCode 8。

## 对照依据

本次使用用户提供的咔捕自研实现，而非 Flutter 官方默认缩放转场：

- `/Users/Python/抖音、小红书下载/lib/app_theme.dart` 第 274–534 行：SmoothPredictiveBackPageTransitionsBuilder、二阶弹簧、_ChatGptSlideFadeTransition。
- `/Users/Python/抖音、小红书下载/docs/PREDICTIVE_BACK_GESTURE_GUIDE.md` 当前自研方案版本。
- `/Users/Python/抖音、小红书下载/docs/screenshots/predictive_back.mp4` 参考录屏。
- 输入文件 SHA-256 见 reference-inputs.sha256。

当前指南的示意时间轴与代码略有差异，本次以实际源码为准：前页在 46% 时完全淡出，后页从 36% 起渐入，同时保留独立背景色遮罩。它不是在某个阈值直接切页。

## 实现映射

| 参考源码 | Compose 实现 |
| --- | --- |
| rawProgress 对数目标映射 | PredictiveMotion.kt / dampedBackTarget |
| stiffness=115、damping=19，dt 限 1–33ms | BackProgressSpring，withFrameNanos 驱动 |
| 当前页最大右移 13%，不缩放 | foregroundXFraction；无 scaleX/scaleY |
| 前页 easeOutCubic，46% 结束 | backLayers / foregroundAlpha |
| 后页 easeInOutCubic，36% 开始，-8% 位移归零 | backgroundAlpha / backgroundXFraction |
| 全屏主题底色遮罩 | SmoothBackHost 中间层；不是两页直接交叉淡化 |
| animation / secondaryAnimation 同步 | 同一 progress 同时驱动三层 |
| 系统取消/提交后停止自研 ticker，由路由继续 | 停止手指追踪，沿当前 progress 使用对应的完成曲线与时长 |

页面内容大小保持不变。移除旧转场的缩放、动态圆角、页面浮起阴影与纵向拖拽。所有仍在导航栈中的页面保持组合，并使用独立可保存状态。

## 数值验证

- 独立 Dart 脚本 reference_motion.dart 按输入源码公式生成阶段与连续 60 帧弹簧数据；产物在 app/src/test/resources/reference-back-*.csv。
- Kotlin 阶段数值与独立 Dart 对照误差小于 1e-6；弹簧轨迹误差小于 1e-9。
- 这项验证是公式数值对照，不等于运行了一次完整 Flutter 页面。
- 50 项单元测试通过：原有 43 项 + 7 项转场测试，见 unit-tests.txt。
- assembleDebug 与 lintDebug 通过，0 errors，14 warnings。见 build.txt、lint.txt。

## 真实 Android UI 验证

独立只读 Android 16 / API 36 模拟器，注入真实触摸事件并录屏：

- 右边缘八阶段持续拖动：前页渐隐、主题底色中间态、主页渐显；保持页面原尺寸。
- 从已显露主页的预览状态取消：仍停留设置页；排除系统栏后，取消前后内容区域像素完全相同，按钮及可访问性节点恢复。
- 左边缘提交、转写返回历史、历史按钮返回主页通过。
- 深色拖动、取消后点击、按钮返回和非手势返回通过；主题遮罩未闪白。
- 键盘 → Key 弹窗 → 转写页面 → 历史的返回优先级通过。没有输入或保存 Key。
- 首轮取消时发现交互状态未释放；修复了 AndroidX 取消回调协程的收尾范围后，重新验证通过。失败版本未交付。

证据：
- gesture-check.txt、dark-check.txt、keyboard-check.txt。
- smooth-back.mp4：约 31 秒完整交互。
- smooth-preview.mp4：保持实时速度的 10 秒节选，未加速或重新制作动画。
- stages-contact.png：连续拖动阶段与取消复原。
- new-middle.png / reference-middle.png：从两段真实录屏分别抽出的底色过渡帧。
- dark-preview.png、dark-cancelled.png：深色预览与复原。

参考与声屿内容布局不同，因此做的是转场结构、尺寸和顺序核对，没有宣称两段录屏逐像素一致。

## 范围与交付

主要文件：SmoothBackHost.kt、PredictiveMotion.kt、MainActivity.kt、InterfaceSurfaces.kt、PredictiveMotionTest.kt。音频与后台引擎未改。

交付 APK：releases/声屿-1.1.4-测试包.apk
SHA-256：2b5568420ef091f250fac03cb21cad3a42f17d0e4ac6e29483f95fd84a29f8ce

这套效果针对应用内页面。主页退出到桌面仍由系统接管。未在厂商实体手机、Android 13/14 或所有刷新率上复核。沿用本机调试签名，与历史 1.0.2 签名不同的升级限制仍在。
