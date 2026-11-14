package com.alyoushw.watchhub.data

import com.alyoushw.watchhub.ui.AnimeLookup
import com.alyoushw.watchhub.ui.decodeAnimeLookup
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AnimeNavigationTest {
    @Test fun `episode page metadata survives navigation restoration including Arabic and URL punctuation`() {
        val lookup = AnimeLookup("هوني ليمون صودا", "Honey Lemon Soda", false,
            "https://example.com/poster.jpg?lang=ar&a=1", MediaType.TV, 123,
            overview = "قصة المدرسة: الموسم الأول / الحلقات", backdrop = "https://example.com/backdrop.jpg")
        assertEquals(lookup, decodeAnimeLookup(Json.encodeToString(AnimeLookup.serializer(), lookup)))
    }

    @Test fun `older lookup payloads keep default optional metadata`() {
        val lookup = decodeAnimeLookup("""{"title":"Naruto","searchTitle":"Naruto","movie":false,"poster":null,"detailType":"ANIME","detailId":20,"malId":20}""")
        assertEquals("", lookup.overview)
        assertFalse(lookup.overviewIsEnglish)
        assertEquals(20, lookup.malId)
    }
}
