package com.alyoushw.watchhub

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.alyoushw.watchhub.ui.WatchHubRoot
import com.alyoushw.watchhub.ui.WatchHubTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleLink(intent)
        setContent {
            WatchHubTheme {
                WatchHubRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** A TV's "scan to sign in" link (https://<site>/pair?s=<id>): ask this phone to approve it. */
    private fun handleLink(intent: Intent?) {
        // A programme reminder was tapped: open its channel.
        if (intent?.action == com.alyoushw.watchhub.data.Reminders.ACTION_PLAY_LIVE) {
            val container = (application as WatchHubApp).container
            val acc = container.settings.xtreamAccount(intent.getStringExtra(com.alyoushw.watchhub.data.Reminders.EXTRA_ACCOUNT)) ?: return
            val stream = intent.getStringExtra(com.alyoushw.watchhub.data.Reminders.EXTRA_STREAM) ?: return
            val name = intent.getStringExtra(com.alyoushw.watchhub.data.Reminders.EXTRA_CHANNEL).orEmpty()
            val ch = com.alyoushw.watchhub.data.XtreamItem(com.alyoushw.watchhub.data.XtreamKind.LIVE, stream, name, null, null, null)
            com.alyoushw.watchhub.player.PlayerActivity.start(this, container.xtream.playUrls(ch, acc), com.alyoushw.watchhub.data.WatchEntry.live(ch, acc))
            return
        }
        val uri = intent?.data ?: return
        if (uri.path?.startsWith("/pair") == true) {
            (application as WatchHubApp).container.cloud.openPairingLink(uri.getQueryParameter("s"))
        }
    }
}
