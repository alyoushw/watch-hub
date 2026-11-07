package com.alyoushw.watchhub.data

/** Arabic nationality adjective for a production country (ISO 3166 code), e.g. "SA" -> "سعودي". */
fun countryLabel(code: String?): String? = when (code?.uppercase()) {
    null, "" -> null
    "SA" -> "سعودي"
    "AE" -> "إماراتي"
    "KW" -> "كويتي"
    "BH" -> "بحريني"
    "QA" -> "قطري"
    "OM" -> "عُماني"
    "EG" -> "مصري"
    "SY" -> "سوري"
    "LB" -> "لبناني"
    "JO" -> "أردني"
    "IQ" -> "عراقي"
    "PS" -> "فلسطيني"
    "MA" -> "مغربي"
    "TN" -> "تونسي"
    "DZ" -> "جزائري"
    "US" -> "أمريكي"
    "GB" -> "بريطاني"
    "CA" -> "كندي"
    "AU" -> "أسترالي"
    "KR" -> "كوري"
    "JP" -> "ياباني"
    "CN" -> "صيني"
    "TW" -> "تايواني"
    "HK" -> "هونغ كونغي"
    "IN" -> "هندي"
    "PK" -> "باكستاني"
    "TR" -> "تركي"
    "ID" -> "إندونيسي"
    "TH" -> "تايلندي"
    "PH" -> "فلبيني"
    "MY" -> "ماليزي"
    "ES" -> "إسباني"
    "MX" -> "مكسيكي"
    "AR" -> "أرجنتيني"
    "CO" -> "كولومبي"
    "BR" -> "برازيلي"
    "FR" -> "فرنسي"
    "DE" -> "ألماني"
    "IT" -> "إيطالي"
    "RU" -> "روسي"
    "SE" -> "سويدي"
    "NO" -> "نرويجي"
    "DK" -> "دنماركي"
    "PL" -> "بولندي"
    "IR" -> "إيراني"
    else -> null
}

/** A country's flag emoji from its ISO code ("SA" -> 🇸🇦). */
fun countryFlag(code: String?): String? {
    val c = code?.uppercase() ?: return null
    if (c.length != 2 || !c.all { it in 'A'..'Z' }) return null
    return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
}

/** Best guess at a title's country from its language when TMDB gives no country (movies in lists). */
fun countryFromLanguage(language: String?): String? = when (language) {
    "ko" -> "KR"
    "ja" -> "JP"
    "zh" -> "CN"
    "hi" -> "IN"
    "tr" -> "TR"
    "id" -> "ID"
    "th" -> "TH"
    "tl" -> "PH"
    "fr" -> "FR"
    "de" -> "DE"
    "it" -> "IT"
    "ru" -> "RU"
    "fa" -> "IR"
    else -> null
}
