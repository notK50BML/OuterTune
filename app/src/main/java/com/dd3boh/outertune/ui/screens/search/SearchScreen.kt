package com.dd3boh.outertune.ui.screens.search

import android.util.Log
import com.dd3boh.outertune.constants.UI_DEBUG
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.core.net.toUri
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AppBarHeight
import com.dd3boh.outertune.constants.DEFAULT_ENABLED_TABS
import com.dd3boh.outertune.constants.EnabledTabsKey
import com.dd3boh.outertune.constants.ShowTopBarLogoKey
import com.dd3boh.outertune.constants.PauseSearchHistoryKey
import com.dd3boh.outertune.constants.SearchSource
import com.dd3boh.outertune.constants.SearchSourceKey
import com.dd3boh.outertune.constants.UpdateAvailableKey
import com.dd3boh.outertune.db.entities.SearchHistory
import com.dd3boh.outertune.extensions.tabMode
import com.dd3boh.outertune.ui.component.AccountAvatar
import com.dd3boh.outertune.ui.component.InputFieldHeight
import com.dd3boh.outertune.ui.component.SearchBar
import com.dd3boh.outertune.ui.component.SearchBarHorizontalPadding
import com.dd3boh.outertune.ui.component.SearchBarVerticalPadding
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.screens.Screens
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.utils.urlEncode
import com.dd3boh.outertune.youtubeNavigator
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBarContainer(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    if (UI_DEBUG) Log.v("SearchBarContainer", "SB-1")
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val database = LocalDatabase.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val playerConnection = LocalPlayerConnection.current

    val enabledTabs by rememberPreference(EnabledTabsKey, defaultValue = DEFAULT_ENABLED_TABS)
    var searchSource by rememberEnumPreference(SearchSourceKey, SearchSource.ONLINE)
    val updateAvailable by rememberPreference(UpdateAvailableKey, defaultValue = false)
    val showTopBarLogo by rememberPreference(ShowTopBarLogoKey, defaultValue = true)

    val navigationItems = remember { Screens.getScreens(enabledTabs) }
    val searchBarFocusRequester = remember { FocusRequester() }
    val snackbarHostState = remember { SnackbarHostState() }

    val (query, onQueryChange) = rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()

    var searchActive by rememberSaveable {
        mutableStateOf(false)
    }
    val onSearchActiveChange: (Boolean) -> Unit = { newActive ->
        searchActive = newActive
        if (!newActive) {
            focusManager.clearFocus()
            if (navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route }) {
                onQueryChange(TextFieldValue())
            }
        }
    }

    val onSearch: (String) -> Unit = {
        if (it.isNotEmpty()) {
            if (searchSource == SearchSource.LOCAL) {
                focusManager.clearFocus(true)
            } else {
                onSearchActiveChange(false)
                if (youtubeNavigator(
                        context,
                        navController,
                        coroutineScope,
                        playerConnection,
                        snackbarHostState,
                        it.toUri()
                    )
                ) {
                    // don't do anything
                } else {
                    navController.navigate("search/${it.urlEncode()}")
                    if (context.dataStore[PauseSearchHistoryKey] != true) {
                        database.query {
                            insert(SearchHistory(query = it))
                        }
                    }
                }
            }
        }
    }


    val isTabRoute = navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route }
    val isSearchRoute = navBackStackEntry?.destination?.route?.startsWith("search/") == true
    val showSearchBar = searchActive || isSearchRoute
    val showIconRow = isTabRoute && !searchActive && !isSearchRoute

    LaunchedEffect(searchActive) {
        if (searchActive) {
            delay(100)
            searchBarFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    val savedHeightOffsets = remember { mutableMapOf<String, Float>() }
    var previousRoute by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(navBackStackEntry) {
        if (searchActive) {
            onSearchActiveChange(false)
        }
        val currentRoute = navBackStackEntry?.destination?.route
        previousRoute?.let { savedHeightOffsets[it] = scrollBehavior.state.heightOffset }
        if (currentRoute?.startsWith("${Screens.Folders.route}/") == true) {
            savedHeightOffsets[Screens.Folders.route] = 0f
        } else {
            scrollBehavior.state.heightOffset = savedHeightOffsets[currentRoute] ?: 0f
            scrollBehavior.state.contentOffset = 0f
        }
        previousRoute = currentRoute
    }

    AnimatedVisibility(
        visible = showSearchBar,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        val searchBarInset = if (!context.tabMode()) {
            WindowInsets.safeDrawing.union(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Start))
        }
        else {
            WindowInsets.systemBars.only(WindowInsetsSides.Top)
        }
        SearchBar(
            query = query,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            active = searchActive,
            onActiveChange = onSearchActiveChange,
            scrollBehavior = scrollBehavior,
            placeholder = {
                Text(
                    text = stringResource(
                        if (!searchActive) R.string.search
                        else when (searchSource) {
                            SearchSource.LOCAL -> R.string.search_library
                            SearchSource.ONLINE -> R.string.search_yt_music
                        }
                    )
                )
            },
            leadingIcon = {
                IconButton(
                    onClick = {
                        when {
                            searchActive -> onSearchActiveChange(false)

                            !searchActive && navBackStackEntry?.destination?.route?.startsWith(
                                "search"
                            ) == true -> {
                                navController.navigateUp()
                            }

                            else -> onSearchActiveChange(true)
                        }
                    },
                ) {
                    Icon(
                        imageVector =
                            if (searchActive || navBackStackEntry?.destination?.route?.startsWith("search") == true) {
                                Icons.AutoMirrored.Rounded.ArrowBack
                            } else {
                                Icons.Rounded.Search
                            },
                        contentDescription = null
                    )
                }
            },
            trailingIcon = {
                if (searchActive) {
                    if (query.text.isNotEmpty()) {
                        IconButton(
                            onClick = { onQueryChange(TextFieldValue("")) }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = null
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            searchSource =
                                if (searchSource == SearchSource.ONLINE) SearchSource.LOCAL else SearchSource.ONLINE
                        }
                    ) {
                        Icon(
                            imageVector = when (searchSource) {
                                SearchSource.LOCAL -> Icons.Rounded.LibraryMusic
                                SearchSource.ONLINE -> Icons.Rounded.Language
                            },
                            contentDescription = null
                        )
                    }
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable {
                                navController.navigate("settings")
                            }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = null
                        )
                    }
                }
            },
            windowInsets = searchBarInset,
            focusRequester = searchBarFocusRequester,
        ) {
            if (UI_DEBUG) Log.v("SearchBarContainer", "SB-2")
            Crossfade(
                targetState = searchSource,
                label = "",
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) { searchSource ->
                when (searchSource) {
                    SearchSource.LOCAL -> LocalSearchScreen(
                        query = query.text,
                        navController = navController,
                        onDismiss = { onSearchActiveChange(false) },
                    )

                    SearchSource.ONLINE -> OnlineSearchScreen(
                        query = query.text,
                        onQueryChange = onQueryChange,
                        navController = navController,
                        onSearch = {
                            if (youtubeNavigator(
                                    context,
                                    navController,
                                    coroutineScope,
                                    playerConnection,
                                    snackbarHostState,
                                    it.toUri()
                                )
                            ) {
                                return@OnlineSearchScreen
                            } else {
                                navController.navigate("search/${it.urlEncode()}")
                                if (context.dataStore[PauseSearchHistoryKey] != true) {
                                    database.query {
                                        insert(SearchHistory(query = it))
                                    }
                                }
                            }
                        },
                        onDismiss = { onSearchActiveChange(false) },
                    )
                }
            }
        }
    }

    AnimatedVisibility(
        visible = showIconRow,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        val iconRowInset = if (!context.tabMode()) {
            WindowInsets.safeDrawing.union(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Start))
        } else {
            WindowInsets.systemBars.only(WindowInsetsSides.Top)
        }
        TopIconBar(
            scrollBehavior = scrollBehavior,
            windowInsets = iconRowInset,
            showLogo = showTopBarLogo,
            onRecognizeClick = { navController.navigate("recognition") },
            onStatsClick = { navController.navigate("stats") },
            onSettingsClick = { navController.navigate("settings") },
            onAccountClick = { navController.navigate("settings/account_sync") },
            onSearchClick = { onSearchActiveChange(true) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopIconBar(
    scrollBehavior: TopAppBarScrollBehavior,
    windowInsets: WindowInsets,
    showLogo: Boolean,
    onRecognizeClick: () -> Unit,
    onStatsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAccountClick: () -> Unit,
    onSearchClick: () -> Unit,
) {
    val heightOffsetLimit = with(LocalDensity.current) {
        -(AppBarHeight.toPx() + WindowInsets.systemBars.getTop(this))
    }
    SideEffect {
        if (scrollBehavior.state.heightOffsetLimit != heightOffsetLimit) {
            scrollBehavior.state.heightOffsetLimit = heightOffsetLimit
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .offset {
                IntOffset(x = 0, y = scrollBehavior.state.heightOffset.roundToInt())
            }
            .fillMaxWidth()
            .windowInsetsPadding(windowInsets)
            .padding(horizontal = SearchBarHorizontalPadding, vertical = SearchBarVerticalPadding)
            .height(InputFieldHeight)
    ) {
        if (showLogo) {
            Image(
                painter = painterResource(R.drawable.app_logo),
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(36.dp)
            )
            Spacer(Modifier.width(8.dp))
        }

        // A field rather than a magnifying glass. It does not accept typing here - tapping it
        // opens the real search bar, which is the same component the rest of the app uses - but
        // it reads as somewhere to type, which a 24dp icon in a row of five identical icons did
        // not. With the logo hidden it runs to the left edge and the row becomes the search.
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(CircleShape)
                .clickable(onClick = onSearchClick)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = stringResource(R.string.search),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.search),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(4.dp))

        IconButton(onClick = onRecognizeClick) {
            Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = stringResource(R.string.recognition)
            )
        }
        IconButton(onClick = onStatsClick) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.TrendingUp,
                contentDescription = stringResource(R.string.stats)
            )
        }
        IconButton(onClick = onSettingsClick) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.settings)
            )
        }
        IconButton(onClick = onAccountClick) {
            AccountAvatar(contentDescription = stringResource(R.string.account)) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = stringResource(R.string.account)
                )
            }
        }
    }
}
