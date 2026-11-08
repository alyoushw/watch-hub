// The user's Xtream Codes account, reached through the site's proxy (most servers are plain http).
import { settings } from "./store.js";

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

function account(override) {
  const a = override || { host: settings.xHost, user: settings.xUser, pass: settings.xPass };
  if (!a.host || !a.user || !a.pass) throw new XtreamError("أضف حساب Xtream Codes من الإعدادات أول");
  return { host: normalizeHost(a.host), user: a.user.trim(), pass: a.pass };
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
    res = await fetch(via(url.href));
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

export async function categories(kind) {
  const list = await call(KINDS[kind].categories);
  const seen = new Set();
  return (Array.isArray(list) ? list : [])
    .map((c) => ({ id: text(c.category_id), name: text(c.category_name) || text(c.category_id) }))
    .filter((c) => c.id && !seen.has(c.id) && seen.add(c.id));
}

function toItem(kind, o) {
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
  };
}

const cache = new Map();

/** Every item of a kind, loaded once per login. */
export async function all(kind) {
  const key = `${settings.xHost}|${settings.xUser}|${kind}`;
  if (!cache.has(key)) {
    const p = call(KINDS[kind].list).then((list) => {
      const seen = new Set();
      return (Array.isArray(list) ? list : []).map((o) => toItem(kind, o)).filter((x) => x && !seen.has(x.id) && seen.add(x.id));
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

export async function shortEpg(streamId) {
  const root = await call("get_short_epg", { stream_id: streamId, limit: "2" });
  return ((root && root.epg_listings) || []).map((p) => ({
    title: decode64(p.title || ""),
    start: p.start_timestamp ? new Date(p.start_timestamp * 1000) : null,
    end: p.stop_timestamp ? new Date(p.stop_timestamp * 1000) : null,
  }));
}

export async function series(id) {
  const root = await call("get_series_info", { series_id: id });
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

function streamUrl(type, file) {
  const a = account();
  return `${a.host}/${type}/${encodeURIComponent(a.user)}/${encodeURIComponent(a.pass)}/${file}`;
}

/** Links to try in order. Live: HLS (works everywhere, iPhone too), then MPEG-TS for computers. */
export function playUrls(item) {
  if (item.kind === "live") return [streamUrl("live", `${item.id}.m3u8`), streamUrl("live", `${item.id}.ts`)];
  if (item.kind === "movie") return vodUrls("movie", item.id, item.ext);
  return [];
}

export const episodeUrls = (ep) => vodUrls("series", ep.id, ep.ext);

/**
 * The file as the provider stores it, plus the same title as HLS (many panels serve that too).
 * Safari cannot open MKV or AVI at all, so there the HLS link goes first.
 */
function vodUrls(type, id, ext) {
  const file = streamUrl(type, `${id}.${ext || "mp4"}`);
  const hls = streamUrl(type, `${id}.m3u8`);
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
export async function findMatch(kind, tmdbId, titles, year) {
  const list = await all(kind);
  const byId = list.find((x) => x.tmdb === String(tmdbId));
  if (byId) return byId;
  const wanted = new Set(titles.filter(Boolean).map(normalizeTitle).filter((t) => t.length >= 2));
  if (!wanted.size) return null;
  const hits = list.filter((x) => wanted.has(normalizeTitle(x.name)));
  return hits.find((x) => year && (x.year === year || x.name.includes(year))) || hits[0] || null;
}
