package com.aerion.amrosa.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.aerion.amrosa.AmrosaApplication
import com.aerion.amrosa.ui.account.AccountScreen
import com.aerion.amrosa.ui.auth.AuthScreen
import com.aerion.amrosa.ui.detail.RecipeDetailScreen
import com.aerion.amrosa.ui.discover.DiscoverScreen
import com.aerion.amrosa.ui.edit.RecipeEditorScreen
import com.aerion.amrosa.ui.freeform.FreeformEntryScreen
import com.aerion.amrosa.ui.home.HomeScreen
import com.aerion.amrosa.ui.home.RecipeFilter
import com.aerion.amrosa.ui.import_recipe.ImportScreen
import com.aerion.amrosa.ui.shared.SharedRecipeDetailScreen
import com.aerion.amrosa.ui.social.FriendsScreen
import com.aerion.amrosa.ui.social.ReceivedRecipeScreen
import com.aerion.amrosa.ui.social.SharedInboxScreen
import com.aerion.amrosa.ui.social.UserSearchScreen

private sealed class BottomTab(val route: String, val label: String, val icon: ImageVector) {
    data object Yours    : BottomTab("yours_tab",    "My Recipes",   Icons.Default.Bookmarks)
    data object Shared   : BottomTab("shared_tab",   "Shared",       Icons.Default.Inbox)
    data object Discover : BottomTab("discover_tab", "Discover",     Icons.Default.AutoAwesome)
    data object Account  : BottomTab("account_tab",  "Account",      Icons.Default.Person)
}

private val bottomTabs = listOf(
    BottomTab.Yours,
    BottomTab.Shared,
    BottomTab.Discover,
    BottomTab.Account
)

@Composable
fun AmrosaNavGraph() {
    val app = LocalContext.current.applicationContext as AmrosaApplication

    // Observe auth state — drives the auth gate
    val currentUser by app.container.authRepository.authStateFlow()
        .collectAsState(initial = app.container.authRepository.currentUser)

    val isSignedIn = currentUser?.isAnonymous == false

    if (!isSignedIn) {
        // ── Auth gate ─────────────────────────────────────────────────────────
        AuthScreen()
    } else {
        // ── Main app ──────────────────────────────────────────────────────────
        MainAppScaffold()
    }
}

@Composable
private fun MainAppScaffold() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val tabRoutes = remember { bottomTabs.map { it.route }.toSet() }
    val showBottomBar = currentDestination?.route in tabRoutes

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomTabs.forEach { tab ->
                        NavigationBarItem(
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = BottomTab.Yours.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            // ── Tab: Your Recipes ─────────────────────────────────────────────
            composable(BottomTab.Yours.route) {
                HomeScreen(
                    filter = RecipeFilter.YOURS,
                    onRecipeClick = { recipeId -> navController.navigate("recipe/$recipeId") },
                    onNeedsReviewClick = { recipeId -> navController.navigate("import?reviewId=$recipeId") },
                    onFabFreeformClick = { navController.navigate("freeform") },
                    onFabImportClick = { navController.navigate("import") }
                )
            }

            // ── Tab: Shared with me ───────────────────────────────────────────
            composable(BottomTab.Shared.route) {
                SharedInboxScreen(
                    onItemClick = { shareId -> navController.navigate("received/$shareId") }
                )
            }

            // ── Tab: Discover (placeholder) ───────────────────────────────────
            composable(BottomTab.Discover.route) {
                DiscoverScreen()
            }

            // ── Tab: Account ──────────────────────────────────────────────────
            composable(BottomTab.Account.route) {
                AccountScreen(
                    onFindPeopleClick = { navController.navigate("user_search") },
                    onFriendsClick = { navController.navigate("friends") }
                )
            }

            // ── Friends list ──────────────────────────────────────────────────
            composable("friends") {
                FriendsScreen(onBack = { navController.popBackStack() })
            }

            // ── User search / follow people ───────────────────────────────────
            composable("user_search") {
                UserSearchScreen(onBack = { navController.popBackStack() })
            }

            // ── Received recipe (shared directly to me) ───────────────────────
            composable(
                route = "received/{shareId}",
                arguments = listOf(navArgument("shareId") { type = NavType.StringType })
            ) { backStackEntry ->
                val shareId = backStackEntry.arguments?.getString("shareId") ?: return@composable
                ReceivedRecipeScreen(
                    shareId = shareId,
                    onBack = { navController.popBackStack() },
                    // After saving, go back to the Shared tab — the card stays there,
                    // and the saved copy is now also in My Recipes
                    onSaved = { navController.popBackStack() }
                )
            }

            // ── Freeform recipe entry ─────────────────────────────────────────
            composable("freeform") {
                FreeformEntryScreen(
                    onBack = { navController.popBackStack() },
                    onEditClick = { recipeId -> navController.navigate("recipe/edit/$recipeId") }
                )
            }

            // ── Import ────────────────────────────────────────────────────────
            composable(
                route = "import?reviewId={reviewId}",
                arguments = listOf(
                    navArgument("reviewId") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { backStackEntry ->
                val reviewId = backStackEntry.arguments?.getString("reviewId")?.ifBlank { null }
                ImportScreen(
                    onBack = { navController.popBackStack() },
                    onRecipeClick = { recipeId -> navController.navigate("recipe/$recipeId") },
                    onEditClick = { recipeId -> navController.navigate("recipe/edit/$recipeId") },
                    reviewRecipeId = reviewId
                )
            }

            // ── Recipe detail (owner / Room-based) ────────────────────────────
            composable(
                route = "recipe/{recipeId}",
                arguments = listOf(navArgument("recipeId") { type = NavType.StringType })
            ) { backStackEntry ->
                val recipeId = backStackEntry.arguments?.getString("recipeId") ?: return@composable
                RecipeDetailScreen(
                    recipeId = recipeId,
                    onBack = { navController.popBackStack() },
                    onEditClick = { navController.navigate("recipe/edit/$recipeId") }
                )
            }

            // ── Shared recipe detail (visitor / Firestore-based) ──────────────
            composable(
                route = "shared/{recipeId}",
                arguments = listOf(navArgument("recipeId") { type = NavType.StringType }),
                deepLinks = listOf(
                    navDeepLink { uriPattern = "https://amrosa-2ec82.web.app/shared/{recipeId}" },
                    navDeepLink { uriPattern = "amrosa://shared/{recipeId}" }
                )
            ) { backStackEntry ->
                val recipeId = backStackEntry.arguments?.getString("recipeId") ?: return@composable
                SharedRecipeDetailScreen(
                    recipeId = recipeId,
                    onBack = { navController.popBackStack() }
                )
            }

            // ── Recipe editor ─────────────────────────────────────────────────
            composable(
                route = "recipe/edit/{recipeId}",
                arguments = listOf(navArgument("recipeId") { type = NavType.StringType })
            ) { backStackEntry ->
                val recipeId = backStackEntry.arguments?.getString("recipeId") ?: return@composable
                RecipeEditorScreen(
                    recipeId = recipeId,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
