package com.alyoushw.watchhub

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.alyoushw.watchhub.ui.WatchHubRoot
import com.alyoushw.watchhub.ui.WatchHubTheme

class MainActivity : ComponentActivity() {
    private var openSerial by androidx.compose.runtime.mutableIntStateOf(0)
    private var openRequest by androidx.compose.runtime.mutableStateOf<android.net.Uri?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequest = intent.data
        openSerial++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openRequest = intent.data
        enableEdgeToEdge()
        setContent {
            WatchHubTheme {
                WatchHubRoot(openRequest, openSerial)
            }
        }
    }
}
