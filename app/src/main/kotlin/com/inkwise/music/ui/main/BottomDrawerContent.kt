package com.inkwise.music.ui.main

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.media.MediaExtractor
import android.media.MediaFormat
import android.view.WindowManager
import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.bumptech.glide.Glide
import com.inkwise.music.R
import com.inkwise.music.data.model.LyricsSource
import com.inkwise.music.data.model.PlayMode
import com.inkwise.music.ui.main.navigationPage.local.formatTime
import com.inkwise.music.audio.BeatDetector
import com.inkwise.music.data.prefs.CoverDisplayMode
import com.inkwise.music.data.prefs.ParticleEffect
import com.inkwise.music.data.prefs.PlayerThemeMode
import com.inkwise.music.data.prefs.PreferencesManager
import com.inkwise.music.data.prefs.PreferencesManagerEntryPoint
import com.inkwise.music.ui.effect.ParticleEffectOverlay
import com.inkwise.music.ui.main.navigationPage.components.ArtistText
import com.inkwise.music.ui.theme.isColorDark
import com.inkwise.music.ui.player.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放页主体（底部抽屉内容区），自上而下分为五段：
 * 顶部歌名/歌手行 → 中部三页横向 Pager（沉浸设置页 / 封面页 / 歌词页）→
 * 进度条 → 上一首/播放/下一首控制 → 底部五个功能按钮（播放模式/定时/音效/队列/菜单）。
 *
 * 交互行为：
 *  - 横向滑动在中部三页间切换（已关闭系统过界拉伸光效）
 *  - 沉浸模式开启且抽屉完全展开时隐藏系统栏；沉浸模式下顶部/进度/控制区整体隐藏
 *  - 沉浸动效开启时封面页被全屏粒子动效取代
 *  - 定时按钮弹出睡眠定时器面板，音效按钮跳转音效页，队列按钮滑动外层 Pager
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BottomDrawerContent(
    pagerState: PagerState,
    animatedThemeColor: Color,
    sheetState: SheetState? = null,
    playerViewModel: PlayerViewModel = hiltViewModel(),
    mainViewModel: MainViewModel = hiltViewModel(),
) {
    val playbackState by playerViewModel.playbackState.collectAsState()
    val currentSong = playbackState.currentSong
    val pageCount = 3
    val coverUri = currentSong?.albumArt
    val scope = rememberCoroutineScope()
    var showSleepSheet by remember { mutableStateOf(false) }
    // 内部横向 Pager 状态：初始页 1（封面页），共 3 页（0 沉浸设置 / 1 封面 / 2 歌词）。
    // 与参数 pagerState（外层 VerticalPager，用于"播放页 ↔ 播放队列"上下切换）是两套独立状态
    val pagerStateB =
        rememberPagerState(
            initialPage = 1,
            pageCount = { pageCount },
        )

    val context = LocalContext.current
    val view = LocalView.current

    // 播放器可见性（P2/P4 修复）：Sheet 目标态为展开才视为可见。
    // 用 targetValue 而非 currentValue：开始拖拽展开即恢复渲染/时钟，
    // 开始收起即停掉，避免收起动画期间 alpha 渐隐却仍在全速空转。
    val playerVisible = (sheetState?.targetValue ?: SheetValue.Expanded) == SheetValue.Expanded
    val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
        context,
        PreferencesManagerEntryPoint::class.java,
    )
    val prefsManager = entryPoint.prefs()
    val particleEffect by prefsManager.particleEffect.collectAsState(initial = ParticleEffect.NONE)
    val coverDisplayMode by prefsManager.coverDisplayMode.collectAsState(initial = CoverDisplayMode.SQUARE)
    val immersiveMode by prefsManager.immersiveMode.collectAsState(initial = false)
    val immersiveEffect by prefsManager.immersiveEffect.collectAsState(initial = false)
    // 封面页歌词（CoverLyricsView）前景色：跟随播放页背景的深浅流光（椒盐式黑/白前景）。
    // 流光主题下由主题决定（浅色流光=深色字，深色流光=白色字）；封面模糊背景沿用系统深浅
    val themeMode by prefsManager.themeMode.collectAsState(initial = com.inkwise.music.data.prefs.ThemeMode.SYSTEM)
    val playerTheme by prefsManager.playerThemeMode.collectAsState(initial = PlayerThemeMode.COVER)
    val coverLyricsDark = when {
        playerTheme == PlayerThemeMode.LIGHT_FLOWING -> false
        playerTheme == PlayerThemeMode.DARK_FLOWING -> true
        else -> when (themeMode) {
            com.inkwise.music.data.prefs.ThemeMode.LIGHT -> false
            com.inkwise.music.data.prefs.ThemeMode.DARK -> true
            com.inkwise.music.data.prefs.ThemeMode.SYSTEM -> isSystemInDarkTheme()
        }
    }
    // 定时器
    val sleepRemaining by playerViewModel.sleepRemaining.collectAsState()

    // 沉浸模式：播放器展开且在全屏沉浸时隐藏系统栏，折叠时恢复
    LaunchedEffect(immersiveMode, sheetState?.currentValue) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowInsetsControllerCompat(window, view)
        val isExpanded = sheetState?.currentValue == SheetValue.Expanded
        if (immersiveMode && isExpanded) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 分享链接已创建，弹出系统分享
    val shareLink by playerViewModel.shareLinkResult.collectAsState()
    LaunchedEffect(shareLink) {
        shareLink?.let { url ->
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, "分享一首歌给你: $url")
                type = "text/plain"
            }
            val shareIntent = Intent.createChooser(sendIntent, "分享歌曲")
            context.startActivity(shareIntent)
            playerViewModel.clearShareLinkResult()
        }
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        // ---------- 横屏：椒盐式两栏 —— 左「封面 + 单行歌词」/ 右「歌名/进度/控制」 ----------
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(if (immersiveEffect) Modifier.background(Color.Black) else Modifier)
                    .then(if (!immersiveMode) Modifier.statusBarsPadding() else Modifier)
                    .padding(horizontal = 28.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左栏：封面占满可用高度（正方形），下方单行歌词（椒盐横屏同款）
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 记录封面实测宽度（正方形宽=高），歌词与封面同宽且同列居中 → 左缘对齐
                var coverSizePx by remember { mutableStateOf(0f) }
                val density = LocalDensity.current
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier =
                            Modifier
                                // 封面 = 屏高约 72%（椒盐横屏实测 263dp），略小于封面区避免顶满
                                .fillMaxHeight(0.92f)
                                .aspectRatio(1f)
                                .onSizeChanged { coverSizePx = it.width.toFloat() }
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        AndroidView(
                            modifier = Modifier.matchParentSize(),
                            factory = { ctx ->
                                ImageView(ctx).apply {
                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                }
                            },
                            update = { imageView ->
                                val uri = coverUri
                                if (uri != null) {
                                    Glide.with(imageView).load(uri).into(imageView)
                                } else {
                                    imageView.setImageDrawable(null)
                                }
                            },
                        )
                        if (coverUri == null) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                CoverLyricsView(
                    viewModel = playerViewModel,
                    // 横屏歌词前景跟随背景亮度（椒盐式）：封面模糊/深色流光 = 白字，
                    // 仅浅色流光用深字（实测椒盐横屏歌词为纯白 254,253,253）
                    darkMode = playerTheme != PlayerThemeMode.LIGHT_FLOWING,
                    visibleLines = 1,
                    // 宽度 = 封面宽度，与封面同列居中 → 歌词左缘与封面左缘对齐
                    modifier =
                        (if (coverSizePx > 0f) {
                            Modifier.width(with(density) { coverSizePx.toDp() })
                        } else {
                            Modifier.fillMaxWidth()
                        })
                            .height(24.dp),
                )
            }

            Spacer(Modifier.width(28.dp))

            // 右栏：歌名/歌手 → 进度/时间 → 播放控制 → 五按钮（垂直弹性分布）
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
            ) {
                Spacer(Modifier.weight(0.5f))
                if (!immersiveMode) {
                    Text(
                        text = currentSong?.title ?: "",
                        color = animatedThemeColor,
                        style =
                            MaterialTheme.typography.titleLarge.copy(
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    currentSong?.let { song ->
                        ArtistText(
                            artist = song.artist,
                            artistIds = song.artistIds,
                            onArtistClick = { mainViewModel.navigateToArtist(it) },
                            color = animatedThemeColor.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    PlayerProgressSection(
                        playbackState = playbackState,
                        playerViewModel = playerViewModel,
                        animatedThemeColor = animatedThemeColor,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                    PlaybackControlsRow(
                        isPlaying = playbackState.isPlaying,
                        playerViewModel = playerViewModel,
                        animatedThemeColor = animatedThemeColor,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                Spacer(Modifier.weight(1.2f))
                PlayerBottomActions(
                    playbackState = playbackState,
                    playerViewModel = playerViewModel,
                    mainViewModel = mainViewModel,
                    animatedThemeColor = animatedThemeColor,
                    sleepRemaining = sleepRemaining,
                    onShowSleepSheet = { showSleepSheet = true },
                    onShowQueue = {
                        scope.launch { pagerState.animateScrollToPage(1) }
                    },
                    // 横屏五按钮居中排列、固定 18dp 间距（椒盐实测中心距 66dp），不拉满整行
                    centered = true,
                )
            }
        }
    } else {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .then(if (immersiveEffect) Modifier.background(Color.Black) else Modifier)
                .then(if (!immersiveMode) Modifier.statusBarsPadding() else Modifier)
                // 椒盐实测(b.jpg,1272x2800 / 1dp=3.5px):左右边线 x108(31dp);
                // 顶部标题字形顶 y231(状态栏 40dp + 24.5dp);底部五按钮槽底 y2692(30.5dp)
                .padding(start = 31.dp, end = 31.dp, top = 24.5.dp, bottom = 30.5.dp),
    ) {
        // ---------- 顶部：歌名/歌手 + 分享按钮 ----------
        if (!immersiveMode) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 标题 + 歌手（左侧，左对齐）
                // 椒盐实测:歌名字形高 88px → 28sp,副标题 41px → 13sp;两行字形间隙仅 3px,
                // 故行高均收紧为 1:1,副标题用 offset 上提对齐(y321)
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        text = currentSong?.title ?: "墨迹",
                        color = animatedThemeColor,
                        style =
                            MaterialTheme.typography.titleLarge.copy(
                                fontSize = 28.sp,
                                lineHeight = 28.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    currentSong?.let { song ->
                        ArtistText(
                            artist = song.artist,
                            artistIds = song.artistIds,
                            onArtistClick = { mainViewModel.navigateToArtist(it) },
                            color = animatedThemeColor,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 13.sp,
                                lineHeight = 13.sp,
                            ),
                            maxLines = 1,
                            modifier = Modifier.offset(y = (-1.5).dp),
                        )
                    } ?: Text(
                        text = "@inkwise",
                        color = animatedThemeColor,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.sp,
                            lineHeight = 13.sp,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.offset(y = (-1.5).dp),
                    )
                }

                Spacer(Modifier.width(8.dp))

                // 右上「广播」图标（椒盐歌词页右上同位置：17x15dp，底边与歌名字形底对齐）
                Icon(
                    imageVector = BroadcastIcon,
                    contentDescription = "音效",
                    tint = animatedThemeColor,
                    modifier =
                        Modifier
                            .size(width = 17.dp, height = 15.dp)
                            .offset(y = (-1.5).dp)
                            .clickable { mainViewModel.navigateToAudioEffect() },
                )

                // 分享按钮（右侧，仅云端歌曲显示）
                if (currentSong?.cloudId != null) {
                    IconButton(
                        onClick = { playerViewModel.shareSong() },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = "分享",
                            tint = animatedThemeColor,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }

        // ---------- 中间：左右切换页面 ----------
        CompositionLocalProvider(
            LocalOverscrollConfiguration provides null, // ❗关键
        ) {
            HorizontalPager(
                state = pagerStateB,
                // 封面页常驻组合（beyondViewportPageCount = 1）：
                // 飞行封面转场需要大封面的实测边界（endBounds）在折叠态就有效，
                // 若按需组合，折叠期间封面页无布局节点 → 终点锚点为 0 →
                // 拖拽后段锚点才就位，飞行动画中途才启用（观感瞬移）。
                // 页面重活已有门控：歌词页逐帧时钟由 isPageActive 控制、
                // 沉浸设置/粒子特效由 playerVisible 控制，常驻组合不会空转。
                // 页面间距 = 左右页边距之和（28dp×2）：相邻页初始时完全在屏幕外，
                // 滑动时下一页从「屏幕右缘」滑入，而不是紧贴当前页边缘闪现；
                // 滑到中点时两页之间也有可见间隔，不再粘在一起
                beyondViewportPageCount = 1,
                pageSpacing = 56.dp,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                userScrollEnabled = true, // 👈 明确打开
            ) { page ->
                when (page) {
                    0 -> {
                        // 歌曲信息页（椒盐式：快捷开关网格 + 音频信息 + 专辑 + 艺术家）
                        SongInfoPage(
                            playbackState = playbackState,
                            playerViewModel = playerViewModel,
                            mainViewModel = mainViewModel,
                            animatedThemeColor = animatedThemeColor,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    1 -> {
                        // 封面页
                        if (immersiveEffect) {
                            // 节拍/频谱订阅只在「沉浸动效开启且播放器可见」时建立（P4 修复）：
                            // 两条流按帧发射，此前在播放页顶层无条件 collect，
                            // 粒子特效为 NONE 也让整页 60fps 重组
                            val beatIntensity = if (playerVisible) {
                                BeatDetector.beatIntensity.collectAsState().value
                            } else 0f
                            val frequencyBands = if (playerVisible) {
                                BeatDetector.frequencyBands.collectAsState().value
                            } else emptyList()
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                ParticleEffectOverlay(
                                    effect = particleEffect,
                                    coverDisplayMode = coverDisplayMode,
                                    beatIntensity = beatIntensity,
                                    frequencyBands = frequencyBands,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        } else {
                            // 椒盐 iy1 式封面布局：BoxWithConstraints 内由一个 spring 驱动的
                            // 单标量（top padding）定位封面，尺寸为静态公式值——
                            // 封面的移动完全由布局求解产生（与椒盐实现原理一致）
                            BoxWithConstraints {
                                // 尺寸公式（椒盐原样）：边长 = clamp(maxWidth-60dp, 0, maxHeight-44dp)
                                val coverSide =
                                    minOf(maxWidth - 60.dp, maxHeight - 44.dp).coerceAtLeast(0.dp)
                                // 歌词区高度（椒盐原样）：空间足够 68dp，否则 24dp
                                val lyricsHeight = if (maxHeight - 86.dp > coverSide) 68.dp else 24.dp
                                // top padding 目标（椒盐原样）：(maxHeight - 边长 - 歌词区) / 3
                                val targetTop = ((maxHeight - coverSide - lyricsHeight) / 3f).value
                                // 唯一的动画：临界阻尼 spring(dampingRatio=1, stiffness=350) 驱动该标量
                                val animatedTop by animateFloatAsState(
                                    targetValue = targetTop,
                                    animationSpec = spring(dampingRatio = 1f, stiffness = 350f),
                                    label = "coverTopPadding",
                                )

                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    // 封面：padding(top=animated) + defaultMinSize + aspectRatio + Box(Center)
                                    Box(
                                        modifier =
                                            Modifier
                                                .padding(top = animatedTop.dp)
                                                .defaultMinSize(minWidth = coverSide, minHeight = coverSide)
                                                .aspectRatio(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        AndroidView(
                                            modifier = Modifier.matchParentSize(),
                                            factory = { context ->
                                                ImageView(context).apply {
                                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                                }
                                            },
                                            update = { imageView ->
                                                val uri = coverUri
                                                if (uri != null) {
                                                    Glide.with(imageView).load(uri).into(imageView)
                                                } else {
                                                    imageView.setImageDrawable(null)
                                                }
                                            },
                                        )

                                        if (coverUri == null) {
                                            Icon(
                                                imageVector = Icons.Default.MusicNote,
                                                contentDescription = null,
                                                modifier = Modifier.size(32.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }

                                        // effect=NONE 时 overlay 直接 return（零渲染），
                                        // 传零值即可，避免为死分支订阅按帧发射的节拍流
                                        ParticleEffectOverlay(
                                            effect = ParticleEffect.NONE,
                                            coverDisplayMode = coverDisplayMode,
                                            beatIntensity = 0f,
                                            frequencyBands = emptyList(),
                                            modifier = Modifier.matchParentSize(),
                                        )
                                    }

                                    // 歌词区域（椒盐原样高度 68/24dp）：CoverLyricsView 三行、靠左，
                                    // 宽度与封面同宽（封面已水平居中，歌词左缘与封面左缘对齐）
                                    Spacer(modifier = Modifier.height(lyricsHeight / 3))
                                    val coverWidthDp = coverSide
                                    CoverLyricsView(
                                        viewModel = playerViewModel,
                                        darkMode = coverLyricsDark,
                                        modifier =
                                            Modifier
                                                .width(coverWidthDp)
                                                .height(lyricsHeight),
                                    )
                                }
                            }
                        }
                    }

                    2 -> {
                        // 歌词页
                        LyricsPage(
                            playerViewModel = playerViewModel,
                            animatedThemeColor = animatedThemeColor,
                            // 仅在本页为当前页且播放器展开时驱动逐帧时钟（P2 修复）
                            isPageActive = playerVisible && pagerStateB.currentPage == 2,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        // ---------- 进度条 ----------
        // 椒盐实测:歌词来源行底 y2074 → 进度条容器顶 y2141,间隙 19dp
        if (!immersiveMode) {
            PlayerProgressSection(
                playbackState = playbackState,
                playerViewModel = playerViewModel,
                animatedThemeColor = animatedThemeColor,
                modifier = Modifier.padding(top = 19.dp),
            )
        }

        // ---------- 播放控制 ----------
        // 椒盐实测:时间行槽底 y2249 → 52dp 槽顶 y2281.5(9.3dp);
        // 槽底 y2464 → 五按钮槽顶 y2525(17.4dp)
        if (!immersiveMode) {
            PlaybackControlsRow(
                isPlaying = playbackState.isPlaying,
                playerViewModel = playerViewModel,
                animatedThemeColor = animatedThemeColor,
                modifier = Modifier.padding(top = 9.dp, bottom = 17.dp),
            )
        }

        // ---------- 底部五按钮 ----------
        // 椒盐实测:五图标中心距 66dp(48dp 槽 + 18dp 间距整体居中),槽底 y2692
        if (!immersiveMode) {
            PlayerBottomActions(
                playbackState = playbackState,
                playerViewModel = playerViewModel,
                mainViewModel = mainViewModel,
                animatedThemeColor = animatedThemeColor,
                sleepRemaining = sleepRemaining,
                onShowSleepSheet = { showSleepSheet = true },
                onShowQueue = {
                    scope.launch { pagerState.animateScrollToPage(1) }
                },
                centered = true,
            )
        }
    }
    }

    // 睡眠定时器面板：由底部"定时"按钮触发；确认后启动倒计时（可选"播完当前歌后退出"并结束应用）
    if (showSleepSheet) {
        SleepTimerBottomSheet(
            onDismiss = { showSleepSheet = false },
            onConfirm = { minutes, stopAfterSong ->
                playerViewModel.startSleepTimer(
                    minutes = minutes,
                    stopAfterSong = stopAfterSong,
                ) {
                    (context as? Activity)?.finishAffinity()
                }
                showSleepSheet = false
            },
        )
    }
}

/**
 * 进度条 + 时间行（竖屏/横屏共用）。
 * 拖动过程中暂存目标位置，松手后才真正 seek，避免高频 HTTP seek 导致 ANR。
 *
 * 竖屏垂直节奏（椒盐 b.jpg 实测）：轨道 y2155-2162、时间字形 y2206-2235（12sp、
 * 分钟两位补零）；调用方通过 [modifier] 控制与上方区块的间隙。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerProgressSection(
    playbackState: com.inkwise.music.data.model.PlaybackState,
    playerViewModel: PlayerViewModel,
    animatedThemeColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        var dragFraction by remember { mutableStateOf<Float?>(null) }

        // 时间格式与椒盐一致：分钟两位补零（"00:15" / "05:01"）
        fun formatTimePadded(millis: Long): String {
            val totalSeconds = millis / 1000
            return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60)
        }

        Box(
            modifier = Modifier.fillMaxWidth().height(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Slider(
                value =
                    dragFraction ?: if (playbackState.duration > 0) {
                        playbackState.currentPosition.toFloat() / playbackState.duration
                    } else {
                        0f
                    },
                onValueChange = { progress ->
                    dragFraction = progress
                },
                onValueChangeFinished = {
                    val target = dragFraction
                    dragFraction = null
                    if (target != null && playbackState.duration > 0) {
                        playerViewModel.seekTo((target * playbackState.duration).toLong())
                    }
                },
                colors =
                    SliderDefaults.colors(
                        activeTrackColor = animatedThemeColor, // 已播放部分的进度条颜色
                        inactiveTrackColor = animatedThemeColor.copy(alpha = 0.24f), // 未播放部分的背景色
                        thumbColor = animatedThemeColor, // 滑块颜色
                        activeTickColor = Color.Transparent, // 隐藏刻度线
                        inactiveTickColor = Color.Transparent,
                    ),
                thumb = {},
                track = { sliderState ->
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier.height(2.dp), // 让进度条更纤细
                        colors =
                            SliderDefaults.colors(
                                activeTrackColor = animatedThemeColor,
                                inactiveTrackColor = animatedThemeColor.copy(alpha = 0.2f),
                            ),
                        thumbTrackGapSize = 0.dp,
                        drawStopIndicator = null,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 椒盐实测:轨道 Box 底 y2176 → 时间字形顶 y2206(12sp 字形居中于 16sp 行,
                // 槽顶 y2193,即间隙 5dp)
                .padding(top = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatTimePadded(playbackState.currentPosition),
                style = MaterialTheme.typography.bodySmall,
                color = animatedThemeColor,
            )
            Text(
                formatTimePadded(playbackState.duration),
                style = MaterialTheme.typography.bodySmall,
                color = animatedThemeColor,
            )
        }
    }
}

/**
 * 上一首/播放/下一首 控制行（竖屏/横屏共用）。
 * 椒盐 b.jpg 实测:切歌键字形 70px → 40dp 图标,暂停字形 76x92px → 29dp 图标;
 * 相邻按钮中心距 266px ≈ 76dp → 48/52/48dp 槽 + 26dp 间距;垂直间隙由调用方传入。
 */
@Composable
private fun PlaybackControlsRow(
    isPlaying: Boolean,
    playerViewModel: PlayerViewModel,
    animatedThemeColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { playerViewModel.skipToPrevious() },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_round_skip_previous_24),
                contentDescription = "上一首",
                tint = animatedThemeColor,
                modifier = Modifier.size(40.dp),
            )
        }

        Spacer(Modifier.width(26.dp))

        IconButton(
            onClick = { playerViewModel.playPause() },
            modifier = Modifier.size(52.dp),
        ) {
            Icon(
                painter =
                    painterResource(
                        id =
                            if (isPlaying) {
                                R.drawable.ic_mini_player_pause
                            } else {
                                R.drawable.ic_mini_player_play
                            },
                    ),
                null,
                modifier = Modifier.size(29.dp),
                tint = animatedThemeColor,
            )
        }

        Spacer(Modifier.width(26.dp))

        IconButton(
            onClick = { playerViewModel.skipToNext() },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_round_skip_next_24),
                contentDescription = "下一首",
                tint = animatedThemeColor,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

/** 底部五按钮：播放模式/定时/音效/队列/菜单（竖屏/横屏共用）。 */
@Composable
private fun PlayerBottomActions(
    playbackState: com.inkwise.music.data.model.PlaybackState,
    playerViewModel: PlayerViewModel,
    mainViewModel: MainViewModel,
    animatedThemeColor: Color,
    sleepRemaining: Long?,
    onShowSleepSheet: () -> Unit,
    onShowQueue: () -> Unit,
    centered: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement =
            if (centered) {
                Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)
            } else {
                Arrangement.SpaceBetween
            },
    ) {
        IconButton(onClick = { playerViewModel.togglePlayMode() }) {
            Icon(
                painter =
                    when (playbackState.playMode) {
                        PlayMode.LIST -> painterResource(id = R.drawable.ic_player_circle)
                        PlayMode.SINGLE -> painterResource(id = R.drawable.ic_player_repeat_one)
                        PlayMode.SHUFFLE -> painterResource(id = R.drawable.ic_player_random)
                    },
                contentDescription = "播放模式",
                tint = animatedThemeColor,
                modifier = Modifier.size(24.dp),
            )
        }
        // 循环模式按钮
        Column {
            IconButton(onClick = onShowSleepSheet) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_sleep_timer),
                    contentDescription = "定时",
                    tint = animatedThemeColor,
                    modifier = Modifier.size(24.dp),
                )
            }
            sleepRemaining?.let { millis ->

                val totalSeconds = millis / 1000
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60

                Text(
                    text = "$minutes:$seconds",
                    style = MaterialTheme.typography.bodyMedium,
                    color = animatedThemeColor,
                )
            }
        }

        IconButton(onClick = { mainViewModel.navigateToAudioEffect() }) {
            Icon(
                painter = painterResource(id = R.drawable.ic_audio_effect),
                contentDescription = "音效",
                tint = animatedThemeColor,
                modifier = Modifier.size(24.dp),
            )
        }
        IconButton(onClick = onShowQueue) {
            Icon(
                painter = painterResource(id = R.drawable.ic_play_queue),
                contentDescription = "播放队列",
                tint = animatedThemeColor,
                modifier = Modifier.size(24.dp),
            )
        }
        IconButton(onClick = { /* 菜单 */ }) {
            Icon(
                painter = painterResource(id = R.drawable.ic_player_more),
                contentDescription = "菜单",
                tint = animatedThemeColor,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Pager 第 0 页：歌曲信息页（对齐椒盐音乐左滑页）。
 * 2×2 快捷开关网格（屏幕常亮/沉浸模式/Original/DLNA）→ 音频信息卡 → 出自专辑卡 →
 * 参与创作的艺术家卡。卡片为白色 10% 圆角容器，前景白色系。
 * "播放界面保持屏幕常亮"真实生效（FLAG_KEEP_SCREEN_ON）；其余开关暂为占位。
 */
@Composable
fun SongInfoPage(
    playbackState: com.inkwise.music.data.model.PlaybackState,
    playerViewModel: PlayerViewModel,
    mainViewModel: MainViewModel,
    animatedThemeColor: Color,
    modifier: Modifier = Modifier,
) {
    val currentSong = playbackState.currentSong
    val context = LocalContext.current

    // ── 屏幕常亮开关（真实生效） ──
    var keepScreenOn by remember { mutableStateOf(false) }
    LaunchedEffect(keepScreenOn) {
        (context as? Activity)?.window?.let { window ->
            if (keepScreenOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // ── 音频信息：声道数/码率由 MediaExtractor 读取（IO 线程） ──
    var channelCount by remember { mutableStateOf<Int?>(null) }
    var bitrateKbps by remember { mutableStateOf<Int?>(null) }
    var formatName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentSong?.id) {
        val song = currentSong ?: return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            try {
                val path = song.path.ifBlank {
                    val uri = song.uri
                    if (uri.startsWith("file://")) android.net.Uri.parse(uri).path else null
                } ?: return@withContext null
                if (!java.io.File(path).exists()) return@withContext null
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(path)
                    var ch: Int? = null
                    var br: Int? = null
                    var mime: String? = null
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val trackMime = format.getString(MediaFormat.KEY_MIME)
                        if (trackMime?.startsWith("audio/") == true) {
                            mime = trackMime
                            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                            if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                                br = format.getInteger(MediaFormat.KEY_BIT_RATE) / 1000
                            }
                            break
                        }
                    }
                    Triple(ch, br, mime)
                } finally {
                    extractor.release()
                }
            } catch (_: Exception) {
                null
            }
        }
        channelCount = result?.first
        bitrateKbps = result?.second
        // MIME → 可读格式名（椒盐式"FLAC format stream"）
        formatName = result?.third?.let { mime ->
            when (mime.removePrefix("audio/").lowercase()) {
                "flac" -> "FLAC"
                "mpeg" -> "MP3"
                "aac", "mp4a-latm" -> "AAC"
                "opus" -> "OPUS"
                "vorbis" -> "VORBIS"
                "x-ms-wma", "ms-wma" -> "WMA"
                "ape" -> "APE"
                "dsf", "dsd" -> "DSD"
                "ogg" -> "OGG"
                "wav", "x-wav" -> "WAV"
                else -> mime.substringAfter('/').uppercase()
            }
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(10.dp))

        // ---------- 2×2 快捷开关网格 ----------
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            InfoToggleCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.WbSunny,
                label = "播放界面保持屏幕常亮",
                selected = keepScreenOn,
                onClick = { keepScreenOn = !keepScreenOn },
            )
            InfoToggleCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Spa,
                label = "沉浸模式",
                selected = false,
                onClick = { },
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            InfoToggleCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Equalizer,
                label = "Original",
                selected = false,
                onClick = { },
            )
            InfoToggleCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Cast,
                label = "DLNA (beta)",
                selected = false,
                onClick = { },
            )
        }

        Spacer(Modifier.height(20.dp))

        // ---------- 音频信息卡片 ----------
        InfoSectionCard {
            Text(
                "音频信息",
                color = animatedThemeColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            // 优先用可读格式名（MediaExtractor MIME 映射），codec 原始值含非可读内容时跳过
            val rawCodec = currentSong?.codec?.takeIf {
                it.isNotBlank() && it.matches(Regex("[A-Za-z0-9 ]+")) && !it.contains("CodecType", true)
            }
            val format = formatName ?: rawCodec
            val codecLine = format?.let { "$it format stream" } ?: "AUDIO format stream"
            Text(
                codecLine,
                color = animatedThemeColor.copy(alpha = 0.6f),
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(6.dp))
            val parts = buildList {
                channelCount?.let { add("$it Channels") }
                currentSong?.sampleRate?.takeIf { it > 0 }?.let { add("$it Hz") }
                bitrateKbps?.let { add("$it kbps") }
                currentSong?.bitDepth?.takeIf { it > 0 }?.let { add("${it}-bit") }
            }
            if (parts.isNotEmpty()) {
                Text(
                    parts.joinToString("    "),
                    color = animatedThemeColor.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---------- 出自专辑卡片 ----------
        InfoSectionCard {
            Text(
                "出自专辑",
                color = animatedThemeColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(animatedThemeColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        modifier = Modifier.matchParentSize(),
                        factory = { ctx ->
                            ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                        },
                        update = { imageView ->
                            val uri = currentSong?.albumArt
                            if (uri != null) {
                                Glide.with(imageView).load(uri).into(imageView)
                            } else {
                                imageView.setImageDrawable(null)
                            }
                        },
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = currentSong?.album ?: "",
                        color = animatedThemeColor,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "未知专辑艺术家",
                        color = animatedThemeColor.copy(alpha = 0.55f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---------- 参与创作的艺术家卡片 ----------
        InfoSectionCard {
            Text(
                "参与创作的艺术家",
                color = animatedThemeColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(50))
                            .background(animatedThemeColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        modifier = Modifier.matchParentSize(),
                        factory = { ctx ->
                            ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                        },
                        update = { imageView ->
                            val uri = currentSong?.albumArt
                            if (uri != null) {
                                Glide.with(imageView).load(uri).into(imageView)
                            } else {
                                imageView.setImageDrawable(null)
                            }
                        },
                    )
                }
                Spacer(Modifier.width(14.dp))
                currentSong?.let { song ->
                    ArtistText(
                        artist = song.artist,
                        artistIds = song.artistIds,
                        onArtistClick = { mainViewModel.navigateToArtist(it) },
                        color = animatedThemeColor,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                        maxLines = 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
    }
}

/** 快捷开关格子（2×2 网格单元）：图标 + 文字，选中态蓝色填充白字（椒盐式）。 */
@Composable
private fun InfoToggleCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) Color(0xFF2196F3) else Color.White.copy(alpha = 0.10f))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 信息分组卡片：白色 10% 圆角容器 + 内边距（椒盐式）。 */
@Composable
private fun InfoSectionCard(
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.10f))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        content = content,
    )
}

/**
 * Pager 第 2 页：歌词页（全屏逐字卡拉OK歌词 + 底部信息条）。
 *  - 歌词字号/字重/是否居中三项从持久化偏好读取，经 LyricsSettingsSheet 修改
 *  - 底部信息条：左侧显示歌词来源（本地 LRC/KRC、内嵌、网络、用户），点击弹出歌词设置；
 *    右侧在歌词带翻译时显示翻译开关
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsPage(
    playerViewModel: PlayerViewModel,
    animatedThemeColor: Color,
    isPageActive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val lyricsState by playerViewModel.lyricsState.collectAsState()
    val context = LocalContext.current
    val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
        context, PreferencesManagerEntryPoint::class.java)
    val prefsManager = entryPoint.prefs()

    // 是否存在至少一行带翻译：决定"翻译"开关是否显示，以及翻译行是否真正渲染
    val hasTranslation = lyricsState.lyrics?.lines?.any { it.translation != null } ?: false
    var showTranslation by remember { mutableStateOf(true) }

    // 从持久化读取歌词设置
    val fontSize by prefsManager.lyricsFontSize.collectAsState(initial = 24)
    val fontWeight by prefsManager.lyricsFontWeight.collectAsState(initial = 700)
    val isCentered by prefsManager.lyricsCentered.collectAsState(initial = true)

    var showLyricsSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        // 歌词主体：占满剩余高度，交给 LyricsView 做全屏逐字卡拉OK渲染
        Box(modifier = Modifier.weight(1f)) {
            LyricsView(
                viewModel = playerViewModel,
                animatedThemeColor = animatedThemeColor,
                showTranslation = showTranslation && hasTranslation,
                isPageActive = isPageActive,
                modifier = Modifier.fillMaxSize(),
                fontSize = fontSize,
                fontWeight = FontWeight(fontWeight),
                isCentered = isCentered,
            )
        }

        // 不加水平内缩：让"本地 LRC/翻译"控制条与歌词文本、顶部歌名保持同一条 31dp 页边线；
        // 行高收紧为 0（椒盐实测行 y2013-2074 仅内容高 62px，上下间隙由相邻区块决定）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 歌词来源标识（椒盐式）：18dp 圆角描边「词」图标 + 大写英文来源，点击弹出歌词设置
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { showLyricsSheet = true },
            ) {
                Box(
                    modifier =
                        Modifier
                            // 椒盐实测:词图标框 62px ≈ 17.7dp 方,细描边 ~1dp,圆角 ~3dp
                            .size(18.dp)
                            .border(
                                1.dp,
                                animatedThemeColor.copy(alpha = 0.75f),
                                RoundedCornerShape(3.dp),
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "词",
                        fontSize = 10.sp,
                        color = animatedThemeColor.copy(alpha = 0.85f),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = lyricsState.lyrics?.source?.let {
                        when (it) {
                            LyricsSource.LOCAL_LRC -> "LRC FILE"
                            LyricsSource.LOCAL_KRC -> "KRC FILE"
                            LyricsSource.EMBEDDED -> "EMBEDDED"
                            LyricsSource.NETWORK -> "NETWORK"
                            LyricsSource.USER_PROVIDED -> "USER"
                        }
                    } ?: "",
                    // 椒盐实测:EMBEDDED 大写字形高 30px ≈ 12sp(与时间行同字号)
                    fontSize = 12.sp,
                    color = animatedThemeColor.copy(alpha = 0.6f),
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            if (hasTranslation) {
                // 音译开关（椒盐 b.jpg 实测:方块 58px ≈ 17dp 正方,圆角 ~4dp,
                // 内"文A"字形 ~40px ≈ 11dp,右缘贴 31dp 页边线）——
                //  关闭 = 透明底 + 0.8dp 细描边（控件色 20% 透明度）+ 控件色图标；
                //  开启 = 控件色填充 + 反色图标
                val onColor = if (isColorDark(animatedThemeColor)) Color.White else Color.Black
                Box(
                    modifier = Modifier
                        .size(17.dp)
                        .then(
                            if (showTranslation) {
                                Modifier.background(animatedThemeColor, RoundedCornerShape(4.dp))
                            } else {
                                Modifier.border(
                                    width = 0.8.dp,
                                    color = animatedThemeColor.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(4.dp),
                                )
                            },
                        )
                        .clickable { showTranslation = !showTranslation },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_translation),
                        contentDescription = "音译",
                        tint = if (showTranslation) onColor else animatedThemeColor,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
    }

    if (showLyricsSheet) {
        LyricsSettingsSheet(
            fontSize = fontSize,
            fontWeight = fontWeight,
            isCentered = isCentered,
            onFontSizeChange = { scope.launch { prefsManager.setLyricsFontSize(it) } },
            onFontWeightChange = { scope.launch { prefsManager.setLyricsFontWeight(it) } },
            onCenteredChange = { scope.launch { prefsManager.setLyricsCentered(it) } },
            onDismiss = { showLyricsSheet = false },
        )
    }
}

/**
 * 歌词设置面板：字号（12~48）、字重（100~900）、歌词居中三项。
 * 面板自身不持有状态，所有改动通过回调上抛并写回持久化偏好。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSettingsSheet(
    fontSize: Int,
    fontWeight: Int,
    isCentered: Boolean,
    onFontSizeChange: (Int) -> Unit,
    onFontWeightChange: (Int) -> Unit,
    onCenteredChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text("歌词设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))

            // 字体大小
            Text("字体大小: ${fontSize}sp", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Slider(
                value = fontSize.toFloat(),
                // 12~48 共 36 档（steps=35），每档 1sp，取整为整数 sp
                onValueChange = { onFontSizeChange(it.toInt()) },
                valueRange = 12f..48f,
                steps = 35,
            )

            Spacer(Modifier.height(16.dp))

            // 字体粗细
            Text("字体粗细: $fontWeight", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Slider(
                value = fontWeight.toFloat(),
                // 字重只在百位整数值生效（100/200/…/900），拖动时向下取整到百位
                onValueChange = { onFontWeightChange((it.toInt() / 100) * 100) },
                valueRange = 100f..900f,
                steps = 7,
            )

            Spacer(Modifier.height(16.dp))

            // 对齐方式
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("歌词居中", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                Switch(checked = isCentered, onCheckedChange = onCenteredChange)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * 椒盐播放页右上「广播」图标的矢量复刻（b.jpg 实测 60x52px ≈ 17x15dp）：
 * 中央实心圆点 + 左右各两条弧线（内弧 r6.8 / 外弧 r10.2，跨 ±55°，圆头描边）。
 */
private val BroadcastIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Broadcast",
        defaultWidth = 24.dp,
        defaultHeight = 21.dp,
        viewportWidth = 24f,
        viewportHeight = 21f,
    ).apply {
        // 中央圆点：center(12, 10.5) r2.6
        path(fill = SolidColor(Color.Black)) {
            moveTo(14.6f, 10.5f)
            arcTo(2.6f, 2.6f, 0f, false, true, 9.4f, 10.5f)
            arcTo(2.6f, 2.6f, 0f, false, true, 14.6f, 10.5f)
            close()
        }
        // 左右各两条弧线：cos55°≈0.574 / sin55°≈0.819
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.1f,
            strokeLineCap = StrokeCap.Round,
        ) {
            // 右内弧 r6.8
            moveTo(15.9f, 4.93f)
            arcTo(6.8f, 6.8f, 0f, false, true, 15.9f, 16.07f)
            // 右外弧 r10.2
            moveTo(17.85f, 2.14f)
            arcTo(10.2f, 10.2f, 0f, false, true, 17.85f, 18.86f)
            // 左内弧
            moveTo(8.1f, 4.93f)
            arcTo(6.8f, 6.8f, 0f, false, false, 8.1f, 16.07f)
            // 左外弧
            moveTo(6.15f, 2.14f)
            arcTo(10.2f, 10.2f, 0f, false, false, 6.15f, 18.86f)
        }
    }.build()
}

