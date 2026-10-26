package dev.local.audio

import androidx.annotation.DrawableRes
import java.io.File

enum class AsrProvider(
    val id: String,
    val displayName: String,
    val modelDisplayName: String,
    val featureTag: String,
    val billingTip: String,
    val tutorialSummary: String,
    @DrawableRes val markRes: Int,
    @DrawableRes val logoRes: Int,
    val websiteUrl: String,
    val keyDocUrl: String
) {
    SILICON_FLOW(
        id = "siliconflow",
        displayName = "硅基流动",
        modelDisplayName = "SenseVoiceSmall",
        featureTag = "免费",
        billingTip = "免费调用，无需充值费用",
        tutorialSummary = "前往官网注册登录，在「API 密钥」中创建 Key 即可免费使用。",
        markRes = R.drawable.ic_siliconflow_mark,
        logoRes = R.drawable.ic_siliconflow_logo,
        websiteUrl = "https://cloud.siliconflow.cn",
        keyDocUrl = "https://cloud.siliconflow.cn/account/ak"
    ),
    MINIMAX(
        id = "minimax",
        displayName = "MiniMax",
        modelDisplayName = "Speech-01",
        featureTag = "更好",
        billingTip = "需在官网自充值，约 2.5 元/小时",
        tutorialSummary = "前往官网注册并创建「接口密钥」，需在账户充值余额（约 2.5 元/小时）后使用。",
        markRes = R.drawable.ic_minimax_mark,
        logoRes = R.drawable.ic_minimax_logo,
        websiteUrl = "https://www.minimaxi.com",
        keyDocUrl = "https://platform.minimaxi.com/user-center/basic-information/interface-key"
    );

    companion object {
        fun fromId(id: String?): AsrProvider =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: SILICON_FLOW
    }
}

data class AsrCapabilities(
    val textOnly: Boolean,
    val supportsServerProgress: Boolean,
    val supportsTimestamps: Boolean,
    val maxFileDurationSeconds: Long,
    val maxFileSizeBytes: Long
)

interface SpeechToTextProvider {
    val provider: AsrProvider
    val capabilities: AsrCapabilities
    suspend fun transcribe(
        audio: File,
        key: String,
        onProgress: (TranscriptionProgress) -> Unit
    ): TranscriptResult
}
