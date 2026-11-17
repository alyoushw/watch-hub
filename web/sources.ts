import { parseHTML } from "npm:linkedom@0.18.12";

type Query = { type: string; tmdbId: number; originalTitle: string; searchTitle?: string; year: number; season?: number; episode?: number; seasonYear?: number; imdbId?: string };
type Variant = { id: string; label: string; url: string; transport: string; httpHeaders?: Record<string, string>; legacyId?: string };
type Source = { id: string; providerId: string; providerName: string; query: Query; variants: Variant[]; webLinks?: { label: string; url: string; download?: boolean }[]; attribution: string; licenceUrl: string };
export type MediaSession = { token: string; host: string; headers: Record<string, string>; expires: number };
type Proxy = (req: Request, url: URL, session?: MediaSession) => Promise<Response>;
const sessions = new Map<string, MediaSession>();
const CORS = { "access-control-allow-origin": "*", "cache-control": "no-store" };
const AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
const starts: Record<string, string> = { "moviz-time": "https://moviz-time.buzz/", topcinema: "https://web6.topcinema.fan/", egybest: "https://egytbest.live/", tuktuk: "https://zx33.tuktuk-sa.online/" };
const labels: Record<string, string> = { "moviz-time": "Moviz Time", topcinema: "TopCinema", egybest: "EgyBest", tuktuk: "TukTuk", catalogue: "كتالوج المصادر", torrent: "تورنت" };
const ORDINALS = ["الاول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن", "التاسع", "العاشر", "الحادي عشر", "الثاني عشر", "الثالث عشر", "الرابع عشر", "الخامس عشر", "السادس عشر", "السابع عشر", "الثامن عشر", "التاسع عشر", "العشرون"];
const arabic = (s: string) => s.replace(/[أإآ]/g, "ا").replace(/ة/g, "ه").replace(/ى/g, "ي").replace(/[٠-٩]/g, (c) => String(c.charCodeAt(0) - 1632));
const key = (s: string) => arabic(s.normalize("NFKC").toLowerCase()).replace(/[^\p{L}\p{N}]+/gu, " ").trim();
const arr = (doc: any, selector: string): any[] => Array.from(doc.querySelectorAll(selector));
const text = (doc: any, selector: string) => doc.querySelector(selector)?.textContent?.trim() || "";
const absolute = (value: string | null, base: string) => { try { const u = new URL(value || "", base); return /^https?:$/.test(u.protocol) ? u.href : ""; } catch { return ""; } };
const json = (data: unknown, status = 200) => Response.json(data, { status, headers: CORS });
async function bounded(url: string, init: RequestInit = {}, limit = 4 * 1024 * 1024) {
  const response = await fetch(url, { ...init, headers: { "user-agent": AGENT, ...Object.fromEntries(new Headers(init.headers)) }, signal: AbortSignal.timeout(30000) });
  if (!response.ok) throw new Error(`المصدر غير متاح (${response.status})`);
  const reader = response.body?.getReader();
  if (!reader) throw new Error("لم تصل بيانات المصدر");
  const chunks: Uint8Array[] = []; let size = 0;
  try { while (true) { const { value, done } = await reader.read(); if (done) break; size += value.length; if (size > limit) throw new Error("بيانات المصدر أكبر من الحد المسموح"); chunks.push(value); } }
  finally { await reader.cancel().catch(() => {}); }
  const bytes = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  return { response, body: new TextDecoder().decode(bytes), bytes };
}
async function page(url: string, referer = url, body?: URLSearchParams) {
  const r = await bounded(url, { headers: { referer, ...(body ? { "x-requested-with": "XMLHttpRequest" } : {}) }, ...(body ? { method: "POST", body } : {}) });
  return { ...r, url: r.response.url || url, doc: (parseHTML(r.body) as any).document };
}
async function anime(path: string, fields: Record<string, string>) {
  const token = Deno.env.get("WATCHHUB_ANIMEIFY_TOKEN") || Deno.env.get("ANIMEIFY_TOKEN");
  if (!token) throw new Error("مصدر أنمي فاي يحتاج تهيئة مفتاح المصدر على خادم الويب");
  const { body } = await bounded("https://animeify.net/animeify/apis_v4/" + path, { method: "POST", body: new URLSearchParams({ ...fields, Token: token }) });
  return JSON.parse(body.replace(/^\uFEFF/, ""));
}
function animeTitles(rows: any[]) {
  return rows.filter((o) => o && (o.AnimeId || o.anime_id)).map((o) => ({ id: String(o.AnimeId || o.anime_id), malId: Number(o.MalId_org) || Number(o.MalId) || null,
    title: o.EN_Title || o.AR_Title || "", type: o.Type || "SERIES", season: Math.max(1, Number(o.Season) || 1), relation: String(o.RelationId || ""),
    poster: o.Thumbnail ? "https://animeify.net/animeify/files/thumbnails/" + encodeURIComponent(o.Thumbnail) : null, status: o.Status || "" }));
}
const animeServerKeys: Record<string, string> = { FRFhdQ: "MediaFire · FHD", FRLink: "MediaFire · HD", FRLowQ: "MediaFire · SD", SVLink: "Sendvid · HD", SVLowQ: "Sendvid · SD", OKLink: "OK" };
async function animeServers(ref: any) {
  const data = await anime("anime/load_servers.php", { UserId: "0", AnimeId: String(ref.animeId), Episode: String(ref.episode), AnimeType: String(ref.type) });
  return Object.entries(animeServerKeys).filter(([k]) => data.CurrentEpisode?.[k] && !["0", "1"].includes(String(data.CurrentEpisode[k]))).map(([k, label]) => ({ key: k, label, value: String(data.CurrentEpisode[k]) }));
}
async function animeUrl(ref: any) {
  const server = (await animeServers(ref)).find((s) => s.key === ref.server);
  if (!server) throw new Error("السيرفر لم يعد متاحاً؛ اختر سيرفراً آخر");
  let url = "";
  if (/^https:\/\/.*\.mp4(?:\?|$)/i.test(server.value)) url = server.value;
  else {
    if (!/^[a-zA-Z0-9_-]{3,160}$/.test(server.value)) throw new Error("معرّف السيرفر غير صالح");
    if (server.key.startsWith("FR")) {
      const p = await page(`https://www.mediafire.com/file/${server.value}`);
      url = p.doc.querySelector("#downloadButton[href]")?.getAttribute("href") || "";
      if (!url) { const encoded = p.doc.querySelector("[data-scrambled-url]")?.getAttribute("data-scrambled-url"); if (encoded) url = atob(encoded); }
      if (!/^https:\/\/download[^/]*\.mediafire\.com\//i.test(url)) throw new Error("تعذّر جلب رابط MediaFire");
    } else if (server.key.startsWith("SV")) {
      const p = await page(`https://sendvid.com/embed/${server.value}`); url = absolute(p.doc.querySelector("source[src],video[src]")?.getAttribute("src"), p.url);
    } else if (server.key === "OKLink") {
      const p = await page(`https://ok.ru/videoembed/${server.value}`);
      for (const el of arr(p.doc, "[data-options]")) { try { const options = JSON.parse(el.getAttribute("data-options")); const data = JSON.parse(options.flashvars.metadata); url = data.videos?.at(-1)?.url || ""; if (url) break; } catch { /* try another player */ } }
    }
  }
  if (!url.startsWith("https://")) throw new Error("لم يُرجع المصدر رابط تشغيل صالحاً");
  return { id: server.key, label: server.label, url, transport: "MP4" } as Variant;
}
function validQuery(q: Query) { return ["MOVIE", "TV"].includes(q?.type) && Number.isInteger(q.tmdbId) && q.tmdbId > 0 && typeof q.originalTitle === "string" && q.originalTitle.length > 0 && q.originalTitle.length <= 300 && q.year >= 1888 && q.year <= 2200 && (q.type !== "TV" || Number.isInteger(q.season) && q.season! >= 0 && Number.isInteger(q.episode) && q.episode! > 0); }
function matches(raw: string, q: Query, requireEpisode = true) {
  let t = arabic(raw).replace(/^مشاهده\s+/, "");
  const series = q.type === "TV";
  if (series && !/^(مسلسل|انمي|كرتون)\s/.test(t) || !series && !/^فيلم\s/.test(t)) return false;
  t = t.substring(t.indexOf(" ") + 1);
  if (series) {
    const season = /الموسم\s+(.+?)(?:\s+الحلقه|\s+مترجم|\s+مدبلج|$)/.exec(t);
    const number = season ? Number(season[1]) || ORDINALS.indexOf(season[1].trim()) + 1 : 1;
    const episode = /الحلقه\s*(\d+)/.exec(t);
    if (number !== q.season || (requireEpisode || episode) && Number(episode?.[1]) !== q.episode) return false;
    t = t.split(/الموسم|الحلقه/)[0].trim();
  } else t = t.split(/مترجم|مدبلج|اونلاين/)[0].replace(/\(?\b(?:18|19|20|21)\d{2}\)?\s*$/, "").trim();
  return [q.originalTitle, q.searchTitle].filter(Boolean).some((name) => key(name!) === key(t));
}
function source(provider: string, id: string, q: Query, url: string, variants: Variant[], webLinks: Source["webLinks"] = []): Source {
  return { id: `${provider}-${id}`, providerId: provider, providerName: labels[provider] || provider, query: q, variants, webLinks, attribution: `${labels[provider]} · ${q.originalTitle} (${q.year})`, licenceUrl: url };
}
function postId(p: any): string | null {
  const short = p.doc.querySelector('link[rel="shortlink"]')?.getAttribute("href") || p.doc.querySelector("input#shortlink")?.getAttribute("value");
  try { const id = new URL(short, p.url).searchParams.get("p"); return id && /^\d{1,10}$/.test(id) ? id : null; } catch { return null; }
}
function imdbMatches(p: any, q: Query) {
  const ids = arr(p.doc, 'a[href*="imdb.com/title/"]').map((a) => /tt\d{7,10}/.exec(a.getAttribute("href"))?.[0]).filter(Boolean);
  return !q.imdbId || !ids.length || ids.includes(q.imdbId);
}
const unquote = (v: string) => v.replace(/\\(u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}|.)/gs, (_, x) => /^[ux]/.test(x) && x.length > 1 ? String.fromCharCode(parseInt(x.slice(1), 16)) : ({ n: "\n", r: "\r", t: "\t" }[x as string] || x));
function unpack(script: string) {
  const all = [script];
  const pattern = /eval\(function\(p,a,c,k,e,[dr]\).*?\}\('((?:\\.|[^'\\])*)',(\d+),(\d+),'((?:\\.|[^'\\])*)'\.split\('\|'\)/gs;
  for (const m of script.matchAll(pattern)) {
    const radix = Number(m[2]), count = Number(m[3]);
    if (radix < 2 || radix > 36 || count < 1 || count > 5000 || all.length > 5) continue;
    const words = unquote(m[4]).split("|");
    all.push(unquote(m[1]).replace(/\b[0-9a-z]+\b/g, (word) => { const n = parseInt(word, radix); return n < count && words[n] ? words[n] : word; }));
  }
  return all;
}
async function embeds(url: string, referer: string, index: string, label: string): Promise<Variant[]> {
  const host = new URL(url).hostname;
  const allowed = ["uqload.co", "uqload.com", "uqload.cx", "streamwish.com", "streamwish.to", "strwish.com", "wishonly.site", "vidtube.pro", "vidtube.one", "vidtube.fun", "updown.cam", "forafile.com", "forafile.net", "filemoon.sx", "filemoon.to", "voe.sx", "uqload.vc", "uqload.net", "mp4upload.com", "vidspeed.org", "vidmoly.org", "1vid.xyz", "mp4plus.org", "smoothpre.com", "minochinos.com", "stmruby.com", "streamruby.com", "hgcloud.to", "playmogo.com", "mixdrop.top", "mixdrop.ps"];
  if (!allowed.some((h) => host === h || host.endsWith("." + h))) return [];
  const p = await page(url, referer);
  const values = new Map<string, string>();
  for (const script of unpack(p.body)) for (const match of script.matchAll(/\b(hls[234]?|file|src|source|wurl)\s*["']?\s*[:=]\s*["']([^"']+)["']/g)) {
    const value = absolute(unquote(match[2]), p.url), path = new URL(value || p.url).pathname.toLowerCase();
    const type = match[1].startsWith("hls") || /\.m3u8|\/hls|\/master\.txt$/.test(path) ? "HLS" : /\.mp4$/.test(path) ? "MP4" : /\.mkv$/.test(path) ? "MKV" : "";
    if (value.startsWith("https://") && type && !values.has(value)) values.set(value, type);
  }
  for (const el of arr(p.doc, "source[src],video[src]")) { const value = absolute(el.getAttribute("src"), p.url); if (/\.(mp4|m3u8|mkv)(?:\?|$)/i.test(value)) values.set(value, /\.m3u8/i.test(value) ? "HLS" : /\.mkv/i.test(value) ? "MKV" : "MP4"); }
  return [...values].slice(0, 3).map(([url, transport], n) => {
    const headers: Record<string, string> = { "User-Agent": AGENT, Referer: p.url };
    if (new URL(url).host === new URL(p.url).host) { const cookie = p.response.headers.getSetCookie().map((c) => c.split(";")[0]).join("; "); if (cookie) headers.Cookie = cookie; }
    return { id: `server-${index}-${n}`, label: `${label} · ${transport}`, url, transport, httpHeaders: headers };
  });
}
async function compatible(v: Variant, deep = false) {
  try {
    const response = await fetch(v.url, { headers: { ...v.httpHeaders, Range: "bytes=0-1023" }, signal: AbortSignal.timeout(12000) });
    if (!response.ok) { await response.body?.cancel(); return false; }
    const reader = response.body?.getReader(); if (!reader) return false;
    const part = await reader.read(); await reader.cancel();
    const bytes = part.value || new Uint8Array(), t = new TextDecoder().decode(bytes.slice(0, 100));
    if (t.trimStart().startsWith("#EXTM3U") && deep) {
      const manifest = await bounded(v.url, { headers: v.httpHeaders }, 2 * 1024 * 1024);
      let playlist = manifest.body, base = v.url;
      if (playlist.includes("#EXT-X-STREAM-INF")) {
        const line = playlist.split("\n").map((s) => s.trim()).find((s) => s && !s.startsWith("#")); if (!line) return false;
        base = new URL(line, base).href; const headers = new URL(base).host === new URL(v.url).host ? v.httpHeaders : { "User-Agent": AGENT };
        playlist = (await bounded(base, { headers }, 2 * 1024 * 1024)).body;
      }
      const segment = playlist.split("\n").map((s) => s.trim()).find((s) => s && !s.startsWith("#")); if (!segment) return false;
      const media = new URL(segment, base).href, headers = new Headers(new URL(media).host === new URL(v.url).host ? v.httpHeaders : { "User-Agent": AGENT }); headers.set("range", "bytes=0-1023");
      const r = await fetch(media, { headers, signal: AbortSignal.timeout(12000) }); const reader = r.body?.getReader(); if (!reader) return false; const part = await reader.read(); await reader.cancel();
      const data = part.value || new Uint8Array(); return r.ok && (data[0] === 0x47 || new TextDecoder().decode(data.slice(4, 8)) === "ftyp" || new TextDecoder().decode(data.slice(4, 8)) === "styp");
    }
    return t.trimStart().startsWith("#EXTM3U") || new TextDecoder().decode(bytes.slice(4, 8)) === "ftyp" || bytes[0] === 0x1a && bytes[1] === 0x45;
  } catch { return false; }
}
async function moviePage(provider: string, p: any, q: Query): Promise<Source | null> {
  const id = postId(p); if (!id || !imdbMatches(p, q)) return null;
  if (provider === "moviz-time") {
    const fields = new Map<string, string>(arr(p.doc, "p.movie_details_section").map((row) => [arabic(text(row, ".ttl")).replace(/:$/, "").trim(), text(row, ".cntt")]));
    const name = (fields.get("اسم المسلسل") || fields.get("اسم الفيلم") || "").replace(/^(فيلم|مسلسل)\s+/, "").split(/مترجم|مدبلج/)[0].replace(new RegExp(`\\(?${q.year}\\)?$`), "").trim();
    if (![q.originalTitle, q.searchTitle].filter(Boolean).some((n) => key(n!) === key(name)) || Number(/\d{4}/.exec(fields.get("سنه الانتاج") || "")?.[0]) !== q.year || !!p.doc.querySelector(".type-series") !== (q.type === "TV")) return null;
    if (q.type === "TV") { const raw = arabic(fields.get("الموسم") || ""); const season = Number(raw) || ORDINALS.indexOf(raw) + 1; if (season !== q.season) return null; }
    const links = q.type === "TV" ? arr(p.doc, ".servers-block .ep-item").filter((el) => Number(/الحلقه\s*(\d+)/.exec(arabic(el.textContent))?.[1]) === q.episode).map((el) => /iframe_area\.location\.href\s*=\s*['"]([^'"]+)['"]/.exec(el.getAttribute("onclick") || "")?.[1]) : arr(p.doc, "#servers_tabs iframe").map((el) => el.getAttribute("data-src") || el.getAttribute("src"));
    const variants: Variant[] = [];
    for (const link of [...new Set(links)].filter(Boolean).slice(0, 4)) {
      try {
        const embed = new URL(link!, p.url); if (embed.hostname !== "vidhls.com" || embed.pathname !== "/player/index.php") continue;
        const ep = await page(embed.href, p.url);
        const config = /FirePlayer\(vhash,\s*(\{.*?\}),\s*(?:false|true)\)/s.exec(ep.body)?.[1]; if (!config) continue;
        const data = JSON.parse(config); if (!/^\/cdn\/hls\/[a-zA-Z0-9_-]+\/master\.txt$/.test(data.videoUrl) || !/^\d{1,3}$/.test(String(data.videoServer))) continue;
        const url = new URL(data.videoUrl, "https://vidhls.com"); url.searchParams.set("s", String(data.videoServer));
        const cookie = (ep.response.headers.getSetCookie()).map((x) => x.split(";")[0]).filter((x) => x.startsWith("PHPSESSID=")).join("; "); if (!cookie) continue;
        const v = { id: `server-${variants.length + 1}`, label: `السيرفر ${variants.length + 1} · HLS`, url: url.href, transport: "HLS", httpHeaders: { "User-Agent": AGENT, Referer: embed.href, Cookie: cookie, Accept: "*/*" } };
        if (await compatible(v)) variants.push(v);
      } catch { /* inspect another server */ }
    }
    return variants.length ? source(provider, id, q, p.url, variants) : null;
  }
  const title = text(p.doc, provider === "egybest" ? "h1.main-title" : "h1.post-title");
  if (!matches(title, q)) return null;
  const years: string[] = arabic(title).match(/\b(?:18|19|20|21)\d{2}\b/g) || [];
  if (provider === "egybest" && !years.includes(String(q.year)) && !years.includes(String(q.seasonYear)) && !(q.imdbId && p.body.includes(q.imdbId))) return null;
  if (provider === "topcinema") {
    const release = arr(p.doc, "li").find((el) => arabic(el.textContent).includes("موعد الصدور"));
    if (!release || Number(/\d{4}/.exec(arabic(release.textContent))?.[0]) !== q.year) return null;
  }
  const webLinks: NonNullable<Source["webLinks"]> = [], variants: Variant[] = [];
  if (provider === "egybest") {
    for (const el of arr(p.doc, "#WatchServers [data-embed-url]")) { const url = absolute(el.getAttribute("data-embed-url"), p.url); if (url.startsWith("https://")) webLinks.push({ label: el.textContent.trim() || new URL(url).hostname, url }); }
    for (const row of arr(p.doc, ".download-section .tr,.downloads .tr")) { const url = absolute(row.querySelector("a[href]")?.getAttribute("href"), p.url); if (url.startsWith("https://")) webLinks.push({ label: row.textContent.trim(), url, download: true }); }
    for (let i = 0; i < webLinks.length; i++) if (!webLinks[i].download) { try { for (const v of await embeds(webLinks[i].url, p.url, String(i), webLinks[i].label)) if (await compatible(v, true)) { const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(webLinks[i].url))); const hash = [...digest.slice(0, 8)].map((x) => x.toString(16).padStart(2, "0")).join(""); const role = /\/(hls[234]?)(?:\/|_)/.exec(new URL(v.url).pathname)?.[1] || `${v.transport.toLowerCase()}-${v.id.split("-").at(-1)}`; variants.push({ ...v, legacyId: v.id, id: `egy-${hash}-${role}` }); } } catch { /* retain the original link */ } }
  } else {
    const canonical = absolute(p.doc.querySelector('link[rel="canonical"]')?.getAttribute("href"), p.url);
    const watchUrl = canonical.replace(/\/$/, "") + "/watch/";
    const watch = await page(watchUrl, p.url);
    const servers = arr(watch.doc, "li.server--item[data-id][data-server]").filter((el) => el.getAttribute("data-id") === id && /uqload|streamwish|videotube|updown/i.test(el.textContent));
    for (const el of servers.slice(0, 4)) {
      try {
        const index = el.getAttribute("data-server"); if (!/^\d{1,3}$/.test(index)) continue;
        const reply = await page(new URL("/wp-content/themes/movies2023/Ajaxat/Single/Server.php", p.url).href, watchUrl, new URLSearchParams({ id, i: index }));
        const embed = absolute(reply.doc.querySelector("iframe[src]")?.getAttribute("src"), reply.url);
        for (const v of await embeds(embed, watchUrl, index, el.textContent.trim())) { if (await compatible(v)) { variants.push(v); break; } }
        if (variants.length >= 2) break;
      } catch { /* inspect another server */ }
    }
  }
  return variants.length || webLinks.length ? source(provider, id, q, p.url, variants, webLinks) : null;
}
async function website(provider: string, q: Query, id?: string): Promise<Source[]> {
  let base = starts[provider]; if (!base) throw new Error("المصدر غير معروف");
  if (provider === "topcinema") { const p = await page(base); base = new URL("/", p.url).href; }
  if (id) { const match = new RegExp(`^${provider}-(\\d{1,10})$`).exec(id); if (!match) return []; const item = await moviePage(provider, await page(`${base}?p=${match[1]}`), q); return item ? [item] : []; }
  for (const alias of [...new Set([q.originalTitle, q.searchTitle].filter(Boolean))]) {
    const precise = q.type === "TV" ? `${alias} الموسم ${ORDINALS[(q.season || 1) - 1] || q.season} الحلقة ${q.episode}` : alias!;
    let next = provider === "topcinema" ? `${base}search/?query=${encodeURIComponent(precise)}&type=${q.type === "TV" ? "series" : "movies"}` : `${base}?s=${encodeURIComponent(provider === "egybest" ? precise : alias!)}`;
    for (let n = 0; n < (provider === "egybest" ? 4 : 2); n++) {
      const p = await page(next);
      const selector = provider === "moviz-time" ? "h2.title-2 a[href]" : provider === "topcinema" ? ".Small--Box a[href][title]" : provider === "egybest" ? "a.block[href]" : "a[href]";
      const candidates = arr(p.doc, selector).filter((el) => {
        const href = absolute(el.getAttribute("href"), p.url);
        if (new URL(href || base).hostname !== new URL(p.url).hostname) return false;
        const raw = provider === "topcinema" ? el.getAttribute("title") : text(el, "h3.title") || el.textContent;
        return provider === "moviz-time" ? key(raw).includes(key(alias!)) : provider === "tuktuk" ? key(raw).includes(key(alias!)) && !new URL(href).search : matches(raw, q, false);
      }).slice(0, provider === "egybest" ? 15 : 5);
      for (const el of candidates) {
        try {
          const url = absolute(el.getAttribute("href"), p.url);
          if (provider === "tuktuk") return [source(provider, "website", q, url, [], [{ label: "فتح صفحة العمل في TukTuk", url }])];
          const details = await page(url, p.url);
          const result = await moviePage(provider, details, q); if (result) return [result];
          if (provider === "egybest" && q.type === "TV") for (const ep of arr(details.doc, "a.block[href]").filter((el) => matches(text(el, "h3.title"), q))) {
            const epUrl = absolute(ep.getAttribute("href"), details.url); if (new URL(epUrl).hostname !== new URL(base).hostname) continue;
            const result = await moviePage(provider, await page(epUrl), q); if (result) return [result];
          }
        } catch { /* candidate errors do not hide other matches */ }
      }
      const link = p.doc.querySelector('a.next,a.next-page,a[rel="next"]')?.getAttribute("href");
      if (!link) break;
      next = absolute(link, p.url); if (new URL(next).hostname !== new URL(base).hostname) break;
    }
  }
  return [];
}
async function catalogue(q: Query, endpoint: string): Promise<Source[]> {
  const url = new URL(endpoint); if (!["https:", "http:"].includes(url.protocol)) throw new Error("رابط الكتالوج غير صالح");
  for (const [k, v] of Object.entries({ tmdb_id: q.tmdbId, type: q.type === "TV" ? "tv" : "movie", original_title: q.originalTitle, year: q.year, season: q.season, episode: q.episode, imdb_id: q.imdbId })) if (v != null) url.searchParams.set(k, String(v));
  const data = JSON.parse((await bounded(url.href)).body);
  if (!Array.isArray(data)) throw new Error("صيغة كتالوج المصادر غير صالحة");
  return data.filter((s) => s.query?.type === q.type && s.query.tmdbId === q.tmdbId && s.query.year === q.year && (s.query.season ?? null) === (q.season ?? null) && (s.query.episode ?? null) === (q.episode ?? null) && key(s.query.originalTitle || "") === key(q.originalTitle) && (!q.imdbId || !s.query.imdbId || s.query.imdbId === q.imdbId) && Array.isArray(s.variants));
}
function openFilms(q: Query): Source[] {
  const films = [{ id: 45745, year: 2010, name: "Sintel", slug: "sintel", url: "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4", licence: "https://durian.blender.org/sharing/", extra: { id: "mkv", label: "MKV · الملف الأصلي", transport: "MKV", url: "https://download.blender.org/durian/movies/Sintel.2010.1080p.mkv" } },
    { id: 10378, year: 2008, name: "Big Buck Bunny", slug: "big-buck-bunny", url: "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4", licence: "https://peach.blender.org/about/", extra: { id: "hls", label: "HLS · جودة متكيّفة", transport: "HLS", url: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8" } }];
  return films.filter((f) => q.type === "MOVIE" && f.id === q.tmdbId && f.year === q.year && key(f.name) === key(q.originalTitle)).map((f) => ({ id: f.slug, providerId: "blender", providerName: "Blender Open Movies", query: q, variants: [{ id: "mp4", label: "MP4", transport: "MP4", url: f.url }, f.extra], attribution: `${f.name} © Blender Foundation · CC BY 3.0`, licenceUrl: f.licence }));
}
// Preserve the Android archive identities so synced history can resolve fresh files.
const values = (v: unknown): string[] => (Array.isArray(v) ? v : v == null ? [] : [v]).filter((x) => typeof x === "string" || typeof x === "number").map(String);
function archiveMatches(meta: any, q: Query) {
  return values(meta.year || meta.date).some((v) => Number(v.slice(0, 4)) === q.year) && values(meta.title).some((v) => key(v.trim().replace(new RegExp(`(?: \\(${q.year}\\)| \\[${q.year}\\]| ${q.year})$`), "")) === key(q.originalTitle));
}
function archiveLicence(meta: any) {
  return values(meta.licenseurl).find((v) => { try { const u = new URL(v); return /^https?:$/.test(u.protocol) && ["creativecommons.org", "www.creativecommons.org"].includes(u.hostname) && /^\/(licenses\/(by(?:-nc)?(?:-sa|-nd)?|publicdomain)|publicdomain\/(zero|mark))\//.test(u.pathname); } catch { return false; } });
}
async function archive(q: Query, sourceId?: string): Promise<Source[]> {
  if (q.type !== "MOVIE") return [];
  let ids: string[];
  if (sourceId) ids = sourceId.startsWith("ia:") ? [sourceId.slice(3)] : [];
  else {
    const u = new URL("https://archive.org/advancedsearch.php");
    u.searchParams.set("q", `title:("${q.originalTitle.replaceAll("\\", "\\\\").replaceAll('"', '\\"')}") AND year:${q.year} AND mediatype:movies AND licenseurl:(*creativecommons.org*)`);
    u.searchParams.set("output", "json"); u.searchParams.set("rows", "12");
    for (const f of ["identifier", "title", "year", "licenseurl"]) u.searchParams.append("fl[]", f);
    u.searchParams.set("sort[]", "downloads desc");
    const data = JSON.parse((await bounded(u.href)).body);
    ids = (data.response?.docs || []).filter((r: any) => archiveMatches(r, q) && archiveLicence(r)).map((r: any) => r.identifier).slice(0, 4);
  }
  const rows = await Promise.all(ids.filter((id) => /^[a-zA-Z0-9_.-]{1,150}$/.test(id)).map(async (id): Promise<Source | null> => {
    try {
      const data = JSON.parse((await bounded(`https://archive.org/metadata/${id}`)).body), meta = data.metadata;
      if (data.is_dark || !meta || meta.mediatype !== "movies" || !archiveMatches(meta, q)) return null;
      const licence = archiveLicence(meta); if (!licence) return null;
      const imdb = [...values(meta["external-identifier"]), ...values(meta.imdb), ...values(meta.imdb_id)].flatMap((v) => v.match(/tt\d{7,10}/g) || []);
      if (q.imdbId && imdb.length && !imdb.includes(q.imdbId)) return null;
      const fileUrl = (name: string) => `https://archive.org/download/${encodeURIComponent(id)}/${encodeURIComponent(name)}`;
      const files = (data.files || []).filter((f: any) => ![true, "true", 1, "1"].includes(f.private));
      const variants = files.filter((f: any) => /\.mp4$/i.test(f.name) && Number.isFinite(Number(f.length)) && Number(f.length) >= 300 && !/(?:^|[_. -])(trailer|sample|preview)(?:[_. -]|$)/i.test(f.name)).slice(0, 3).map((f: any) => ({ id: f.name, label: Number(f.height) >= 144 && Number(f.height) <= 4320 ? `${f.height}p · MP4` : `MP4 · ${f.name}`, transport: "MP4", url: fileUrl(f.name) }));
      if (!variants.length) return null;
      const webLinks = files.filter((f: any) => /\.(srt|vtt)$/i.test(f.name)).slice(0, 4).map((f: any) => ({ label: `ترجمة · ${f.name}`, url: fileUrl(f.name), download: true }));
      return { id: `ia:${id}`, providerId: "internet-archive", providerName: "Internet Archive · بث مباشر", query: q, variants, webLinks, attribution: `${q.originalTitle} (${q.year}) · archive.org/details/${id}`, licenceUrl: licence };
    } catch { return null; }
  }));
  return rows.filter((r): r is Source => r !== null);
}
async function torrent(q: Query): Promise<Source[]> {
  const term = `${q.originalTitle} ${q.type === "MOVIE" ? q.year : `S${String(q.season).padStart(2, "0")}E${String(q.episode).padStart(2, "0")}`}`;
  const rows = JSON.parse((await bounded(`https://apibay.org/q.php?q=${encodeURIComponent(term)}&cat=`)).body);
  const compact = (v: string) => key(v).replaceAll(" ", "");
  const name = compact(q.originalTitle), coordinates = q.type === "TV" ? new RegExp(`s0*${q.season}e0*${q.episode}(?![0-9])`, "i") : null;
  return rows.filter((r: any) => /^[0-9a-f]{40}$/i.test(r.info_hash) && Number(r.seeders) > 0 && compact(r.name).includes(name) && (coordinates ? coordinates.test(r.name) : new RegExp(`\\b${q.year}\\b`).test(r.name)) && (!q.imdbId || !r.imdb || r.imdb === q.imdbId)).sort((a: any, b: any) => Number(b.seeders) - Number(a.seeders)).slice(0, 20).map((r: any) => ({ id: `tpb-${r.id}`, providerId: "tpb", providerName: "تورنت", query: q, variants: [], attribution: r.name, licenceUrl: `https://thepiratebay.org/description.php?id=${r.id}`, webLinks: [{ label: `${r.name} · ${r.seeders} أقران · فتح في مشغّل تورنت`, url: `magnet:?xt=urn:btih:${r.info_hash}&dn=${encodeURIComponent(r.name)}` }] }));
}
export async function handleSources(req: Request, url: URL, proxy: Proxy): Promise<Response> {
  const proxyKey = Deno.env.get("PROXY_KEY");
  if (proxyKey && url.searchParams.get("key") !== proxyKey) return json({ error: "مفتاح البروكسي غير صحيح" }, 401);
  try {
    const path = url.pathname.slice("/sources/".length);
    if (path === "config") return json({ tmdbConfigured: !!(Deno.env.get("WATCHHUB_TMDB_KEY") || Deno.env.get("TMDB_API_KEY")), animeifyConfigured: !!(Deno.env.get("WATCHHUB_ANIMEIFY_TOKEN") || Deno.env.get("ANIMEIFY_TOKEN")) });
    if (path === "tmdb") {
      const key = Deno.env.get("WATCHHUB_TMDB_KEY") || Deno.env.get("TMDB_API_KEY");
      if (!key) return json({ error: "خدمة بيانات المحتوى غير مهيأة على الخادم" }, 503);
      const path = url.searchParams.get("path") || "";
      if (!/^(tv|movie|person|search|discover|trending|configuration|watch)(?:\/[a-zA-Z0-9_-]+)*$/.test(path)) return json({ error: "الرابط غير صالح" }, 400);
      const target = new URL("https://api.themoviedb.org/3/" + path);
      for (const [name, value] of url.searchParams) if (!["path", "key", "api_key"].includes(name)) target.searchParams.set(name, value);
      const headers = new Headers();
      if (key.startsWith("eyJ")) headers.set("Authorization", "Bearer " + key);
      if (!key.startsWith("eyJ")) target.searchParams.set("api_key", key);
      const response = await fetch(target, { headers, signal: AbortSignal.timeout(30000) });
      return new Response(response.body, { status: response.status, headers: { ...CORS, "content-type": "application/json" } });
    }
    if (path.startsWith("media/")) {
      const token = path.split("/")[1], session = sessions.get(token);
      if (!session || session.expires < Date.now()) { sessions.delete(token); return json({ error: "انتهت جلسة التشغيل؛ افتح المصدر مرة ثانية" }, 410); }
      const target = url.searchParams.get("url"); if (!target) return json({ error: "الرابط غير مكتمل" }, 400);
      const forward = new URL("/proxy", url); forward.searchParams.set("url", target); if (proxyKey) forward.searchParams.set("key", proxyKey);
      return proxy(req, forward, session);
    }
    if (path.startsWith("anime/")) {
      const op = path.slice(6), p = url.searchParams, from = String(Math.max(0, Number(p.get("from")) || 0));
      const common = { UserId: "0", Language: "ar" };
      if (op === "search") return json(animeTitles(await anime("anime/load_anime_list_v2.php", { ...common, FilterType: "SEARCH", FilterData: (p.get("title") || "").slice(0, 300), Type: p.get("movie") === "true" ? "MOVIE" : "SERIES", From: "0" })));
      if (op === "related") return json(animeTitles(await anime("anime/load_related_anime.php", { ...common, RelationId: p.get("relation") || "", RelatedId: p.get("id") || "" })));
      if (op === "episodes") return json((await anime("episodes/load_episodes.php", { AnimeID: p.get("id") || "" })).filter((r: any) => Number.isFinite(Number(r.Episode)) && Number(r.Episode) >= 0).map((r: any) => ({ number: String(r.Episode), extra: r.ExtraEpisodes || "" })).sort((a: any, b: any) => Number(a.number) - Number(b.number)));
      if (op === "servers") return json((await animeServers(Object.fromEntries(p))).map(({ key, label }) => ({ key, label })));
      if (op === "latest") {
        const rows = await anime("episodes/load_latest_episodes.php", { ...common, From: from });
        return json({ items: rows.flatMap((r: any) => { const title = animeTitles([r.Anime || {}])[0]; return title && r.Episode && (!r.Episode.AnimeID || String(r.Episode.AnimeID) === title.id) ? [{ title, episode: { number: String(r.Episode.Episode), extra: r.Episode.ExtraEpisodes || "" } }] : []; }), nextFrom: Number(from) + rows.length, hasMore: rows.length > 0 });
      }
      if (op === "current") {
        const now = new Date(), season = ["Winter", "Spring", "Summer", "Fall"][Math.floor(now.getUTCMonth() / 3)];
        const rows = await anime("anime/load_anime_list_v2.php", { ...common, FilterType: "PREMIERED", FilterData: `${season} ${now.getUTCFullYear()}`, Type: "SERIES", From: from });
        return json({ items: animeTitles(rows).filter((r) => r.status === "AIRING"), nextFrom: Number(from) + rows.length, hasMore: rows.length > 0 });
      }
    }
    if (path === "search") {
      const q = JSON.parse(url.searchParams.get("query") || "null") as Query, provider = url.searchParams.get("provider") || "";
      if (!validQuery(q)) return json({ error: "بيانات مطابقة العمل غير مكتملة" }, 400);
      let rows: Source[];
      if (provider === "catalogue") rows = await catalogue(q, url.searchParams.get("catalogue") || "");
      else if (provider === "torrent") {
        const free = openFilms(q), found = await Promise.allSettled([archive(q), torrent(q)]);
        rows = [...free, ...found.flatMap((r) => r.status === "fulfilled" ? r.value : [])];
        if (!rows.length && found.every((r) => r.status === "rejected")) throw (found[0] as PromiseRejectedResult).reason;
      }
      else rows = await website(provider, q);
      return json(rows.map((s) => ({ ...s, variants: s.variants.map(({ id, label, transport }) => ({ id, label, transport })) })));
    }
    if (path === "resolve") {
      const ref = JSON.parse(url.searchParams.get("ref") || "null");
      let variant: Variant;
      if (ref?.animeId) variant = await animeUrl(ref);
      else {
        const q = ref?.query as Query; if (!validQuery(q)) throw new Error("بيانات المصدر غير مكتملة");
        const rows = ["open-films", "blender"].includes(ref.providerId) ? openFilms(q) : ref.providerId === "internet-archive" ? await archive(q, ref.sourceId) : ref.providerId === "catalogue" || url.searchParams.get("catalogue") ? await catalogue(q, url.searchParams.get("catalogue") || "") : await website(ref.providerId, q, ref.sourceId);
        const found = rows.find((s) => s.id === ref.sourceId && s.providerId === ref.providerId);
        const selected = found?.variants.find((v) => v.id === ref.variantId || v.legacyId === ref.variantId); if (!selected) throw new Error("الجودة المطلوبة لم تعد متاحة؛ اختر مصدراً آخر");
        variant = selected;
      }
      if (!/^https?:\/\//.test(variant.url)) throw new Error("صيغة المصدر غير متوافقة مع المتصفح");
      for (const [token, value] of sessions) if (value.expires < Date.now()) sessions.delete(token);
      if (sessions.size > 500) sessions.delete(sessions.keys().next().value!);
      const token = crypto.randomUUID(), session = { token, host: new URL(variant.url).host, headers: variant.httpHeaders || {}, expires: Date.now() + 12 * 60 * 60 * 1000 };
      sessions.set(token, session);
      const ext = variant.transport === "HLS" ? "m3u8" : variant.transport === "MKV" ? "mkv" : "mp4";
      const target = new URL(`/sources/media/${token}/video.${ext}`, url); target.searchParams.set("url", variant.url); if (proxyKey) target.searchParams.set("key", proxyKey);
      return json({ urls: [target.href] });
    }
    return json({ error: "الخيار غير متاح" }, 404);
  } catch (e) { return json({ error: e instanceof Error ? e.message : "تعذّر الاتصال بالمصدر" }, 502); }
}
