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
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import androidx.hilt.navigation.compose.hiltViewModel
import coil.ImageLoader
import coil.request.ImageRequest
import com.inkwise.music.ui.player.PlayerViewModel
import com.inkwise.music.ui.theme.extractThemeColor
import com.inkwise.music.ui.theme.prepareCoverForBackground
import com.inkwise.music.ui.theme.toSoftBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val imageLoader = remember { ImageLoader.Builder(context).build() }

    // ---------------- Pager 修复逻辑 ----------------

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

    // ---------------- 主题色（控件颜色 = 深色） ----------------

    var themeColor by remember { mutableStateOf(Color(0xFF2196F3)) }
    val animatedThemeColor by animateColorAsState(
        targetValue = themeColor,
        animationSpec = tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "ThemeColorAnimation",
    )

    // ---------------- 背景色（底色 = 浅色变体） ----------------

    var bgBaseColor by remember { mutableStateOf(Color(0xFFF5F7FA)) }
    val animatedBgBase by animateColorAsState(
        targetValue = bgBaseColor,
        animationSpec = tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "BgBaseAnimation",
    )

    // ---------------- 处理后的封面 ----------------

    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // ---------------- 取色与封面处理 ----------------

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
            val bgBitmap = prepareCoverForBackground(bitmap, 280)

            withContext(Dispatchers.Main) {
                themeColor = Color(controlColor)
                bgBaseColor = Color(controlColor).toSoftBackground()
                processedBitmap = bgBitmap
            }
        }
    }

    // ---------------- UI ----------------

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        // ---------- 背景 ----------

        AnimatedContent(
            targetState = coverUri,
            transitionSpec = {
                fadeIn(tween(600)) togetherWith fadeOut(tween(800))
            },
            label = "BackgroundTransition",
        ) { _ ->

            Box(modifier = Modifier.fillMaxSize()) {
                // Layer 0: 浅色纯色底色 (提取色 → toSoftBackground，极浅)
                Box(
                    modifier = Modifier.fillMaxSize().background(animatedBgBase),
                )

                // Layer 1: 模糊封面 (1.5x, 25dp, alpha=0.4)
                if (processedBitmap != null) {
                    Image(
                        bitmap = processedBitmap!!.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = 1.5f
                                    scaleY = 1.5f
                                    alpha = 0.4f
                                }
                                .blur(radius = 25.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
                    )
                }

                // Layer 2: 白色雾化叠加（模拟 Salt Player flow light: 58%+16% white before blur）
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.12f)),
                )

                // Layer 3: 上下暗色渐变
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors =
                                        listOf(
                                            Color.Black.copy(alpha = 0.18f),
                                            Color.Transparent,
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.10f),
                                        ),
                                ),
                            ),
                )
            }
        }

        // ---------- Pager 内容 ----------

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
                    )
                }
            }
        }
    }
}
