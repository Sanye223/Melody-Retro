package com.melody.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.melody.player.data.AppSettings
import com.melody.player.data.SettingsRepository
import com.melody.player.playback.PlayerManager
import com.melody.player.ui.components.MiniPlayer
import com.melody.player.ui.components.PlayerSheetOverlay
import com.melody.player.ui.components.rememberPlayerSheetState
import com.melody.player.ui.screens.AlbumDetailScreen
import com.melody.player.ui.screens.AlbumsScreen
import com.melody.player.ui.screens.ArtistDetailScreen
import com.melody.player.ui.screens.ArtistsScreen
import com.melody.player.ui.screens.NowPlayingScreen
import com.melody.player.ui.screens.SettingsScreen
import com.melody.player.ui.screens.SongsScreen
import com.melody.player.util.AudioPermission
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import com.melody.player.ui.components.MiniPlayerHeight
import com.melody.player.ui.theme.rememberRetroColors
import com.melody.player.playback.PlayerUiState
import com.melody.player.ui.components.PlayerSheetState
import com.melody.player.ui.components.miniAlphaAt
import com.melody.player.ui.components.playerAlphaAt

private object Routes {
    const val SONGS = "songs"
    const val ALBUMS = "albums"
    const val ARTISTS = "artists"
    const val SETTINGS = "settings"
    const val ALBUM_ARG = "albumId"
    const val ARTIST_ARG = "artistId"
    const val ALBUM_DETAIL = "album/{$ALBUM_ARG}"
    const val ARTIST_DETAIL = "artist/{$ARTIST_ARG}"

    fun album(id: Long) = "album/$id"
    fun artist(id: Long) = "artist/$id"
}

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem(Routes.SONGS, "歌曲", Icons.Rounded.LibraryMusic),
    TabItem(Routes.ALBUMS, "专辑", Icons.Rounded.Album),
    TabItem(Routes.ARTISTS, "艺术家", Icons.Rounded.Person),
)

