# 声屿 1.0.2 最终设备验证

## 结论与冻结版本

最终主 APK：`round3/apks/app.apk`，SHA-256 `b4e70674b1c11511e2775bd413f08853eef1e13efd9f2e096b9b3958e533c520`。
最终测试 APK：`round3/apks/tests.apk`，SHA-256 `74ffd41e4752a51fd284e3cd08ae0d26f6babbc383fbe19dacc01783696866d3`。
设备实际安装版本 `versionCode=3 / versionName=1.0.2 / minSdk=26 / targetSdk=35`，同签名覆盖安装成功。验证期间未改冻结主代码或 APK。

| 官方模拟器 | 短测试 | 显式 972 秒长测试 | 最终计数 |
|---|---:|---:|---:|
| Android 15 / API35 AOSP ATD | 36/36 通过 | 1/1 通过 | **37/37，0 跳过** |
| Android 9 / API28 default | 35/36 通过 | 1/1 通过 | **36/37，1 个兼容失败，0 跳过** |

**保留的失败：API28 的 MP3 音轨封装在 MP4。** 原生提取器明确输出 `MP3 track in MP4/3GPP file is not supported`，未暴露可读音轨；同字节素材 host ffprobe 有真实 MP3 音轨，API35 的 M4A/WAV 转换均通过。没有降低断言、改为跳过或宣称所有格式兼容。详见 `api28/MP3-IN-MP4-LIMITATION.md`。

每轮日志分别保留。最终结果只来自 round3 对应 SHA，不把此前版本长测试或未完成的运行计入最终通过数。机器可读汇总：`round3/atd35/final-results.json`、`round3/api28/final-results.json`。

## 已实测范围

- AAC 有效负 PTS、恰好 -1µs 的有效首包、非负对照；M4A 所有 AAC 包数/内容 SHA 与时间跨度保持。
- MP4/H.264、HEVC、fragmented MP4、MOV、多个音轨、Opus/Vorbis 转 AAC 与 WAV；MP3-in-MP4 的平台差异如上。
- 官方平台编码器真实生成并经 ffprobe/完整解码确认的 HE-AAC、HE-AACv2。HEv2 源 core 22050Hz mono 与实际解码 44100Hz stereo 分别验证，未把源元数据误当输出声道。
- 系统 ContentProvider：已知长度、非零 offset、未知长度可 seek、不可 seek pipe；pipe 成功暂存、合法 offset、空流、提前 EOF、可靠管道远端错误、阻塞时及时取消与暂存清理。
- 原有 15 项：无音轨、取消清理、真实 AAC/Vorbis/HEVC/Sintel 音轨处理、Key 加密/替换/删除、本地 mock 转写成功/鉴权错误/限流/失败续传/取消、M4A 本地解码后 mock 转写。
- 全部转写联网测试仅向本地 mock 服务；没有真实 MiniMax 请求或真实 Key。

## 长文件不是抽样

972 秒、44100Hz、双声道合成输入完整复制到设备私有 cache，host/device SHA 相同，显式运行单个长测试且非 assumption skip。M4A 包身份、时间跨度和时长全部验证，WAV 实际完整落盘后验证 RIFF 与数据长度：

- API35：测试运行 1345.821 秒；PCM **171462656 字节**，**972.0105215 秒**。
- API28：测试运行 580.404 秒；PCM **171466752 字节**，**972.0337415 秒**。旧平台 priming 差异仍在明确时长容差内。
- API35 抽样 App PSS 32688–36673 KiB；API28 中途完整 meminfo 快照 PSS 87789 KiB。不是性能基准或整体内存上限保证。
- 本轮输出/暂存清理检查无遗留 `.partial`。API28 原 1.0.1 的 Sintel 历史音频被保留，没有误删旧音频。

原始结果、增长日志、内存、诊断及清理列表位于各设备目录的 `long-*`、`exports-after-long.txt`、`staging-after-long.txt`。

## 旧版因果对照：实际复现同文案

`atd35/baseline-aot-after-final/` 保存冻结 1.0.1 的真实运行：在同一 API35 上消除冷加载因素后，两项 AAC 测试均出现 **“音轨没有可读取的音频数据”**，分别在 remux 与 decodeWav。不是启动 ANR。

