package com.alyoushw.watchhub.tv

import com.alyoushw.watchhub.data.*

/** An aired, unfinished item explicitly marked as watching by its owner. */
fun WatchItem.canPublishWatchNext(): Boolean {
    if (status != WatchStatus.WATCHING) return false
    if (type == MediaType.MOVIE) return true
    val next = nextEpisode() ?: return false
    return latestAired == null || next <= latestAired
}
