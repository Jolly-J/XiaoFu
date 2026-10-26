> 当前版本为 **1.1.5 转写进度与限高文字容器**（versionCode=9）。根因、设备验收与 APK 校验值见 [1.1.5 验证](verification-1.1.5/RESULTS.md)。后台偶发失败未复现，本次未调整后台运行机制。以下为 1.1.0 功能验证基线，所列 APK 哈希对应 1.1.0。

# 声屿 1.1.0 验证记录

日期：2026-10-26。交付版本 versionCode=4 / versionName=1.1.0。

## 本次完成

- 工具型首页：视频导入、格式选择与提取、最近音频；无聊天输入框。历史与设置位于侧栏。
- 根据用户实际客户端截图校正：28dp 圆角分组、1dp 细边、组内分隔线、圆形返回按钮；黑白灰主题和图标操作，精简常驻说明。
- 每片独立异步任务，最多 3 路并发。所有响应成功后按原顺序提交全文；一段失败不取消其余段，成功段缓存供重试。
- 转写由 dataSync 前台服务执行，与页面生命周期分离。通知授权、真实段数进度、点击返回任务、取消、完成通知、后台时限处理及受限唤醒锁。
- 公开通知 extra `android.requestPromotedOngoing` 请求实时活动提升；声明 `POST_PROMOTED_NOTIFICATIONS`。不使用悬浮窗、私有厂商字段或自定义 RemoteViews。
- 统一 canonicalPath 任务标识，修复页面与服务因 Android 私有目录别名造成两份任务状态的问题。
- 预测式返回：左右边缘跟随、缩放、上一页露出、取消回弹；根页面交还系统处理。

## 构建与自动验证

- assembleDebug、assembleDebugAndroidTest 构建成功。
- 单元测试 43 项通过：AudioEngine 29、TranscriptionEngine 4、Diagnostics 10。
- 最终 UI 构建与 lintDebug 通过，0 errors。保留的 warnings 主要是既有 API/依赖升级提示及非功能性建议；见 verification-1.1.0/lint.txt。
- Android 16 / API 36 arm64 模拟器设备测试共 10 项通过。网络全部使用本地模拟 HTTP 服务，未调用付费 MiniMax API。

设备测试证据：
- `verification-1.1.0/device-tests.txt`：9 项通过。
- `verification-1.1.0/notification-denied-test.txt`：1 项通过。

关键断言：
1. 三个完整切片上传在任一响应释放前同时到达，观测最大并发为 3。
2. 强制乱序返回，最终全文仍为 first / middle / last 的原音频顺序。
3. 中间片失败后等待其余片完成，不输出部分全文；重试只多请求失败的中间片。
4. 服务运行时回到桌面，任务继续；完成后全文可读、常驻通知移除。
5. 通知被拒绝且屏幕关闭时任务仍完成。
6. 后台取消断开等待，未提交全文且常驻通知移除。
7. 原有 multipart、时间戳、M4A 解码、鉴权/限流错误、重试、连接取消回归通过。

通知拒绝测试在 instrumentation 启动前由 adb 撤销权限。测试进程内部撤销权限会被 Android 以 permissions revoked 原因终止，已修正测试流程；不是应用运行时崩溃。

## 真实界面验证

在独立只读模拟器上读取真实截图和 UI 层级，未用网页模型代替 Android UI：
- `home-final-light.png` / `home-final-dark.png`：工具型首页。
- `settings-final-light.png` / `settings-final-dark.png`：浅深色分组容器。
- `predictive-back-progress.png`：真实边缘手势中间态。
- `predictive-back-cancelled.png` 与 `predictive-cancelled.xml`：取消后设置页保留。
- `predictive-committed.xml`：完成手势后回到首页。
- `extraction-result.xml`：合成 AAC 视频导入、提取成功，播放/另存/分享/转文字入口存在。
- `background-notification.png`：后台系统通知截图。它证明标准通知展示，不证明厂商胶囊展示。

上述文件位于 verification-1.1.0。最终将格式选项默认紫色改为中性色；交付构建再次编译与 lint 通过。先前聊天式首页截图仅留在 rejected-chat-layout，不作为最终验收。

## APK 与升级边界

交付：`releases/声屿-1.1.0-测试包.apk`

SHA-256：`0674179a72fca52bcc894f818bccba656b238f1bb7032fba89708573dd8da9a5`

本机调试证书 SHA-256：`f7ea4ea99032b3bd97f40a75c6bf1e2b59eb9f8e00d31dfbc1c481a8c69fc848`。
旧版报告证书：`46abb05ac593ce6ed15ed6bef535f8da83bd92ea959e0854da766cedc8c25584`。

两者不同，因此不能声明测试包可以直接覆盖真实手机上的旧版。正式覆盖升级需要原签名密钥；不要通过卸载旧版绕过，卸载会丢失应用内音频、转写和 Key。已在本次测试模拟器上使用同一本机证书覆盖安装，保留测试文件与设置。

## 未覆盖与真实限制

- 没有真实 MiniMax 凭据调用，识别质量、账户并发额度与真实网络未验收。
- 没有 OPPO、小米、vivo、华为实体设备；厂商流体云/胶囊未验收。API 36 模拟器验证了通知参数与标准通知，未验证 API 36.1 的系统提升结果。
- 长时间 Doze、厂商省电策略、系统强杀后的整条恢复流程、6 小时时限触发未实机验收。代码保存成功片段和任务记录；进程重启后需用户主动继续，不自动重传未确认请求。
- 本次没有把本地音频提取迁移为后台服务，也没有重复执行历史完整编解码矩阵。
- 原有完整 WAV 预解码、磁盘占用和 RIFF 大小限制仍存在；同时在转写的音频任务限一份，其内部切片最多 3 路。

官方能力核验：
- https://developer.android.com/develop/ui/views/notifications/live-update
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/background-work/services/fgs/timeout
- https://developer.android.com/develop/ui/compose/notifications/notification-permission

旧报告已保存在 docs/TEST_REPORT-1.0.2-HISTORICAL.md。
