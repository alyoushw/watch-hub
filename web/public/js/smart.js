// The search's categories and filters, and an offline understanding of everyday Arabic
// requests ("مسلسل كوري درامي", "فيلم رعب إندونيسي", "شي مشابه لـ Interstellar").
// Same lists and rules as the Android app (SmartSearch.kt), so both behave alike.
import { SERVICES } from "./tmdb.js";

export const GENRES = [
  { key: "SCIFI", label: "خيال علمي", movie: 878, tv: 10765, words: ["خيال علمي", "فضاء", "فضايي", "فضائي"] },
  { key: "ACTION", label: "أكشن", movie: 28, tv: 10759, words: ["اكشن", "حركه", "قتال"] },
  { key: "ADVENTURE", label: "مغامرة", movie: 12, tv: 10759, words: ["مغامر"] },
  { key: "ANIMATION", label: "رسوم متحركة", movie: 16, tv: 16, words: ["كرتون", "انيميشن", "رسوم"] },
  { key: "COMEDY", label: "كوميدي", movie: 35, tv: 35, words: ["كوميد", "مضحك", "يضحك", "ضحك", "فكاه"] },
  { key: "CRIME", label: "جريمة", movie: 80, tv: 80, words: ["جريم", "جرايم", "جرائم", "عصابات", "مافيا", "بوليسي"] },
  { key: "DOCUMENTARY", label: "وثائقي", movie: 99, tv: 99, words: ["وثائقي", "وثايقي"] },
  { key: "DRAMA", label: "دراما", movie: 18, tv: 18, words: ["دراما", "درامي", "حزين", "يبكي"] },
  { key: "FAMILY", label: "عائلي", movie: 10751, tv: 10751, words: ["عائلي", "عايلي", "عائله", "عايله", "اطفال"] },
  { key: "FANTASY", label: "فانتازيا", movie: 14, tv: 10765, words: ["فانتازي", "خيالي", "سحر"] },
  { key: "HISTORY", label: "تاريخي", movie: 36, tv: 18, words: ["تاريخ"] },
  { key: "HORROR", label: "رعب", movie: 27, tv: 9648, words: ["رعب", "مرعب", "مخيف", "يخوف", "زومبي"] },
  { key: "MYSTERY", label: "غموض", movie: 9648, tv: 9648, words: ["غموض", "غامض", "لغز", "الغاز"] },
  { key: "ROMANCE", label: "رومانسي", movie: 10749, tv: 18, words: ["رومانس", "رومنس", "حب", "عاطفي"] },
  { key: "THRILLER", label: "إثارة", movie: 53, tv: 80, words: ["اثاره", "تشويق", "مثير"] },
  { key: "WAR", label: "حرب", movie: 10752, tv: 10768, words: ["حرب", "حروب", "عسكري"] },
];

export const ORIGINS = [
  { key: "SAUDI", label: "سعودي", lang: "ar", words: ["سعودي", "سعوديه"], countries: "SA" },
  { key: "GULF", label: "خليجي", lang: "ar", words: ["خليجي", "خليجيه", "كويتي", "اماراتي", "بحريني", "قطري", "عماني"], countries: "SA|AE|KW|BH|QA|OM" },
  { key: "EGYPTIAN", label: "مصري", lang: "ar", words: ["مصري", "مصريه"], countries: "EG" },
  { key: "LEVANTINE", label: "شامي", lang: "ar", words: ["سوري", "لبناني", "شامي", "اردني"], countries: "SY|LB|JO|PS" },
  { key: "ARABIC", label: "عربي", lang: "ar", words: ["عربي", "عربيه"] },
  { key: "KOREAN", label: "كوري", lang: "ko", words: ["كوري", "كوريا"] },
  { key: "JAPANESE", label: "ياباني", lang: "ja", words: ["ياباني", "يابان"] },
  { key: "CHINESE", label: "صيني", lang: "zh", words: ["صيني", "صين"] },
  { key: "INDIAN", label: "هندي", lang: "hi", words: ["هندي", "بوليوود"] },
  { key: "TURKISH", label: "تركي", lang: "tr", words: ["تركي", "تركيا", "تركيه"] },
  { key: "INDONESIAN", label: "إندونيسي", lang: "id", words: ["اندونيسي", "اندونيسيا"] },
  { key: "THAI", label: "تايلندي", lang: "th", words: ["تايلندي", "تايلند"] },
  { key: "SPANISH", label: "إسباني", lang: "es", words: ["اسباني", "مكسيكي"] },
  { key: "FRENCH", label: "فرنسي", lang: "fr", words: ["فرنسي"] },
  { key: "ENGLISH", label: "أمريكي/بريطاني", lang: "en", words: ["امريكي", "بريطاني", "انجليزي", "هوليوود"] },
  { key: "GERMAN", label: "ألماني", lang: "de", words: ["الماني"] },
  { key: "ITALIAN", label: "إيطالي", lang: "it", words: ["ايطالي"] },
];

