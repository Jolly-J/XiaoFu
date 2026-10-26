# 声屿 · 原生 Android 视频音频工具

独立原创 Kotlin / Jetpack Compose 应用。Android 8.0（API 26）及以上。

## 工作流（1.1.5）

- **转音频**：首页只负责选择视频、提取 M4A 或 WAV、播放和另存。不自动进入转写、不自动上传。
- **历史**：从主页右上角打开，展示全部成功导出的音频；打开音频后可播放、另存、分享和转文字。确认上传后才识别。
- **设置**：选择浅色、深色或跟随系统（跨重启保留），配置、替换或删除自己的 MiniMax API Key。

**本地提取不联网、不上传视频。只有用户主动确认的转写会把音频发送给 MiniMax。** API Key 使用 Android Keystore 加密保存在不备份目录；没有内置 Key，不写入日志。MiniMax 调用可能计费。音频库在应用私有目录，卸载将删除，请另存重要文件。

### 1.0.2 修复与诊断

修复有效负音频时间戳（包括恰好 `-1µs`）被误判为空音轨的问题。结束状态只根据真实轨道/读取结果判断，AAC 原样封装保留全部可读包；真正加密内容仍不支持。保留 1.0.1 对 EOF 加密误报和限定 API28 AAC 元数据异常的修复。

设置与提取失败卡片提供“复制诊断信息”：仅最近三次内存中的白名单技术数据，不含 Key、URI、路径、文件名或媒体内容，不自动上传。详见 `docs/NEGATIVE-PTS-1.0.2.md`。

1.0.2 的同签名覆盖升级结论仅适用于当时交付包。当前 1.1.5 测试 APK 使用本机调试签名，与旧版报告的证书不同，不能直接覆盖旧版；正式升级须用原签名密钥重新构建。不要卸载旧版来绕过签名问题。具体设备测试结果见 `TEST_REPORT.md`。

## 格式边界

- MP4 是容器；H.264/H.265/HEVC 是视频编码。提取只读取音轨，不需要解码视频画面。
- 常用 MP4/MOV/WebM/MKV 等容器的可读性取决于 Android MediaExtractor 和设备能力，不承诺所有格式或所有编码组合。
- AAC → M4A 不重编码；其他可解码音轨 → AAC-LC，单声道 128 kbps / 立体声 192 kbps。转码会损失质量。
- WAV：PCM16，保留源采样率，支持单/双声道，传统 RIFF 上限约 4 GiB。
- DRM/加密内容、损坏文件、无音轨、设备不支持的编码会明确报错。首版不提供 MP3 编码，不虚报 MP3 支持。
- 已实测限制：Android 9/API28 的系统提取器不支持 MP3 音轨封装在 MP4/3GPP 中；同一素材在 API35 可转。此组合在旧平台会报告无可读取音轨，不等于原文件实际无音轨。
- 首版选择第一条音轨；不提供多音轨选择、剪辑或批处理。

## 构建

JDK 17+（需完整 JDK，不是 JRE）、Android SDK 35 / Build Tools 35.0.0。

```sh
# Android Studio 打开本目录；或设置 ANDROID_HOME、JAVA_HOME 后：
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
# 源码交付不包含测试视频；先准备fixture（需FFmpeg/curl）：
./scripts/prepare-test-media.sh
./compatibility-tests/prepare-media.sh
# 有连接设备或模拟器时：
./gradlew connectedDebugAndroidTest
```

版本固定在 Gradle 配置中。Debug APK 自签名，可侧载安装，仅供个人试用；不代表已发布商店或经过生产审核。不要把调试签名用于正式公开分发。

## 设计与架构

- Kotlin / Compose，自定义多彩界面：顶部图标栏、文件导入工作区、格式选择与提取操作、右上角历史与设置入口；28dp 分组圆角、浅灰细边和轻量圆形导航按钮，支持深浅色。
- `AudioEngine`：MediaExtractor / MediaMuxer / MediaCodec；逐包/逐帧真实进度、协程取消与失败清理。
- `AudioViewModel`：保留旋转期间任务和状态；本地导出列表从磁盘恢复。
- `TranscriptionEngine`：每片独立异步任务，最多 3 路并发，全部成功后按原顺序合并全文；失败只重试缺失段。
- `TranscriptionService` / `TranscriptionTasks`：前台服务承接后台转写，统一任务状态、通知、取消及中断恢复。`ApiKeyStore` 保持加密凭据。
- 应用内预测式返回使用三层滑移渐隐与对数/弹簧进度，保持页面尺寸；取消恢复原页，主页退出仍交给系统。
- 不使用 WebView，不包含第三方商业 APK、品牌或反编译源代码，不内置分析/广告 SDK。

## 运行限制

转写支持离开页面、回到桌面及锁屏后继续，由 dataSync 前台服务承接。首次转写按系统版本申请通知权限；拒绝通知不阻止任务，但常规通知可能不可见。设置提供系统通知/实时活动与后台运行入口。没有通用的“后台权限”运行时弹窗，厂商电池策略由用户在系统设置中管理。

标准常驻进度通知使用系统模板，支持的平台请求提升为实时活动；不使用悬浮窗。厂商流体云/胶囊是否显示、用户是否允许提升，由对应系统决定，不能把标准通知等同于各厂商适配已通过。

后台执行受系统时限和回收约束。进程被杀后，保留成功片段与任务状态，重新打开可手动继续；不会自动重传可能已经计费的未确认请求。前台服务不是无限保活。本次未把本地提取迁移为后台服务。真实 MiniMax 账户、厂商后台策略仍需实机验证。

## 官方参考

- https://developer.android.com/reference/android/media/MediaExtractor
- https://developer.android.com/reference/android/media/MediaMuxer
- https://developer.android.com/reference/android/media/MediaCodec
- https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture
- https://solutions.minimaxi.com/debug/asr

测试状态见 `TEST_REPORT.md`。没有 API Key，因此不进行真实付费转写调用。

取消云端转写不能撤回已经上传的内容，也不保证取消计费。删除音频库条目会同时删除关联的本地转写文字与分段缓存，已另存副本不受影响。
