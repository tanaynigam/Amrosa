package com.aerion.amrosa.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Settings
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
import com.aerion.amrosa.ui.detail.RecipeDetailScreen
import com.aerion.amrosa.ui.home.HomeScreen
import com.aerion.amrosa.ui.import_recipe.ImportScreen
import com.aerion.amrosa.ui.settings.SettingsScreen

private sealed class BottomTab(val route: String, val label: String, val icon: ImageVector) {
    data object Recipes : BottomTab("recipes_tab", "Recipes", Icons.AutoMirrored.Filled.MenuBook)
    data object Imported : BottomTab("imported_tab", "Imported", Icons.Default.CloudDownload)
}

private val bottomTabs = listOf(BottomTab.Recipes, BottomTab.Imported)

@Composable
fun AmrosaNavGraph() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Show bottom bar only on the two main tabs
    val showBottomBar = currentDestination?.route in bottomTabs.map { it.route }

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
            startDestination = BottomTab.Recipes.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(BottomTab.Recipes.route) {
                HomeScreen(
                    onRecipeClick = { recipeId -> navController.navigate("recipe/$recipeId") },
                    onSettingsClick = { navController.navigate("settings") }
                )
            }

            composable(BottomTab.Imported.route) {
                ImportScreen(
                    onRecipeClick = { recipeId -> navController.navigate("recipe/$recipeId") }
                )
            }

            composable("settings") {
                SettingsScreen(onBack = { navController.popBackStack() })
            }

            composable(
                route = "recipe/{recipeId}",
                arguments = listOf(navArgument("recipeId") { type = NavType.StringType })
            ) { backStackEntry ->
                val recipeId = backStackEntry.arguments?.getString("recipeId") ?: return@composable
                RecipeDetailScreen(
                    recipeId = recipeId,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