export const SORTS = [["POPULAR", "الأشهر"], ["TOP_RATED", "الأعلى تقييماً"], ["RECENT", "الأحدث"]];

export const ERAS = (() => {
  const y = new Date().getFullYear();
  return [["الكل", null, null], ["آخر سنتين", y - 1, null], ["2020+", 2020, null], ["2010–2019", 2010, 2019],
    ["2000–2009", 2000, 2009], ["التسعينات", 1990, 1999], ["أقدم", null, 1989]];
})();

export const EXAMPLES = ["مسلسل كوري درامي", "فيلم رعب إندونيسي", "أفضل أفلام أكشن على نتفلكس", "شي مشابه لـ Interstellar",
  "أنمي فانتازيا", "كوميدي عائلي جديد", "رشّح لي من قائمتي"];

export const emptyFilter = (type = "movie") => ({ type, genres: [], origin: null, services: [], sort: "POPULAR", yearFrom: null, yearTo: null, minRating: null });

export const isEmpty = (f) => !f.genres.length && !f.origin && !f.services.length && f.yearFrom == null && f.yearTo == null && f.minRating == null && f.sort === "POPULAR";

const genre = (k) => GENRES.find((g) => g.key === k);
const origin = (k) => ORIGINS.find((o) => o.key === k);

/** Short labels, e.g. ["مسلسلات", "دراما", "كوري"]. */
export function summary(f) {
  const out = [f.type === "movie" ? "أفلام" : "مسلسلات"];
  f.genres.forEach((k) => out.push(genre(k).label));
  if (f.origin) out.push(origin(f.origin).label);
  f.services.forEach((k) => out.push(SERVICES.find((s) => s.key === k)?.label));
  if (f.sort !== "POPULAR") out.push(SORTS.find((s) => s[0] === f.sort)[1]);
  if (f.yearFrom != null && f.yearTo != null) out.push(f.yearFrom === f.yearTo ? `${f.yearFrom}` : `${f.yearFrom}–${f.yearTo}`);
  else if (f.yearFrom != null) out.push(`من ${f.yearFrom}`);
  else if (f.yearTo != null) out.push(`قبل ${f.yearTo + 1}`);
  if (f.minRating) out.push(`تقييم ${f.minRating}+`);
  return out.filter(Boolean);
}

