package com.inkwise.music

/**
 * 应用全局入口 [Application]。
 *
 * 负责进程启动时的一次性初始化：MMKV 键值库、Coil 图片加载器（自动携带
 * Bearer token）、播放器管理器、同步播放管理器、恢复上次播放状态、
 * 指纹后台扫描与全局崩溃捕获。同时内置 IO 工具方法，以及崩溃日志
 * 记录与展示用的 [CrashHandler] / [CrashActivity]。
 */
import android.app.Activity
import android.app.Application
import android.content.*
import android.content.pm.PackageInfo
import android.content.res.Resources
import android.graphics.Typeface
import com.inkwise.music.ui.main.CoverFlightBootstrap
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
import com.inkwise.music.sync.SyncPlayManager
import com.tencent.mmkv.MMKV
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import dagger.hilt.EntryPoints
import dagger.hilt.android.HiltAndroidApp
import java.io.*
import java.lang.Thread.UncaughtExceptionHandler
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** 标注为 Hilt 注入的应用类，Dagger 将在此生成全局单例图。 */
@HiltAndroidApp
class MusicApp : Application() {
    /** 应用启动回调：按依赖顺序完成全局管理器初始化与状态恢复。 */
    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)

        // 从 Hilt 单例图中取应用级组件（Application 无法直接 @Inject 到需要它的管理器）
        val entryPoint = EntryPoints.get(this, MusicAppEntryPoint::class.java)
        // 设置全局 Coil ImageLoader，自动为图片请求添加 Bearer token
        coil.Coil.setImageLoader(entryPoint.imageLoader)

        MusicPlayerManager.init(this, entryPoint.prefsManager, entryPoint.audioEffectManager, entryPoint.streamCacheManager)

        // 初始化同步播放管理器：WebSocket 用长超时，适应弱网下的 NTP 时钟校准长连接
        val wsOkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
        SyncPlayManager.init(this, entryPoint.prefsManager, wsOkHttpClient)

        restoreSavedPlaybackState()
        entryPoint.fingerprintManager.startBackgroundScan()
        CrashHandler.instance.registerGlobal(this)
    }

    /**
     * 同步预解码当前歌封面（冷启动首拖的飞行封面兜底）。
     * 仅处理本地来源：albumArt 文件直接解码，缺失时读音频内嵌封面；
     * 网络封面不阻塞启动，走后续异步加载。
     */
    private fun decodeCoverSynchronously(song: com.inkwise.music.data.model.Song?) {
        if (song == null) return
        val albumArt = song.albumArt
        if (!albumArt.isNullOrBlank()) {
            if (albumArt.startsWith("http")) return
            runCatching { android.graphics.BitmapFactory.decodeFile(albumArt) }.getOrNull()?.let {
                CoverFlightBootstrap.cachedUri = albumArt
                CoverFlightBootstrap.cachedBitmap = it
                return
            }
        }
        val path = song.path.ifBlank { song.uri.removePrefix("file://") }
        if (path.isBlank() || path.startsWith("http") || !java.io.File(path).exists()) return
        runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(path)
            val bytes = retriever.embeddedPicture
            retriever.release()
            bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
        }.getOrNull()?.let {
            CoverFlightBootstrap.cachedUri = albumArt ?: path
            CoverFlightBootstrap.cachedBitmap = it
        }
    }

    /** 恢复上次退出时的播放队列与进度，让用户能继续收听。 */
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
                // 同步预解码当前歌封面：冷启动首拖（早于一切异步图片加载）也有图可飞
                decodeCoverSynchronously(songs.getOrNull(saved.currentIndex))
            }
        }
    }

    companion object {
        init {
            // 加载音频分析 JNI 库：音频指纹与节奏分析等能力依赖该原生库，注意名称不带 .so
            System.loadLibrary("audio_analyzer") // 注意不要 .so
        }

        /** 通用流拷贝工具：以 8KB 缓冲将输入流逐段复制到输出流。 */
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

        /** 将字节数组写入文件：自动创建父目录，便于写日志等场景。 */
        @Throws(IOException::class)
        fun write(
            file: File,
            data: ByteArray,
        ) {
            // 父目录不存在时先创建，避免 FileOutputStream 抛 FileNotFoundException
            file.parentFile?.takeIf { !it.exists() }?.mkdirs()
            ByteArrayInputStream(data).use { input ->
                FileOutputStream(file).use { output ->
                    write(input, output)
                }
            }
        }

        /** 将输入流整体读取为 UTF-8 字符串，用于读取 /proc/version 等文本文件。 */
        @Throws(IOException::class)
        fun toString(input: InputStream): String {
            ByteArrayOutputStream().use { output ->
                write(input, output)
                return output.toString(Charsets.UTF_8.name())
            }
        }

        /** 批量安全关闭可关闭资源：逐个 try-catch，单个失败不影响其余资源。 */
        fun closeIO(vararg closeables: Closeable?) {
            closeables.forEach {
                try {
                    it?.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    /**
     * 全局崩溃处理器。
     *
     * 注册为系统默认 UncaughtExceptionHandler 后，未捕获异常会先写入本地
     * 崩溃日志（含设备/系统/版本信息），再跳转到 [CrashActivity] 展示，
     * 随后结束进程；回退链路确保写入自身也失败时交给原默认处理器。
     */
    class CrashHandler private constructor() {
        companion object {
            /** 系统原有的默认异常处理器，用于回退转发。 */
            val DEFAULT_HANDLER: UncaughtExceptionHandler? =
                Thread.getDefaultUncaughtExceptionHandler()

            /** 单例：全局仅需要一个崩溃处理器。 */
            val instance: CrashHandler by lazy { CrashHandler() }
        }

        /** 把本类实现的异常处理器注册为全局默认。 */
        fun registerGlobal(
            context: Context,
            crashDir: String? = null,
        ) {
            Thread.setDefaultUncaughtExceptionHandler(
                UncaughtExceptionHandlerImpl(context.applicationContext, crashDir),
            )
        }

        /** 恢复系统默认异常处理器（一般用于退出时还原现场）。 */
        fun unregister() {
            Thread.setDefaultUncaughtExceptionHandler(DEFAULT_HANDLER)
        }

        /** 真正的异常处理实现：负责格式化日志、写盘并跳转崩溃展示页。 */
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
                    // 组装崩溃上下文并写入日志，随后尝试展示到独立 Activity
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

            /** 组装崩溃日志：时间、设备、系统、应用版本与内核信息 + 完整堆栈。 */
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

            /** 把崩溃日志写入外部缓存目录下的 crash/ 文件夹，文件名带时间戳便于排查。 */
            private fun writeLog(log: String) {
                val time = dateFormat.format(Date())
                val file = File(crashDirFile, "crash_$time.txt")
                try {
                    write(file, log.toByteArray(Charsets.UTF_8))
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }

            /** 读取内核版本信息（/proc/version），失败时降级为 "unknown"。 */
            private fun getKernel(): String =
                try {
                    MusicApp.toString(FileInputStream("/proc/version")).trim()
                } catch (e: Throwable) {
                    e.message ?: "unknown"
                }
        }
    }

    /** 崩溃展示页：全屏展示崩溃日志文本，支持复制与重启应用。 */
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

        /** 重启应用：拉起启动器主入口后结束当前崩溃页与进程。 */
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
            // 崩溃页不允许直接退出，返回键等同“重启应用”
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

        /** dp→px 换算：+0.5f 四舍五入，用于给崩溃日志文本计算内边距。 */
        private fun dp2px(dp: Float): Int {
            val scale = Resources.getSystem().displayMetrics.density
            return (dp * scale + 0.5f).toInt()
        }
    }
}
