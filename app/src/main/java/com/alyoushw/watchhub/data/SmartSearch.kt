package com.alyoushw.watchhub.data

import kotlinx.serialization.Serializable
import java.time.LocalDate

/** TMDB genres with Arabic names and the words people use for them. */
@Serializable
enum class Genre(
    val label: String,
    val movieId: Int,
    /** TV genre id; TMDB has no TV horror/romance/thriller, so those use the closest one. */
    val tvId: Int,
    val words: List<String>,
) {
    SCIFI("خيال علمي", 878, 10765, listOf("خيال علمي", "فضاء", "فضايي", "فضائي")),
    ACTION("أكشن", 28, 10759, listOf("اكشن", "حركه", "قتال")),
    ADVENTURE("مغامرة", 12, 10759, listOf("مغامر")),
    ANIMATION("رسوم متحركة", 16, 16, listOf("كرتون", "انيميشن", "رسوم")),
    COMEDY("كوميدي", 35, 35, listOf("كوميد", "مضحك", "يضحك", "ضحك", "فكاه")),
    CRIME("جريمة", 80, 80, listOf("جريم", "جرايم", "جرائم", "عصابات", "مافيا", "بوليسي")),
    DOCUMENTARY("وثائقي", 99, 99, listOf("وثائقي", "وثايقي")),
    DRAMA("دراما", 18, 18, listOf("دراما", "درامي", "حزين", "يبكي")),
    FAMILY("عائلي", 10751, 10751, listOf("عائلي", "عايلي", "عائله", "عايله", "اطفال")),
    FANTASY("فانتازيا", 14, 10765, listOf("فانتازي", "خيالي", "سحر")),
    HISTORY("تاريخي", 36, 18, listOf("تاريخ")),
    HORROR("رعب", 27, 9648, listOf("رعب", "مرعب", "مخيف", "يخوف", "زومبي")),
    MYSTERY("غموض", 9648, 9648, listOf("غموض", "غامض", "لغز", "الغاز")),
    ROMANCE("رومانسي", 10749, 18, listOf("رومانس", "رومنس", "حب", "عاطفي")),
    THRILLER("إثارة", 53, 80, listOf("اثاره", "تشويق", "مثير")),
    WAR("حرب", 10752, 10768, listOf("حرب", "حروب", "عسكري"));

    fun idFor(type: MediaType): Int = if (type == MediaType.MOVIE) movieId else tvId
}

/**
 * Story topics beyond TMDB's genres ("سيارات", "سرقة", "زومبي"…). [terms] are TMDB keyword names,
 * looked up when searching; a topic matches titles tagged with any of them.
 */
