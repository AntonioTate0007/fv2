package com.thumbshade.app.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.thumbshade.app.notif.NotifOps
import com.thumbshade.app.notif.NotificationRepo
import com.thumbshade.app.overlay.OverlayService
import com.thumbshade.app.overlay.ShadeScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var pendingRulePkg by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // If the app crashed last time, show the report in plain Android views first, so it can be
        // read even when the crash is in the normal (Compose) screens.
        val crash = com.thumbshade.app.CrashLog.read(this)
        if (crash != null) {
            setContentView(CrashReportView.build(this, crash) {
                com.thumbshade.app.CrashLog.clear(this)
                recreate()
            })
            return
        }
        handle(intent)
        setContent {
            ThumbTheme {
                AppRoot(
                    pendingRulePkg = pendingRulePkg,
                    onRulePkgConsumed = { pendingRulePkg = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_NEW_RULE_FOR_APP) pendingRulePkg = intent.getStringExtra(EXTRA_PKG)
    }

    companion object {
        const val ACTION_NEW_RULE_FOR_APP = "com.thumbshade.app.NEW_RULE_FOR_APP"
        const val EXTRA_PKG = "pkg"
    }
}

private enum class AppTab(val label: String, val icon: ImageVector) {
    GENERAL("General", Icons.Filled.Tune),
    NOTIFICATIONS("Notifications", Icons.Filled.Notifications),
    BUTTON("Button", Icons.Filled.RadioButtonChecked),
    SHADE("Shade", Icons.Filled.ViewDay),
    RULES("Rules", Icons.Filled.Rule),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(pendingRulePkg: String?, onRulePkgConsumed: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    if (pendingRulePkg != null && tab != AppTab.RULES.ordinal) tab = AppTab.RULES.ordinal

    val s by com.thumbshade.app.data.SettingsRepo.state.collectAsState()
    Scaffold(
        topBar = {
            Column(Modifier.padding(top = 40.dp)) {
                Text(
                    "ThumbShade",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                )
                if (!s.tabBarBottom) {
                    PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                        AppTab.entries.forEach { t ->
                            Tab(selected = tab == t.ordinal, onClick = { tab = t.ordinal }, text = { Text(t.label, maxLines = 1) })
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (s.tabBarBottom) {
                NavigationBar {
                    AppTab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t.ordinal,
                            onClick = { tab = t.ordinal },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label, maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (AppTab.entries[tab] == AppTab.RULES) {
            RulesScreen(Modifier.padding(padding), pendingRulePkg, onRulePkgConsumed)
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(PaddingValues(horizontal = 14.dp, vertical = 8.dp)),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (AppTab.entries[tab]) {
                    AppTab.GENERAL -> GeneralScreen()
                    AppTab.NOTIFICATIONS -> NotificationsScreen()
                    AppTab.BUTTON -> ButtonScreen()
                    AppTab.SHADE -> ShadeSettingsScreen()
                    AppTab.RULES -> Unit
                }
            }
        }
    }
}

/** Shade as an activity: used on the lock screen, and when the overlay can't be shown. */
class ShadeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        val state = MutableTransitionState(false).apply { targetState = true }
        setContent {
            ShadeScreen(
                visibleState = state,
                onClose = {
                    state.targetState = false
                    lifecycleScope.launch {
                        delay(350)
                        finish()
                    }
                },
                onOpenSettings = {
                    val km = getSystemService(KeyguardManager::class.java)
                    km?.requestDismissKeyguard(this, null)
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                },
            )
        }
    }
}

/** Entry point for widgets, the "Open shade" icon and automation apps. */
class ShowShadeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent?.getStringExtra(EXTRA_KEY)
        if (key != null) {
            NotificationRepo.get(key)?.let { NotifOps.open(this, it) }
            finish()
            return
        }
        val km = getSystemService(KeyguardManager::class.java)
        val svc = OverlayService.instance
        when {
            km?.isKeyguardLocked == true -> startActivity(Intent(this, ShadeActivity::class.java))
            svc != null -> svc.openShade()
            Settings.canDrawOverlays(this) -> OverlayService.send(this, OverlayService.ACTION_OPEN_SHADE)
            else -> startActivity(Intent(this, ShadeActivity::class.java))
        }
        finish()
    }

    companion object {
        const val EXTRA_KEY = "key"
    }
}
