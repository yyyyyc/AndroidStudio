package com.example.superprices

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2E7D32), secondary = Color(0xFFEF6C00))) {
                // Hebrew UI whatever the phone's language.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    App(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** A price-drop notification opens its product. */
    private fun handle(intent: Intent?) {
        val code = intent?.getStringExtra(EXTRA_CODE) ?: return
        intent.removeExtra(EXTRA_CODE)
        vm.tab = Tab.WATCH
        vm.openProduct(code)
    }

    companion object {
        const val EXTRA_CODE = "code"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: AppViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.snack) {
        vm.snack?.let {
            snackbar.showSnackbar(it)
            vm.snack = null
        }
    }

    val scan = vm.scanning
    if (scan != null) {
        BackHandler { vm.scanning = null }
        ScannerScreen(onCode = vm::onScanned, onClose = { vm.scanning = null })
        return
    }

    var editServer by remember { mutableStateOf(false) }
    if (editServer) ServerDialog(vm.serverUrl, onDismiss = { editServer = false }) { editServer = false; vm.saveServer(it) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(vm.product?.let { "פרטי מוצר" } ?: titleOf(vm.tab)) },
                    actions = {
                        IconButton(onClick = vm::refreshPrices, enabled = !vm.refreshing || vm.refreshWaiting) {
                            Icon(Icons.Filled.Refresh, contentDescription = "עדכן מחירים")
                        }
                        IconButton(onClick = { editServer = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "שרת מחירים")
                        }
                    },
                )
                if (vm.refreshing) {
                    LinearProgressIndicator(progress = { vm.refreshProgress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        vm.refreshMsg.orEmpty(),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar {
                for (t in Tab.entries) {
                    NavigationBarItem(
                        selected = vm.tab == t && vm.product == null,
                        onClick = { vm.closeProduct(); vm.openPromo = null; vm.tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.SEARCH -> Icons.Filled.Search
                                    Tab.LIST -> Icons.Filled.ShoppingCart
                                    Tab.PROMOS -> Icons.Filled.Star
                                    Tab.WATCH -> Icons.Filled.Notifications
                                    Tab.STORES -> Icons.Filled.Place
                                },
                                contentDescription = null,
                            )
                        },
                        label = {
                            Text(
                                when (t) {
                                    Tab.LIST -> "רשימה"
                                    Tab.WATCH -> "מעקב"
                                    Tab.STORES -> "חנויות"
                                    else -> titleOf(t)
                                },
                                fontSize = 12.sp,
                            )
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Row(Modifier.fillMaxSize().padding(pad)) {
            val p = vm.product
            if (p != null) {
                BackHandler { vm.closeProduct() }
                ProductScreen(vm, p)
            } else when (vm.tab) {
                Tab.SEARCH -> SearchScreen(vm)
                Tab.LIST -> ListScreen(vm)
                Tab.PROMOS -> PromosScreen(vm)
                Tab.WATCH -> WatchScreen(vm)
                Tab.STORES -> StoresScreen(vm)
            }
        }
    }
}

fun titleOf(t: Tab) = when (t) {
    Tab.SEARCH -> "חיפוש"
    Tab.LIST -> "רשימת קניות"
    Tab.PROMOS -> "מבצעים"
    Tab.WATCH -> "מעקב מחירים"
    Tab.STORES -> "החנויות שלי"
}
