// "✨ مختار لك": learns the user's taste from what they watch (the watch history and their list)
// and suggests titles with the reason they fit: "لأنك شفت «X» · دراما، غموض · كوري".
// Same idea as the Android app (data/Recommender.kt). "مو لي" shows less of that kind.
import * as tmdb from "./tmdb.js";
import { history, watchlist, isFinished } from "./store.js";
import { normalizeTitle } from "./xtream.js";

const GENRES = {
  28: "أكشن", 12: "مغامرة", 16: "رسوم متحركة", 35: "كوميدي", 80: "جريمة", 99: "وثائقي", 18: "دراما", 10751: "عائلي",
  14: "فانتازيا", 36: "تاريخي", 27: "رعب", 10402: "موسيقي", 9648: "غموض", 10749: "رومانسي", 878: "خيال علمي", 53: "إثارة",
  10752: "حربي", 37: "غربي", 10759: "أكشن ومغامرة", 10762: "أطفال", 10764: "واقعي", 10765: "خيال وفانتازيا", 10768: "حرب وسياسة",
};
const COUNTRIES = { KR: "كوري", JP: "ياباني", US: "أمريكي", GB: "بريطاني", TR: "تركي", SA: "سعودي", EG: "مصري", KW: "كويتي", AE: "إماراتي", CN: "صيني", IN: "هندي", ES: "إسباني", FR: "فرنسي", TH: "تايلندي" };

const KEY = "watchhub.foryou";
const mem = (() => {
  try {
    return JSON.parse(localStorage.getItem(KEY) || "{}");
  } catch {
    return {};
  }
})();
mem.ids ??= {};
mem.nope ??= [];
mem.nopeGenres ??= {};
const save = () => {
  try { localStorage.setItem(KEY, JSON.stringify(mem)); } catch { /* private mode */ }
};

/** TMDB id for a title from the account (by its cleaned-up name), remembered. */
async function resolve(type, title) {
  const name = normalizeTitle(title);
  if (!name) return null;
  const k = `${type}:${name}`;
  if (k in mem.ids) return mem.ids[k];
  try {
    const hit = (await tmdb.search(name)).find((r) => r.media_type === type);
    mem.ids[k] = hit?.id || 0;
    save();
    return hit?.id || null;
  } catch {
    return null;
  }
}

let cache = null;

export function notForMe(r) {
  mem.nope.push(`${r.media_type}:${r.id}`);
  for (const g of r.genre_ids || []) mem.nopeGenres[g] = (mem.nopeGenres[g] || 0) + 1;
  save();
  if (cache) cache.list = cache.list.filter((p) => !(p.r.id === r.id && p.r.media_type === r.media_type));
}

/** Suggestions, best first: [{ r, reason }]. */
export async function picks() {
  const entries = history.list();
  const sig = entries.map((e) => `${e.key}:${isFinished(e)}`).join() + watchlist.all().length + mem.nope.length;
  if (cache && cache.sig === sig) return cache.list;

  const seeds = [];
  const series = new Map();
  for (const e of entries) if (e.kind === "episode" && e.seriesId) {
    const k = `${e.accountId}:${e.seriesId}`;
    if (!series.has(k)) series.set(k, []);
    series.get(k).push(e);
  }
  for (const eps of [...series.values()].slice(0, 10)) {
    const id = await resolve("tv", eps[0].title);
    const done = eps.filter(isFinished).length;
    if (id) seeds.push({ type: "tv", id, title: normalizeTitle(eps[0].title) || eps[0].title, w: done >= 5 ? 3 : done >= 2 ? 2 : done ? 1 : 0.4, done: done > 0 });
  }
  for (const m of entries.filter((e) => e.kind === "movie").slice(0, 10)) {
    const id = m.tmdbId || await resolve("movie", m.title);
    if (id) seeds.push({ type: "movie", id, title: m.title, w: isFinished(m) ? 2 : 0.5, done: isFinished(m) });
  }
  for (const w of watchlist.all().filter((x) => x.type === "movie" || x.type === "tv").slice(-8)) {
    seeds.push({ type: w.type, id: w.id, title: w.title, w: w.status === "done" ? 2 : w.status === "watching" ? 1.5 : 0.7, done: w.status === "done" });
  }
  const uniq = [...new Map(seeds.sort((a, b) => b.w - a.w).map((s) => [`${s.type}:${s.id}`, s])).values()].slice(0, 12);
  if (!uniq.length) {
    cache = { sig, list: [] };
    return [];
  }

  const details = (await Promise.all(uniq.map((s) => tmdb.details(s.type, s.id).then((d) => [s, d]).catch(() => null)))).filter(Boolean);
  const gw = {};
  const cw = {};
  for (const [s, d] of details) {
    // Its proper name ("Dear X"), not the account's ("AR-AS-S: Dear X (2025) (KR)").
    s.title = d.name || d.title || s.title;
    for (const g of d.genres || []) gw[g.id] = (gw[g.id] || 0) + s.w;
    const c = d.origin_country?.[0] || d.production_countries?.[0]?.iso_3166_1;
    if (c) cw[c] = (cw[c] || 0) + s.w;
  }
  const gTotal = Math.max(1, Object.values(gw).reduce((a, b) => a + b, 0));
  const cTotal = Math.max(1, Object.values(cw).reduce((a, b) => a + b, 0));
  const disliked = new Set(Object.entries(mem.nopeGenres).filter(([, n]) => n >= 3).map(([g]) => Number(g)));
  const seen = new Set([...uniq.map((s) => `${s.type}:${s.id}`), ...watchlist.all().map((w) => `${w.type}:${w.id}`), ...mem.nope]);

  const cands = new Map();
  for (const [s, d] of details) {
    for (const r0 of (d.recommendations?.results || []).slice(0, 20)) {
      const r = { ...r0, media_type: r0.media_type || s.type };
      const k = `${r.media_type}:${r.id}`;
      if (seen.has(k) || !r.poster_path) continue;
      const c = cands.get(k) || { r, from: [], seed: 0 };
      c.from.push(s);
      c.seed += s.w;
      cands.set(k, c);
    }
  }
  const list = [...cands.values()].map(({ r, from, seed }) => {
    const country = r.origin_country?.[0];
    const g = (r.genre_ids || []).reduce((a, id) => a + (gw[id] || 0) / gTotal, 0);
    const c = country ? (cw[country] || 0) / cTotal : 0;
    const bad = (r.genre_ids || []).filter((id) => disliked.has(id)).length * 0.8;
    const score = seed * 1.2 + g * 3 + c * 2 + ((r.vote_average || 6) - 6) / 4 - bad;
    const best = from.sort((a, b) => b.w - a.w)[0];
    const parts = [];
    if (best) parts.push(best.done || best.w >= 2 ? `لأنك شفت «${best.title}»` : `يشبه «${best.title}»`);
    const names = [...new Set((r.genre_ids || []).filter((id) => gw[id]).map((id) => GENRES[id]).filter(Boolean))].slice(0, 2);
    if (names.length) parts.push(names.join("، "));
    if (COUNTRIES[country]) parts.push(COUNTRIES[country]);
    if (r.vote_average >= 7.5) parts.push(`تقييم ${r.vote_average.toFixed(1)}`);
    return { r, reason: parts.join(" · ") || "يناسب ذوقك", score };
  }).sort((a, b) => b.score - a.score).slice(0, 20);
  cache = { sig, list };
  return list;
}
