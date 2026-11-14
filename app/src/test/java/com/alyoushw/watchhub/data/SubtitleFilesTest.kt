package com.alyoushw.watchhub.data

import org.junit.Assert.*
import org.junit.Test

class SubtitleFilesTest {
    @Test fun srtAndWebVttPreserveCueTimingAndArabicText() {
        val srt = "\uFEFF1\r\n00:00:01,250 --> 00:00:03,500\r\nمرحباً\r\nبالعالم\r\n\r\n2\r\n00:00:04,000 --> 00:00:05,000\r\nHello"
        val captions = SubtitleFiles.parse(srt)
        assertEquals(1250L, captions[0].startMs)
        assertEquals(3500L, captions[0].endMs)
        assertEquals("مرحباً\nبالعالم", captions[0].text)
        assertEquals(captions, SubtitleFiles.parse(SubtitleFiles.webVtt(captions)))
    }

    @Test fun webVttMetadataSettingsAndCueIdsAreNotTranslatedAsDialogue() {
        val vtt = "WEBVTT\n\nNOTE metadata\nnot dialogue\n\ncue-1\n00:01.500 --> 00:03.000 align:start position:10%\nA real caption\n\nSTYLE\n::cue { color: red; }"
        assertEquals(listOf(TimedCaption(1500, 3000, "A real caption")), SubtitleFiles.parse(vtt))
    }

    @Test fun malformedOrReversedTimingAndHtmlDoNotBecomeSubtitleFiles() {
        assertThrows(IllegalArgumentException::class.java) { SubtitleFiles.parse("<html>expired link</html>") }
        assertThrows(IllegalArgumentException::class.java) { SubtitleFiles.parse("1\n00:00:04,000 --> 00:00:01,000\nWrong") }
        assertThrows(IllegalArgumentException::class.java) { SubtitleFiles.parse("1\n00:61:01,000 --> 00:62:00,000\nWrong") }
    }
}
