package com.aerion.amrosa.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.aerion.amrosa.ui.account.AccountScreen
import com.aerion.amrosa.ui.auth.AuthScreen
import com.aerion.amrosa.ui.detail.RecipeDetailScreen
import com.aerion.amrosa.ui.edit.RecipeEditorScreen
import com.aerion.amrosa.ui.freeform.FreeformEntryScreen
import com.aerion.amrosa.ui.home.HomeScreen
import com.aerion.amrosa.ui.home.RecipeFilter
import com.aerion.amrosa.ui.import_recipe.ImportScreen
import com.aerion.amrosa.ui.shared.SharedRecipeDetailScreen
import com.aerion.amrosa.ui.shared.SharedScreen

private sealed class BottomTab(val route: String, val label: String, val icon: ImageVector) {
    data object All      : BottomTab("all_tab",      "All",      Icons.AutoMirrored.Filled.MenuBook)
    data object Personal : BottomTab("personal_tab", "Personal", Icons.Default.Bookmarks)
    data object Imported : BottomTab("imported_tab", "Imported", Icons.Default.CloudDownload)
    data object Shared   : BottomTab("shared_tab",   "Shared",   Icons.Default.Public)
    data object Account  : BottomTab("account_tab",  "Account",  Icons.Default.Person)
}

private val bottomTabs = listOf(
    BottomTab.All,
    BottomTab.Personal,
    BottomTab.Imported,
    BottomTab.Shared,
    BottomTab.Account
)

@Composable
fun AmrosaNavGraph() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val tabRoutes = bottomTabs.map { it.route }.toSet()
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
            startDestination = BottomTab.All.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            // ── Tab: All recipes ──────────────────────────────────────────────
            composable(BottomTab.All.route) {
                HomeScreen(
                    filter = RecipeFilter.ALL,
                    onRecipeClick = { recipeId -> navController.navigate("recipe/$recipeId") },
                    onSettingsClick = {
                        navController.navigate(BottomTab.Account.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }

            // ── Tab: Personal recipes ─────────────────────────────────────────
            composable(BottomTab.Personal.route) {
                HomeScreen(
                    filter = RecipeFilter.PERSONAL,
                    onRecipeClick = { recipeId -> navController.navigate("recipe/$recipeId") },
                    onFabFreeformClick = { navController.navigate("freeform") },
                    onFabImportClick = {
                        navController.navigate(BottomTab.Imported.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }

            // ── Tab: Imported recipes ─────────────────────────────────────────
            composable(BottomTab.Imported.route) {
                ImportScreen(
                    onRecipeClick = { recipeId -> navController.navigate("recipe/$recipeId") },
                    onEditClick = { recipeId -> navController.navigate("recipe/edit/$recipeId") }
                )
            }

            // ── Tab: Shared recipes ───────────────────────────────────────────
            composable(BottomTab.Shared.route) {
                SharedScreen(
                    onOwnRecipeClick = { recipeId -> navController.navigate("recipe/$recipeId") },
                    onSharedRecipeClick = { recipeId -> navController.navigate("shared/$recipeId") }
                )
            }

            // ── Tab: Account ──────────────────────────────────────────────────
            composable(BottomTab.Account.route) {
                AccountScreen(onSignInClick = { navController.navigate("auth") })
            }

            // ── Auth ──────────────────────────────────────────────────────────
            composable("auth") {
                AuthScreen(onBack = { navController.popBackStack() })
            }

            // ── Freeform recipe entry ─────────────────────────────────────────
            composable("freeform") {
                FreeformEntryScreen(
                    onBack = { navController.popBackStack() },
                    onEditClick = { recipeId -> navController.navigate("recipe/edit/$recipeId") }
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
                arguments = listOf(navArgument("recipeId") { type = NavType.StringType })
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
