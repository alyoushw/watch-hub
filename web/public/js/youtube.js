// The user's YouTube (read-only), with their own Google account: subscriptions, popular, liked,
// playlists and search. Linking is Google's "enter a code" sign-in through this site's server
// (the client secret stays there); the refresh token stays on this device; video data comes
// straight from the YouTube Data API. Same as the Android app (data/YouTube.kt).
import { ytAuth, settings } from "./store.js";

let access = null;
let accessUntil = 0;

async function post(path, body) {
  let res;
  try {
    res = await fetch(path, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body || {}) });
  } catch {
    throw new Error("ما قدرنا نوصل للموقع، تأكد من الإنترنت");
  }
  try {
    return await res.json();
  } catch {
    throw new Error(`الموقع ما رد (${res.status})`);
  }
}

export const linked = () => !!ytAuth.get();

export async function startLink() {
  const o = await post("/yt/device/start");
  if (o.error) throw new Error(o.error === "not_configured" ? "يوتيوب غير مفعّل في السيرفر (GOOGLE_TV_CLIENT_ID)" : o.error === "invalid_scope" ? "فعّل «YouTube Data API v3» في Google Cloud أول" : `ما قدرنا نبدأ الربط (${o.error})`);
  return o;
}

/** Waits until the user approves [code]; resolves true when linked. [alive] stops the wait. */
export async function finishLink(code, alive = () => true) {
  let wait = Math.max(3, code.interval || 5) * 1000;
  const until = Date.now() + (code.expiresIn || 1800) * 1000;
  while (Date.now() < until && alive()) {
    await new Promise((r) => setTimeout(r, wait));
    if (!alive()) return false;
    let o;
    try {
      o = await post("/yt/device/poll", { deviceCode: code.deviceCode });
    } catch {
      continue;
    }
    if (o.status === "ok" && o.refreshToken) {
      ytAuth.set(o.refreshToken);
      access = o.accessToken;
      accessUntil = Date.now() + ((o.expiresIn || 3600) - 120) * 1000;
      return true;
    }
    if (o.status === "slow_down") wait += 2000;
    else if (o.status !== "pending") return false;
  }
  return false;
}

export function unlink() {
  ytAuth.set(null);
  access = null;
  accessUntil = 0;
  feed = null;
}

async function token() {
  const refresh = ytAuth.get();
  if (!refresh) access = null;
  if (access && Date.now() < accessUntil) return access;
  if (!refresh) throw new Error("اربط حساب يوتيوب من الإعدادات");
  const o = await post("/yt/refresh", { refreshToken: refresh });
  if (!o.accessToken) {
    if (o.error === "invalid_grant") {
      unlink();
      throw new Error("انتهى ربط يوتيوب، اربطه مرة ثانية من الإعدادات");
    }
    throw new Error("ما قدرنا نجدد الدخول ليوتيوب");
  }
  access = o.accessToken;
  accessUntil = Date.now() + ((o.expiresIn || 3600) - 120) * 1000;
  return access;
}

async function api(path, query) {
  const url = new URL(`https://www.googleapis.com/youtube/v3/${path}`);
  for (const [k, v] of Object.entries(query)) if (v != null) url.searchParams.set(k, v);
  const res = await fetch(url, { headers: { authorization: `Bearer ${await token()}` } });
  const o = await res.json().catch(() => ({}));
  if (!res.ok) {
    const reason = o.error?.errors?.[0]?.reason;
    if (res.status === 401) access = null;
    throw new Error(reason === "quotaExceeded" ? "خلص حد يوتيوب اليومي، يرجع بكرة"
      : reason === "accessNotConfigured" || reason === "SERVICE_DISABLED" ? "فعّل «YouTube Data API v3» في Google Cloud"
        : res.status === 401 ? "انتهى الدخول ليوتيوب، حاول مرة ثانية" : `يوتيوب ما رد (${reason || res.status})`);
  }
  return o;
}

const thumb = (sn) => {
  const t = sn?.thumbnails || {};
  return (t.high || t.medium || t.standard || t.default)?.url || null;
};
const seconds = (iso) => {
  const m = /P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?/.exec(iso || "");
  return m ? (+m[1] || 0) * 86400 + (+m[2] || 0) * 3600 + (+m[3] || 0) * 60 + (+m[4] || 0) : null;
};
const unescape = (s) => String(s || "").replace(/&amp;/g, "&").replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">");