@Serializable
enum class Topic(val label: String, val words: List<String>, val terms: List<String>) {
    CARS("سيارات وسباقات", listOf("سيار", "سباق", "درفت", "تفحيط"), listOf("car race", "street racing", "car chase", "race car")),
    SUPERHERO("أبطال خارقين", listOf("خارق", "سوبرهيرو", "سوبر هيرو", "مارفل"), listOf("superhero", "marvel cinematic universe (mcu)")),
    FOOTBALL("كرة قدم", listOf("كره قدم", "كوره", "فوتبول"), listOf("football (soccer)", "soccer")),
    BOXING("ملاكمة وقتال", listOf("ملاكم", "مصارع", "كونغ فو", "كاراتيه", "فنون قتاليه"), listOf("boxing", "martial arts", "kung fu")),
    HEIST("سرقة وسطو", listOf("سرق", "سطو", "عصابه سرقه"), listOf("heist", "bank robbery", "robbery")),
    PRISON("سجون", listOf("سجن", "سجون", "هروب من السجن"), listOf("prison", "prison escape")),
    SCHOOL("مدرسة وجامعة", listOf("مدرس", "ثانوي", "جامع", "طلاب"), listOf("high school", "school", "college")),
    VAMPIRE("مصاصي دماء", listOf("مصاص", "مصاصين"), listOf("vampire")),
    ZOMBIE("زومبي", listOf("زومبي"), listOf("zombie")),
    TIME_TRAVEL("سفر عبر الزمن", listOf("الزمن", "زمني"), listOf("time travel")),
    SURVIVAL("نجاة", listOf("نجاه", "بقاء", "جزيره"), listOf("survival")),
    SPY("جاسوسية", listOf("جاسوس", "تجسس", "مخابرات"), listOf("spy", "espionage")),
    POLICE("شرطة وتحقيق", listOf("شرطه", "شرطي", "محقق", "تحقيق"), listOf("police", "detective", "investigation")),
    MEDICAL("أطباء ومستشفيات", listOf("طبيب", "دكتور", "مستشفي", "اطباء"), listOf("hospital", "doctor")),
    LAWYER("محاكم ومحاماة", listOf("محامي", "محكمه", "محاكم", "قاضي"), listOf("lawyer", "courtroom")),
    COOKING("طبخ", listOf("طبخ", "مطبخ", "طباخ", "شيف"), listOf("cooking", "chef")),
    MUSIC("موسيقى وغناء", listOf("موسيق", "غناء", "مغني", "فرقه"), listOf("musician", "music", "singer")),
    ANIMALS("حيوانات", listOf("حيوان", "كلب", "كلاب", "قطط"), listOf("animal", "dog")),
    SHARK("أسماك القرش", listOf("قرش"), listOf("shark")),
    ALIENS("كائنات فضائية", listOf("كائنات", "فضائيين", "غزو"), listOf("alien", "alien invasion")),
    ROBOTS("روبوتات وذكاء اصطناعي", listOf("روبوت", "ذكاء اصطناعي"), listOf("robot", "artificial intelligence (a.i.)")),
    DINOSAURS("ديناصورات", listOf("ديناصور"), listOf("dinosaur")),
    PIRATES("قراصنة", listOf("قراصن", "قرصان"), listOf("pirate")),
    SAMURAI("ساموراي ونينجا", listOf("ساموراي", "نينجا"), listOf("samurai", "ninja")),
    TRUE_STORY("قصة حقيقية", listOf("قصه حقيقيه", "واقعيه", "احداث حقيقيه"), listOf("based on true story")),
    REVENGE("انتقام", listOf("انتقام"), listOf("revenge")),
    SERIAL_KILLER("قاتل متسلسل", listOf("متسلسل", "سفاح"), listOf("serial killer")),
    HITMAN("قاتل مأجور", listOf("ماجور", "قناص", "اغتيال"), listOf("hitman", "assassin")),
    DISASTER("كوارث", listOf("كارث", "زلزال", "تسونامي", "اعصار", "بركان"), listOf("natural disaster", "disaster")),
    APOCALYPSE("نهاية العالم", listOf("نهايه العالم"), listOf("post-apocalyptic future", "apocalypse")),
    KIDNAPPING("خطف", listOf("خطف", "اختطاف", "رهائن"), listOf("kidnapping", "hostage")),
    DRUGS("مخدرات", listOf("مخدرات", "كارتل"), listOf("drug cartel", "drug dealer")),
    WEDDING("زواج وأعراس", listOf("زواج", "عرس", "زفاف"), listOf("wedding", "marriage")),
    DESERT("صحراء", listOf("صحرا", "بدو"), listOf("desert")),
    SEA("بحر وغوص", listOf("البحر", "بحار", "محيط", "غواص", "غوص"), listOf("ocean", "sea")),
    SPACE("فضاء", listOf("فضاء", "فضائيه", "رائد فضاء"), listOf("space", "astronaut")),
    GAMES("ألعاب فيديو", listOf("قيمز", "العاب فيديو", "بلايستيشن"), listOf("video game", "based on video game")),
    NOVEL("مقتبس من رواية", listOf("روايه", "رواية", "مقتبس"), listOf("based on novel or book")),
}

