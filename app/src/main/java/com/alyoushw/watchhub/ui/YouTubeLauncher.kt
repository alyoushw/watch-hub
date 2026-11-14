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
        body = "على يسار الشاشة: رجوع، والرئيسية، و⛶ حجم الصورة (أو زر القائمة في الريموت).",
        actionLabel = "افتح يوتيوب",
        onAction = { YouTubeTvActivity.start(context) },
    )
}
