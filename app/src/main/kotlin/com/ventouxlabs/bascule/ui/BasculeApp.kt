package com.ventouxlabs.bascule.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ventouxlabs.bascule.R
import com.ventouxlabs.bascule.ui.nav.BasculeDestination
import com.ventouxlabs.bascule.ui.theme.BasculeTheme

/**
 * The app shell: a flat bottom-navigation bar across the three top-level
 * screens (`00-design.md` §5), plus a History-only FAB shortcut straight into
 * Manual Entry — the one-tap path for "I want to log a weight right now"
 * without detouring through the tab bar first.
 */
@Composable
fun BasculeApp() {
    BasculeTheme {
        val navController = rememberNavController()
        val currentBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = currentBackStackEntry?.destination

        Scaffold(
            bottomBar = {
                NavigationBar {
                    BasculeDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute?.hierarchy?.any { it.route == destination.route } == true,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            },
            floatingActionButton = {
                if (currentRoute?.hierarchy?.any { it.route == BasculeDestination.History.route } == true) {
                    FloatingActionButton(
                        onClick = {
                            navController.navigate(BasculeDestination.ManualEntry.route) {
                                launchSingleTop = true
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_weight_fab))
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = BasculeDestination.History.route,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(BasculeDestination.History.route) { HistoryScreen() }
                composable(BasculeDestination.ManualEntry.route) {
                    ManualEntryScreen(onSaved = { navController.popBackStack() })
                }
                composable(BasculeDestination.Config.route) { ConfigScreen() }
                composable(BasculeDestination.Scale.route) { ScaleScreen() }
            }
        }
    }
}
