// The user's Xtream Codes account, reached through the site's proxy (most servers are plain http).
import { settings, accounts } from "./store.js?v=20261118d";
import { urls as fourUUrls } from "./fouru.js?v=20261118d";

export class XtreamError extends Error {}

export const KINDS = {
  live: { label: "مباشر", categories: "get_live_categories", list: "get_live_streams" },
  movie: { label: "الأفلام", categories: "get_vod_categories", list: "get_vod_streams" },
  series: { label: "المسلسلات", categories: "get_series_categories", list: "get_series" },
};

export function normalizeHost(raw) {
  let h = (raw || "").trim().replace(/\/+$/, "").replace(/\/?player_api\.php$/i, "");
  if (h && !/^https?:\/\//i.test(h)) h = `http://${h}`;
  return h;
}

/** Sends a URL through the proxy, unless the user turned the proxy off. */
export function via(url) {
  if (new URL(url, location.href).origin === location.origin && new URL(url, location.href).pathname.match(/^\/(fouru|sources|offline)\//)) return new URL(url, location.href).href;
  let base = (settings.proxy || "").trim();
  if (!base) return url;
  // Anything that is not a path or a link (e.g. a password typed in the wrong box) means the built-in proxy.
  if (!base.startsWith("/") && !/^https?:\/\//i.test(base)) base = "/proxy";
  const abs = new URL(base, location.href).href;
  const key = settings.proxyKey ? `&key=${encodeURIComponent(settings.proxyKey)}` : "";
  return `${abs}${abs.includes("?") ? "&" : "?"}url=${encodeURIComponent(url)}${key}`;
}

/** Logos and posters: https ones load directly, http ones through the proxy. */
export const img = (url) => (!url ? null : url.startsWith("https://") ? url : url.startsWith("http://") ? via(url) : null);

/** [acc] (an account object), or the account the pages are showing. */
function account(acc) {
  const a = acc || accounts.active();
  if (!a || !a.host || !a.user || !a.pass) throw new XtreamError("أضف حساب Xtream Codes من الإعدادات أول");
  return { id: a.id, host: normalizeHost(a.host), user: a.user.trim(), pass: a.pass };
}

/** The account an item or history entry belongs to. */
const accountOf = (x) => {
  if (!x?.accountId) return accounts.active();
  const selected = accounts.get(x.accountId);
  if (!selected) throw new XtreamError("حساب هذا المحتوى غير موجود على هذا المتصفح؛ افتح حسابه الصحيح من الإعدادات");
  return selected;
};

/**
 * The free server sleeps when unused; while it wakes up, the host answers with its own
 * loading page instead of ours. Wait and retry (about a minute at most) until the proxy answers.
 */
async function fetchWaking(href, signal) {
  for (let i = 0; ; i++) {
    const res = await fetch(href, { signal });
    const type = res.headers.get("content-type") || "";
    const ours = res.headers.has("access-control-allow-origin");
    if (ours || !type.includes("text/html") || !settings.proxy || i >= 20) return res;
    await new Promise((r) => setTimeout(r, 3000));
  }
}

async function call(action, params = {}, override, signal) {
  const a = account(override);
  const url = new URL(`${a.host}/player_api.php`);
  url.searchParams.set("username", a.user);
  url.searchParams.set("password", a.pass);
  if (action) url.searchParams.set("action", action);
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);

  let res;
  try {
    res = await fetchWaking(via(url.href), signal);
  } catch {
    throw new XtreamError(settings.proxy
      ? "ما قدرنا نوصل للبروكسي، تأكد من رابطه في الإعدادات"
      : "المتصفح منع الاتصال بالسيرفر، فعّل البروكسي في الإعدادات");
  }
  if (res.status === 401 && settings.proxy) {
    const text = await res.text();
    if (text.includes("proxy key")) throw new XtreamError("مفتاح البروكسي غلط، راجعه في الإعدادات");
    throw new XtreamError("اسم المستخدم أو كلمة المرور غلط");
  }
  if (res.status === 403) {
    const body = await res.text();
    if (body.trim() === "host not allowed") throw new XtreamError("البروكسي ما يسمح بهذا الهوست (PROXY_HOSTS)؛ يلزم إضافته في إعدادات الاستضافة");
    throw new XtreamError("سيرفر الاشتراك رفض اتصال الموقع (403)؛ قد يكون الهوست تغيّر أو الاتصال من الاستضافة محظوراً");
  }
  if (res.status === 502) throw new XtreamError("البروكسي ما قدر يوصل للسيرفر، تأكد من الهوست");
  if (!res.ok) throw new XtreamError(`السيرفر رد بخطأ (${res.status})`);
  try {
    return await res.json();
  } catch {
    throw new XtreamError("الرد مو من سيرفر Xtream Codes، تأكد من الهوست");
  }
}

const text = (v) => (v === null || v === undefined ? null : String(v).trim() || null);

export async function check(override) {
  const root = await call(null, {}, override);
  const u = root && root.user_info;
  if (!u) throw new XtreamError("الرد مو من سيرفر Xtream Codes، تأكد من الهوست");
  if (String(u.auth) !== "1") throw new XtreamError("اسم المستخدم أو كلمة المرور غلط");
  return {
    status: text(u.status),
    expires: text(u.exp_date) ? new Date(Number(u.exp_date) * 1000) : null,
    max: text(u.max_connections),
    active: text(u.active_cons),
  };
}

export async function categories(kind, acc) {
  const list = await call(KINDS[kind].categories, {}, acc);
  const seen = new Set();
  return (Array.isArray(list) ? list : [])
    .map((c) => ({ id: text(c.category_id), name: text(c.category_name) || text(c.category_id) }))
    .filter((c) => c.id && !seen.has(c.id) && seen.add(c.id));
}

function toItem(kind, o, accountId) {
  const id = text(kind === "series" ? o.series_id : o.stream_id);
  if (!id) return null;
  const image = text(kind === "series" ? o.cover : o.stream_icon);
  return {
    kind,
    id,
    name: text(o.name) || text(o.title) || id,
    image: image && image.startsWith("http") ? image : null,
    ext: text(o.container_extension),
    directSource: text(o.direct_source),
    categoryId: text(o.category_id),
    num: Number(o.num) || null,
    tmdb: text(o.tmdb) || text(o.tmdb_id),
    year: (text(o.year) || text(o.releaseDate) || text(o.release_date) || "").slice(0, 4) || null,
    accountId,
  };
}

const cache = new Map();
const seriesDetails = new Map();

/** Every item of a kind, loaded once per login. */
export async function all(kind, acc) {
  const a = account(acc);
  const key = `${a.host}|${a.user}|${kind}`;
  if (!cache.has(key)) {
    const p = call(KINDS[kind].list, {}, a).then((list) => {
      const seen = new Set();
      return (Array.isArray(list) ? list : []).map((o) => toItem(kind, o, a.id)).filter((x) => x && !seen.has(x.id) && seen.add(x.id));
    });
    cache.set(key, p);
    p.catch(() => cache.delete(key));
  }
  return cache.get(key);
}

export function clearCache() {
  cache.clear();
  seriesDetails.clear();
}

function decode64(s) {
  try {
    return new TextDecoder().decode(Uint8Array.from(atob(s), (c) => c.charCodeAt(0))) || s;
  } catch {
    return s;
  }
}

export async function shortEpg(streamId, acc) {
  const root = await call("get_short_epg", { stream_id: streamId, limit: "2" }, acc);
  return ((root && root.epg_listings) || []).map((p) => ({
    title: decode64(p.title || ""),
    start: p.start_timestamp ? new Date(p.start_timestamp * 1000) : null,
    end: p.stop_timestamp ? new Date(p.stop_timestamp * 1000) : null,
  }));
}

export async function series(id, acc, signal) {
  const root = await call("get_series_info", { series_id: id }, acc, signal);
  const info = (root && root.info) || {};
  const groups = root && root.episodes;
  const raw = Array.isArray(groups) ? groups.flat() : Object.values(groups || {}).flat();
  const seen = new Set();
  const episodes = raw
    .filter((e) => e && text(e.id) && !seen.has(text(e.id)) && seen.add(text(e.id)))
    .map((e) => ({
      id: text(e.id),
      season: Number(e.season) || 1,
      number: Number(e.episode_num) || 0,
      title: text(e.title) || `الحلقة ${e.episode_num}`,
      ext: text(e.container_extension),
      directSource: text(e.direct_source),
      image: e.info && typeof e.info.movie_image === "string" && e.info.movie_image.startsWith("http") ? e.info.movie_image : null,
    }))
    .sort((a, b) => a.season - b.season || a.number - b.number);
  const result = { name: text(info.name) || "", plot: text(info.plot), cover: text(info.cover), episodes };
  const a = account(acc);
  if (seriesDetails.size >= 16) seriesDetails.clear();
  seriesDetails.set(`${a.id}:${id}`, { at: Date.now(), result });
  return result;
}

function streamUrl(acc, type, file) {
  const a = account(acc);
  return `${a.host}/${type}/${encodeURIComponent(a.user)}/${encodeURIComponent(a.pass)}/${file}`;
}

/** Links to try in order. Live: HLS (works everywhere, iPhone too), then MPEG-TS for computers. */
export function playUrls(item) {
  const acc = accountOf(item);
  if (item.kind === "live") return withDirect(acc, item.directSource, [streamUrl(acc, "live", `${item.id}.m3u8`), streamUrl(acc, "live", `${item.id}.ts`)]);
  if (item.kind === "movie") return vodUrls(acc, "movie", item.id, item.ext, item.directSource);
  return [];
}

/** [acc] is the account the series was opened from. */
export const episodeUrls = (ep, acc) => vodUrls(acc || accountOf(ep), "series", ep.id, ep.ext, ep.directSource);

/**
 * The file as the provider stores it, plus the same title as HLS (many panels serve that too).
 * Safari cannot open MKV or AVI at all, so there the HLS link goes first.
 */
function withDirect(acc, value, fallback) {
  let direct;
  try { if (value && /^(https?:\/\/|\/)/i.test(value)) { const u = new URL(value, account(acc).host); if (/^https?:$/.test(u.protocol)) direct = u.href; } } catch { /* use the canonical provider URL */ }
  return [...new Set([...(direct ? [direct] : []), ...fallback])];
}

function vodUrls(acc, type, id, ext, directSource) {
  const file = streamUrl(acc, type, `${id}.${ext || "mp4"}`);
  const hls = streamUrl(acc, type, `${id}.m3u8`);
  const playable = /^(mp4|m4v|mov)$/i.test(ext || "mp4");
  const appleMobile = /iPhone|iPad|iPod/.test(navigator.userAgent) || navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1;
  const safari = appleMobile || /Version\/[\d.]+.*Safari/.test(navigator.userAgent) && !/Chrome|Chromium|Edg\//.test(navigator.userAgent);
  return withDirect(acc, directSource, !playable && safari ? [hls, file] : [file, hls]);
}

/** "AR| The Batman (2022) 4K" → "the batman"; folds Arabic letter variants. Same rules as the Android app. */
export function normalizeTitle(raw) {
  let t = raw || "";
  // Provider labels in front of the name: "[AR]", "|AR|", "AR:", "AR - ", "AR-AS-S:", "4K-AR|".
  const tag = /^\s*(\[[^\]]*\]|\|[^|]{1,6}\||[A-Z0-9]{1,6}(-[A-Z0-9]{1,6}){1,4}\s*[|:]\s*|[A-Z]{2,3}\s*[|:]\s*|[A-Z]{2,3}\s+-\s+)/;
  t = t.replace(tag, "").replace(tag, "");
  return t.toLowerCase()
    .replace(/[([{][^)\]}]*[)\]}]/g, " ")
    .replace(/\b(4k|uhd|fhd|hd|sd|hevc|multi[- ]?sub|vip)\b/g, " ")
    .replace(/\b(19|20)\d{2}\b/g, " ")
    .replace(/[أإآ]/g, "ا").replace(/ة/g, "ه").replace(/ى/g, "ي")
    .replace(/[^\p{L}\p{Nd}]+/gu, " ")
    .trim().replace(/\s+/g, " ");
}