/** Where a title comes from, as TMDB's original language. */
@Serializable
enum class Origin(
    val label: String,
    val language: String,
    val words: List<String>,
    /** TMDB origin countries ("SA|AE"); narrower than the language when set. */
    val countries: String? = null,
) {
    SAUDI("سعودي", "ar", listOf("سعودي", "سعوديه"), "SA"),
    GULF("خليجي", "ar", listOf("خليجي", "خليجيه", "كويتي", "اماراتي", "بحريني", "قطري", "عماني"), "SA|AE|KW|BH|QA|OM"),
    EGYPTIAN("مصري", "ar", listOf("مصري", "مصريه"), "EG"),
    LEVANTINE("شامي", "ar", listOf("سوري", "لبناني", "شامي", "اردني"), "SY|LB|JO|PS"),
    ARABIC("عربي", "ar", listOf("عربي", "عربيه")),
    KOREAN("كوري", "ko", listOf("كوري", "كوريا")),
    JAPANESE("ياباني", "ja", listOf("ياباني", "يابان")),
    CHINESE("صيني", "zh", listOf("صيني", "صين")),
    INDIAN("هندي", "hi", listOf("هندي", "بوليوود")),
    TURKISH("تركي", "tr", listOf("تركي", "تركيا", "تركيه")),
    INDONESIAN("إندونيسي", "id", listOf("اندونيسي", "اندونيسيا")),
    THAI("تايلندي", "th", listOf("تايلندي", "تايلند")),
    SPANISH("إسباني", "es", listOf("اسباني", "مكسيكي")),
    FRENCH("فرنسي", "fr", listOf("فرنسي")),
    ENGLISH("أمريكي/بريطاني", "en", listOf("امريكي", "بريطاني", "انجليزي", "هوليوود")),
    GERMAN("ألماني", "de", listOf("الماني")),
    ITALIAN("إيطالي", "it", listOf("ايطالي")),
}

@Serializable
enum class SortMode(val label: String) {
    POPULAR("الأشهر"),
    TOP_RATED("الأعلى تقييماً"),
    RECENT("الأحدث"),
}

/** Everything the search can filter on; also what a saved "taste" preset stores. */
@Serializable
data class DiscoverFilter(
    val type: MediaType = MediaType.MOVIE,
    val genres: Set<Genre> = emptySet(),
    val origin: Origin? = null,
    val services: Set<String> = emptySet(),
    val sort: SortMode = SortMode.POPULAR,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val minRating: Int? = null,
    /** Story topics (TMDB keywords), all of which must match. */
    val topics: Set<Topic> = emptySet(),
    /** Actors or directors by name ("محمد سعد", "Tom Cruise"), looked up on TMDB when searching. */
    val people: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = genres.isEmpty() && origin == null && services.isEmpty() && topics.isEmpty() && people.isEmpty() &&
            yearFrom == null && yearTo == null && minRating == null && sort == SortMode.POPULAR

    /** Short labels describing the filter, e.g. ["مسلسلات", "دراما", "كوري"]. */
    fun summary(): List<String> = buildList {
        add(if (type == MediaType.MOVIE) "أفلام" else "مسلسلات")
        genres.forEach { add(it.label) }
        topics.forEach { add(it.label) }
        people.forEach { add("🎭 $it") }
        origin?.let { add(it.label) }
        services.mapNotNull { StreamingService.forKey(it)?.label }.forEach { add(it) }
        if (sort != SortMode.POPULAR) add(sort.label)
        when {
            yearFrom != null && yearTo != null && yearFrom == yearTo -> add("$yearFrom")
            yearFrom != null && yearTo != null -> add("$yearFrom–$yearTo")
            yearFrom != null -> add("من $yearFrom")
            yearTo != null -> add("قبل ${yearTo + 1}")
        }
        minRating?.let { add("تقييم $it+") }
    }

    /**
     * Whether a result really fits: every chosen genre, and the chosen country (or language).
     * TMDB's discover is looser than that (e.g. language-only matches), so results are re-checked.
     */
    fun matches(r: SearchResult): Boolean {
        val genreIds = genres.map { it.idFor(type) }.toSet()
        if (genreIds.isNotEmpty() && r.genreIds.isNotEmpty() && !r.genreIds.containsAll(genreIds)) return false
        val o = origin ?: return true
        val countries = o.countries?.split("|")?.toSet()
        return when {
            countries != null && r.originCountry.isNotEmpty() -> r.originCountry.any { it in countries }
            r.originalLanguage != null -> r.originalLanguage == o.language
            else -> true
        }
    }

    /** TMDB /discover parameters for this filter. */
    fun toParams(today: LocalDate = LocalDate.now()): Map<String, String> = buildMap {
        val dateField = if (type == MediaType.MOVIE) "primary_release_date" else "first_air_date"
        if (genres.isNotEmpty()) put("with_genres", genres.map { it.idFor(type) }.distinct().joinToString(","))
        origin?.let {
            put("with_original_language", it.language)
            it.countries?.let { c -> put("with_origin_country", c) }
        }
        if (services.isNotEmpty()) {
            val ids = services.mapNotNull { StreamingService.forKey(it) }.flatMap { it.tmdbIds }
            // Shahid / STC tv have no fixed TMDB id; skip rather than send an empty filter.
            if (ids.isNotEmpty()) put("with_watch_providers", ids.joinToString("|"))
        }
        yearFrom?.let { put("$dateField.gte", "$it-01-01") }
        yearTo?.let { put("$dateField.lte", "$it-12-31") }
        when (sort) {
            SortMode.POPULAR -> put("sort_by", "popularity.desc")
            SortMode.TOP_RATED -> {
                put("sort_by", "vote_average.desc")
                put("vote_count.gte", if (type == MediaType.MOVIE) "300" else "100")
            }
            // "آخر فيلم لمحمد سعد": newest first across all years, released titles only.
            SortMode.RECENT -> if (people.isNotEmpty()) {
                put("sort_by", "$dateField.desc")
                if (yearTo == null) put("$dateField.lte", today.toString())
            } else {
                put("sort_by", "popularity.desc")
                if (yearFrom == null) put("$dateField.gte", today.minusYears(1).toString())
                if (yearTo == null) put("$dateField.lte", today.toString())
            }
        }
        minRating?.let {
            put("vote_average.gte", "$it")
            if (!containsKey("vote_count.gte")) put("vote_count.gte", "50")
        }
    }
}

