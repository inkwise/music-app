/*
 * 应用主界面骨架：BottomSheetScaffold 同时承载"主页导航区"和"播放器"。
 * - 主内容区（scaffold 内容体）：NavigationContent，即侧边栏 + 顶栏 + 各导航页面；
 * - Sheet 收起态：只露出 peekHeight 高度的迷你播放条（controlContent）；
 * - Sheet 展开态：显示播放器主页/播放列表（playerScreen，Pager 两页）。
 * 两种形态通过 expandProgress（0f~1f）做交叉透明度过渡，避免跳变。
 */
package com.inkwise.music.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
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

    // Sheet 展开进度：0 = 完全收起（只露手柄），1 = 完全展开（播放器）
    var expandProgress by remember { mutableStateOf(0f) }
    // 飞行封面转场状态：迷你播放条封面 ↔ 播放页大封面的弧线跟随动画（椒盐式）
    val coverFlight = remember { CoverFlightState() }
    // 共享封面加载器需要当前歌曲封面 uri（与迷你条/播放页同源）
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val playbackState by playerViewModel.playbackState.collectAsState()
    // Sheet 内容实测高度（px）：用于计算"收起态 offset"= sheetHeight - peekHeight。
    // 不能用首帧 offset 当基准——展开态旋转屏幕重建后首帧 offset≈0，会把展开误判为收起
    var sheetHeightPx by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    // val peekHeightPx = with(density) { dimens.sheetPeekHeightDp.toPx() }
    val peekHeightPx = with(density) { peekHeight.toPx() }

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
    // 监听 BottomSheet 拖拽
    LaunchedEffect(scaffoldState.bottomSheetState) {
        snapshotFlow {
            scaffoldState.bottomSheetState.requireOffset()
        }.collect { offset: Float ->
            // 进度 = 距"完全展开"的程度 / 总可拖拽距离（与首帧状态无关，旋转重建后仍正确）
            val maxOffset = sheetHeightPx - peekHeightPx
            expandProgress =
                if (maxOffset > 1f) {
                    ((maxOffset - offset) / maxOffset).coerceIn(0f, 1f)
                } else {
                    0f
                }
            // 冻结飞行起点：折叠态持续对齐迷你封面实测屏幕位置，拖拽开始后冻结——
            // 头部水平段在屏幕上纯水平右移（不随 Sheet 上移变成斜线）
            if (expandProgress < 0.01f) {
                coverFlight.anchorStart = coverFlight.startBounds
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
        // Sheet 内容：播放器与迷你控制条叠加在同一 Box 内，用透明度切换形态
        sheetContent = {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged { sheetHeightPx = it.height.toFloat() }
                        // Sheet 内容盒原点（root 坐标）：终点锚点由 root 坐标换算成
                        // Sheet 局部坐标时使用（CoverFlightState.sheetOrigin）
                        .onGloballyPositioned { coverFlight.sheetOrigin = it.boundsInRoot().topLeft },
            ) {
                // 背景播放器：展开时显示
                playerScreen(
                    pagerState = pagerState,
                    sheetState = sheetState,
                    coverFlight = coverFlight,
                    expandProgress = expandProgress,
                    modifier = Modifier.alpha(expandProgress),
                )

                // 控制栏：收起时显示。飞行动画已接管时快速隐去（前 25% 进度内 alpha→0），
                // 对齐椒盐"一展开迷你条即让位、封面独自飞行"的观感；
                // 尚未接管（如共享位图仍在解码）时保持原速渐隐，封面留在原位可见，
                // 待接管后由飞行封面的补间从当前位置平滑接走（CoverFlight.kt）
                controlContent(
                    coverFlight = coverFlight,
                    expandProgress = expandProgress,
                    modifier =
                        Modifier.alpha(
                            if (coverFlight.shouldFly(expandProgress)) {
                                (1f - expandProgress * 4f).coerceIn(0f, 1f)
                            } else {
                                1f - expandProgress
                            },
                        ),
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

                // 飞行封面覆盖层：展开/折叠过程中由它接管两张真实封面的显示，
                // 放在 Box 最后一个子级 → 天然置顶（椒盐式封面弧线跟随动画）。
                // SharedCoverLoader 预解码全分辨率共享位图，全程只绘制这一份内容
                SharedCoverLoader(
                    coverUri = playbackState.currentSong?.albumArt,
                    flight = coverFlight,
                )
                FlyingCoverOverlay(
                    flight = coverFlight,
                    progress = expandProgress,
                    modifier = Modifier.fillMaxSize(),
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
