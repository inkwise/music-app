package com.inkwise.music.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Dp.Companion.Infinity
import androidx.hilt.navigation.compose.hiltViewModel
import com.inkwise.music.ui.player.PlayerViewModel
import com.inkwise.music.ui.theme.LocalAppDimens
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val dimens = LocalAppDimens.current

    val peekHeight =
        rememberSheetPeekHeight(
            dimens.sheetPeekHeightDp,
        )

    val scaffoldState =
        rememberBottomSheetScaffoldState()

    val scope =
        rememberCoroutineScope()

    var expandProgress by remember {
        mutableStateOf(0f)
    }

    val coverFlight =
        remember {
            CoverFlightState()
        }

    val playerViewModel: PlayerViewModel =
        hiltViewModel()

    val playbackState by
        playerViewModel
            .playbackState
            .collectAsState()

    var rootHeightPx by remember {
        mutableStateOf(0f)
    }

    val density =
        LocalDensity.current

    val sheetState =
        scaffoldState.bottomSheetState

    val pagerState =
        rememberPagerState(
            initialPage = 0,
            pageCount = {
                2
            },
        )

    LaunchedEffect(sheetState.currentValue) {
        if (
            sheetState.currentValue !=
                SheetValue.Expanded
        ) {
            pagerState.scrollToPage(0)
        }
    }

    LaunchedEffect(sheetState) {
        snapshotFlow {
            sheetState.requireOffset()
        }.collect { offset ->

            val peekHeightPx =
                with(density) {
                    peekHeight.toPx()
                }

            val maxOffset =
                rootHeightPx -
                    peekHeightPx

            expandProgress =
                if (maxOffset > 1f) {
                    (
                        (maxOffset - offset) /
                            maxOffset
                    ).coerceIn(
                        0f,
                        1f,
                    )
                } else {
                    0f
                }

            if (
                expandProgress <=
                    0.0001f
            ) {
                coverFlight.anchorStart =
                    coverFlight.startBounds
            }
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    rootHeightPx =
                        size.height.toFloat()
                },
    ) {
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = peekHeight,
            sheetDragHandle = null,
            sheetShape = RectangleShape,
            sheetMaxWidth = Infinity,
            sheetContent = {
                val playerHeightPx =
                    rootHeightPx.coerceAtLeast(1f)

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .requiredHeight(
                                with(density) {
                                    playerHeightPx.toDp()
                                },
                            )
                            .clipToBounds()
                            .onGloballyPositioned { coordinates ->
                                coverFlight.sheetOrigin =
                                    coordinates
                                        .boundsInRoot()
                                        .topLeft
                            },
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val miniPlayerAlpha =
                                        (
                                            1f -
                                                expandProgress *
                                                8f
                                        ).coerceIn(
                                            0f,
                                            1f,
                                        )

                                    alpha =
                                        1f -
                                            miniPlayerAlpha
                                },
                    ) {
                        playerScreen(
                            pagerState = pagerState,
                            sheetState = sheetState,
                            coverFlight = coverFlight,
                            expandProgress = expandProgress,
                            modifier =
                                Modifier.fillMaxSize(),
                        )
                    }

                    controlContent(
                        coverFlight = coverFlight,
                        expandProgress = expandProgress,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(
                                    dimens.sheetPeekHeightDp,
                                )
                                .graphicsLayer {
                                    alpha =
                                        (
                                            1f -
                                                expandProgress *
                                                8f
                                        ).coerceIn(
                                            0f,
                                            1f,
                                        )
                                },
                        onClick = {
                            scope.launch {
                                sheetState.expand()
                            }
                        },
                        showPlayQueue = {
                            scope.launch {
                                pagerState.scrollToPage(1)
                                sheetState.expand()
                            }
                        },
                    )
                }

                val isExpanded =
                    sheetState.currentValue ==
                        SheetValue.Expanded ||
                        sheetState.targetValue ==
                        SheetValue.Expanded

                val isAtSecondPage =
                    pagerState.currentPage > 0

                BackHandler(
                    enabled =
                        isExpanded ||
                            isAtSecondPage,
                ) {
                    scope.launch {
                        if (
                            pagerState.currentPage > 0
                        ) {
                            pagerState.animateScrollToPage(0)
                        } else {
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

        SharedCoverLoader(
            coverUri =
                playbackState
                    .currentSong
                    ?.albumArt,
            flight = coverFlight,
        )

        FlyingCoverOverlay(
            flight = coverFlight,
            progress = expandProgress,
            modifier =
                Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun rememberSheetPeekHeight(
    baseHeight: Dp,
): Dp {
    val navigationBarHeight =
        WindowInsets.navigationBars
            .asPaddingValues()
            .calculateBottomPadding()

    return baseHeight +
        navigationBarHeight
}