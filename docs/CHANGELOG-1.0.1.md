# 1.0.1

- 修复真实Android MediaExtractor在EOS返回全部`-1`时，WAV及AAC转码路径误报加密。
- 正常EOS与`readSampleData < 0`均先处理；有效样本仍验证加密标志，未放宽DRM边界。
- 主页仅转音频；底部固定“转音频/历史/设置”。历史成功项按需转文字，不自动上传。
- 设置提供持久化系统/浅/深主题与Keystore加密Key管理。
- 新增7个EOS/加密标志单测与M4A→WAV预处理→本地mock转写联动仪器用例。
- 保留同签名，versionCode=2，支持覆盖1.0.0。

证据和当前验证结果见根目录TEST_REPORT.md。用户的原始16:12视频未提供给开发环境，不能宣称已对该具体文件复测。未调用真实MiniMax服务。
