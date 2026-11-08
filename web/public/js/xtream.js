// The user's Xtream Codes account, reached through the site's proxy (most servers are plain http).
import { settings, accounts } from "./store.js";

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
const accountOf = (x) => accounts.get(x && x.accountId) || accounts.active();

/**
 * The free server sleeps when unused; while it wakes up, the host answers with its own
 * loading page instead of ours. Wait and retry (about a minute at most) until the proxy answers.
 */
async function fetchWaking(href) {
  for (let i = 0; ; i++) {
    const res = await fetch(href);
    const type = res.headers.get("content-type") || "";
    const ours = res.headers.has("access-control-allow-origin");
    if (ours || !type.includes("text/html") || !settings.proxy || i >= 20) return res;
    await new Promise((r) => setTimeout(r, 3000));
  }
}

async function call(action, params = {}, override) {
  const a = account(override);
  const url = new URL(`${a.host}/player_api.php`);
  url.searchParams.set("username", a.user);
  url.searchParams.set("password", a.pass);
  if (action) url.searchParams.set("action", action);
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);

  let res;
  try {
    res = await fetchWaking(via(url.href));
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
  if (res.status === 403) throw new XtreamError("البروكسي ما يسمح بهذا الهوست (PROXY_HOSTS)");
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
    categoryId: text(o.category_id),
    num: Number(o.num) || null,
    tmdb: text(o.tmdb) || text(o.tmdb_id),
    year: (text(o.year) || text(o.releaseDate) || text(o.release_date) || "").slice(0, 4) || null,
    accountId,
  };
}

const cache = new Map();

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

export async function series(id, acc) {
  const root = await call("get_series_info", { series_id: id }, acc);
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
      image: e.info && typeof e.info.movie_image === "string" && e.info.movie_image.startsWith("http") ? e.info.movie_image : null,
    }))
    .sort((a, b) => a.season - b.season || a.number - b.number);
  return { name: text(info.name) || "", plot: text(info.plot), cover: text(info.cover), episodes };
}

function streamUrl(acc, type, file) {
  const a = account(acc);
  return `${a.host}/${type}/${encodeURIComponent(a.user)}/${encodeURIComponent(a.pass)}/${file}`;
}

/** Links to try in order. Live: HLS (works everywhere, iPhone too), then MPEG-TS for computers. */
export function playUrls(item) {
  const acc = accountOf(item);
  if (item.kind === "live") return [streamUrl(acc, "live", `${item.id}.m3u8`), streamUrl(acc, "live", `${item.id}.ts`)];
  if (item.kind === "movie") return vodUrls(acc, "movie", item.id, item.ext);
  return [];
}

/** [acc] is the account the series was opened from. */
export const episodeUrls = (ep, acc) => vodUrls(acc || accountOf(ep), "series", ep.id, ep.ext);

/**
 * The file as the provider stores it, plus the same title as HLS (many panels serve that too).
 * Safari cannot open MKV or AVI at all, so there the HLS link goes first.
 */
function vodUrls(acc, type, id, ext) {
  const file = streamUrl(acc, type, `${id}.${ext || "mp4"}`);
  const hls = streamUrl(acc, type, `${id}.m3u8`);
  const playable = /^(mp4|m4v|mov)$/i.test(ext || "mp4");
  const safari = document.createElement("video").canPlayType("application/vnd.apple.mpegurl") !== "";
  return !playable && safari ? [hls, file] : [file, hls];
}

/** "AR| The Batman (2022) 4K" → "the batman"; folds Arabic letter variants. Same rules as the Android app. */
export function normalizeTitle(raw) {
  let t = raw || "";
  const tag = /^\s*(\[[^\]]*\]|\|[^|]{1,6}\||[A-Z]{2,3}\s*[|:]\s*|[A-Z]{2,3}\s+-\s+)/;
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
  key: key(c.accountId, "live", c.id), kind: "live", itemId: c.id, title: c.name, image: c.image, accountId: c.accountId,
});

export const entryMovie = (m, tmdbId) => ({
  key: key(m.accountId, "movie", m.id), kind: "movie", itemId: m.id, title: m.name, image: m.image, ext: m.ext,
  accountId: m.accountId, tmdbId: tmdbId || Number(m.tmdb) || null,
});

export const entryEpisode = (acc, seriesId, seriesName, cover, ep) => ({
  key: key(acc.id, "episode", ep.id),
  kind: "episode",
  itemId: ep.id,
  title: seriesName,
  subtitle: `الموسم ${ep.season} · الحلقة ${ep.number}` + (ep.title && !ep.title.startsWith("الحلقة") ? ` · ${ep.title}` : ""),
  image: ep.image || cover,
  ext: ep.ext,
  seriesId,
  accountId: acc.id,
});

export const episodeKey = (acc, ep) => key(acc.id, "episode", ep.id);

/** Links for a history entry, from the account it was watched on. */
export function urlsFor(e) {
  if (e.kind === "live") return playUrls({ kind: "live", id: e.itemId, accountId: e.accountId });
  if (e.kind === "movie") return playUrls({ kind: "movie", id: e.itemId, ext: e.ext, accountId: e.accountId });
  return episodeUrls({ id: e.itemId, ext: e.ext, accountId: e.accountId });
}

/** Searches every account's channels, films and series by name (for the search page). */
/** The words to look for in names: [normalizeTitle] without "مسلسل", "فيلم", … in front. */
export const searchWords = (q) => normalizeTitle(q).replace(/^(?:(?:ابي|ابغى|ابغي|اشوف|شغل|مسلسل|فيلم|فلم|انمي|قناه|قناة|the)\s+)+/u, "").trim();

export async function searchAll(q) {
  const wanted = searchWords(q);
  if (wanted.length < 2) return [];
  const out = [];
  await Promise.all(accounts.all().flatMap((acc) => ["movie", "series", "live"].map(async (kind) => {
    try {
      const list = await all(kind, acc);
      for (const x of list) if (normalizeTitle(x.name).includes(wanted)) out.push(x);
    } catch { /* that account is unreachable right now */ }
  })));
  const rank = (x) => (normalizeTitle(x.name) === wanted ? 0 : normalizeTitle(x.name).startsWith(wanted) ? 1 : 2);
  return out.sort((a, b) => rank(a) - rank(b)).slice(0, 60);
}
