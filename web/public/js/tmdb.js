// The Movie Database: titles, details and where each one streams (via JustWatch).
import { settings } from "./store.js";

const BASE = "https://api.themoviedb.org/3";

export class ApiError extends Error {}

export const image = (path, size = "w342") =>
  !path ? null : path.startsWith("http") ? path : `https://image.tmdb.org/t/p/${size}${path}`;

/** Streaming services with their TMDB provider ids (Shahid and STC tv are matched by name). */
export const SERVICES = [
  { key: "netflix", label: "نتفلكس", ids: [8, 1796], color: "#e50914", home: "https://www.netflix.com/", search: "https://www.netflix.com/search?q=" },
  { key: "prime", label: "برايم فيديو", ids: [9, 119, 2100], color: "#00a8e1", home: "https://www.primevideo.com/", search: "https://www.primevideo.com/search/?phrase=" },
  { key: "shahid", label: "شاهد", ids: [], names: ["shahid", "شاهد"], color: "#14b88a", home: "https://shahid.mbc.net/ar", search: "https://shahid.mbc.net/ar/search?term=" },
  { key: "crunchyroll", label: "كرنشي رول", ids: [283, 1968], color: "#f47521", home: "https://www.crunchyroll.com/", search: "https://www.crunchyroll.com/search?q=" },
  { key: "stctv", label: "STC tv", ids: [], names: ["stc tv", "stctv", "jawwy"], color: "#4f008c", home: "https://www.stctv.com/", search: "https://www.stctv.com/search?q=" },
];

export function serviceFor(provider) {
  const name = (provider.provider_name || "").toLowerCase();
  return SERVICES.find((s) => s.ids.includes(provider.provider_id) || (s.names || []).some((n) => name.includes(n)));
}

const cache = new Map();

export async function get(path, params = {}) {
  const key = (settings.tmdbKey || "").trim();
  if (!key) throw new ApiError("أضف مفتاح TMDB من الإعدادات أول");
  const bearer = key.startsWith("eyJ");
  const url = new URL(`${BASE}/${path}`);
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
  if (!bearer) url.searchParams.set("api_key", key);

  const cacheKey = url.href;
  if (cache.has(cacheKey)) return cache.get(cacheKey);

  let res;
  try {
    res = await fetch(url, { headers: bearer ? { Authorization: `Bearer ${key}`, Accept: "application/json" } : { Accept: "application/json" } });
  } catch {
    throw new ApiError("تأكد من اتصالك بالإنترنت");
  }
  if (res.status === 401) throw new ApiError("مفتاح TMDB غير صحيح، راجعه في الإعدادات");
  if (res.status === 404) throw new ApiError("ما لقينا هذا العمل");
  if (!res.ok) throw new ApiError(`صار خطأ من TMDB (${res.status})`);
  const json = await res.json();
  cache.set(cacheKey, json);
  return json;
}

const withType = (type) => (r) => ({ ...r, media_type: r.media_type || type });

export const trending = (type) => get(`trending/${type}/week`, { language: "ar" }).then((r) => r.results.map(withType(type)));

export function discover(type, params) {
  return discoverPage(type, params).then((r) => r.results);
}

/**
 * One page of TMDB discover. With [streamableOnly] (the default, and always when a
 * provider is chosen) only titles that stream in the user's region come back.
 */
export async function discoverPage(type, params, page = 1, streamableOnly = true) {
  const base = { language: "ar", include_adult: "false", page: String(page) };
  if (streamableOnly || params.with_watch_providers) {
    base.watch_region = settings.region;
    base.with_watch_monetization_types = "flatrate|free|ads";
  }
  const r = await get(`discover/${type}`, { ...base, ...params });
  return { ...r, results: r.results.map(withType(type)) };
}

export const recommendations = (type, id) =>
  get(`${type}/${id}/recommendations`, { language: "ar" }).then((r) => r.results.map(withType(type)));

/** An actor's details (Arabic where TMDB has it) with every film and series they appear in. */
export const person = (id) => get(`person/${id}`, { language: "ar", append_to_response: "combined_credits" });

export const personEnglish = (id) => get(`person/${id}`, { language: "en-US" }).catch(() => null);

export const search = (q) =>
  get("search/multi", { query: q, language: "ar", include_adult: "false" })
    .then((r) => r.results.filter((x) => x.media_type === "movie" || x.media_type === "tv"));

export const details = (type, id) =>
  get(`${type}/${id}`, {
    language: "ar",
    append_to_response: "watch/providers,credits,videos,recommendations",
    include_video_language: "ar,en",
  });

export const englishDetails = (type, id) => get(`${type}/${id}`, { language: "en-US" }).catch(() => null);

/** Provider ids for the user's region, so Shahid and STC tv rows work without hard-coded ids. */
export async function regionServiceIds(type) {
  const list = await get(`watch/providers/${type}`, { language: "ar", watch_region: settings.region }).catch(() => ({ results: [] }));
  const ids = {};
  for (const p of list.results) {
    const s = serviceFor(p);
    if (s) (ids[s.key] ??= []).push(p.provider_id);
  }
  return ids;
}