@Composable
fun AppRoot(settings: AppSettings) {
    val context = LocalContext.current
    val libraryViewModel: LibraryViewModel = viewModel()
    val library by libraryViewModel.state.collectAsStateWithLifecycle()
    val playerState by PlayerManager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var hasPermission by remember { mutableStateOf(AudioPermission.isGranted(context)) }

    // 歌词字号/间距的新默认值（1.2× / 4dp）只对老数据生效一次；
    // 用户自己调过的数值保持不变
    LaunchedEffect(Unit) { SettingsRepository.migrateLyricDefaultsIfNeeded(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasPermission = AudioPermission.isGranted(context)
    }

    LaunchedEffect(hasPermission, settings.sortOrder) {
        if (hasPermission) libraryViewModel.refresh(settings.sortOrder)
    }

    // 用户从系统设置页返回后重新检查一次权限
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !hasPermission) {
                if (AudioPermission.isGranted(context)) hasPermission = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val actions = remember {
        PlayerActions(
            playQueue = { tracks, index -> PlayerManager.playQueue(tracks, index) },
            shufflePlay = { tracks -> PlayerManager.playQueue(tracks, 0, shuffle = true) },
            togglePlayPause = PlayerManager::togglePlayPause,
            next = PlayerManager::next,
            previous = PlayerManager::previous,
            seekTo = { PlayerManager.seekTo(it) },
            toggleShuffle = PlayerManager::toggleShuffle,
            cycleRepeatMode = PlayerManager::cycleRepeatMode,
        )
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // 播放页不再是一个导航目的地，而是盖在主界面上的抽屉 ——
    // 这样下滑收起时下面的歌曲列表仍然在，才能做出「缩回迷你播放器」的连贯动画
    val sheetState = rememberPlayerSheetState()

    // 播放页展开时，系统返回键先收起它
    BackHandler(enabled = sheetState.isOpen) { sheetState.collapse(scope) }

    fun navigateTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    CompositionLocalProvider(
        LocalPlayerState provides playerState,
        LocalPlayerActions provides actions,
        LocalOnlineLyrics provides settings.onlineLyrics,
        LocalOnlineArtwork provides settings.onlineArtwork,
        LocalShowTrackInfo provides settings.showTrackInfo,
        LocalLyricFontScale provides settings.lyricFontScale,
        LocalLyricSpacing provides settings.lyricSpacing,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val wide = maxWidth >= 720.dp
            // 底部栏一直保留：抽屉盖在上面，隐藏/显示会让下层列表重排，动画看着一跳
            val showBars = hasPermission

            // 抽屉还没挂载时也要知道屏幕高度，第一次上滑才能立刻跟手
            val fullHeightPx = constraints.maxHeight.toFloat()
            SideEffect { sheetState.heightPx = fullHeightPx }

            Row(modifier = Modifier.fillMaxSize()) {
                if (wide && showBars) {
                    AppNavigationRail(
                        selectedRoute = currentRoute,
                        onNavigate = ::navigateTab,
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                    )
                }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = NoInsets,
                    bottomBar = {
                        if (showBars) {
                            // 只做高度占位：迷你播放器跟着面板走，导航栏画在最上层，
                            // 这里量出来的高度就是抽屉收起时面板顶边要落到的位置
                            BottomBarPlaceholder(
                                wide = wide,
                                hasMiniPlayer = playerState.current != null,
                                onMeasured = { measured -> sheetState.bottomBarHeightPx = measured },
                            )
                        }
                    },
                ) { padding ->
                    if (!hasPermission) {
                        PermissionView(
                            modifier = Modifier.padding(padding),
                            onGrant = { permissionLauncher.launch(AudioPermission.required()) },
                        )
                    } else {
                        NavHost(
                            navController = navController,
                            startDestination = Routes.SONGS,
                            modifier = Modifier.padding(padding),
                        ) {
                            composable(Routes.SONGS) {
                                SongsScreen(
                                    state = library,
                                    onOpenSettings = {
                                        navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                                    },
                                    onRescan = {
                                        libraryViewModel.refresh(settings.sortOrder, force = true)
                                    },
                                )
                            }

                            composable(Routes.ALBUMS) {
                                AlbumsScreen(
                                    state = library,
                                    onOpenAlbum = { id -> navController.navigate(Routes.album(id)) },
                                    onRescan = {
                                        libraryViewModel.refresh(settings.sortOrder, force = true)
                                    },
                                )
                            }

                            composable(Routes.ARTISTS) {
                                ArtistsScreen(
                                    state = library,
                                    onOpenArtist = { id -> navController.navigate(Routes.artist(id)) },
                                    onRescan = {
                                        libraryViewModel.refresh(settings.sortOrder, force = true)
                                    },
                                )
                            }

                            composable(
                                route = Routes.ALBUM_DETAIL,
                                arguments = listOf(navArgument(Routes.ALBUM_ARG) { type = NavType.LongType }),
                            ) { entry ->
                                val albumId = entry.arguments?.getLong(Routes.ALBUM_ARG) ?: 0L
                                AlbumDetailScreen(
                                    album = library.albums.firstOrNull { it.id == albumId },
                                    onBack = { navController.popBackStack() },
                                )
                            }

                            composable(
                                route = Routes.ARTIST_DETAIL,
                                arguments = listOf(navArgument(Routes.ARTIST_ARG) { type = NavType.LongType }),
                            ) { entry ->
                                val artistId = entry.arguments?.getLong(Routes.ARTIST_ARG) ?: 0L
                                ArtistDetailScreen(
                                    artist = library.artists.firstOrNull { it.id == artistId },
                                    onBack = { navController.popBackStack() },
                                )
                            }

                            composable(Routes.SETTINGS) {
                                SettingsScreen(
                                    settings = settings,
                                    songCount = library.tracks.size,
                                    onThemeMode = { mode ->
                                        scope.launch { SettingsRepository.setThemeMode(context, mode) }
                                    },
                                    onDynamicColor = { enabled ->
                                        scope.launch { SettingsRepository.setDynamicColor(context, enabled) }
                                    },
                                    onSortOrder = { order ->
                                        scope.launch {
                                            SettingsRepository.setSortOrder(context, order)
                                            libraryViewModel.refresh(order, force = true)
                                        }
                                    },
                                    onOnlineLyrics = { enabled ->
                                        scope.launch { SettingsRepository.setOnlineLyrics(context, enabled) }
                                    },
                                    onOnlineArtwork = { enabled ->
                                        scope.launch { SettingsRepository.setOnlineArtwork(context, enabled) }
                                    },
                                    onShowTrackInfo = { enabled ->
                                        scope.launch { SettingsRepository.setShowTrackInfo(context, enabled) }
                                    },
                                    onLyricFontScale = { scale ->
                                        scope.launch { SettingsRepository.setLyricFontScale(context, scale) }
                                    },
                                    onLyricSpacing = { dp ->
                                        scope.launch { SettingsRepository.setLyricSpacing(context, dp) }
                                    },
                                    onRescan = {
                                        libraryViewModel.refresh(settings.sortOrder, force = true)
                                    },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                        }
                    }
                }
            }

            // 播放页抽屉：面板自带背景，迷你播放器是面板顶部的一条，跟着面板一起上下走
            PlayerSheetOverlay(state = sheetState) {
                PlayerSheetContent(
                    state = sheetState,
                    playerState = playerState,
                    actions = actions,
                    albumId = playerState.current?.albumId ?: 0L,
                    onCollapse = { sheetState.collapse(scope) },
                    onExpand = { sheetState.expand(scope) },
                )
            }

            // 底部导航栏画在面板之上（与上游同一套 z 序），展开时淡出并滑出屏幕
            if (showBars && !wide) {
                BottomNavigationBar(
                    selectedRoute = currentRoute,
                    onNavigate = ::navigateTab,
                    progress = sheetState.progress,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/**
 * 面板内部：整块渐变底 + 播放页 + 顶部的迷你播放器。
 *
 * 迷你播放器在**面板顶部**（上游 `sliding_music_panel_layout.xml` 里就是这个结构），
 * 所以它会跟着面板一起上下走，而不是钉在底栏里不动。
 */
@Composable
private fun PlayerSheetContent(
    state: PlayerSheetState,
    playerState: PlayerUiState,
    actions: PlayerActions,
    albumId: Long,
    onCollapse: () -> Unit,
    onExpand: () -> Unit,
) {
    val retro = rememberRetroColors(albumId)
    val background = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to retro.gradientTop,
            0.42f to retro.gradientMiddle,
            1f to retro.gradientBottom,
        ),
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
    ) {
        // 播放页常驻，只改透明度：卸载会丢掉歌词位置和 pager 页，
        // 而且它在迷你播放器下面，不会挡住迷你播放器的点击
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = playerAlphaAt(state.progress) },
        ) {
            NowPlayingScreen(onBack = onCollapse)
        }

        // 迷你播放器完全淡出后卸载（等价上游的 isGone），
        // 否则它透明仍会盖在播放页顶栏上吞掉点击
        val miniAlpha = miniAlphaAt(state.progress)
        if (miniAlpha > 0.001f) {
            MiniPlayer(
                state = playerState,
                actions = actions,
                onExpand = onExpand,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer { alpha = miniAlpha },
            )
        }
    }
}

/** M3 `NavigationBar` 的容器高度（不含系统导航栏 inset），占位要与它对齐。 */
private val NavigationBarHeight = 80.dp

@Composable
private fun BottomBarPlaceholder(
    wide: Boolean,
    hasMiniPlayer: Boolean,
    onMeasured: (Float) -> Unit,
) {
    Column(
        modifier = Modifier.onGloballyPositioned { coordinates ->
            val measured = coordinates.size.height.toFloat()
            if (measured > 0f) onMeasured(measured)
        },
    ) {
        if (hasMiniPlayer) Spacer(modifier = Modifier.height(MiniPlayerHeight))
        if (!wide) Spacer(modifier = Modifier.height(NavigationBarHeight))
        Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun BottomNavigationBar(
    selectedRoute: String?,
    onNavigate: (String) -> Unit,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    // 与迷你播放器同步浮现：收起时完整显示，展开后淡出并整条滑出屏幕
    val alpha = miniAlphaAt(progress)
    NavigationBar(
        modifier = modifier.graphicsLayer {
            this.alpha = alpha
            translationY = size.height * (1f - alpha)
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = selectedRoute == tab.route,
                onClick = { onNavigate(tab.route) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
            )
        }
    }
}

@Composable
private fun AppNavigationRail(
    selectedRoute: String?,
    onNavigate: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Spacer(modifier = Modifier.height(24.dp))
        tabs.forEach { tab ->
            NavigationRailItem(
                selected = selectedRoute == tab.route,
                onClick = { onNavigate(tab.route) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        NavigationRailItem(
            selected = selectedRoute == Routes.SETTINGS,
            onClick = onOpenSettings,
            icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
            label = { Text("设置") },
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun PermissionView(modifier: Modifier = Modifier, onGrant: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.LibraryMusic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "需要音乐访问权限",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "为了扫描并播放你设备上的本地音乐，请授予音频读取权限。应用完全离线运行，不会上传任何数据。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(28.dp))
        Button(onClick = onGrant) { Text("授予权限") }
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = { AudioPermission.openAppSettings(context) }) {
            Text("前往系统设置")
        }
        Spacer(modifier = Modifier.width(8.dp))
    }
}