/** TMDB /discover parameters; [regionIds] adds the region's ids for Shahid / STC tv. */
export function toParams(f, regionIds = {}) {
  const p = {};
  const date = f.type === "movie" ? "primary_release_date" : "first_air_date";
  if (f.genres.length) p.with_genres = [...new Set(f.genres.map((k) => genre(k)[f.type === "movie" ? "movie" : "tv"]))].join(",");
  if (f.origin) {
    const o = origin(f.origin);
    p.with_original_language = o.lang;
    if (o.countries) p.with_origin_country = o.countries;
  }
  if (f.services.length) {
    const ids = f.services.flatMap((k) => [...(SERVICES.find((s) => s.key === k)?.ids || []), ...(regionIds[k] || [])]);
    if (ids.length) p.with_watch_providers = [...new Set(ids)].join("|");
  }
  if (f.yearFrom != null) p[`${date}.gte`] = `${f.yearFrom}-01-01`;
  if (f.yearTo != null) p[`${date}.lte`] = `${f.yearTo}-12-31`;
  if (f.sort === "TOP_RATED") {
    p.sort_by = "vote_average.desc";
    p["vote_count.gte"] = f.type === "movie" ? "300" : "100";
  } else {
    p.sort_by = "popularity.desc";
    if (f.sort === "RECENT") {
      const today = new Date();
      const yearAgo = new Date(today);
      yearAgo.setFullYear(today.getFullYear() - 1);
      if (f.yearFrom == null) p[`${date}.gte`] = yearAgo.toISOString().slice(0, 10);
      if (f.yearTo == null) p[`${date}.lte`] = today.toISOString().slice(0, 10);
    }
  }
  if (f.minRating) {
    p["vote_average.gte"] = String(f.minRating);
    p["vote_count.gte"] ??= "50";
  }
  return p;
}

/** TMDB's discover is looser than asked (e.g. language-only matches), so results are re-checked. */
export function matches(f, r) {
  const ids = new Set(f.genres.map((k) => genre(k)[f.type === "movie" ? "movie" : "tv"]));
  if (ids.size && (r.genre_ids || []).length && ![...ids].every((id) => r.genre_ids.includes(id))) return false;
  if (!f.origin) return true;
  const o = origin(f.origin);
  const countries = o.countries ? o.countries.split("|") : null;
  if (countries && (r.origin_country || []).length) return r.origin_country.some((c) => countries.includes(c));
  if (r.original_language) return r.original_language === o.lang;
  return true;
}

// ---------- understanding free text ----------

export const normalize = (t) => t.toLowerCase()
  .replace(/[ً-ْـ]/g, "")
  .replace(/[أإآ]/g, "ا").replace(/ة/g, "ه").replace(/ى/g, "ي").replace(/ؤ/g, "و").replace(/ئ/g, "ي");

const SIMILAR = /(?:نفس جو|بجو|على جو|مشابه|مشابة|يشبه|شبيه|زي|مثل|similar to|like)\s*(?:لـ|ل)?\s*(.+)/i;
const VAGUE_TAIL = /\s*(?:زي كذا|كذا|او شي|أو شي|وكذا|يعني)\s*$/;
const TAIL_NOISE = /\s*(?:الي|اللي|اللى|الى)\s+(?:عجبني|حبيته|شفته|شفتها).*$/;
const PREFIXES = ["وال", "بال", "فال", "كال", "لل", "ال", "و", "ب", "ل", "ف"];
const TITLE_NOISE = ["الفيلم", "المسلسل", "فيلم", "مسلسل", "انمي", "أنمي", "الانمي", "الأنمي"];

/**
 * Returns { kind: "plain", text } | { kind: "filter", filter } | { kind: "similar", title, type, origin }
 * | { kind: "watchlist" }.
 */
