/*
 * 主界面导航容器。
 * 结构：ModalNavigationDrawer（侧边栏抽屉） → Scaffold（顶部栏） → NavHost（全部路由页面）。
 * 职责：
 * 1. 声明搜索、主页、本地/云端歌曲、各类设置、登录注册、详情页等路由及各自转场动画；
 * 2. 订阅 MainViewModel 的一次性导航事件（音效设置/艺术家/专辑/编辑歌曲），跳转后把 Pager 归位第 0 页并将播放器 Sheet 收回到 partialExpand；
 * 3. 双向同步侧边栏开合状态（UiState ↔ DrawerState）；
 * 4. 返回键拦截：Sheet 展开或 Pager 在第 2 页时优先回退，其次关闭侧边栏。
 */
package com.inkwise.music.ui.main

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
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
import com.inkwise.music.ui.main.navigationPage.components.EditSongScreen
import com.inkwise.music.ui.main.navigationPage.home.AlbumDetailScreen
import com.inkwise.music.ui.main.navigationPage.home.ArtistDetailScreen
import com.inkwise.music.ui.main.navigationPage.home.HomeScreen
import com.inkwise.music.ui.main.navigationPage.home.PlaylistDetailScreen
import com.inkwise.music.ui.main.navigationPage.local.LocalSongsScreen
import com.inkwise.music.ui.main.navigationPage.search.SearchScreen
import com.inkwise.music.ui.main.navigationPage.settings.SettingsScreen
import com.inkwise.music.ui.main.navigationPage.settings.SyncPlaySettingsScreen
import com.inkwise.music.ui.theme.LocalAppDimens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 主界面导航内容区：侧边栏抽屉 + 顶栏 + 页面导航。
 * 由 [MainScreen] 在 BottomSheetScaffold 的主内容区中调用，
 * sheetState/pagerState 与播放器 Sheet 共享，保证两边状态一致。
 */
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

    // 登录前想去的页面（当前仅云端歌曲页）：登录/注册成功后由 onSuccess 回跳。
    // 云端歌曲入口统一先判登录态，未登录直接进登录页——避免先渲染云端页、
    // 再被 requireLogin 事件重定向造成的"闪一下空白页"
    var pendingPostLoginRoute by remember { mutableStateOf<String?>(null) }

    /** 云端歌曲入口的统一导航：已登录直达；未登录先去登录页并记住目标。
     *  同步判断（读内存 token 缓存）+ 同帧导航，点击后不会先渲染云端页再转走 */
    fun navigateToCloudGuarded() {
        if (viewModel.isLoggedInNow()) {
            navController.navigate("cloud") {
                popUpTo("home") { inclusive = false }
                launchSingleTop = true
            }
        } else {
            pendingPostLoginRoute = "cloud"
            navController.navigate("login") {
                popUpTo("home") { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    // 登录/注册成功后回跳：有登录前目标页则直达该页，否则维持原有"回主页"
    fun navigateAfterAuthSuccess() {
        val target = pendingPostLoginRoute
        pendingPostLoginRoute = null
        if (target != null) {
            navController.navigate(target) {
                popUpTo("home") { inclusive = false }
                launchSingleTop = true
            }
        } else {
            navController.navigate("home") {
                popUpTo("home") { inclusive = true }
            }
        }
    }

    // 监听登录需求事件，跳转时清除触发页避免返回循环
    LaunchedEffect(Unit) {
        viewModel.loginRequiredEvents.collect {
            navController.navigate("login") {
                popUpTo("home") { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    // 跳转音效设置：进入设置页的同时把 Pager 归位、播放器 Sheet 收起，避免被遮挡
    LaunchedEffect(Unit) {
        viewModel.navigateToAudioEffectEvents.collect {
            navController.navigate("audio-effect-settings")
            pagerState.scrollToPage(0)
            sheetState.partialExpand()
        }
    }

    // 跳转艺术家详情页（按 ID）
    LaunchedEffect(Unit) {
        viewModel.navigateToArtistEvents.collect { artistId ->
            Log.d("NavigationContent", "navigating to artist/$artistId")
            navController.navigate("artist/$artistId")
            pagerState.scrollToPage(0)
            sheetState.partialExpand()
        }
    }

    // 跳转艺术家详情页（按名称，中文/特殊字符需 URL 编码后作为路由参数）
    LaunchedEffect(Unit) {
        viewModel.navigateToArtistByNameEvents.collect { name ->
            Log.d("NavigationContent", "navigating to artist/by-name/$name")
            navController.navigate("artist/by-name/${Uri.encode(name)}")
            pagerState.scrollToPage(0)
            sheetState.partialExpand()
        }
    }

    // 跳转专辑详情页（专辑名作为路由参数，需 URL 编码）
    LaunchedEffect(Unit) {
        viewModel.navigateToAlbumEvents.collect { albumName ->
            navController.navigate("album/${Uri.encode(albumName)}")
            pagerState.scrollToPage(0)
            sheetState.partialExpand()
        }
    }

    // 跳转歌曲信息编辑页
    LaunchedEffect(Unit) {
        viewModel.navigateToEditSongEvents.collect { songId ->
            navController.navigate("edit_song/$songId")
            pagerState.scrollToPage(0)
            sheetState.partialExpand()
        }
    }

    // 把 UiState 里的侧边栏开关同步到 DrawerState（点顶栏汉堡按钮时触发）
    LaunchedEffect(uiState.sidebarOpen) {
        if (uiState.sidebarOpen) {
            drawerState.open()
        } else {
            drawerState.close()
        }
    }

    // 反向同步：用户手势关掉抽屉后把 UiState 标志位复位，避免下次点汉堡按钮失效
    LaunchedEffect(drawerState.isClosed) {
        if (drawerState.isClosed && uiState.sidebarOpen) {
            viewModel.closeSidebar()
        }
    }

    // 最外层抽屉容器：左侧滑出侧边栏，内容区为主界面
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // 侧边栏面板：固定 280dp 宽
            ModalDrawerSheet(
                modifier = Modifier.width(280.dp),
            ) {
                SidebarContent(
                    onNavigate = { route ->
                        // 云端歌曲入口先判登录态（未登录直接进登录页，登录后回跳）
                        if (route == "cloud") {
                            navigateToCloudGuarded()
                        } else {
                            // 一级页面（home/local/settings）导航时清栈到 home，
                            // 避免反复切换导致返回栈无限叠加
                            navController.navigate(route) {
                                if (route in setOf("home", "local", "settings")) {
                                    popUpTo("home") { inclusive = false }
                                    launchSingleTop = true
                                } else {
                                    launchSingleTop = true
                                }
                            }
                        }
                        viewModel.closeSidebar()
                    },
                    currentRoute = navController.currentBackStackEntry?.destination?.route,
                )
            }
        },
        gesturesEnabled = true,
    ) {
        // 主内容区：顶部栏 + NavHost 页面
        Scaffold(
            topBar = {
                // 根据当前路由映射顶栏标题；详情类页面（艺术家/专辑/歌单）自带标题栏，故留空
                val route = navController.currentBackStackEntry?.destination?.route
                val title = when {
                    route == null || route == "home" -> ""
                    route == "local" -> "本地歌曲"
                    route == "cloud" -> "云端歌曲"
                    route == "settings" -> "设置"
                    route == "ui-settings" -> "UI 设置"
                    route == "playback-settings" -> "播放设置"
                    route == "audio-effect-settings" -> "音效设置"
                    route == "sync-settings" -> "同步播放"
                    route == "login" -> "登录"
                    route == "register" -> "注册"
                    route == "profile" -> "用户资料"
                    route == "search" -> "搜索"
                    route.startsWith("artist/") -> "" // 艺术家详情有自己的标题
                    route.startsWith("album/") -> "" // 专辑详情有自己的标题
                    route.startsWith("playlist/") -> "" // 歌单详情有自己的标题
                    route.startsWith("edit_song/") -> "编辑歌曲信息"
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
                    // 左上角汉堡按钮：开关侧边栏
                    navigationIcon = {
                        IconButton(onClick = { viewModel.toggleSidebar() }) {
                            Icon(Icons.Default.Menu, "菜单")
                        }
                    },
                    // 右上角搜索入口
                    actions = {
                        IconButton(onClick = {
                            navController.navigate("search") {
                                launchSingleTop = true
                            }
                        }) {
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
                    .padding(top = padding.calculateTopPadding()),
            ) {
                // 导航图：起始页为 home，底部预留播放器手柄高度避免内容被遮挡
                NavHost(
                    navController = navController,
                    startDestination = "home",
                    modifier = Modifier.padding(bottom = peekHeight),
                ) {
                    // 搜索页：关闭转场动画，切换即时完成
                    composable(
                        "search",
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        SearchScreen(
                            onNavigateToCloud = { navigateToCloudGuarded() },
                            onNavigateToArtist = { artistId ->
                                navController.navigate("artist/$artistId")
                            },
                            onNavigateToAlbum = { albumName ->
                                navController.navigate("album/${Uri.encode(albumName)}")
                            }
                        )
                    }
                    // 主页：应用默认起始页
                    composable(
                        "home",
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        HomeScreen(
                            onNavigateToLocal = { navController.navigate("local") },
                            onNavigateToCloud = { navigateToCloudGuarded() },
                            onNavigateToPlaylist = { id ->
                                navController.navigate("playlist/$id")
                            }
                        )
                    }
                    // 本地歌曲页
                    composable(
                        "local",
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        LocalSongsScreen(mainViewModel = viewModel)
                    }
                    // 云端歌曲页：渲染前兜底校验登录态——即便某条路径绕过了入口拦截
                    //（如进程恢复直接落在本路由），也绝不渲染云端页（避免上传按钮闪现），
                    // 而是立即转去登录页
                    composable(
                        "cloud",
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        if (viewModel.isLoggedInNow()) {
                            CloudSongsScreen(mainViewModel = viewModel)
                        } else {
                            LaunchedEffect(Unit) {
                                navController.navigate("login") {
                                    popUpTo("home") { inclusive = false }
                                    launchSingleTop = true
                                }
                            }
                        }
                    }
                    // 设置主页：四个设置子页的入口
                    composable(
                        "settings",
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        SettingsScreen(
                            onNavigateToUI = { navController.navigate("ui-settings") },
                            onNavigateToPlayback = { navController.navigate("playback-settings") },
                            onNavigateToAudioEffect = { navController.navigate("audio-effect-settings") },
                            onNavigateToSyncPlay = { navController.navigate("sync-settings") },
                        )
                    }
                    // UI 设置子页：以下四个设置子页均使用水平滑动转场
                    composable(
                        route = "ui-settings",
                        enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                        exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) },
                        popEnterTransition = { slideInHorizontally(initialOffsetX = { -it }) },
                        popExitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
                    ) {
                        com.inkwise.music.ui.main.navigationPage.settings.UISettingsScreen()
                    }
                    // 播放设置子页
                    composable(
                        route = "playback-settings",
                        enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                        exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) },
                        popEnterTransition = { slideInHorizontally(initialOffsetX = { -it }) },
                        popExitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
                    ) {
                        com.inkwise.music.ui.main.navigationPage.settings.PlaybackSettingsScreen()
                    }
                    // 音效设置子页
                    composable(
                        route = "audio-effect-settings",
                        enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                        exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) },
                        popEnterTransition = { slideInHorizontally(initialOffsetX = { -it }) },
                        popExitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
                    ) {
                        com.inkwise.music.ui.main.navigationPage.settings.AudioEffectSettingsScreen()
                    }
                    // 同步播放设置子页
                    composable(
                        route = "sync-settings",
                        enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                        exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) },
                        popEnterTransition = { slideInHorizontally(initialOffsetX = { -it }) },
                        popExitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
                    ) {
                        SyncPlaySettingsScreen()
                    }
                    // 登录页：登录成功后清栈回主页，防止返回键又回到登录页
                    composable(
                        "login",
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        LoginScreen(
                            onNavigateToRegister = {
                                navController.navigate("register") {
                                    launchSingleTop = true
                                }
                            },
                            onSuccess = { navigateAfterAuthSuccess()
                            }
                        )
                    }
                    // 注册页：成功后同样清栈回主页，系统返回则回到登录页
                    composable(
                        "register",
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        RegisterScreen(
                            onNavigateToLogin = {
                                navController.popBackStack()
                            },
                            onSuccess = { navigateAfterAuthSuccess() }
                        )
                    }
                    // 用户资料页：退出登录后清栈回主页
                    composable(
                        "profile",
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        UserProfileScreen(
                            onLogout = {
                                navController.navigate("home") {
                                    popUpTo("home") { inclusive = true }
                                }
                            }
                        )
                    }
                    // 歌单详情页（按歌单 ID 路由）
                    composable(
                        route = "playlist/{playlistId}",
                        arguments = listOf(
                            navArgument("playlistId") { type = NavType.LongType }
                        ),
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        PlaylistDetailScreen(mainViewModel = viewModel)
                    }
                    // 艺术家详情页（按艺术家 ID 路由）
                    composable(
                        route = "artist/{artistId}",
                        arguments = listOf(
                            navArgument("artistId") { type = NavType.LongType }
                        ),
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        ArtistDetailScreen(mainViewModel = viewModel)
                    }
                    // 艺术家详情页（按艺术家名路由，用于本地/云端无 ID 的数据）
                    composable(
                        route = "artist/by-name/{artistName}",
                        arguments = listOf(
                            navArgument("artistName") { type = NavType.StringType }
                        ),
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        ArtistDetailScreen(mainViewModel = viewModel)
                    }
                    // 专辑详情页（按专辑名路由）
                    composable(
                        route = "album/{albumName}",
                        arguments = listOf(
                            navArgument("albumName") { type = NavType.StringType }
                        ),
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        AlbumDetailScreen(mainViewModel = viewModel)
                    }
                    // 歌曲信息编辑页（水平滑动转场，自带返回回调）
                    composable(
                        route = "edit_song/{songId}",
                        arguments = listOf(
                            navArgument("songId") { type = NavType.LongType }
                        ),
                        enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                        exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) },
                        popEnterTransition = { slideInHorizontally(initialOffsetX = { -it }) },
                        popExitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
                    ) {
                        EditSongScreen(
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }

                // 返回键拦截：Sheet 展开时先收起，Pager 在第 2 页（播放列表）时先回第 0 页
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
