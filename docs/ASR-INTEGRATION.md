# MiniMax ASR 集成边界

核对日期：2026-10-25。

官方来源：
- https://solutions.minimaxi.com/debug/asr （国内调试页 api.minimaxi.com）
- https://platform.minimax.cn/docs/api-reference/speech-to-text （当前文档 api.minimax.cn）

使用官方调试页固定 HTTPS 端点 `https://api.minimaxi.com/v1/speech_to_text`，不接受用户自定义服务器、不跟随重定向。multipart 字段：model=asr-1.0、response_format=verbose_json、timestamp_level=word、stream=false。Authorization Bearer 只发给该主机。响应 `segments[].start/end` 以秒计。

官方限制 500 秒、50 MB；应用先在本地生成 PCM16 WAV，按不超过450秒和45,000,000字节双上限切片，每个接缝重叠1秒。字/词按全局时间戳中点归属到互不重叠的核心区间，按顺序合并。英文相邻词补空格，中文字符直接相连。不同分片的模型识别可能不同，接缝仍需人工校对；不承诺自动转写逐字无误。服务若没有返回时间戳则保留完整分段文本并提示可能重复，不擅自删词。

每片一个异步任务，最多 3 路并发；完成顺序不影响原音频顺序。成功响应在私有 noBackupFilesDir 按音频 SHA256 和段编号原子保存。单段失败不取消其他段，全部任务结束后汇总失败；重试只请求未缓存段。只有所有分段成功才提交全文与 complete 标志，用户不会拿到缺段的“最终结果”。旧版不完整文本也不再作为全文展示。已完成全文再次打开直接读取，不重复请求。

上传按待上传音频的实际字节比例显示，解码采用真实解码进度；等待服务端识别和整理全文时使用不定进度动画。多段同时显示已完成段数，不再将单段的 0/1 显示成静止的 0% 识别进度。取消会断开连接，但已发送请求仍可能由服务处理或计费。

转写由 TranscriptionService 承接，与 Activity/ViewModel 生命周期解耦。dataSync 前台服务、常驻进度通知、按需通知授权、受限 PARTIAL_WAKE_LOCK；通知可返回任务或取消。支持标准系统实时活动提升请求，旧系统回落普通通知。中断时保存任务状态，重进手动恢复。后台服务有系统时间上限，不承诺无限保活。页面与服务的音频路径统一为 canonicalPath，避免 /data/user/0 与 /data/data 别名产生两份任务状态。

API Key 由用户在App填写，用Android Keystore AES-GCM加密后存在noBackupFilesDir，不写日志、不硬编码、不包含在导出文件。音频提取不联网；只有用户确认转写才上传所选音频分片。服务费用、保留策略由用户的MiniMax账户与服务条款决定。

实现限制：系统解码器须支持输入音轨；临时完整WAV需要额外磁盘，标准RIFF限制约4GB，超长音频可能需先在外部裁短。原始音频不修改。默认仅单/双声道，媒体兼容性依设备。真实MiniMax识别未测试（没有用户API Key）；本地模拟HTTP测试不能证明服务账户权限、额度、实际识别质量或地域连通性。