/** Finds a title page's film or series in the account: TMDB id first, then name and year. */
export async function findMatch(kind, tmdbId, titles, year, acc) {
  const list = await all(kind, acc);
  const byId = list.find((x) => x.tmdb === String(tmdbId));
  if (byId) return byId;
  const wanted = new Set(titles.filter(Boolean).map(normalizeTitle).filter((t) => t.length >= 2));
  if (!wanted.size) return null;
  const hits = list.filter((x) => wanted.has(normalizeTitle(x.name)));
  return hits.find((x) => year && (x.year === year || x.name.includes(year))) || hits[0] || null;
}

// ---------- watch history entries (same shape as the Android app) ----------

const key = (accountId, kind, id) => `${accountId}:${kind}:${id}`;

export const entryLive = (c) => ({
  key: key(c.accountId, "live", c.id), kind: "live", itemId: c.id, title: c.name, image: c.image, accountId: c.accountId, directSource: c.directSource,
});

export const entryMovie = (m, tmdbId) => ({
  key: key(m.accountId, "movie", m.id), kind: "movie", itemId: m.id, title: m.name, image: m.image, ext: m.ext,
  directSource: m.directSource, accountId: m.accountId, tmdbId: tmdbId || Number(m.tmdb) || null,
});

export const entryEpisode = (acc, seriesId, seriesName, cover, ep) => ({
  key: key(acc.id, "episode", ep.id),
  kind: "episode",
  itemId: ep.id,
  title: seriesName,
  subtitle: `الموسم ${ep.season} · الحلقة ${ep.number}` + (ep.title && !ep.title.startsWith("الحلقة") ? ` · ${ep.title}` : ""),
  image: ep.image || cover,
  ext: ep.ext,
  directSource: ep.directSource,
  season: ep.season,
  episodeNumber: ep.number,
  seriesId,
  accountId: acc.id,
});