const videoOf = (o) => o?.snippet && {
  id: o.id, title: o.snippet.title, channel: o.snippet.channelTitle, thumbnail: thumb(o.snippet),
  publishedAt: Date.parse(o.snippet.publishedAt) || null, duration: seconds(o.contentDetails?.duration),
};
const playlistVideo = (o) => {
  const sn = o.snippet;
  const id = o.contentDetails?.videoId || sn?.resourceId?.videoId;
  if (!sn || !id || sn.title === "Private video" || sn.title === "Deleted video") return null;
  return {
    id, title: sn.title, channel: sn.videoOwnerChannelTitle || sn.channelTitle, thumbnail: thumb(sn),
    publishedAt: Date.parse(o.contentDetails?.videoPublishedAt || sn.publishedAt) || null,
  };
};

async function withDurations(list) {
  const ids = [...new Set(list.map((v) => v.id))];
  const d = {};
  for (let i = 0; i < ids.length; i += 50) {
    try {
      const o = await api("videos", { part: "contentDetails", id: ids.slice(i, i + 50).join(","), maxResults: 50 });
      for (const v of o.items || []) d[v.id] = seconds(v.contentDetails?.duration);
    } catch { /* lengths are optional */ }
  }
  return list.map((v) => ({ ...v, duration: d[v.id] ?? v.duration ?? null }));
}

let feed = null;

/** Latest uploads of the channels the user subscribes to, newest first (kept 20 minutes). */
export async function subscriptionsFeed(force = false) {
  if (!force && feed && Date.now() - feed.at < 20 * 60000) return feed.list;
  const channels = [];
  let page;
  for (let i = 0; i < 2; i++) {
    const o = await api("subscriptions", { part: "snippet", mine: "true", maxResults: 50, order: "relevance", pageToken: page });
    for (const it of o.items || []) if (it.snippet?.resourceId?.channelId) channels.push(it.snippet.resourceId.channelId);
    page = o.nextPageToken;
    if (!page) break;
  }
  const lists = await Promise.all([...new Set(channels)].slice(0, 60).map((ch) =>
    api("playlistItems", { part: "snippet,contentDetails", playlistId: "UU" + ch.replace(/^UC/, ""), maxResults: 4 })
      .then((o) => (o.items || []).map(playlistVideo).filter(Boolean)).catch(() => [])));
  const list = await withDurations(lists.flat().sort((a, b) => (b.publishedAt || 0) - (a.publishedAt || 0)).slice(0, 120));
  feed = { at: Date.now(), list };
  return list;
}

export async function popular() {
  const o = await api("videos", { part: "snippet,contentDetails", chart: "mostPopular", regionCode: settings.region || "SA", maxResults: 40 });
  return (o.items || []).map(videoOf).filter(Boolean);
}

export async function liked() {
  const o = await api("videos", { part: "snippet,contentDetails", myRating: "like", maxResults: 50 });
  return (o.items || []).map(videoOf).filter(Boolean);
}

export async function playlists() {
  const o = await api("playlists", { part: "snippet,contentDetails", mine: "true", maxResults: 50 });
  return (o.items || []).map((p) => ({ id: p.id, title: p.snippet?.title || "", thumbnail: thumb(p.snippet), count: p.contentDetails?.itemCount }));
}

export async function playlist(id) {
  const o = await api("playlistItems", { part: "snippet,contentDetails", playlistId: id, maxResults: 50 });
  return withDurations((o.items || []).map(playlistVideo).filter(Boolean));
}

/** Search costs 100 of the day's 10 000 units: only on submit, never while typing. */
export async function search(q) {
  const o = await api("search", { part: "snippet", q, type: "video", maxResults: 30, relevanceLanguage: "ar" });
  return withDurations((o.items || []).filter((it) => it.id?.videoId).map((it) => ({
    id: it.id.videoId, title: unescape(it.snippet.title), channel: it.snippet.channelTitle, thumbnail: thumb(it.snippet),
    publishedAt: Date.parse(it.snippet.publishedAt) || null,
  })));
}