@Serializable
data class SavedFilter(val name: String, val filter: DiscoverFilter)

/** What the user asked for, understood from free text (typed or spoken). */
sealed interface SmartQuery {
    /** Titles like [title]; [origin] keeps them to a country/language when the user named one. */
    data class Similar(val title: String, val type: MediaType?, val origin: Origin? = null) : SmartQuery
    data object FromWatchlist : SmartQuery
    data class Filter(val filter: DiscoverFilter) : SmartQuery
    data class Plain(val text: String) : SmartQuery
}

/**
 * A small, offline understanding of Arabic (and some English) requests like
 * "ابي مسلسل كوري درامي", "فيلم رعب اندونيسي", "شي مشابه لـ Interstellar" or "رشح لي من قائمتي".
 */
object SmartSearch {
    private val similarPattern = Regex("(?:نفس جو|بجو|على جو|مشابه|مشابة|يشبه|شبيه|زي|مثل|similar to|like)\\s*(?:لـ|ل)?\\s*(.+)", RegexOption.IGNORE_CASE)
    private val vagueTail = Regex("\\s*(?:زي كذا|كذا|او شي|أو شي|وكذا|يعني)\\s*$")
    private val yearPattern = Regex("(19\\d\\d|20\\d\\d)")
    private val ratingPattern = Regex("(?:فوق|اكثر من|اعلي من|تقييم)\\s*(\\d+)")
    private val prefixes = listOf("وال", "بال", "فال", "كال", "لل", "ال", "و", "ب", "ل", "ف")
    private val titleNoise = listOf("الفيلم", "المسلسل", "فيلم", "مسلسل", "انمي", "أنمي", "الانمي", "الأنمي")
    /** Words that carry no search meaning of their own (requests, types, sorting…). */
    private val fillerWords = listOf(
        "ابي", "ابغي", "ابغا", "ابا", "اريد", "ودي", "عطني", "اعطني", "رشح", "رشحلي", "ابحث", "دور", "دورلي", "شي", "شيء",
        "حلو", "حلوه", "زين", "قوي", "مره", "مرره", "فيه", "في", "عن", "من", "مع", "او", "بس", "لي", "الي", "اللي",
        "حق", "تبع", "بطوله", "تمثيل", "ممثل", "الممثل", "مخرج", "المخرج", "اخراج", "كل", "نوع", "يكون", "عشان",
        "فيلم", "فلم", "افلام", "مسلسل", "مسلسلات", "انمي", "سيريس", "حلقات", "movie", "series",
        "افضل", "احسن", "اقوي", "اعلي", "جديد", "احدث", "اخر", "نازل", "حديث", "قديم", "كلاسيك", "تسعينات", "ثمانينات", "الفينات",
        "نتفلكس", "نتفليكس", "netflix", "برايم", "امازون", "prime", "كرنشي", "كرانشي", "crunchyroll", "قائمتي", "ذوقي",
        "قبل", "بعد", "فوق", "اكثر", "تقييم", "سنه", "سنة", "عام", "اللي", "هذا", "هذي", "شوف", "ابيه", "ابيها",
    )
    private val tailNoise = Regex("\\s*(?:الي|اللي|اللى|الى)\\s+(?:عجبني|حبيته|شفته|شفتها).*$")

