# 验证记录 · 声屿 1.0.2

**本记录对应冻结1.0.2测试版，明确保留一项旧平台格式限制。** 用户原始16:12视频未提供，不能宣称已在该文件或用户手机上复测成功。没有真实MiniMax Key，未调用真实付费服务。

## 冻结候选

- versionCode3 / versionName1.0.2；minSdk26 / targetSdk35。
- 最终候选主APK SHA256：`b4e70674b1c11511e2775bd413f08853eef1e13efd9f2e096b9b3958e533c520`。
- `apksigner verify`通过v2签名，RSA2048证书SHA256 `46abb05ac593ce6ed15ed6bef535f8da83bd92ea959e0854da766cedc8c25584`，与1.0.0/1.0.1相同，覆盖升级无需卸载。
- 仪器APK SHA256：`74ffd41e4752a51fd284e3cd08ae0d26f6babbc383fbe19dacc01783696866d3`。
- 完整构建：`assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest` 成功；冻结构建55秒。
- JVM：43/43，0失败/0跳过（Engine29、Diagnostics10、Transcription4）。
- lint：0 errors、11 warnings，未抑制新增API错误或设baseline。一次中间构建发现fcntlInt仅API30，已替换为公开的可中断FileChannel方案再通过全构建。

## Android 15 / API35 官方 AOSP ATD：实际设备回归

最终候选短矩阵：**36/36通过，0失败、0跳过，277.431秒**。

涵盖：
- 合法负首PTS及恰好-1µs、非负对照；AAC完整包数与逐包SHA256、时间跨度保持。
- AAC-LC/H264、HEVC（不解视频）、fragmented MP4、MOV、多音轨确定性选择第一条、Opus WebM、MP3音轨MP4、Vorbis及Sintel公开素材。
- 真HE-AAC/HE-AACv2：平台encoder生成，ffprobe确认profile并独立完整解码；HEv2 extractor核心22050Hz mono与实际decoder44100Hz stereo分别核对，输出断言仍严格双声道44100Hz/时长，不以放宽标准获得通过。
- Provider可seek已知length、offset4096、未知length；不可seek pipe成功、offset+length精确复制、空流、提前EOF、可靠pipe错误不误成功、停滞取消与缓存清理。
- 真Keystore写/替换/删除；本地模拟HTTP multipart、鉴权、401/429/500重试、取消、601秒分片、只重试失败片段、有序拼接、M4A本地解码接模拟转写。没有真实MiniMax请求。

最终SHA的972秒长项：**通过，OK (1 test)，0跳过，1345.821秒**。WAV44100Hz双声道，PCM171462656字节=972.0105215秒；M4A包数/hash/跨度/时长通过，输出及暂存清理正常。API35最终合计37项通过。前一候选长项结果单独保留，不混入最终候选计数。

## Android 9 / API28 最终候选

短矩阵：**36项实际运行，35通过、1兼容失败、0跳过**，没有将失败改为skip或全通过。唯一失败为MP3音轨封装在MP4：独立原生probe返回0轨/NO_AUDIO，系统日志明确 `MP3 track in MP4/3GPP file is not supported`。同一SHA素材经host ffprobe有H264+MP3/44100Hz双声道，API35读取2轨并两输出完成，因此属于已验证的平台组合限制，保留失败测试及原始日志。

其余AAC、HE/HEv2、负PTS对照、全部pipe模式及原有mockASR/Key等35项通过。API28原生探针也补齐：相同AAC三种edit-list素材首包均time0，default encoder-delay45158；API35同字节首包分别-23219/-1/0。不同版本行为不再凭推断。

最终SHA的972秒长项：**OK (1 test)，580.404秒，0跳过**。WAV44100Hz双声道PCM171466752字节=972.0337415秒（旧平台priming差异）；M4A包/hash/时长通过。最终API28合计36/37通过、1已知平台兼容失败，0skip。启动清理实测source_stale.partial删除、keep.txt内容保留，旧版Sintel成功历史文件仍保留。

## 最终候选实际界面与系统文件工作流

