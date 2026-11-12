package com.alyoushw.watchhub.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * How far ahead the player loads, changeable while playing: about a minute normally (no more of
 * the connection than needed), several minutes when the user asks to load ahead on a slow
 * connection. Kept in memory, so at most a few minutes.
 */
@OptIn(UnstableApi::class)
class FlexibleLoadControl : DefaultLoadControl(
    DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    15_000,
    MAX_MS.toInt(),
    2_500,
    5_000,
    200 * 1024 * 1024,
    true,
    0,
    false,
) {
    /** How much to keep ready ahead now, in ms. */
    @Volatile
    var aheadMs: Long = NORMAL_MS

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        if (parameters.bufferedDurationUs >= aheadMs * 1000) return false
        return super.shouldContinueLoading(parameters)
    }

    companion object {
        const val NORMAL_MS = 60_000L
        private const val MAX_MS = 6 * 60_000L
    }
}
