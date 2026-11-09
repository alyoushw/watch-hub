package com.alyoushw.watchhub.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.alyoushw.watchhub.player.YouTubeTvActivity

/** The "يوتيوب" tab: opens YouTube itself (with the picture tools) at once; reopen from here. */
@Composable
fun YouTubeLauncher() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { YouTubeTvActivity.start(context) }
    MessageBox(
        title = "يوتيوب",
        body = "أدوات حجم الصورة في الزر الصغير ⛶ على يسار الشاشة (أو زر القائمة في الريموت). رجوع مرتين بسرعة يطلعك.",
        actionLabel = "افتح يوتيوب",
        onAction = { YouTubeTvActivity.start(context) },
    )
}
