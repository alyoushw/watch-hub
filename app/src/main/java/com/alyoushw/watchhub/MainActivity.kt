package com.alyoushw.watchhub

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
        setContent {
            WatchHubTheme {
                WatchHubRoot()
            }
        }
    }
}
