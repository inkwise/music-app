package com.inkwise.music.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.inkwise.music.data.prefs.PreferencesManagerEntryPoint
import com.inkwise.music.ui.theme.LocalAppDimens
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val dimens = LocalAppDimens.current
    val peekHeight = rememberSheetPeekHeight(dimens.sheetPeekHeightDp)

    val scaffoldState = rememberBottomSheetScaffoldState()
    val scope = rememberCoroutineScope()

    var expandProgress by remember { mutableStateOf(0f) }
    var initialOffset by remember { mutableStateOf<Float?>(null) }
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
    val savedPage = rememberSaveable { mutableIntStateOf(0) }
    val sheetState = scaffoldState.bottomSheetState

    val pagerState =
        rememberPagerState(
            initialPage = savedPage.intValue,
            pageCount = { 2 },
        )
    LaunchedEffect(pagerState.currentPage) {
        savedPage.intValue = pagerState.currentPage
    }
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

            // 第一次记录“收起时”的 offset
            if (initialOffset == null) {
                initialOffset = offset
            }

            val start = initialOffset ?: return@collect

            expandProgress =
                ((start - offset) / peekHeightPx)
                    .coerceIn(0f, 1f)
        }
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = peekHeight,
        sheetDragHandle = null,
        // sheetContainerColor = Color.Transparent,
        sheetShape = RectangleShape,
        sheetContent = {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize(),
            ) {
                // 背景播放器：展开时显示
                playerScreen(
                    pagerState = pagerState,
                    sheetState = sheetState,
                    modifier = Modifier.alpha(expandProgress),
                )

                // 控制栏：收起时显示
                controlContent(
                    modifier = Modifier.alpha(1f - expandProgress),
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
        NavigationContent(
            sheetState = sheetState,
            pagerState = pagerState,
            scope = scope,
        )
    }
}

@Composable
fun rememberSheetPeekHeight(baseHeight: Dp): Dp {
    val density = LocalDensity.current
    val navigationBarHeight =
        WindowInsets.navigationBars
            .asPaddingValues()
            .calculateBottomPadding()

    return baseHeight + navigationBarHeight
}