已亲眼检查最终APK截图并核对文件证据：
- 转音频/历史/设置三入口；主页只提取，历史手动转文字，Key为空时不上传。
- 选择浅色后完整重启仍保留；跟随系统实际深浅切换。系统切换瞬间状态栏曾延迟刷新，稳定后图标恢复正确。
- 系统Downloads选择无音轨文件，真实错误卡显示；实际点击复制诊断，从系统Clipboard读取并JSON解析为1.0.2/API28/content/NO_AUDIO_TRACK，无文件名、URI、路径或Key。
- 通过系统Downloads选择AAC视频，分别M4A与WAV“提取完成”，产物20829/180268字节；历史新增两条，原Sintel历史仍保留。
- 两格式都通过系统另存到Downloads，私有原件与另存副本SHA逐一相同，ffprobe与完整解码成功（44100Hz mono、2.043356秒）。
- 历史点击转文字，显示MiniMax上传/计费说明和API Key未配置态，未发生真实API请求。

- 稳定后的系统深/浅主题截图状态栏图标对比正确。
- Key空对话框实际窗口SECURE标志有效，截图被阻止；Back正常退出，没有输入真实Key。
- WAV实际播放进入停止按钮状态，NuPlayer解码90112帧并EOS；软件模拟器不等于人工听音验证。

截图及原始窗口/媒体日志位于`round3/api28/ui/`。

## 确定缺陷与对照

1. 1.0.0在EOS先检查flags=-1，误报加密；1.0.1修复但错误地把负时间当EOS。
2. API35真实原生探针：首包time=-23219（另一fixture恰好-1），track1/read258/flags1均合法；真正EOS才track/read等为-1。冻结1.0.1在API35真正进入函数后，同一SHA素材的M4A/WAV两项均实测复现“音轨没有可读取的音频数据”；最终1.0.2同两项通过（证据`atd35/baseline-aot-after-final/`），不是启动ANR推断。1.0.2以track/read判断EOS，nullable首时间锚点保留负首包并整体归零。
3. 合法非seekable pipe真实setDataSource失败；仅OS lseek==ESPIPE时本地暂存。可seek未知length不复制，权限/解析错误不兜底。
4. 保留仅SDK28/AAC/异常delay范围触发的旧平台edit-list兼容，其他平台/正常delay不改。

详见 `docs/NEGATIVE-PTS-1.0.2.md` 与 `docs/ANDROID28-AAC-COMPAT.md`。

## 测试环境问题不混作App结果

无KVM，模拟器使用软件CPU仿真。重型Google API35未正常完成框架启动；切换官方ATD后可启动。首次APK冷启动在DexFileVerifier/ZIP校验中超过系统启动期限被ANR杀死，尚未进入JUnit；对安装包执行官方 `cmd package compile -m speed -f` 后正常执行。保留原始日志，不把提前退出算通过。

测试Provider早期独立进程缺Kotlin runtime，改为纯Java后重跑；只有修复测试环境后的pipe异常才是App真实缺口。round2共31项中29通过2失败，随后修复真实pipe和HEv2测试源/输出语义，最终round3全36通过。ATD不用于SystemUI/预测返回动画结论。

## 隐私与边界

- 视频仅本地处理；转写必须用户确认上传音频到固定MiniMax域名。
- Key使用Android Keystore与不备份目录，密码输入与安全窗口，无内置Key，无敏感日志。
- 诊断只存最近3次内存白名单元数据，手动复制；不含Key、URI、路径、文件名、个人媒体或原始异常。
- pipe暂存保留64MiB空间、8GiB/30分钟上限、30秒空闲超时，可取消；成功/失败清理，进程重启清理本应用遗留source_*.partial。普通可seek文件无该暂存上限。
- 解码依赖平台；输出M4A/WAV，不提供MP3编码；支持单/双声道，传统WAV约4GiB上限。
- 转写先生成完整临时WAV，需额外空间；取消不能撤回已上传内容或保证免计费。
- 前台交互任务，无后台常驻服务；进程被系统回收会中断任务，已有导出和成功转写片段保留。
- Debug个人试用版，不是商店生产发布；模拟器不等于用户真机或所有厂商兼容。

原始证据在 `verification-1.0.2/`。测试视频不打包，合成脚本与官方平台HE探针在 `compatibility-tests/`，公开视频来源在 `test-media/SOURCES.md`。

独立最终只读复核未发现新增阻断，涵盖EOS/DRM、FileChannel取消、AFD边界、隐私诊断、启动清理；删除缓存为best-effort，OS权限/存储异常仍可能遗留私有缓存。

系统分享chooser已真实打开并取消返回，未选择接收方、未发送媒体。全部模拟器已停止，无后台设备测试。完整设备复核见 `verification-1.0.2/FINAL-DEVICE-VERIFICATION.md`。
