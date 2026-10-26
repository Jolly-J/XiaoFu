# Android 9 / API28 AAC edit-list 兼容

真实API28探针读取同一2秒AAC MP4：系统给出encoder-delay=45158，默认解码仅89,908字节；将该异常delay/padding清零后180,224字节，约2.043秒，保留整段内容。对照日志在verification-1.0.1/eos-baseline/decode-delay.txt。

AOSP MPEG4Extractor曾使用电影timescale而非音轨timescale换算edit-list，且官方已有异常delay大于4096或负数时忽略该edit-list的保护：
https://android.googlesource.com/platform/frameworks/av/+/a4af050bab773a75c799bb9cbd886e3b83655daf/media/extractors/mp4/MPEG4Extractor.cpp
后续实现改用音轨timescale：
https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android11-d2-release/media/extractors/mp4/MPEG4Extractor.cpp

本应用1.0.1仅在 **SDK恰为28、AAC、encoder-delay为负或大于4096** 时忽略异常delay/padding。正常0–4096延迟、其他编码、API26/27/29及以上不变。无损AAC封装完全不改压缩音频包。此兼容不绕过加密检查。

忽略异常编辑信息可能保留几十毫秒编码器起始/末尾补样；不宣称采样级无缝剪辑。目标是避免已实测的一秒内容截断。未验证所有厂商Android9变体，不能将该平台兼容等同于全部设备验证。
