package com.inkwise.music.ui.main

import android.app.Activity
import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.inkwise.music.data.prefs.PreferencesManager
import com.inkwise.music.data.prefs.PreferencesManagerEntryPoint
import com.inkwise.music.ui.effect.ParticleEffectOverlay
import com.inkwise.music.ui.main.navigationPage.components.ArtistText
import com.inkwise.music.ui.player.PlayerViewModel
import kotlinx.coroutines.launch

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
    val pagerStateB =
        rememberPagerState(
            initialPage = 1,
            pageCount = { pageCount },
        )

    val context = LocalContext.current
    val view = LocalView.current

    // 粒子动效数据
    val beatIntensity by BeatDetector.beatIntensity.collectAsState()
    val frequencyBands by BeatDetector.frequencyBands.collectAsState()
    val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
        context,
        PreferencesManagerEntryPoint::class.java,
    )
    val prefsManager = entryPoint.prefs()
    val particleEffect by prefsManager.particleEffect.collectAsState(initial = ParticleEffect.NONE)
    val coverDisplayMode by prefsManager.coverDisplayMode.collectAsState(initial = CoverDisplayMode.SQUARE)
    val immersiveMode by prefsManager.immersiveMode.collectAsState(initial = false)
    val immersiveEffect by prefsManager.immersiveEffect.collectAsState(initial = false)
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

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .then(if (immersiveEffect) Modifier.background(Color.Black) else Modifier)
                .then(if (!immersiveMode) Modifier.statusBarsPadding() else Modifier)
                // .padding(horizontal = 16.dp)
                // .padding(bottom = 16.dp)
                .padding(28.dp), // ,
    ) {
        // ---------- 顶部：歌名 / 歌手 ----------
        if (!immersiveMode) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = currentSong?.title ?: "墨迹",
                    color = animatedThemeColor,
                    style =
                        MaterialTheme.typography.titleLarge.copy(
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
                        color = animatedThemeColor,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1
                    )
                } ?: Text(
                    text = "@inkwise",
                    color = animatedThemeColor,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // ---------- 中间：左右切换页面 ----------
        CompositionLocalProvider(
            LocalOverscrollConfiguration provides null, // ❗关键
        ) {
            HorizontalPager(
                state = pagerStateB,
                beyondViewportPageCount = 2,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                userScrollEnabled = true, // 👈 明确打开
            ) { page ->
                when (page) {
                    0 -> {
                        // 沉浸式设置页
                        ImmersiveSettingsPage(
                            animatedThemeColor = animatedThemeColor,
                            prefsManager = prefsManager,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    1 -> {
                        // 封面页
                        if (immersiveEffect) {
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
                            Column(
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                // -------------------------------
                                // 封面区域（固定占剩余空间）
                                // -------------------------------
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        modifier =
                                            Modifier
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

                                        ParticleEffectOverlay(
                                            effect = ParticleEffect.NONE,
                                            coverDisplayMode = coverDisplayMode,
                                            beatIntensity = beatIntensity,
                                            frequencyBands = frequencyBands,
                                            modifier = Modifier.matchParentSize(),
                                        )
                                    }
                                }

                                // -------------------------------
                                // 歌词区域（不影响封面）
                                // -------------------------------
                                MiniLyricsView(
                                    viewModel = playerViewModel,
                                    animatedThemeColor = animatedThemeColor,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .height(60.dp),
                                )
                            }
                        }
                    }

                    2 -> {
                        // 歌词页
                        LyricsPage(
                            playerViewModel = playerViewModel,
                            animatedThemeColor = animatedThemeColor,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        // ---------- 进度条 ----------
        if (!immersiveMode) {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                Slider(
                value =
                    if (playbackState.duration > 0) {
                        playbackState.currentPosition.toFloat() / playbackState.duration
                    } else {
                        0f
                    },
                onValueChange = { progress ->
                    playerViewModel.seekTo((progress * playbackState.duration).toLong())
                },
                // 1. 自定义颜色
                colors =
                    SliderDefaults.colors(
                        activeTrackColor = animatedThemeColor, // 已播放部分的进度条颜色
                        inactiveTrackColor = animatedThemeColor.copy(alpha = 0.24f), // 未播放部分的背景色
                        thumbColor = animatedThemeColor, // 滑块颜色
                        activeTickColor = Color.Transparent, // 隐藏刻度线
                        inactiveTickColor = Color.Transparent,
                    ),
                // 隐藏滑块
                thumb = {},
                // 3. 调整轨道高度 (取消默认厚度)
                track = { sliderState ->
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier.height(2.dp), // 让进度条更纤细
                        colors =
                            SliderDefaults.colors(
                                activeTrackColor = animatedThemeColor,
                                inactiveTrackColor = animatedThemeColor.copy(alpha = 0.2f),
                            ),
                        // 取消隐藏滑块后的缺口
                        thumbTrackGapSize = 0.dp,
                        // 关闭尾部小圆点
                        drawStopIndicator = null,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    formatTime(playbackState.currentPosition),
                    style = MaterialTheme.typography.bodySmall,
                    color = animatedThemeColor,
                )
                Text(
                    formatTime(playbackState.duration),
                    style = MaterialTheme.typography.bodySmall,
                    color = animatedThemeColor,
                )
            }
            }
        }

        // ---------- 播放控制 ----------
        if (!immersiveMode) {
            Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
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
                    modifier = Modifier.size(38.dp),
                )
            }

            Spacer(Modifier.width(24.dp))

            IconButton(
                onClick = { playerViewModel.playPause() },
                modifier = Modifier.size(52.dp),
            ) {
                Icon(
                    painter =
                        painterResource(
                            id =
                                if (playbackState.isPlaying) {
                                    R.drawable.ic_mini_player_pause
                                } else {
                                    R.drawable.ic_mini_player_play
                                },
                        ),
                    null,
                    modifier = Modifier.size(32.dp),
                    tint = animatedThemeColor,
                )
            }

            Spacer(Modifier.width(24.dp))

            IconButton(
                onClick = { playerViewModel.skipToNext() },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_round_skip_next_24),
                    contentDescription = "下一首",
                    tint = animatedThemeColor,
                    modifier = Modifier.size(38.dp),
                )
            }
        }

        }

        // ---------- 底部五按钮 ----------
        if (!immersiveMode) {
            Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
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
                IconButton(onClick = { showSleepSheet = true }) {
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
            IconButton(onClick = {
                scope.launch {
                    pagerState.animateScrollToPage(1)
                }
            }) {
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
    }

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

@Composable
fun ImmersiveSettingsPage(
    animatedThemeColor: Color,
    prefsManager: PreferencesManager,
    modifier: Modifier = Modifier,
) {
    var immersiveMode by remember { mutableStateOf(false) }
    var immersiveEffect by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(prefsManager) {
        prefsManager.immersiveMode.collect { immersiveMode = it }
    }
    LaunchedEffect(prefsManager) {
        prefsManager.immersiveEffect.collect { immersiveEffect = it }
    }

    Column(
        modifier = modifier.padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_audio_effect),
            contentDescription = null,
            tint = animatedThemeColor.copy(alpha = 0.6f),
            modifier = Modifier.size(48.dp),
        )

        Spacer(Modifier.height(16.dp))

        Text(
            "沉浸模式",
            color = animatedThemeColor,
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            "全屏沉浸，隐藏状态栏与导航栏",
            color = animatedThemeColor.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(24.dp))

        // ── 沉浸模式开关 ──
        Card(
            modifier = Modifier.fillMaxWidth(0.85f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = animatedThemeColor.copy(alpha = 0.08f),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "全屏沉浸",
                        color = animatedThemeColor,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "播放时全屏显示，隐藏系统栏",
                        color = animatedThemeColor.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = immersiveMode,
                    onCheckedChange = { value ->
                        immersiveMode = value
                        scope.launch { prefsManager.setImmersiveMode(value) }
                    },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 沉浸动效开关 ──
        Card(
            modifier = Modifier.fillMaxWidth(0.85f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = animatedThemeColor.copy(alpha = 0.08f),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "沉浸动效",
                        color = animatedThemeColor,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "沉浸模式下显示粒子动效",
                        color = animatedThemeColor.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = immersiveEffect,
                    onCheckedChange = { value ->
                        immersiveEffect = value
                        scope.launch { prefsManager.setImmersiveEffect(value) }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsPage(
    playerViewModel: PlayerViewModel,
    animatedThemeColor: Color,
    modifier: Modifier = Modifier,
) {
    val lyricsState by playerViewModel.lyricsState.collectAsState()
    val context = LocalContext.current
    val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
        context, PreferencesManagerEntryPoint::class.java)
    val prefsManager = entryPoint.prefs()

    val hasTranslation = lyricsState.lyrics?.lines?.any { it.translation != null } ?: false
    var showTranslation by remember { mutableStateOf(true) }

    // 从持久化读取歌词设置
    val fontSize by prefsManager.lyricsFontSize.collectAsState(initial = 24)
    val fontWeight by prefsManager.lyricsFontWeight.collectAsState(initial = 700)
    val isCentered by prefsManager.lyricsCentered.collectAsState(initial = true)

    var showLyricsSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            LyricsView(
                viewModel = playerViewModel,
                animatedThemeColor = animatedThemeColor,
                showTranslation = showTranslation && hasTranslation,
                modifier = Modifier.fillMaxSize(),
                fontSize = fontSize,
                fontWeight = FontWeight(fontWeight),
                isCentered = isCentered,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = lyricsState.lyrics?.source?.let {
                    when (it) {
                        LyricsSource.LOCAL_LRC -> "本地 LRC"
                        LyricsSource.LOCAL_KRC -> "本地 KRC"
                        LyricsSource.EMBEDDED -> "内嵌歌词"
                        LyricsSource.NETWORK -> "网络歌词"
                        LyricsSource.USER_PROVIDED -> "用户歌词"
                    }
                } ?: "",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.clickable { showLyricsSheet = true },
            )

            Spacer(modifier = Modifier.weight(1f))

            if (hasTranslation) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("翻译", fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = showTranslation, onCheckedChange = { showTranslation = it })
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

