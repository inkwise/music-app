package com.inkwise.music.ui.main

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import androidx.hilt.navigation.compose.hiltViewModel
import coil.ImageLoader
import coil.Coil
import coil.request.ImageRequest
import com.inkwise.music.data.prefs.PlayerThemeMode
import com.inkwise.music.ui.player.PlayerViewModel
import androidx.compose.ui.viewinterop.AndroidView
import com.inkwise.music.ui.theme.extractProminentColor
import com.inkwise.music.ui.theme.extractThemeColor
import com.inkwise.music.ui.theme.playerControlColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 椒盐 az1.smali 预置流光主题的"附加流光色"（ARGB，注意高位是 alpha）：
// 浅色流光 = 半透明白渐变（提亮画面）；深色流光 = 半透明黑渐变（压暗画面）
private val LIGHT_FLOWING_ADDITIONAL = intArrayOf(0x95FFFFFF.toInt(), 0x2AFFFFFF.toInt())
private val DARK_FLOWING_ADDITIONAL = intArrayOf(0x52000000, 0x1A000000)

/**
 * 播放页入口：整页背景 + 垂直两页结构。
 *
 *  - 背景：按"流光"设置二选一 —— 桌面版流光 FlowingLightView（View 1:1 移植）或
 *    安卓版封面模糊色块 SaltPlayerBackground；换歌时 AnimatedContent 交叉淡入淡出
 *  - 内容：VerticalPager 第 0 页为播放页主体 BottomDrawerContent、
 *    第 1 页为播放队列 PlayQueueBottomSheet（自底部上滑进入）
 *  - 主题色：从封面 280px 缩略图取色，900ms 缓动过渡，驱动播放页全部控件着色
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun playerScreen(
    modifier: Modifier = Modifier,
    pagerState: PagerState,
    sheetState: SheetState? = null,
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val playbackState by playerViewModel.playbackState.collectAsState()
    val currentSong = playbackState.currentSong
    val coverUri = currentSong?.albumArt

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 进程级单例：remember 只活在本次组合，进出播放页会反复新建 ImageLoader
    // （各自带内存缓存与线程池）且旧的从不 shutdown；复用单例避免资源堆积与重复请求
    val imageLoader = remember {
        Coil.imageLoader(context)
    }

    // ---------------- Pager 修复逻辑 ----------------
    // 修复惯性甩动后偶发"停在两页之间"：fling 结束（onPostFling）时若仍有页偏移未归零，
    // 主动动画吸附到目标页。该连接挂在下方 VerticalPager 的 nestedScroll 上。

    val fixStuckConnection =
        remember {
            object : NestedScrollConnection {
                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ): Velocity {
                    if (pagerState.currentPageOffsetFraction != 0f) {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.targetPage)
                        }
                    }
                    return super.onPostFling(consumed, available)
                }
            }
        }

    // 翻页手势参数：拖过页宽 8% 即吸附，采用无回弹的 spring 动画
    val flingBehavior =
        PagerDefaults.flingBehavior(
            state = pagerState,
            snapPositionalThreshold = 0.08f,
            snapAnimationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
        )

    // ---------------- 播放页背景模式（设置） ----------------
    // 播放页主题决定背景种类（封面模糊 / 浅色流光 / 深色流光）；
    // 动态流光开关控制流光是否流动；themeMode 仅用于"封面模糊背景"的明暗参数

    val prefsManager = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context,
            com.inkwise.music.data.prefs.PreferencesManagerEntryPoint::class.java,
        ).prefs()
    }
    val playerTheme by prefsManager.playerThemeMode.collectAsState(initial = PlayerThemeMode.COVER)
    val dynamicFlowingLight by prefsManager.dynamicFlowingLight.collectAsState(initial = false)
    val themeMode by prefsManager.themeMode.collectAsState(initial = com.inkwise.music.data.prefs.ThemeMode.SYSTEM)
    val darkMode = when (themeMode) {
        com.inkwise.music.data.prefs.ThemeMode.LIGHT -> false
        com.inkwise.music.data.prefs.ThemeMode.DARK -> true
        com.inkwise.music.data.prefs.ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val isFlowingTheme = playerTheme == PlayerThemeMode.LIGHT_FLOWING || playerTheme == PlayerThemeMode.DARK_FLOWING
    val isDarkFlowing = playerTheme == PlayerThemeMode.DARK_FLOWING

    // ---------------- 主题色（控件前景色） ----------------

    // 默认蓝色兜底，取色成功后更新（"封面模糊背景"控件色与封面原始主色共用）
    var themeColor by remember { mutableStateOf(Color(0xFF2196F3)) }
    // 封面原始主色（不压深）：流光主题下与预设基色 50/50 混合，控件随封面柔和变化
    var coverAccent by remember { mutableStateOf(Color(0xFF2196F3)) }
    // 前景目标色（对齐椒盐 PlayerViewModel.color 语义）：
    //  - 封面模糊背景 = 随封面取色，深色画布时控件用白色；
    //  - 流光主题 = 封面主色与椒盐预设基色（#FF282828 / 白）平均，随封面/背景切换而变化
    val foregroundTarget =
        when (playerTheme) {
            PlayerThemeMode.COVER -> if (darkMode) Color.White else themeColor
            PlayerThemeMode.LIGHT_FLOWING ->
                Color(playerControlColor(coverAccent.toArgb(), dark = false))
            PlayerThemeMode.DARK_FLOWING ->
                Color(playerControlColor(coverAccent.toArgb(), dark = true))
        }
    // animatedThemeColor 以 900ms 缓动跟随目标色，供全页控件使用（切主题时平滑过渡）
    val animatedThemeColor by animateColorAsState(
        targetValue = foregroundTarget,
        animationSpec = tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "ThemeColorAnimation",
    )

    // ---------------- 流光背景封面（FlowingLightView 1:1 移植） ----------------

    // 解码后的封面位图（280px 缩略图）：流光背景与安卓版模糊背景共用的输入
    var coverBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // 播放器可见性（P3 修复）：收起为迷你条时整个播放页只是 alpha(0)，
    // 用 Sheet 目标态驱动流光背景的渲染开关，避免折叠态持续全画布模糊/网格重绘。
    // 用 targetValue：开始拖拽展开即恢复渲染，开始收起即停
    val playerVisible = (sheetState?.targetValue ?: SheetValue.Expanded) == SheetValue.Expanded

    // ---------------- 取色与封面处理 ----------------
    // 换歌时：IO 线程用 Coil 解码 280px 缩略封面（禁用硬件位图并立即复制，
    // 避免 Coil 在 execute() 返回后回收 bitmap），取主题色后回主线程一次性
    // 更新主题色与封面位图，从而同时驱动背景与控件刷新。

    LaunchedEffect(coverUri) {
        if (coverUri == null) return@LaunchedEffect

        val bitmap = withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(coverUri)
                    .size(280)
                    .allowHardware(false)
                    .build()
                val result = imageLoader.execute(request)
                val source = (result.drawable as? BitmapDrawable)?.bitmap ?: return@withContext null
                // 立即复制: Coil 可能在 execute() 返回后回收 bitmap
                source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
            } catch (e: Exception) {
                null
            }
        } ?: return@LaunchedEffect

        withContext(Dispatchers.IO) {
            val controlColor = extractThemeColor(bitmap)
            val accent = extractProminentColor(bitmap)

            withContext(Dispatchers.Main) {
                themeColor = Color(controlColor)
                coverAccent = Color(accent)
                coverBitmap = bitmap
                // 同步给飞行封面作即时兜底图：高清共享位图未就绪时用 280px 先起飞
            }
        }
    }

    // ---------------- UI ----------------

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        // ---------- 背景（设置切换：桌面版流光 / 安卓版封面模糊） ----------

        // 外层按"播放页主题"切换（切主题时柔和过渡）。注意不按 coverUri 键控：
        // 流光 View 必须常驻，换歌的柔和过渡由其内部 500ms 交叉淡出完成，
        // 若随封面重建会先闪黑再猛变（椒盐无此现象）
        AnimatedContent(
            targetState = playerTheme,
            transitionSpec = {
                fadeIn(tween(600)) togetherWith fadeOut(tween(800))
            },
            label = "BackgroundTransition",
        ) { theme ->
            when (theme) {
                // 浅/深色流光：FlowingLightView（椒盐 rg 流光 View 移植）。
                // 附加色与动态开关均对齐椒盐 az1.smali 预置主题 / dynamic_flowing_light
                PlayerThemeMode.LIGHT_FLOWING,
                PlayerThemeMode.DARK_FLOWING,
                -> {
                    AndroidView(
                        factory = { ctx ->
                            FlowingLightView(ctx).apply {
                                setFps(30f)
                                setTopCornerRadius(0f)
                            }
                        },
                        update = { view ->
                            // 浅色流光对封面做雾白化（黑封面背景变白，椒盐 ou0 行为）；深色流光用原图
                            view.setCoverBrighten(!isDarkFlowing)
                            coverBitmap?.let { view.setArtwork(it) }
                            view.setRenderActive(playerVisible)
                            view.setDynamic(dynamicFlowingLight)
                            view.setFlowingLightAdditionalColors(
                                if (isDarkFlowing) DARK_FLOWING_ADDITIONAL else LIGHT_FLOWING_ADDITIONAL,
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // 封面模糊背景：随封面交叉淡入淡出（600/800ms），内部 2.5x 饱和 + 强模糊 + scrim
                PlayerThemeMode.COVER -> {
                    AnimatedContent(
                        targetState = coverUri,
                        transitionSpec = {
                            fadeIn(tween(600)) togetherWith fadeOut(tween(800))
                        },
                        label = "CoverBackgroundTransition",
                    ) { _ ->
                        SaltPlayerBackground(
                            coverBitmap = coverBitmap,
                            themeColor = themeColor,
                            darkMode = darkMode,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        // ---------- Pager 内容 ----------
        // 第 0 页：播放页主体；第 1 页：播放队列（上滑进入）。
        // beyondViewportPageCount = 1 预加载另一页，fixStuckConnection 兜底吸附。

        VerticalPager(
            state = pagerState,
            key = { it },
            modifier =
                Modifier
                    .fillMaxSize()
                    .nestedScroll(fixStuckConnection),
            beyondViewportPageCount = 1,
            flingBehavior = flingBehavior,
        ) { page ->

            when (page) {
                0 -> {
                    BottomDrawerContent(
                        pagerState = pagerState,
                        animatedThemeColor = animatedThemeColor,
                        sheetState = sheetState,
                    )
                }

                1 -> {
                    PlayQueueBottomSheet(
                        playerViewModel = playerViewModel,
                        pagerState = pagerState,
                    )
                }
            }
        }
    }
}
