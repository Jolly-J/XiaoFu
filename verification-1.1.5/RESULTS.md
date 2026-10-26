# 1.1.5 转写细节验证

日期：2026-10-26。versionCode=9 / versionName=1.1.5。

## 范围与根因

用户复测切回桌面后未再出现后台失败，本次聚焦进度条与文字容器。没有调整前台服务生命周期、网络超时、唤醒锁或重试策略；不能据此宣称真实手机上的偶发后台失败已解决。

原进度以“成功返回的段数 / 总段数”计算，而请求层的上传回调被忽略。单段音频会在识别期间持续显示确定的 0%，界面上仅剩轨道和终点标记。本次接通请求层进度，明确区分已知进度与未知识别时间。

## 修改

- 解码显示实际解码进度。
- 上传按待上传音频字节汇总比例，适用于单段和并发多段；缓存段不进入上传分母。
- 等待识别与整理全文显示不定进度动画。单段不再以“0/1 段”作为进度副标题，多段保留完成数。
- 系统通知使用相同阶段进度；没有伪造服务端识别百分比。
- 转写结果放入细边分组容器。正文最大 360dp，且不超过页面可用高度的 45%；在容器内滚动。
- 复制、另存、分享位于容器头部，正文滚动时位置不变；操作对象仍是全文。

主要文件：TranscriptionEngine.kt、TranscriptionProgressTracker.kt、TranscriptionService.kt（仅进度映射）、TranscriptionScreen.kt。

## 验证结果

- assembleDebug、assembleDebugAndroidTest、testDebugUnitTest、lintDebug 通过，lint 0 errors，14 warnings。
- 54 项单元测试通过，包括单段上传/识别状态切换、按字节汇总、缓存分母、失败计数等 4 项新测试。见 unit-tests.txt。
- Android 16 独立只读模拟器的 9 项设备测试通过，全部使用本地模拟 HTTP 服务或合成文本。见 device-tests.txt。
- 单段 5 秒 WAV 上传完成后，等待响应期间状态为“正在识别”，progress 为 null，UI 使用动态不定进度条。
- 250 行长文本验证：正文滚动节点高度不超过 360dp，滚动前后复制按钮屏幕位置完全相同。点击复制后读取剪贴板，与全部原文逐字一致，包含最后的结束标记。
- 原有 multipart、M4A 解码、鉴权/限流、重试、连接取消、三路并发乱序合并、失败段重试回归通过。
- 前台服务在模拟器回到桌面后完成的回归通过。这不代表用户手机偶发后台问题已定位。

视觉证据均已查看：
- recognition-progress.png：单段正在识别时的实际进度条；另运行一次对应测试以避免系统剪贴板预览遮挡，见 progress-preview-check.txt。
- transcript-bounded-before.png：250 行正文的限高容器及顶部工具栏。
- transcript-bounded-after.png：内部滚动后的文本位置；工具栏位置不变，复制全文已验证。底部浮层为 Android 的剪贴板预览。

## 交付

APK：releases/声屿-1.1.5-测试包.apk
SHA-256：06c3f587d78f7fa146ce73e9fe1710d6f5bb30a6ff5ba4ee49db910c5f1730b1

沿用本机调试签名，可覆盖本次 1.1.x 测试包。未使用真实 MiniMax 凭据验收，也未连接用户截图中的实体手机。
