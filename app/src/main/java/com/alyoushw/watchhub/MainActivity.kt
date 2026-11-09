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
        val uri = intent?.data ?: return
        if (uri.path?.startsWith("/pair") == true) {
            (application as WatchHubApp).container.cloud.openPairingLink(uri.getQueryParameter("s"))
        }
    }
}
