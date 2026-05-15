package com.inkwise.music.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.inkwise.music.R
import com.inkwise.music.ui.main.navigationPage.auth.LoginScreen
import com.inkwise.music.ui.main.navigationPage.auth.RegisterScreen
import com.inkwise.music.ui.main.navigationPage.auth.UserProfileScreen
import com.inkwise.music.ui.main.navigationPage.cloud.CloudSongsScreen
import com.inkwise.music.ui.main.navigationPage.home.HomeScreen
import com.inkwise.music.ui.main.navigationPage.home.PlaylistDetailScreen
import com.inkwise.music.ui.main.navigationPage.local.LocalSongsScreen
import com.inkwise.music.ui.main.navigationPage.settings.SettingsScreen
import com.inkwise.music.ui.theme.LocalAppDimens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationContent(
    sheetState: SheetState,
    pagerState: PagerState,
    scope: CoroutineScope,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val dimens = LocalAppDimens.current
    val peekHeight = rememberSheetPeekHeight(dimens.sheetPeekHeightDp)

    // 监听登录需求事件，跳转时清除触发页避免返回循环
    LaunchedEffect(Unit) {
        viewModel.loginRequiredEvents.collect {
            navController.navigate("login") {
                popUpTo("home") { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.navigateToAudioEffectEvents.collect {
            navController.navigate("audio-effect-settings")
            pagerState.scrollToPage(0)
            sheetState.partialExpand()
        }
    }

    LaunchedEffect(uiState.sidebarOpen) {
        if (uiState.sidebarOpen) {
            drawerState.open()
        } else {
            drawerState.close()
        }
    }

    LaunchedEffect(drawerState.isClosed) {
        if (drawerState.isClosed && uiState.sidebarOpen) {
            viewModel.closeSidebar()
        }
    }

    // 导航切换后短暂拦截触摸事件，防止鬼点击
    var blockTouch by remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(navController.currentBackStackEntry?.destination?.route) {
        blockTouch = true
        delay(300)
        blockTouch = false
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(280.dp),
            ) {
                SidebarContent(
                    onNavigate = { route ->
                        navController.navigate(route) {
                            launchSingleTop = true
                        }
                        viewModel.closeSidebar()
                    },
                    currentRoute = navController.currentBackStackEntry?.destination?.route,
                )
            }
        },
        gesturesEnabled = true,
    ) {
        Scaffold(
            topBar = {
                val route = navController.currentBackStackEntry?.destination?.route
                val title = when {
                    route == null || route == "home" -> ""
                    route == "local" -> "本地歌曲"
                    route == "cloud" -> "云端歌曲"
                    route == "settings" -> "设置"
                    route == "ui-settings" -> "UI 设置"
                    route == "playback-settings" -> "播放设置"
                    route == "audio-effect-settings" -> "音效设置"
                    route == "login" -> "登录"
                    route == "register" -> "注册"
                    route == "profile" -> "用户资料"
                    route.startsWith("playlist/") -> "" // 歌单详情有自己的标题
                    else -> ""
                }
                TopAppBar(
                    title = {
                        if (title.isNotEmpty()) {
                            Text(
                                text = title,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.toggleSidebar() }) {
                            Icon(Icons.Default.Menu, "菜单")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.toggleBottomDrawer() }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_search),
                                contentDescription = "搜索",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        // 导航切换后短暂拦截触摸，防止鬼点击
                        if (blockTouch) Modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            }
                        } else Modifier
                    )
                    .padding(top = padding.calculateTopPadding()),
            ) {
                NavHost(
                    navController = navController,
                    startDestination = "home",
                    modifier = Modifier.padding(bottom = peekHeight),
                ) {
                    composable("home") {
                        HomeScreen(
                            onNavigateToLocal = { navController.navigate("local") },
                            onNavigateToCloud = { navController.navigate("cloud") },
                            onNavigateToPlaylist = { id ->
                                navController.navigate("playlist/$id")
                            }
                        )
                    }
                    composable("local") {
                        LocalSongsScreen()
                    }
                    composable("cloud") {
                        CloudSongsScreen()
                    }
                    composable("settings") {
                        SettingsScreen(
                            onNavigateToUI = { navController.navigate("ui-settings") },
                            onNavigateToPlayback = { navController.navigate("playback-settings") },
                            onNavigateToAudioEffect = { navController.navigate("audio-effect-settings") },
                        )
                    }
                    composable(
                        route = "ui-settings",
                        enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                        exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) },
                        popEnterTransition = { slideInHorizontally(initialOffsetX = { -it }) },
                        popExitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
                    ) {
                        com.inkwise.music.ui.main.navigationPage.settings.UISettingsScreen()
                    }
                    composable(
                        route = "playback-settings",
                        enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                        exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) },
                        popEnterTransition = { slideInHorizontally(initialOffsetX = { -it }) },
                        popExitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
                    ) {
                        com.inkwise.music.ui.main.navigationPage.settings.PlaybackSettingsScreen()
                    }
                    composable(
                        route = "audio-effect-settings",
                        enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                        exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) },
                        popEnterTransition = { slideInHorizontally(initialOffsetX = { -it }) },
                        popExitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
                    ) {
                        com.inkwise.music.ui.main.navigationPage.settings.AudioEffectSettingsScreen()
                    }
                    composable("login") {
                        LoginScreen(
                            onNavigateToRegister = {
                                navController.navigate("register") {
                                    launchSingleTop = true
                                }
                            },
                            onSuccess = {
                                navController.navigate("home") {
                                    popUpTo("home") { inclusive = true }
                                }
                            }
                        )
                    }
                    composable("register") {
                        RegisterScreen(
                            onNavigateToLogin = {
                                navController.popBackStack()
                            },
                            onSuccess = {
                                navController.navigate("home") {
                                    popUpTo("home") { inclusive = true }
                                }
                            }
                        )
                    }
                    composable("profile") {
                        UserProfileScreen(
                            onLogout = {
                                navController.navigate("home") {
                                    popUpTo("home") { inclusive = true }
                                }
                            }
                        )
                    }
                    composable(
                        route = "playlist/{playlistId}",
                        arguments = listOf(
                            navArgument("playlistId") { type = NavType.LongType }
                        )
                    ) {
                        PlaylistDetailScreen()
                    }
                }

                val shouldIntercept =
                    sheetState.targetValue == SheetValue.Expanded || pagerState.currentPage > 0

                BackHandler(enabled = shouldIntercept) {
                    scope.launch {
                        if (pagerState.currentPage > 0) {
                            pagerState.animateScrollToPage(0)
                        } else {
                            sheetState.partialExpand()
                        }
                    }
                }

                // 侧边栏返回键：必须在 NavHost 之后 compose，优先级高于页面返回
                BackHandler(enabled = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open) {
                    viewModel.closeSidebar()
                }
            }
        }
    }
}
