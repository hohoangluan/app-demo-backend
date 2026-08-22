package com.youreyes.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.youreyes.app.ui.activitylog.ActivityLogRoute
import com.youreyes.app.ui.album.AlbumRoute
import com.youreyes.app.ui.auth.AuthRoute
import com.youreyes.app.ui.community.CommunityScreen
import com.youreyes.app.ui.display.DisplayRoute
import com.youreyes.app.ui.features.FeaturesScreen
import com.youreyes.app.ui.functiontest.FunctionTestRoute
import com.youreyes.app.ui.glasses.GlassesLinkRoute
import com.youreyes.app.ui.guide.UserGuideScreen
import com.youreyes.app.ui.meeting.MeetingRoute
import com.youreyes.app.ui.overview.OverviewRoute
import com.youreyes.app.ui.profile.ProfileRoute
import com.youreyes.app.ui.safety.SafetyScreen
import com.youreyes.app.ui.support.SupportRoute
import com.youreyes.app.ui.theme.AppTheme
import com.youreyes.app.ui.translation.TranslationRoute

/**
 * Navigation.
 *
 * This replaces a `var selectedTab: Int` with a `when (selectedTab)` over
 * thirteen branches. That structure had one consequence worth spelling out,
 * because it is the kind of bug that only bites the people this app is for: it
 * had no back stack, so the system Back button on any of the eight screens
 * reachable only from a card — Album, Nhật ký, Hỗ trợ, Tài khoản, and the rest —
 * closed the app instead of returning to where you came from. Someone navigating
 * by touch exploration cannot see that they have left; they simply find the app
 * gone.
 *
 * Five destinations sit in the bottom bar. Everything else is pushed onto the
 * stack, which means Back works, and it means each of those screens can show a
 * back affordance of its own rather than depending on a hardware gesture.
 */
object Routes {
    // Bottom bar
    const val HOME = "home"
    const val FEATURES = "features"
    const val SAFETY = "safety"
    const val COMMUNITY = "community"
    const val PROFILE = "profile"

    // Pushed
    const val GLASSES = "glasses"
    const val ACTIVITY_LOG = "activity-log"
    const val AUTH = "auth"
    const val SUPPORT = "support"
    const val ALBUM = "album"
    const val GUIDE = "guide"
    const val TRANSLATION = "translation"
    const val MEETING = "meeting"
    const val DISPLAY = "display"
    const val DEV_TEST = "dev-test"
}

private data class BottomDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val bottomDestinations = listOf(
    BottomDestination(Routes.HOME, "Trang chủ", Icons.Default.Home),
    BottomDestination(Routes.FEATURES, "Tính năng", Icons.Default.Star),
    BottomDestination(Routes.SAFETY, "An toàn", Icons.Default.Favorite),
    BottomDestination(Routes.COMMUNITY, "Cộng đồng", Icons.Default.Share),
    BottomDestination(Routes.PROFILE, "Hồ sơ", Icons.Default.Person),
)

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (currentRoute in bottomDestinations.map { it.route }) {
                AppBottomBar(navController = navController, currentRoute = currentRoute)
            }
        },
    ) { padding ->
        val screenModifier = Modifier
            .fillMaxSize()
            .padding(padding)

        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize(),
        ) {
            appDestinations(navController, screenModifier)
        }
    }
}

private fun NavGraphBuilder.appDestinations(
    navController: NavHostController,
    modifier: Modifier,
) {
    composable(Routes.HOME) {
        OverviewRoute(
            modifier = modifier,
            onNavigateToFeatures = { navController.navigate(Routes.FEATURES) },
            onNavigateToProfile = { navController.navigate(Routes.PROFILE) },
            onNavigateToActivityLog = { navController.navigate(Routes.ACTIVITY_LOG) },
            onNavigateToAlbum = { navController.navigate(Routes.ALBUM) },
            onNavigateToGuide = { navController.navigate(Routes.GUIDE) },
            onNavigateToTranslation = { navController.navigate(Routes.TRANSLATION) },
            onNavigateToMeeting = { navController.navigate(Routes.MEETING) },
        )
    }
    composable(Routes.FEATURES) { FeaturesScreen(modifier = modifier) }
    composable(Routes.SAFETY) {
        SafetyScreen(
            modifier = modifier,
            onNavigateToProfile = { navController.navigate(Routes.PROFILE) },
        )
    }
    composable(Routes.COMMUNITY) { CommunityScreen(modifier = modifier) }
    composable(Routes.PROFILE) {
        ProfileRoute(
            modifier = modifier,
            onNavigateToDevTest = { navController.navigate(Routes.DEV_TEST) },
            onNavigateToAuth = { navController.navigate(Routes.AUTH) },
            onNavigateToSupport = { navController.navigate(Routes.SUPPORT) },
            onNavigateToDisplay = { navController.navigate(Routes.DISPLAY) },
            onNavigateToGlasses = { navController.navigate(Routes.GLASSES) },
        )
    }

    composable(Routes.GLASSES) { GlassesLinkRoute(modifier = modifier) }
    composable(Routes.ACTIVITY_LOG) { ActivityLogRoute(modifier = modifier) }
    composable(Routes.AUTH) { AuthRoute(modifier = modifier) }
    composable(Routes.SUPPORT) { SupportRoute(modifier = modifier) }
    composable(Routes.ALBUM) { AlbumRoute(modifier = modifier) }
    composable(Routes.GUIDE) { UserGuideScreen(modifier = modifier) }
    composable(Routes.TRANSLATION) { TranslationRoute(modifier = modifier) }
    composable(Routes.MEETING) { MeetingRoute(modifier = modifier) }
    composable(Routes.DISPLAY) { DisplayRoute(modifier = modifier) }
    composable(Routes.DEV_TEST) { FunctionTestRoute(modifier = modifier) }
}

/**
 * Bottom bar.
 *
 * Taller and larger than the Material default: 88dp with 30dp icons and a label
 * that is always shown. The labels were 11sp and the icons 20dp, which is the
 * densest control in the app sitting at the smallest size in the app.
 *
 * Selection is announced through [stateDescription] as well as the selected
 * flag, because "Trang chủ, đang mở" tells someone where they are and a
 * highlighted pill does not.
 */
@Composable
private fun AppBottomBar(navController: NavHostController, currentRoute: String?) {
    val colors = AppTheme.colors

    NavigationBar(
        containerColor = colors.surface,
        contentColor = colors.ink,
        tonalElevation = 8.dp,
        modifier = Modifier.defaultMinSize(minHeight = 88.dp),
    ) {
        bottomDestinations.forEach { destination ->
            val isSelected = currentRoute == destination.route
            NavigationBarItem(
                selected = isSelected,
                onClick = {
                    if (!isSelected) {
                        navController.navigate(destination.route) {
                            popUpTo(Routes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (isSelected) colors.accentSoft else Color.Transparent,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = null,
                            tint = if (isSelected) colors.accentText else colors.inkSecondary,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                },
                label = {
                    Text(
                        text = destination.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) colors.accentText else colors.inkSecondary,
                    )
                },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = Color.Transparent,
                ),
                modifier = Modifier.semantics {
                    stateDescription = if (isSelected) "đang mở" else "chưa mở"
                },
            )
        }
    }
}
