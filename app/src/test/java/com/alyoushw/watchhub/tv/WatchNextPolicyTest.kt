package com.alyoushw.watchhub.tv

import com.alyoushw.watchhub.data.*
import org.junit.Assert.*
import org.junit.Test

class WatchNextPolicyTest {
    private fun item()=WatchItem(5,MediaType.TV,"Test",status=WatchStatus.WATCHING,
        seasons=listOf(SeasonInfo(1,2),SeasonInfo(2,2)),latestAired=EpisodeMark(2,2))
    @Test fun plannedAndDoneNeverAppear(){
        assertFalse(item().copy(status=WatchStatus.PLANNED).canPublishWatchNext())
        assertFalse(item().copy(status=WatchStatus.DONE).canPublishWatchNext())
    }
    @Test fun firstEpisodeIsOffered(){assertEquals(EpisodeMark(1,1),item().nextEpisode());assertTrue(item().canPublishWatchNext())}
    @Test fun nextSeasonAfterFinalEpisode(){assertEquals(EpisodeMark(2,1),item().copy(progress=EpisodeMark(1,2)).nextEpisode())}
    @Test fun finishedSeriesIsRemoved(){assertFalse(item().copy(progress=EpisodeMark(2,2)).canPublishWatchNext())}
    @Test fun unairedEpisodeIsNotAdvertised(){assertFalse(item().copy(progress=EpisodeMark(1,2),latestAired=EpisodeMark(1,2)).canPublishWatchNext())}
    @Test fun moviesDoNotInventEpisodeNumbers(){assertTrue(item().copy(type=MediaType.MOVIE).canPublishWatchNext())}
}