export const episodeKey = (acc, ep) => key(acc.id, "episode", ep.id);

/** Links for a history entry, from the account it was watched on. */
export function urlsFor(e) {
  if (e.fourURef) return fourUUrls(e.fourURef);
  if (e.kind === "live") return playUrls({ kind: "live", id: e.itemId, accountId: e.accountId, directSource: e.directSource });
  if (e.kind === "movie") return playUrls({ kind: "movie", id: e.itemId, ext: e.ext, accountId: e.accountId, directSource: e.directSource });
  return episodeUrls({ id: e.itemId, ext: e.ext, accountId: e.accountId, directSource: e.directSource });
}

/** Searches every account's channels, films and series by name (for the search page). */

/** Refresh VOD metadata for history/resume instead of trusting an old file suffix forever. */
export async function resolvePlayback(entry, supplied = []) {
  const a = accountOf(entry); // Never silently substitute a different provider's account.
  if (!a) return supplied;
  if (entry.kind === "live") return urlsFor(entry);
  const controller = new AbortController(), timer = setTimeout(() => controller.abort(), 10000);
  try {
    if (entry.kind === "episode" && entry.seriesId) {
      const old = seriesDetails.get(`${a.id}:${entry.seriesId}`);
      const info = old && Date.now() - old.at < 30000 ? old.result : await series(entry.seriesId, a, controller.signal);
      const episode = info.episodes.find((e) => e.id === String(entry.itemId));
      if (episode) return episodeUrls({ ...episode, ext: episode.ext || entry.ext, directSource: episode.directSource || entry.directSource }, a);
      // The provider may have replaced an episode ID; match only its exact season/number.
      const named = /الموسم\s*(\d+).*?الحلقة\s*(\d+)/.exec(entry.subtitle || "");
      const season = Number(entry.season || named?.[1]), number = Number(entry.episodeNumber || named?.[2]);
      const same = info.episodes.filter((e) => e.season === season && e.number === number);
      if (same.length === 1) return episodeUrls({ ...same[0], ext: same[0].ext || entry.ext }, a);
    } else if (entry.kind === "movie") {
      const root = await call("get_vod_info", { vod_id: entry.itemId }, a, controller.signal);
      const row = root?.movie_data;
      if (row && String(row.stream_id || entry.itemId) === String(entry.itemId)) return vodUrls(a, "movie", entry.itemId, text(row.container_extension) || entry.ext, text(row.direct_source) || entry.directSource);
    }
  } catch { /* Some panels do not implement detail reads; retain the provided file URL. */ }
  finally { clearTimeout(timer); }
  return supplied.length ? supplied : urlsFor(entry);
}

/** A provider's media may be reachable from the user's network even when its server route fails. */
export function secureDirectUrl(entry, raw) {
  if (entry.animeRef || entry.sourceRef || entry.fourURef || !entry.accountId) return null;
  const a = accounts.get(entry.accountId); if (!a) return null;
  try {
    const url = new URL(raw), host = new URL(normalizeHost(a.host));
    if (url.origin === location.origin || url.hostname !== host.hostname) return null;
    if (url.protocol === "https:") return url.href;
    // Try the standard secure port only for a standard HTTP URL. Custom ports keep using the proxy.
    if (url.protocol !== "http:" || url.port && url.port !== "80") return null;
    url.protocol = "https:"; url.port = ""; return url.href;
  } catch { return null; }
}
