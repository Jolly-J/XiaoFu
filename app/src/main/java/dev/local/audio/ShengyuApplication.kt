package dev.local.audio

import android.app.Application
import java.io.File

class ShengyuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Runs once per process, before any Activity/ViewModel can start a source copy.
        // A process kill skips finally; only remove our own abandoned private-cache files.
        File(cacheDir, "source-staging").listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith("source_") && file.name.endsWith(".partial")) file.delete()
        }
    }
}
