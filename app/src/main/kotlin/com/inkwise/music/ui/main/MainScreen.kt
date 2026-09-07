/*
 * 应用主界面骨架：BottomSheetScaffold 同时承载"主页导航区"和"播放器"。
 * - 主内容区（scaffold 内容体）：NavigationContent，即侧边栏 + 顶栏 + 各导航页面；
 * - Sheet 收起态：只露出 peekHeight 高度的迷你播放条（controlContent）；
 * - Sheet 展开态：显示播放器主页/播放列表（playerScreen，Pager 两页）。
 * 播放页 Sheet 整页升起（内容完整渲染），迷你栏被覆盖（椒盐式）。
 */
package com.inkwise.music.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp.Companion.Infinity
import androidx.hilt.navigation.compose.hiltViewModel
import com.inkwise.music.data.prefs.PreferencesManagerEntryPoint
import com.inkwise.music.ui.player.PlayerViewModel
import com.inkwise.music.ui.theme.LocalAppDimens
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

/** 主界面入口：组装播放器 Sheet 与主页导航内容，并处理 Sheet 展开进度、页面记忆与返回键逻辑。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val dimens = LocalAppDimens.current
    val peekHeight = rememberSheetPeekHeight(dimens.sheetPeekHeightDp)

    val scaffoldState = rememberBottomSheetScaffoldState()
    val scope = rememberCoroutineScope()

    // Sheet 内容实测高度（px）：用于计算"收起态 offset"= sheetHeight - peekHeight。
    // 不能用首帧 offset 当基准——展开态旋转屏幕重建后首帧 offset≈0，会把展开误判为收起
    var sheetHeightPx by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    // val peekHeightPx = with(density) { dimens.sheetPeekHeightDp.toPx() }
    val peekHeightPx = with(density) { peekHeight.toPx() }

    // 展开进度 p（0 收起 → 1 展开）：椒盐 iu0 管线的驱动源，
    // 仅被各 graphicsLayer/offset 的 lambda 在 draw 阶段直读（拖拽时零重组）
    var progress by remember { mutableFloatStateOf(0f) }

    // PlayerSurface（椒盐 12.3.1）：morph 表面从迷你栏封面 bounds 长大到全屏。
    // 起点形状由布局常量直接计算（封面 50dp、左 16dp、垂直居中于迷你栏），
    // 无需实测——彻底避开冷启动布局测量不可靠的问题
    val coverSurface = remember { CoverSurfaceState() }
    var boxSizePx by remember { mutableStateOf(IntSize.Zero) }
    val miniCornerRadiusPx = with(density) { 3.dp.toPx() }

    val playerViewModel: PlayerViewModel = hiltViewModel()
    val playbackState by playerViewModel.playbackState.collectAsState()

    // 共享封面位图（morph 面板内铺满绘制，全程同一张图）
    SharedCoverLoader(
        coverUri = playbackState.currentSong?.albumArt,
        state = coverSurface,
    )

    val context = LocalContext.current
    val prefsManager =
        remember {
            EntryPointAccessors.fromApplication(
                context,
                PreferencesManagerEntryPoint::class.java,
            ).prefs()
        }
    // 记忆播放器 Pager 当前页（进程内重建/旋转后恢复），0=播放器主页，1=播放队列
    val savedPage = rememberSaveable { mutableIntStateOf(0) }
    val sheetState = scaffoldState.bottomSheetState

    // 播放器内部的两页 Pager：第 0 页为播放器主页，第 1 页为播放队列
    val pagerState =
        rememberPagerState(
            initialPage = savedPage.intValue,
            pageCount = { 2 },
        )
    // 每次翻页都把页码写入 savedPage，用于下次展开时恢复
    LaunchedEffect(pagerState.currentPage) {
        savedPage.intValue = pagerState.currentPage
    }
    // Sheet 完全展开时把 Pager 归位到记忆的页码，避免拖拽过程中遗留的页偏移
    LaunchedEffect(sheetState.currentValue) {
        if (sheetState.currentValue == SheetValue.Expanded) {
            pagerState.scrollToPage(savedPage.intValue)
        }
    }
    // 监听 BottomSheet 拖拽：只更新 progress 状态（消费方全在 draw 阶段 lambda 里）
    LaunchedEffect(scaffoldState.bottomSheetState) {
        snapshotFlow {
            scaffoldState.bottomSheetState.requireOffset()
        }.collect { offset: Float ->
            val maxOffset = sheetHeightPx - peekHeightPx
            progress =
                if (maxOffset > 1f) {
                    ((maxOffset - offset) / maxOffset).coerceIn(0f, 1f)
                } else {
                    0f
                }
        }
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = peekHeight,
        sheetDragHandle = null,
        // sheetContainerColor = Color.Transparent,
        sheetShape = RectangleShape,
        // 横屏下 sheet 不限制最大宽度（M3 默认 640dp 会让播放器两侧露出主页内容）
        sheetMaxWidth = Dp.Infinity,
        // Sheet 内容：播放器与迷你控制条叠加，透明度按椒盐 iu0 公式由进度驱动
        //（graphicsLayer 直读，draw 阶段取值，拖拽时零重组）
        sheetContent = {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged { sheetHeightPx = it.height.toFloat() },
            ) {
                // 播放页（完整渲染；p<0.95 期间被 PlayerSurface morph 面板遮蔽）
                playerScreen(
                    pagerState = pagerState,
                    sheetState = sheetState,
                )

                // PlayerSurface morph 面板（椒盐 12.3.1）：z 在迷你栏与播放页之上。
                // 迷你条封面在 Sheet 局部系：宽 50dp、水平左 16dp、垂直居中于 peek 区
                val peekPx = with(density) { peekHeight.toPx() }
                val barH = with(density) { dimens.sheetPeekHeightDp.toPx() }
                val coverSidePx = with(density) { 50.dp.toPx() }
                val coverLeftPx = with(density) { 16.dp.toPx() }
                val miniBoundsLocal =
                    Rect(
                        coverLeftPx,
                        (barH - coverSidePx) / 2f,
                        coverLeftPx + coverSidePx,
                        (barH + coverSidePx) / 2f,
                    )
                PlayerSurfaceOverlay(
                    state = coverSurface,
                    progress = progress,
                    miniBoundsLocal = miniBoundsLocal,
                    sheetVisibleHeight = peekPx + progress * (sheetHeightPx - peekPx),
                    fullWidthPx = boxSizePx.width.toFloat(),
                    fullCornerRadiusPx = 0f,
                    miniCornerRadiusPx = miniCornerRadiusPx,
                )

                // 迷你播放栏（封面 scale = 0.95 + 0.05×p，椒盐 iu0 同式；
                // 封面区域被 PlayerSurface 面板从进度 0 起遮蔽接管）
                controlContent(
                    coverScaleProvider = { 0.05f * progress + 0.95f },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(dimens.sheetPeekHeightDp),
                    onClick = {
                        scope.launch {
                            scaffoldState.bottomSheetState.expand()
                        }
                    },
                    showPlayQueue = {
                        scope.launch {
                            // 立即切到播放列表页，再展开 Sheet，避免先闪现播放器主页
                            savedPage.intValue = 1
                            pagerState.scrollToPage(1)
                            scaffoldState.bottomSheetState.expand()
                        }
                    },
                )
            }
            // --- 核心：将 BackHandler 放在这里 ---
            // 使用 currentValue 配合 targetValue 确保在动画过程中也能精准拦截
            val isExpanded =
                sheetState.currentValue == SheetValue.Expanded || sheetState.targetValue == SheetValue.Expanded
            val isAtSecondPage = pagerState.currentPage > 0

            BackHandler(enabled = isExpanded || isAtSecondPage) {
                scope.launch {
                    if (pagerState.currentPage > 0) {
                        // 如果在第二页，先回第一页
                        pagerState.animateScrollToPage(0)
                    } else {
                        // 如果在第一页且展开，则收起
                        sheetState.partialExpand()
                    }
                }
            }
        },
    ) {
        // 主内容区：侧边栏 + 顶栏 + 导航页面（Sheet 收起时就是"主页"）+
        // PlayerSurface morph 覆盖层（置顶；迷你栏在 sheetContent 顶部，被面板接管）
        Box(modifier = Modifier.fillMaxSize()) {
            NavigationContent(
                sheetState = sheetState,
                pagerState = pagerState,
                scope = scope,
            )

        }
    }
}

/**
 * 计算迷你播放条（Sheet 露出高度）：基础高度 + 系统导航栏高度，
 * 避免手势导航/三键导航遮挡播放条。
 */
@Composable
fun rememberSheetPeekHeight(baseHeight: Dp): Dp {
    val density = LocalDensity.current
    val navigationBarHeight =
        WindowInsets.navigationBars
            .asPaddingValues()
            .calculateBottomPadding()

    return baseHeight + navigationBarHeight
}
