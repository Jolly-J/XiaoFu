package dev.local.audio

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TranscriptionPresentationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            result += node
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }
    private fun awaitNodes(predicate: (List<AccessibilityNodeInfo>) -> Boolean): List<AccessibilityNodeInfo> {
        val end = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < end) {
            val current = nodes()
            if (predicate(current)) return current
            Thread.sleep(100)
        }
        error("Expected presentation controls not visible")
    }
    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun longTranscriptIsBoundedAndCopyRemainsAccessible() {
        val name = "presentation-${UUID.randomUUID()}"
        val audio = File(File(context.filesDir, "exports").apply { mkdirs() }, "$name.wav")
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(32036).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(32000).array()
        audio.outputStream().use { it.write(header); it.write(ByteArray(32000)) }
        val text = (1..250).joinToString("\n") { "第 $it 行：这是用于验证内部滚动和完整复制的测试文字。" } + "\n全文结束标记"
        val output = File(File(context.filesDir, "transcripts").apply { mkdirs() }, "$name.txt").apply { writeText(text) }
        val complete = File(output.path + ".complete").apply { writeText("complete") }
        try {
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(TranscriptionService.FILE, audio.name))
            val initial = awaitNodes { list -> list.any { it.contentDescription == "复制全文" } }
            val copyBefore = Rect().also { rect -> initial.first { it.contentDescription == "复制全文" }.getBoundsInScreen(rect) }
            val scroll = initial.filter { it.isScrollable }.minByOrNull { node -> Rect().also { node.getBoundsInScreen(it) }.height() }!!
            val body = Rect().also(scroll::getBoundsInScreen)
            assertTrue("Text body must be limited to 360dp", body.height() <= 360 * context.resources.displayMetrics.density + 3)
            assertTrue("Copy is above the scroll body", copyBefore.bottom <= body.top + 2)
            screenshot("transcript-bounded-before.png")
            assertTrue(scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            Thread.sleep(500)
            val after = awaitNodes { list -> list.any { it.contentDescription == "复制全文" } }
            var copyNode = after.first { it.contentDescription == "复制全文" }
            val copyAfter = Rect().also(copyNode::getBoundsInScreen)
            assertEquals("Inner scroll must not move the copy toolbar", copyBefore, copyAfter)
            while (!copyNode.isClickable && copyNode.parent != null) copyNode = copyNode.parent
            assertTrue(copyNode.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            Thread.sleep(300)
            var copied: String? = null
            instrumentation.runOnMainSync {
                copied = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
            }
            assertEquals("Copy must include the last line, not only the viewport", text, copied)
            screenshot("transcript-bounded-after.png")
        } finally {
            output.delete(); complete.delete(); audio.delete()
        }
    }
}
