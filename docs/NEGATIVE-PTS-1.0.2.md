# 1.0.2：合法负时间戳与音轨结束不能混淆

## 证据

用户报告 1.0.1 在同一视频的 M4A/WAV 均提示无可读音频；我们没有该原文件，不能认定已经复现其完整环境。

独立构造的 AAC MP4 在 Android API 35 的真实 `MediaExtractor` 上读取到：

```
HEAD time=-23219 track=1 size=258 flags=1 read=258
HEAD time=0      track=1 size=258 flags=1 read=258
EOS  time=-1    track=-1 size=-1 flags=-1 read=-1
```

另一合法 edit-list 测试文件的首包恰好为 `time=-1`，但 `track=1,size=258,flags=1,read=258`。因此无论 `time < 0` 还是 `time == -1`，都不能单独判断 EOS。

AOSP MPEG4Extractor 明确说明音频 gapless 初始样本的 CTS 可以为负，然后将其转换为微秒：
https://android.googlesource.com/platform/frameworks/av/+/45fa42d7989078fe34a32a0e1f21a60dbea854b5/media/module/extractors/mp4/MPEG4Extractor.cpp

探针原始记录保存在 `verification-1.0.2/api35/`。宿主 FFmpeg 检查仅辅助确认素材结构；Android 兼容结论必须来自设备测试，见最终 `TEST_REPORT.md`。

## 旧实现错误与修复

1.0.1 的 remux 循环要求 `sampleTime >= 0`，解码入口也把负时间当 EOS。因此有效负首包会让两种输出都直接结束。这是确定代码缺陷，并与用户现象一致，但用户原视频未测。

1.0.2 改为依据 `sampleTrackIndex < 0` 或 `readSampleData < 0` 判断 EOS。只有真实可读样本才检查加密标志；真正 DRM 仍拒绝。

首时间基准改为 nullable，避免负号哨兵在多个负包上反复初始化。解码输入保留原始 PTS，输出整体相对首包归零，不删除负 PTS 音频包。AAC 无损封装测试核对每包数量与内容 SHA256。

## 诊断

设置及失败卡片可主动复制最近三次内存诊断：应用版本、SDK、来源 scheme 类别、音频 MIME、采样率/声道/时长、数值 AFD offset/length、样本时间/大小/标志、阶段和受控错误枚举。不包含 Key、URI、路径、文件名、音视频内容或原始异常；不自动上传。

## 仍需区分

- 有效负时间戳不是加密、损坏或 EOF。
- EOF 的 `sampleFlags=-1` 不应进入位标志加密判断。
- 不同平台的 edit-list 行为有差异；API 28 的异常 AAC encoder-delay 修复保持独立且受版本/类型/值限制。
- 构建成功、单测通过、原生探针读到包，都不能代替完整 M4A/WAV 输出验证。

## 非 seekable 文件提供器的独立问题

完整 Android 35 仪器测试另外证实：合法管道 AFD 在 `MediaExtractor.setDataSource(fd)` 抛 `IOException`，而相同未知长度但可 seek 的文件通过。因此仅在同一实际 AFD 的 `Os.lseek` 明确返回 `ESPIPE` 时，将来源流暂存到私有 cache；其他权限、打开、解析错误不进入通用兜底。

暂存保留 offset/声明长度契约，使用100ms轮询与可中断 FileChannel 读取检查取消（独占复制FD，不改变共享FD flags）；30秒无新数据、30分钟总时限、8GiB上限、64MiB剩余空间保护。完整读取后交给提取器，临时文件成功/失败/取消均删除；进程被杀的残留由下次 Application 启动在开始任务前删除。普通可seek视频不复制。此限制针对来源管道暂存，不是普通文件的统一8GiB限制。

官方 FD 生命周期说明：调用 `setDataSource` 返回后调用方可以关闭原FD，提取器持有自己的引用。
https://developer.android.com/reference/android/media/MediaExtractor

测试早期 Provider 的 Kotlin runtime 独立进程缺失属于测试工具缺陷，修复为纯Java后才取得上述真正 App 失败证据；保留早期记录，不将其混算为App失败。
