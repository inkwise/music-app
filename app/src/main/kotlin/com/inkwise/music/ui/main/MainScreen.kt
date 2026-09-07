/*
 * 应用主界面骨架（椒盐 12.3.1 式）：
 * BottomSheetScaffold 承载主页与播放页。Sheet 内容是一个**全宽裁剪面板**：
 * 高度从迷你条高度随拖拽进度长到全屏，内部放完整播放页（按全高布局、超出被裁剪）
 * ——拖拽时播放页从迷你条「向上展开」逐渐显现（封面随之显现并就位）。
 * 迷你播放栏覆盖在面板顶部，随进度淡出（椒盐 iu0：max(0, 16(1−p)−15)）。
 */
package com.inkwise.music.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp.Companion.Infinity
import androidx.hilt.navigation.compose.hiltViewModel
import com.inkwise.music.data.prefs.PreferencesManagerEntryPoint
import com.inkwise.music.ui.theme.LocalAppDimens
import dagger.hilt.android.EntryPointAccessors
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import com.inkwise.music.ui.player.PlayerViewModel
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch

/** 主界面入口：全宽裁剪面板承载播放页升降 + 迷你栏淡出，处理手势、页面记忆与返回键逻辑。 */
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
    val peekHeightPx = with(density) { peekHeight.toPx() }

    // 展开进度 p（0 收起 → 1 展开）：椒盐 iu0 管线的驱动源，
    // 仅被各 height/graphicsLayer 的 lambda 在布局/绘制阶段直读（拖拽时零重组）
    var progress by remember { mutableFloatStateOf(0f) }

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
    // 监听 BottomSheet 拖拽：只更新 progress 状态（消费方全在布局/绘制 lambda 里）
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
        sheetShape = RectangleShape,
        // 横屏下 sheet 不限制最大宽度（M3 默认 640dp 会让播放器两侧露出主页内容）
        sheetMaxWidth = Dp.Infinity,
        // Sheet 内容（椒盐 12.3.1 式）：全宽面板，高度从迷你条高度随进度长到全屏，
        // 顶部裁剪（clipToBounds）——播放页完整布局在面板内按全高布局、超出被裁剪，
        // 拖拽时播放页从迷你条「向上展开」逐渐显现：
        //  - 迷你播放栏覆盖在面板顶部，随进度淡出（椒盐 iu0：max(0, 16(1−p)−15)）
        //  - p≥0.95 时面板 = 全屏，内容即完整播放页（单点切换，与面板无缝）
        sheetContent = {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged { sheetHeightPx = it.height.toFloat() },
            ) {
                // 面板高度：从迷你条高度（peek）随进度长到全屏
                val panelHeightPx = peekHeightPx + progress * (sheetHeightPx - peekHeightPx)
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(with(density) { panelHeightPx.toDp() })
                            .clipToBounds(),
                ) {
                    // 播放页：按 Sheet 全高布局（不被面板高度压缩），超出部分被面板裁剪
                    Box(
                        modifier =
                            Modifier.requiredHeight(
                                with(density) { sheetHeightPx.toDp() },
                            ),
                    ) {
                        playerScreen(
                            pagerState = pagerState,
                            sheetState = sheetState,
                        )
                    }

                    // 迷你播放栏：覆盖面板顶部，随进度淡出（前 12.5% 进度内 → 0）
                    controlContent(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(dimens.sheetPeekHeightDp)
                                .graphicsLayer {
                                    alpha = (1f - progress * 8f).coerceIn(0f, 1f)
                                },
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
        // 主内容区：侧边栏 + 顶栏 + 导航页面（Sheet 收起时就是"主页"）
        NavigationContent(
            sheetState = sheetState,
            pagerState = pagerState,
            scope = scope,
        )
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