    fun normalize(text: String): String = text.lowercase()
        .replace(Regex("[\\u064B-\\u0652\\u0640]"), "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ة', 'ه').replace('ى', 'ي').replace('ؤ', 'و').replace('ئ', 'ي')

    fun parse(raw: String): SmartQuery {
        val text = raw.trim()
        if (text.isEmpty()) return SmartQuery.Plain("")
        val norm = normalize(text)
        val tokens = norm.split(Regex("[\\s،,.!?؟]+")).filter { it.isNotBlank() }

        fun hasWord(word: String): Boolean {
            val w = normalize(word)
            if (' ' in w) return w in norm
            return tokens.any { token ->
                token.startsWith(w) || prefixes.any { p -> token.startsWith(p) && token.removePrefix(p).startsWith(w) }
            }
        }

        val explicitType = when {
            listOf("انمي").any(::hasWord) -> MediaType.ANIME
            listOf("مسلسل", "سيريس", "حلقات", "series").any(::hasWord) -> MediaType.TV
            listOf("فيلم", "افلام", "فلم", "movie").any(::hasWord) -> MediaType.MOVIE
            else -> null
        }

        // "شي مشابه لـ X"
        similarPattern.find(text)?.let { match ->
            var title = match.groupValues[1].replace(tailNoise, "").replace(vagueTail, "").trim()
            titleNoise.forEach { noise ->
                if (title.startsWith("$noise ")) title = title.removePrefix("$noise ").trim()
            }
            if (title.length >= 2) {
                val before = normalize(text.substring(0, match.range.first))
                val named = Origin.entries.filter { o -> o.words.any { w -> normalize(w) in before } }
                val origin = when {
                    named.isEmpty() -> null
                    named.all { it.language == "ar" } && Origin.GULF in named -> Origin.GULF
                    else -> named.first()
                }
                return SmartQuery.Similar(title, explicitType?.takeIf { it != MediaType.ANIME }, origin)
            }
        }

        if (listOf("قائمتي", "قايمتي", "المفضلة", "المفضله", "مفضلتي", "ذوقي", "اضفته", "بناء علي").any(::hasWord)) return SmartQuery.FromWatchlist

        val genres = Genre.entries.filter { g -> g.words.any(::hasWord) }.toMutableSet()
        val topics = Topic.entries.filter { t -> t.words.any(::hasWord) }.toSet()
        // "خيال علمي" is sci-fi, not fantasy.
        if (Genre.SCIFI in genres) genres.remove(Genre.FANTASY)
        val named = Origin.entries.filter { o -> o.words.any(::hasWord) }
        var origin = when {
            named.isEmpty() -> null
            named.size == 1 -> named.first()
            named.all { it.language == "ar" } && Origin.GULF in named -> Origin.GULF
            named.all { it.language == "ar" } -> Origin.ARABIC
            else -> named.first()
        }
        val services = buildSet {
            if (listOf("نتفلكس", "نتفليكس", "netflix").any(::hasWord)) add(StreamingService.NETFLIX.key)
            if (listOf("برايم", "امازون", "prime").any(::hasWord)) add(StreamingService.PRIME.key)
            if (listOf("كرنشي", "كرانشي", "crunchyroll").any(::hasWord)) add(StreamingService.CRUNCHYROLL.key)
        }
        val sort = when {
            listOf("افضل", "احسن", "اقوي", "اعلي").any(::hasWord) -> SortMode.TOP_RATED
            listOf("جديد", "احدث", "اخر", "نازل", "حديث").any(::hasWord) -> SortMode.RECENT
            else -> SortMode.POPULAR
        }

        var yearFrom: Int? = null
        var yearTo: Int? = null
        yearPattern.find(norm)?.let { m ->
            val year = m.value.toInt()
            val before = norm.substring(0, m.range.first)
            when {
                before.trimEnd().endsWith("بعد") || before.trimEnd().endsWith("من") -> yearFrom = year
                before.trimEnd().endsWith("قبل") -> yearTo = year - 1
                else -> {
                    yearFrom = year
                    yearTo = year
                }
            }
        }
        when {
            hasWord("تسعينات") -> { yearFrom = 1990; yearTo = 1999 }
            hasWord("ثمانينات") -> { yearFrom = 1980; yearTo = 1989 }
            hasWord("الفينات") -> { yearFrom = 2000; yearTo = 2009 }
            hasWord("قديم") || hasWord("كلاسيك") -> if (yearTo == null) yearTo = 1999
        }
        val minRating = ratingPattern.find(norm)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..9 }

        var type = explicitType
        if (type == MediaType.ANIME) {
            // Anime through TMDB: Japanese animated series (or films, if a film was asked for).
            genres.add(Genre.ANIMATION)
            if (origin == null) origin = Origin.JAPANESE
            type = if (listOf("فيلم", "افلام", "فلم").any(::hasWord)) MediaType.MOVIE else MediaType.TV
        }

        // Words nobody claimed, e.g. "محمد سعد" in "آخر فيلم لمحمد سعد": maybe an actor or director.
        val stems = (Genre.entries.flatMap { it.words } + Origin.entries.flatMap { it.words } + Topic.entries.flatMap { it.words })
            .map(::normalize)
        val fillers = fillerWords.map(::normalize).toSet()
        fun bare(token: String) = listOf(token) + prefixes.filter { token.startsWith(it) }.map { token.removePrefix(it) }
        fun claimed(token: String) = bare(token).any { t -> t in fillers } ||
            stems.any { w -> if (' ' in w) token in w.split(' ') else bare(token).any { it.startsWith(w) } }
        val leftover = tokens.filterNot { it.length < 2 || it.all(Char::isDigit) || claimed(it) }
            .map { t -> if (t.length > 4 && t.startsWith("ل") && !t.startsWith("لل")) t.drop(1) else t }
        val people = if (leftover.size in 2..4) listOf(leftover.joinToString(" ")) else emptyList()

        val understood = genres.isNotEmpty() || origin != null || services.isNotEmpty() || topics.isNotEmpty() ||
            sort != SortMode.POPULAR || yearFrom != null || yearTo != null || minRating != null || explicitType != null
        if (!understood) return SmartQuery.Plain(text)

        // Without a type, guess from the origin: Korean/Turkish/Arabic picks are mostly series.
        val finalType = type ?: if (origin in setOf(Origin.KOREAN, Origin.TURKISH, Origin.ARABIC)) MediaType.TV else MediaType.MOVIE

        val filter = DiscoverFilter(
            type = finalType,
            genres = genres,
            origin = origin,
            services = services,
            sort = sort,
            yearFrom = yearFrom,
            yearTo = yearTo,
            minRating = minRating,
            topics = topics,
            people = people,
        )
        // Only a type word and nothing else (e.g. "فيلم Inception"): treat it as a title search.
        if (filter.isEmpty && explicitType != null && tokens.size > 1) return SmartQuery.Plain(text)
        return SmartQuery.Filter(filter)
    }
}
