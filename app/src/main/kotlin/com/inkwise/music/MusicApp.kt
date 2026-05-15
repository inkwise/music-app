package com.inkwise.music
import android.app.Activity
import android.app.Application
import android.content.*
import android.content.pm.PackageInfo
import android.content.res.Resources
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import com.inkwise.music.di.MusicAppEntryPoint
import com.inkwise.music.player.MusicPlayerManager
import com.tencent.mmkv.MMKV
import dagger.hilt.EntryPoints
import dagger.hilt.android.HiltAndroidApp
import java.io.*
import java.lang.Thread.UncaughtExceptionHandler
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@HiltAndroidApp
class MusicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)

        val entryPoint = EntryPoints.get(this, MusicAppEntryPoint::class.java)
        // 设置全局 Coil ImageLoader，自动为图片请求添加 Bearer token
        coil.Coil.setImageLoader(entryPoint.imageLoader)

        MusicPlayerManager.init(this, entryPoint.prefsManager, entryPoint.audioEffectManager)
        restoreSavedPlaybackState()
        entryPoint.fingerprintManager.startBackgroundScan()
        CrashHandler.instance.registerGlobal(this)
    }

    private fun restoreSavedPlaybackState() {
        val entryPoint = EntryPoints.get(this, MusicAppEntryPoint::class.java)
        val prefs = entryPoint.prefsManager
        val songDao = entryPoint.songDao

        runBlocking {
            // 恢复缓存的 token 供图片加载器等同步读取
            prefs.restoreCachedToken(prefs.authToken.first())
            prefs.restorePlaybackPreferences()
            val saved = prefs.savedPlaybackState.first()
            if (saved.queueIds.isEmpty()) return@runBlocking
            val songs = saved.queueIds.mapNotNull { songDao.getSongById(it) }
            if (songs.isNotEmpty()) {
                MusicPlayerManager.restorePlaybackState(songs, saved.currentIndex, saved.lastPosition)
            }
        }
    }

    companion object {
        init {
            System.loadLibrary("audio_analyzer") // 注意不要 .so
        }

        @Throws(IOException::class)
        fun write(
            input: InputStream,
            output: OutputStream,
        ) {
            val buf = ByteArray(8 * 1024)
            var len: Int
            while (input.read(buf).also { len = it } != -1) {
                output.write(buf, 0, len)
            }
        }

        @Throws(IOException::class)
        fun write(
            file: File,
            data: ByteArray,
        ) {
            file.parentFile?.takeIf { !it.exists() }?.mkdirs()
            ByteArrayInputStream(data).use { input ->
                FileOutputStream(file).use { output ->
                    write(input, output)
                }
            }
        }

        @Throws(IOException::class)
        fun toString(input: InputStream): String {
            ByteArrayOutputStream().use { output ->
                write(input, output)
                return output.toString(Charsets.UTF_8.name())
            }
        }

        fun closeIO(vararg closeables: Closeable?) {
            closeables.forEach {
                try {
                    it?.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    class CrashHandler private constructor() {
        companion object {
            val DEFAULT_HANDLER: UncaughtExceptionHandler? =
                Thread.getDefaultUncaughtExceptionHandler()

            val instance: CrashHandler by lazy { CrashHandler() }
        }

        fun registerGlobal(
            context: Context,
            crashDir: String? = null,
        ) {
            Thread.setDefaultUncaughtExceptionHandler(
                UncaughtExceptionHandlerImpl(context.applicationContext, crashDir),
            )
        }

        fun unregister() {
            Thread.setDefaultUncaughtExceptionHandler(DEFAULT_HANDLER)
        }

        private class UncaughtExceptionHandlerImpl(
            private val context: Context,
            crashDir: String?,
        ) : UncaughtExceptionHandler {
            private val dateFormat: DateFormat =
                SimpleDateFormat("yyyy_MM_dd-HH_mm_ss", Locale.getDefault())

            private val crashDirFile: File =
                if (crashDir.isNullOrEmpty()) {
                    File(context.externalCacheDir, "crash")
                } else {
                    File(crashDir)
                }

            override fun uncaughtException(
                thread: Thread,
                throwable: Throwable,
            ) {
                try {
                    val log = buildLog(throwable)
                    writeLog(log)

                    try {
                        val intent =
                            Intent(context, CrashActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra(Intent.EXTRA_TEXT, log)
                            }
                        context.startActivity(intent)
                    } catch (e: Throwable) {
                        e.printStackTrace()
                        writeLog(e.toString())
                    }

                    throwable.printStackTrace()
                    android.os.Process.killProcess(android.os.Process.myPid())
                    System.exit(0)
                } catch (e: Throwable) {
                    DEFAULT_HANDLER?.uncaughtException(thread, throwable)
                }
            }

            private fun buildLog(t: Throwable): String {
                val time = dateFormat.format(Date())

                var versionName = "unknown"
                var versionCode = 0L
                try {
                    val info: PackageInfo =
                        context.packageManager.getPackageInfo(context.packageName, 0)
                    versionName = info.versionName ?: "unknown"
                    versionCode =
                        if (Build.VERSION.SDK_INT >= 28) {
                            info.longVersionCode
                        } else {
                            info.versionCode.toLong()
                        }
                } catch (_: Throwable) {
                }

                val head =
                    linkedMapOf(
                        "Time Of Crash" to time,
                        "Device" to "${Build.MANUFACTURER}, ${Build.MODEL}",
                        "Android Version" to "${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})",
                        "App Version" to "$versionName ($versionCode)",
                        "Kernel" to getKernel(),
                        "Support Abis" to
                            if (Build.VERSION.SDK_INT >= 21) {
                                Build.SUPPORTED_ABIS?.contentToString() ?: "unknown"
                            } else {
                                "unknown"
                            },
                        "Fingerprint" to Build.FINGERPRINT,
                    )

                return buildString {
                    head.forEach { (k, v) ->
                        append(k).append(" :    ").append(v).append('\n')
                    }
                    append('\n')
                    append(Log.getStackTraceString(t))
                }
            }

            private fun writeLog(log: String) {
                val time = dateFormat.format(Date())
                val file = File(crashDirFile, "crash_$time.txt")
                try {
                    write(file, log.toByteArray(Charsets.UTF_8))
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }

            private fun getKernel(): String =
                try {
                    MusicApp.toString(FileInputStream("/proc/version")).trim()
                } catch (e: Throwable) {
                    e.message ?: "unknown"
                }
        }
    }

    class CrashActivity : Activity() {
        private var logText: String? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            setTheme(android.R.style.Theme_DeviceDefault)
            title = "App Crash"

            logText = intent.getStringExtra(Intent.EXTRA_TEXT)

            val scrollView = ScrollView(this).apply { isFillViewport = true }
            val hScroll = HorizontalScrollView(this)

            val textView =
                TextView(this).apply {
                    val padding = dp2px(16f)
                    setPadding(padding, padding, padding, padding)
                    text = logText
                    setTextIsSelectable(true)
                    typeface = Typeface.DEFAULT
                    linksClickable = true
                }

            hScroll.addView(textView)
            scrollView.addView(
                hScroll,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

            setContentView(scrollView)
        }

        private fun restart() {
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(it)
            }
            finish()
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(0)
        }

        override fun onBackPressed() {
            restart()
        }

        override fun onCreateOptionsMenu(menu: Menu): Boolean {
            menu
                .add(0, android.R.id.copy, 0, android.R.string.copy)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            return true
        }

        override fun onOptionsItemSelected(item: MenuItem): Boolean {
            if (item.itemId == android.R.id.copy) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(packageName, logText))
                return true
            }
            return super.onOptionsItemSelected(item)
        }

        private fun dp2px(dp: Float): Int {
            val scale = Resources.getSystem().displayMetrics.density
            return (dp * scale + 0.5f).toInt()
        }
    }
}