从旧 App cache 取出的实际测试文件与新版 fixture SHA 完全相同。独立 MediaExtractor 观察首包 `time=-23219 / track=1 / read=258`，88 个有效包；实际 EOF 才是 `track=-1 / read=-1`。另一个合法文件首包时间恰为 -1µs，仍有有效音轨和数据。API28 同字节三种输入首时间均为 0；这些差异均有实际探针记录，不是跨版本推断。

最终 1.0.2 在 API35 上同两项及全 37 项通过。这里只证明已知缺陷与合成复现，不声称读取过用户私有视频。

## 最终 APK 的真实 UI / SAF 闭环（API28）

全部新证据在 `round3/api28/ui/`，不是旧版截图：

1. 三栏“转音频 / 历史 / 设置”实际导航；旧音频历史保留。
2. 浅色选择跨完整设备重启仍保留（`reboot-light-persisted.png`）；“跟随系统”实际响应深浅切换。稳定截图 `settings-system-dark-stable.png` / `settings-system-light-stable.png` 的状态栏图标对比正确；早期截图仅为切换瞬态。
3. SAF 系统 Downloads 选择无音轨素材 → 真实失败卡 → 关闭提示可继续。通过设置按钮复制诊断，再从真实系统剪贴板取回 JSON：`copied-diagnostic.json`，版本1.0.2/API28/content/NO_AUDIO_TRACK。验证不含文件名、URI 字符串、Key 或内容。
4. SAF 选择同一个 2 秒 AAC 视频，分别真正完成 M4A 与 WAV 提取（`m4a-result.png` / `wav-result.png`），不是进度截图。历史出现两条新音频加旧 Sintel。
5. 两格式分别通过系统另存到 Downloads。私有原件与另存件 SHA 相同；拉回文件 ffprobe 与完整 FFmpeg 解码成功：M4A 20829 字节、WAV 180268 字节，均44100Hz mono、2.043356秒。记录在 `saved-media/` 与 `*-export-sha256.txt`。
6. WAV 播放按钮实际进入停止态，NuPlayer 完整 EOS / AudioTrack 90112 帧；模拟器使用无声配置，未做主观听音判断。
7. 历史手动“转为文字 · MiniMax”进入 Key 空态，明确显示音频上传/费用说明；未配置 Key，未发送真实请求（`transcription-no-key.png`）。
8. 空 Key 对话框与主窗口实际具备 SECURE flags（`key-dialog-window.txt`）；截图尝试无图像输出，保存为 `.capture-empty`，不伪称获得黑屏 PNG。没有输入任何 Key，Back 正常关闭。
9. 分享打开 Android 系统 chooser，FileProvider URI 具备临时读授权；未选择接收方、未发送。Back 返回原历史，三条音频仍在（`share-chooser.png` / `history-after-share-cancel.png`）。
10. 在 App 未运行时创建 `source_stale.partial` 与 `keep.txt`，真实 Activity 启动只删除前者，后者内容 KEEP_SENTINEL 保留；测试后移除自建哨兵，最终暂存目录为空。

## 环境与未验证边界

- 无 KVM，串行软件模拟器；无 Gradle/模拟器并发。API35 首次 DEX 冷加载出现进程启动 ANR，官方 package AOT 后正常执行；原始失败保留。早期 Kotlin 测试 Provider 独立进程缺少 stdlib 导致测试框架失败，已仅将测试 Provider 改为 Java，并完整重测。
- API28 UI 首次被 System UI ANR 遮挡；正常 Wait 后恢复。一个 `am start -W` 等待曾阻塞，保留数据重启 UI-only runner 并改为有超时/独立控制后完成全部上述闭环。没有把遮挡截图或未完成动作记为通过；重启后 data_app_anr 查询无 App ANR 条目。
- uiautomator 在本环境返回 null root，UI 定位依据实际截图像素，不假装有有效 XML。
- 未验证用户实际视频、实体手机/厂商 codec、Android26/27及其他未列版本、真实 MiniMax 账户/服务；API35 ATD 不代表完整系统文件选择器或预测返回手势 UI 已验证。
- 所有媒体均为合成样本或项目已记录许可的公开 Sintel 测试片段。没有用户文件/Key 上传。

## 停止状态

所有本任务模拟器会话已结束。最终 UI runner 收到 `adb emu kill` 的 OK 确认，emulator 以 SIGUSR1 结束，runner 终态 exit138（非仍运行）；见 `api28-ui/shutdown-result.txt`。API35 runner 先前正常 exit0。未留下后台设备测试工作。