export function parse(raw) {
  const text = raw.trim();
  if (!text) return { kind: "plain", text: "" };
  const norm = normalize(text);
  const tokens = norm.split(/[\s،,.!?؟]+/).filter(Boolean);
  const hasWord = (word) => {
    const w = normalize(word);
    if (w.includes(" ")) return norm.includes(w);
    return tokens.some((t) => t.startsWith(w) || PREFIXES.some((p) => t.startsWith(p) && t.slice(p.length).startsWith(w)));
  };
  const any = (list) => list.some(hasWord);

  const explicitType = any(["انمي"]) ? "anime" : any(["مسلسل", "سيريس", "حلقات", "series"]) ? "tv" : any(["فيلم", "افلام", "فلم", "movie"]) ? "movie" : null;

  const sim = text.match(SIMILAR);
  if (sim) {
    let title = sim[1].replace(TAIL_NOISE, "").replace(VAGUE_TAIL, "").trim();
    for (const n of TITLE_NOISE) if (title.startsWith(`${n} `)) title = title.slice(n.length + 1).trim();
    if (title.length >= 2) {
      const before = normalize(text.slice(0, sim.index));
      const named = ORIGINS.filter((o) => o.words.some((w) => before.includes(normalize(w))));
      const org = !named.length ? null : named.every((o) => o.lang === "ar") && named.some((o) => o.key === "GULF") ? "GULF" : named[0].key;
      return { kind: "similar", title, type: explicitType === "anime" ? null : explicitType, origin: org };
    }
  }

  if (any(["قائمتي", "قايمتي", "ذوقي", "اضفته", "بناء علي"])) return { kind: "watchlist" };

  let genres = GENRES.filter((g) => g.words.some(hasWord)).map((g) => g.key);
  // "خيال علمي" is sci-fi, not fantasy.
  if (genres.includes("SCIFI")) genres = genres.filter((k) => k !== "FANTASY");
  const named = ORIGINS.filter((o) => o.words.some(hasWord));
  let org = !named.length ? null : named.length === 1 ? named[0].key
    : named.every((o) => o.lang === "ar") && named.some((o) => o.key === "GULF") ? "GULF"
      : named.every((o) => o.lang === "ar") ? "ARABIC" : named[0].key;
  const services = [];
  if (any(["نتفلكس", "نتفليكس", "netflix"])) services.push("netflix");
  if (any(["برايم", "امازون", "prime"])) services.push("prime");
  if (any(["كرنشي", "كرانشي", "crunchyroll"])) services.push("crunchyroll");
  const sort = any(["افضل", "احسن", "اقوي", "اعلي"]) ? "TOP_RATED" : any(["جديد", "احدث", "اخر", "نازل", "حديث"]) ? "RECENT" : "POPULAR";

  let yearFrom = null;
  let yearTo = null;
  const ym = norm.match(/(19\d\d|20\d\d)/);
  if (ym) {
    const year = Number(ym[1]);
    const before = norm.slice(0, ym.index).trimEnd();
    if (before.endsWith("بعد") || before.endsWith("من")) yearFrom = year;
    else if (before.endsWith("قبل")) yearTo = year - 1;
    else yearFrom = yearTo = year;
  }
  if (hasWord("تسعينات")) [yearFrom, yearTo] = [1990, 1999];
  else if (hasWord("ثمانينات")) [yearFrom, yearTo] = [1980, 1989];
  else if (hasWord("الفينات")) [yearFrom, yearTo] = [2000, 2009];
  else if ((hasWord("قديم") || hasWord("كلاسيك")) && yearTo == null) yearTo = 1999;
  const rm = norm.match(/(?:فوق|اكثر من|اعلي من|تقييم)\s*(\d+)/);
  const minRating = rm && Number(rm[1]) >= 1 && Number(rm[1]) <= 9 ? Number(rm[1]) : null;

  let type = explicitType;
  if (type === "anime") {
    // Anime through TMDB: Japanese animated series (or films, if a film was asked for).
    if (!genres.includes("ANIMATION")) genres.push("ANIMATION");
    org ??= "JAPANESE";
    type = any(["فيلم", "افلام", "فلم"]) ? "movie" : "tv";
  }

  const understood = genres.length || org || services.length || sort !== "POPULAR" || yearFrom != null || yearTo != null || minRating != null || explicitType;
  if (!understood) return { kind: "plain", text };
  const finalType = type || (["KOREAN", "TURKISH", "ARABIC"].includes(org) ? "tv" : "movie");
  const filter = { type: finalType, genres, origin: org, services, sort, yearFrom, yearTo, minRating };
  // Only a type word and nothing else (e.g. "فيلم Inception"): a title search.
  if (isEmpty(filter) && explicitType && tokens.length > 1) return { kind: "plain", text };
  return { kind: "filter", filter };
}
