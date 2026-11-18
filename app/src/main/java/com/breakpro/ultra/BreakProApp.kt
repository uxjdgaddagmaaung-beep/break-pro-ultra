package com.breakpro.ultra

import android.app.Application
import android.util.Log
import com.breakpro.ultra.data.AppDatabase
import java.io.File

class BreakProApp : Application() {
    val db: AppDatabase by lazy { AppDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
    }

    /** Writes uncaught exceptions to filesDir/logs/crash.log (crash recovery groundwork). */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val dir = File(filesDir, "logs").apply { mkdirs() }
                File(dir, "crash.log").appendText(
                    "${System.currentTimeMillis()} ${t.name}\n${Log.getStackTraceString(e)}\n\n"
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }
}
