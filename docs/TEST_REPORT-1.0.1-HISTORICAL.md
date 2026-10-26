# 验证记录 · 声屿 1.0.1

验证日期：2026-10-25 UTC。此版本修复1.0.0已确认缺陷，保留同签名，支持覆盖安装。

## 最终构建与静态检查：通过

- `assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest`：BUILD SUCCESSFUL，最后一次57秒。
- JVM单元测试：26/26，无跳过（22项音频/EOF/标志/平台延迟边界，4项分片/文字拼接）。
- Android lint：0 errors、8 warnings；为较新API属性在旧版忽略、固定依赖存在新版及SharedPreferences的KTX风格建议。未屏蔽错误或设baseline。
- `apksigner verify`：通过；Android Debug RSA2048/v2签名，证书SHA256与1.0.0一致。
- minSdk26、targetSdk35、versionCode2、versionName1.0.1。
- 清单权限为INTERNET及AndroidX内部动态receiver签名权限；没有相册全盘、存储全盘、麦克风、摄像头权限。
- 独立只读安全/边界复核未发现P0/P1；API Key不备份、不日志，不允许用户自定义服务器，不跟随API重定向。

## Android9（API28）软件模拟器：15/15仪器测试通过

最终同签名覆盖安装主APK、测试APK均`Success`；pm确认versionCode2/versionName1.0.1。最终全包结果`OK (15 tests)`，40.192秒。

覆盖：
- AAC原样提取M4A，逐包数据一致；官方Sintel52秒公开视频逐包一致。
- HEVC视频容器中提取AAC；无音轨明确失败；取消不保留半成品。
- AAC→WAV、Vorbis→WAV、Vorbis→AAC/M4A，包含时长/PCM字节数检查。
- API Key实际Keystore加密、替换、删除。
- 本机模拟HTTP：multipart字段/鉴权头、响应文字/时间戳、401/429、500重试、取消请求。
- 601秒音频自动两段；第二段失败后仅重传失败段，按顺序合并。
- M4A→本地WAV预处理→模拟转写的联动路径。

模拟服务器只监听本机，**未调用真实MiniMax、没有使用真实API Key、没有产生真实转写费用**。

## 缺陷复现与修复证据

1. 1.0.0真实Android回归为14项中11过3失败。AAC→WAV、Vorbis两条转换路径在EOS把`sampleFlags=-1`误当加密；探针实测time/track/size/flags/read都为-1。1.0.1先处理EOS，真实有效样本仍检查加密；原失败路径最终均通过。
2. API28旧MP4提取器把2秒AAC编码延迟读成45158，默认输出89,908字节，导致约1秒截断；异常延迟清除后180,224字节。兼容仅限SDK==28、AAC、异常delay负值或>4096，正常延迟/其他平台/其他编码不动。详见`docs/ANDROID28-AAC-COMPAT.md`。原PCM字节数断言未降低。

## 界面验证

已亲眼核对最终实际干净截图：
- 转音频、历史、设置三个固定入口；主页仅提取，不自动转写。
- 设置浅/深切换；选择深色后force-stop并重新启动，仍为深色，状态栏图标正确。
- Key窗口的dumpsys包含SECURE标志，截图被阻止；未输入真实Key。
- 通过系统Downloads选择公开Sintel视频，显示真实元数据；UI点击提取M4A后确实生成836,310字节文件，历史显示1个音频及可选“转为文字 · MiniMax”按钮。
- 从历史手动进入转写页，真实显示API Key未配置与配置按钮；未配置时不上传。
- “提取12%”过程截图未被当作成功证据；只有真实文件与历史成功条目出现后才确认完成。

跟随系统选项已代码/界面核对，但未执行动态切换系统主题的完整自动回归；播放听感、另存及分享目标应用也未逐一实测。

## 未验证与限制

- 用户原始16:12视频没有提供给开发环境，不能宣称已复测该具体文件，也不能据旧错误文字认定视频真的加密。
- 真实MiniMax账户权限、额度、地域连通性、识别质量未验证；本地模拟HTTP不替代真实服务测试。
- API28软件模拟器不是物理手机；未覆盖其他厂商/Android版本矩阵。无KVM导致System UI曾超时弹窗，点Wait后取得干净图；这不代表App本身ANR，也不构成性能基准。
- 预测返回手势动画、TalkBack、超大字体、超长视频、低存储/断电压力测试未全面覆盖。
- 解码依赖平台；首版单/双声道，输出M4A/WAV，不提供MP3编码。转写先生成完整临时WAV，需额外磁盘并受约4GiB限制。
- 分段接缝仍需校对；API28异常edit-list兼容可能保留几十毫秒codec补样，不宣称采样级无缝剪辑。
- 当前是个人试用Debug构建，非商店正式发布版本。自行重建源码会使用自己的签名，不能凭不同签名直接覆盖此APK。

证据在`verification-1.0.1/`，包含最终构建、JUnit、lint、签名、安装、版本、15项仪器日志及原版失败/探针对照。交付不含测试视频；`scripts/prepare-test-media.sh`可重建，许可见`test-media/SOURCES.md`。
