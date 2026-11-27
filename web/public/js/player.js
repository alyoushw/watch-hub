// Watch Hub's own player for computers (and Android browsers): custom controls, gestures,
// subtitle and audio-track menus, picture size, speed, resume. On iPhone and iPad everything
// tries supported media here first; VLC remains an explicit option for unsupported files.
//
// Safari plays HLS itself; other browsers use hls.js, and MPEG-TS live streams use mpegts.js.
import { via, resolvePlayback, secureDirectUrl } from "./xtream.js?v=20261118d";
import { offlineUrl, addDownload } from "./downloads.js?v=20261118d";
import { resolveEntry } from "./sources.js?v=20261118d";
import { settings, history, isFinished, historySaved } from "./store.js?v=20261118d";
import * as cloud from "./cloud.js?v=20261118d";

const HLS_JS = "https://cdn.jsdelivr.net/npm/hls.js@1.5.17/dist/hls.min.js";
const MPEGTS_JS = "https://cdn.jsdelivr.net/npm/mpegts.js@1.7.3/dist/mpegts.js";
const VLC_STORE = "https://apps.apple.com/app/vlc-media-player/id650377962";

const $ = (id) => document.getElementById(id);
const box = $("player");
const video = $("video");
const errorEl = $("player-error");
const ui = {
  title: $("player-title"), sub: $("player-sub"), play: $("pl-play"), cur: $("pl-cur"), dur: $("pl-dur"),
  bar: $("pl-bar"), fill: $("pl-fill"), buf: $("pl-buf"), thumb: $("pl-thumb"), tip: $("pl-tip"),
  live: $("pl-live"), speed: $("pl-speed"), aspect: $("pl-aspect"), menu: $("pl-menu"), spin: $("pl-spin"),
  ind: $("pl-ind"), indText: $("pl-ind-text"), indLevel: $("pl-ind-level"), vol: $("pl-vol"), mute: $("pl-mute"),
  gest: $("pl-gest"), back10: $("pl-back10"), fwd10: $("pl-fwd10"),
  prev: $("pl-prev"), next: $("pl-next"), quality: $("pl-quality"), skip: $("pl-skip"), banner: $("pl-banner"),
  upnext: $("pl-upnext"), upnextName: $("pl-upnext-name"), upnextBar: $("pl-upnext-bar"),
};

// What the player goes through: the channels of the list it was opened from (←/→ switch
// channel) or a series' episodes ("الحلقة الجاية").
let queue = [];
let qIndex = 0;

let engine = null; // { destroy, onFatal, hls? }
let session = 0;
let opening = 0;
let cancelStart = null;
let current = null; // { urls, entry, live }
let saveTimer = null;

const ASPECTS = [
  ["contain", "ملاءمة الشاشة (بدون قص)"], ["cover", "ملء الشاشة مع قص الأطراف"], ["fill", "تمديد على كامل الشاشة"],
  ["fill", "16:9", 16 / 9], ["fill", "18:9", 2], ["fill", "21:9", 21 / 9], ["fill", "2.35:1 (سينما)", 2.35], ["fill", "4:3", 4 / 3],
];
// Kept on this device (a car screen keeps its own).
const savedPic = (() => {
  try {
    return JSON.parse(localStorage.getItem("pl.picture") || "{}");
  } catch {
    return {};
  }
})();
let stretch = Number(savedPic.stretch) || 1;
function savePicture() {
  try {
    localStorage.setItem("pl.picture", JSON.stringify({ aspect, zoom, stretch }));
  } catch { /* private mode */ }
}
const SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 2];
let aspect = Math.min(ASPECTS.length - 1, Number(savedPic.aspect) || 0);
let zoom = Number(savedPic.zoom) || 1;
let brightness = 1;

// ---------- helpers ----------

const scripts = new Map();
function loadScript(src) {
  if (!scripts.has(src)) {
    scripts.set(src, new Promise((resolve, reject) => {
      const s = document.createElement("script");
      s.src = src;
      const timeout = setTimeout(() => { s.remove(); scripts.delete(src); reject(new Error("script timeout")); }, 12000);
      s.onload = () => { clearTimeout(timeout); resolve(); };
      s.onerror = () => {
        clearTimeout(timeout);
        scripts.delete(src);
        reject(new Error("script"));
      };
      document.head.appendChild(s);
    }));
  }
  return scripts.get(src);
}

export const isIOS = () =>
  /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);

export function fmt(sec) {
  if (!isFinite(sec) || sec < 0) return "--:--";
  sec = Math.floor(sec);
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  const pad = (n) => String(n).padStart(2, "0");
  return h ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`;
}

const fileUrl = (urls) => urls.find((u) => !/\.m3u8$/i.test(new URL(u, location.href).pathname)) || urls[0];
const vlcLink = (url) => /Android/i.test(navigator.userAgent)
  ? `intent://${url.replace(/^https?:\/\//i, "")}#Intent;scheme=${new URL(url).protocol.slice(0, -1)};package=org.videolan.vlc;type=video/*;end`
  : `vlc-x-callback://x-callback-url/stream?url=${encodeURIComponent(url)}`;

let indTimer;
function flash(text, level) {
  ui.indText.textContent = text;
  ui.indLevel.hidden = level === undefined;
  if (level !== undefined) ui.indLevel.firstElementChild.style.width = `${Math.round(level * 100)}%`;
  ui.ind.hidden = false;
  clearTimeout(indTimer);
  indTimer = setTimeout(() => (ui.ind.hidden = true), 900);
}

let idleTimer;
function wake() {
  box.classList.remove("idle");
  clearTimeout(idleTimer);
  idleTimer = setTimeout(() => {
    if (!video.paused && ui.menu.hidden) box.classList.add("idle");
  }, 3500);
}

function panel(text, buttons) {
  errorEl.innerHTML = "";
  const msg = document.createElement("p");
  msg.style.margin = "0 0 12px";
  msg.textContent = text;
  const row = document.createElement("div");
  row.style.cssText = "display:flex;gap:8px;flex-wrap:wrap;justify-content:center";
  for (const [label, onClick, primary] of buttons) {
    const b = document.createElement("button");
    b.type = "button";
    b.className = primary ? "btn small" : "btn ghost small";
    b.textContent = label;
    b.addEventListener("click", onClick);
    row.appendChild(b);
  }
  errorEl.append(msg, row);
  errorEl.hidden = false;
  return msg;
}

// ---------- engine ----------

function reset() {
  cancelStart?.();
  cancelStart = null;
  if (engine) {
    try {
      engine.destroy();
    } catch { /* already gone */ }
    engine = null;
  }
  video.pause();
  video.removeAttribute("src");
  video.load();
  [...video.textTracks].forEach((t) => (t.mode = "disabled"));
}

function waitForStart(timeoutMs) {
  return new Promise((resolve, reject) => {
    const done = (ok) => {
      clearTimeout(timer);
      if (cancelStart === onErr) cancelStart = null;
      video.removeEventListener("loadeddata", onOk);
      video.removeEventListener("error", onErr);
      ok ? resolve() : reject(new Error("failed"));
    };
    const onOk = () => done(true);
    const onErr = () => done(false);
    const timer = setTimeout(() => done(false), timeoutMs);
    cancelStart = onErr;
    video.addEventListener("loadeddata", onOk);
    video.addEventListener("error", onErr);
    engine?.onFatal?.(() => done(false));
  });
}

// ---------- MKV/AVI on Apple: the server rewraps the film as HLS (see web/remux.ts) ----------
// The HLS stream starts where it was opened ([offset]); the timeline still shows the whole film.
let remux = null; // { src, duration, offset, retries }
let remuxProblem = ""; // why the server could not rewrap the last film
const total = () => (remux && remux.duration > 0 ? remux.duration : video.duration);
const posNow = () => (remux ? remux.offset : 0) + video.currentTime;
const appleSafari = () => /iPhone|iPad|iPod/.test(navigator.userAgent) || navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1
  || /Version\/[\d.]+.*Safari/.test(navigator.userAgent) && !/Chrome|Chromium|Edg\//.test(navigator.userAgent);
const needsRemux = (url) => appleSafari() && /\.(mkv|avi|wmv|flv)$/i.test(new URL(url, location.href).pathname);

async function openRemux(src, t) {
  const key = settings.proxyKey ? `&key=${encodeURIComponent(settings.proxyKey)}` : "";
  const r = await fetch(`/remux/open?url=${encodeURIComponent(src)}&t=${Math.floor(t)}${key}`);
  const j = await r.json().catch(() => ({}));
  if (!r.ok) { remuxProblem = j.error || `HTTP ${r.status}`; throw new Error("remux"); }
  return { playlist: new URL(j.playlist, location.href).href, duration: j.duration || 0 };
}

/** A jump past what the server has prepared (or before where it started) reopens it there. */
async function restartRemux(t) {
  const r0 = remux;
  if (!r0) return;
  const mine = session;
  ui.spin.hidden = false;
  try {
    const r = await openRemux(r0.src, t);
    if (mine !== session || remux !== r0) return;
    r0.offset = t;
    if (r.duration) r0.duration = r.duration;
    video.src = r.playlist;
    video.play().catch(() => {});
  } catch {
    if (mine === session) { ui.spin.hidden = true; showFailure(r0.src, false); }
  }
}

function seekTo(t) {
  if (!remux) { video.currentTime = t; return; }
  const local = t - remux.offset;
  const end = video.seekable.length ? video.seekable.end(video.seekable.length - 1) : 0;
  if (local >= 0 && local < end - 3) video.currentTime = local;
  else restartRemux(Math.max(0, Math.floor(t)));
}

const nativeHls = () => video.canPlayType("application/vnd.apple.mpegurl") !== "";

async function tryOne(url, startAt, mine, direct = false) {
  if (mine !== session) throw new Error("cancelled");
  reset();
  const src = direct ? url : via(url);
  const path = new URL(url).pathname.toLowerCase();
  if (path.endsWith(".m3u8") && !nativeHls()) {
    await loadScript(HLS_JS);
    if (mine !== session) throw new Error("cancelled");
    const Hls = window.Hls;
    if (!Hls || !Hls.isSupported()) throw new Error("hls");
    const hls = new Hls({ enableWorker: true, startPosition: startAt || -1 });
    let fatal = null;
    hls.on(Hls.Events.ERROR, (_e, d) => d.fatal && fatal?.());
    engine = { hls, destroy: () => hls.destroy(), onFatal: (fn) => (fatal = fn) };
    hls.loadSource(src);
    hls.attachMedia(video);
  } else if (path.endsWith(".ts")) {
    await loadScript(MPEGTS_JS);
    if (mine !== session) throw new Error("cancelled");
    const mpegts = window.mpegts;
    if (!mpegts || !mpegts.getFeatureList().mseLivePlayback) throw new Error("ts");
    const p = mpegts.createPlayer({ type: "mpegts", isLive: true, url: src });
    let fatal = null;
    p.on(mpegts.Events.ERROR, () => fatal?.());
    engine = { destroy: () => p.destroy(), onFatal: (fn) => (fatal = fn) };
    p.attachMediaElement(video);
    p.load();
  } else {
    video.src = src;
  }
  const started = waitForStart(direct ? 12000 : 25000);
  video.play().catch(() => { /* needs a tap; the play button is there */ });
  await started;
  if (startAt > 0 && Math.abs(video.currentTime - startAt) > 5) video.currentTime = startAt;
}

// ---------- open / close ----------

function openOverlay(entry, live) {
  box.hidden = false;
  errorEl.hidden = true;
  ui.menu.hidden = true;
  ui.title.textContent = entry.title || "";
  ui.sub.textContent = entry.subtitle || "";
  ui.live.hidden = !live;
  [ui.cur, ui.dur, ui.bar, ui.speed, ui.back10, ui.fwd10].forEach((el) => (el.hidden = live));
  document.body.style.overflow = "hidden";
  brightness = 1;
  applyPicture();
  wake();
}

function saveProgress(important = false) {
  if (!current || current.live || !current.started) return;
  const d = total();
  if (!isFinite(d) || d <= 0) return;
  history.save({ ...current.entry, positionMs: Math.round(posNow() * 1000), durationMs: Math.round(d * 1000) }, important);
}

/**
 * Plays [urls] (tried in order); [entry] names it and keeps its place in the watch history.
 * [list] (items of { urls, entry, number? }) and [index]: the channels or episodes around it.
 */
export async function play(urls, entry, list = null, index = 0) {
  const request = ++opening;
  saveProgress(true);
  // Invalidate the previous async start before any storage or source lookup.
  session++;
  reset();
  const downloaded = await offlineUrl(entry.key);
  if (request !== opening) return;
  if (downloaded) { urls = [downloaded]; list = null; }
  queue = list && list.length ? list : [{ urls, entry }];
  qIndex = list && list.length ? Math.max(0, Math.min(index, list.length - 1)) : 0;
  if (!downloaded && isIOS() && settings.vlcOnIos !== false) {
    try {
      if (entry.animeRef || entry.sourceRef || entry.fourURef) urls = await resolveEntry(entry);
      else if (entry.accountId) urls = await resolvePlayback(entry, urls);
      if (request !== opening) return;
    } catch (e) { if (request !== opening) return; openOverlay(entry, entry.kind === "live"); ui.spin.hidden = true; errorEl.hidden = false; errorEl.textContent = e.message; return; }
  }
  if (!downloaded && isIOS() && settings.vlcOnIos !== false) {
    history.save(entry, true);
    return openInVlc(urls, entry);
  }
  return playAt(qIndex);
}

function playAt(i) {
  if (i < 0 || i >= queue.length) return;
  opening++;
  if (current?.started) saveProgress(true);
  qIndex = i;
  upNextDismissed = null;
  upNextTicks = 0;
  frames = { n: -1, t: 0, fps: 0 };
  const item = queue[i];
  const live = item.entry.kind === "live";
  const many = queue.length > 1;
  ui.prev.hidden = !many || i === 0;
  ui.next.hidden = !many || i === queue.length - 1;
  ui.prev.title = live ? "القناة السابقة" : "الحلقة السابقة";
  ui.next.title = live ? "القناة الجاية" : "الحلقة الجاية";
  ui.quality.hidden = true;
  if (live && many) showBanner(item);
  return playHere(item.urls, item.entry);
}

let bannerTimer;
function showBanner(item) {
  const num = item.number ?? item.entry.number;
  ui.banner.innerHTML = `${num ? `<b class="num"></b>` : ""}<div><b class="name"></b><small>← → لتغيير القناة</small></div>`;
  if (num) ui.banner.querySelector(".num").textContent = num;
  ui.banner.querySelector(".name").textContent = item.entry.title || "";
  ui.banner.hidden = false;
  clearTimeout(bannerTimer);
  bannerTimer = setTimeout(() => (ui.banner.hidden = true), 3000);
}

function step(delta) {
  const i = qIndex + delta;
  if (i < 0 || i >= queue.length) return;
  playAt(i);
}

// ---------- "الحلقة الجاية" and "تخطي المقدمة" ----------

let upNextDismissed = null;
let sleep = null; // { at } or { atEnd: true }

function qualityText() {
  const h = video.videoHeight;
  if (!h) return "";
  const lines = Math.min(h, video.videoWidth || h);
  const name = video.videoWidth >= 3800 || lines >= 2100 ? "4K" : lines >= 1400 ? "1440p" : `${lines}p`;
  return frames.fps > 1 ? `${name} · ${Math.round(frames.fps)}fps` : name;
}

// Subtitle size and background, kept on this device.
const SUB_SIZES = [["sm", "صغير"], ["md", "متوسط"], ["lg", "كبير"], ["xl", "كبير جداً"]];
let subSize = localStorage.getItem("pl.subSize") || "md";
let subBg = localStorage.getItem("pl.subBg") === "1";
function applySubs() {
  box.classList.remove("pl-sub-sm", "pl-sub-lg", "pl-sub-xl", "pl-sub-bg", "pl-sub-nobg");
  if (subSize !== "md") box.classList.add(`pl-sub-${subSize}`);
  box.classList.add(subBg ? "pl-sub-bg" : "pl-sub-nobg");
}
applySubs();

$("pl-sleep").addEventListener("click", () => {
  const set = (v, text) => {
    sleep = v;
    $("pl-sleep").classList.toggle("on", !!v);
    flash(text);
  };
  const live = current?.live;
  openMenu("مؤقت النوم", [
    ["بدون", !sleep, () => set(null, "انلغى مؤقت النوم")],
    ...[15, 30, 45, 60, 90].map((m) => [`بعد ${m} دقيقة`, false, () => set({ at: Date.now() + m * 60000 }, `☾ يوقف بعد ${m} دقيقة`)]),
    live ? ["بعد ساعتين", false, () => set({ at: Date.now() + 120 * 60000 }, "☾ يوقف بعد ساعتين")]
      : ["نهاية الحلقة / الفيلم", !!sleep?.atEnd, () => set({ atEnd: true }, "☾ يوقف بنهاية الحلقة")],
  ]);
});

$("pl-info").addEventListener("click", () => {
  const rows = [];
  if (video.videoHeight) {
    rows.push(["الدقة", `${video.videoWidth}×${video.videoHeight}`]);
    if (frames.fps > 1) rows.push(["الفريمات", `${Math.round(frames.fps)} fps`]);
  }
  const lvl = engine?.hls?.levels?.[engine.hls.currentLevel];
  if (lvl) {
    if (lvl.bitrate) rows.push(["معدل البث", `${(lvl.bitrate / 1e6).toFixed(1)} Mbps`]);
    if (lvl.videoCodec) rows.push(["ترميز الصورة", /hvc|hev/.test(lvl.videoCodec) ? "HEVC" : /avc/.test(lvl.videoCodec) ? "H.264" : lvl.videoCodec]);
    if (lvl.audioCodec) rows.push(["ترميز الصوت", /mp4a/.test(lvl.audioCodec) ? "AAC" : /ec-3/.test(lvl.audioCodec) ? "Dolby Digital+" : /ac-3/.test(lvl.audioCodec) ? "Dolby Digital" : lvl.audioCodec]);
  }
  const audio = engine?.hls?.audioTracks?.length || video.audioTracks?.length || 1;
  rows.push(["أصوات معلّقة", String(audio)]);
  rows.push(["ترجمات", String([...video.textTracks].filter((t) => t.kind === "subtitles" || t.kind === "captions").length)]);
  if (current) {
    const ext = new URL(fileUrl(current.urls)).pathname.split(".").pop();
    if (ext && ext.length <= 5) rows.push(["صيغة الملف", ext.toUpperCase()]);
  }
  if (video.buffered.length) rows.push(["المحمّل مسبقاً", `${Math.max(0, Math.round(video.buffered.end(video.buffered.length - 1) - video.currentTime))} ث`]);
  const q = video.getVideoPlaybackQuality?.();
  if (q) rows.push(["فريمات ضايعة", String(q.droppedVideoFrames)]);
  ui.menu.innerHTML = "<h3>معلومات التشغيل</h3>";
  for (const [k, v] of rows) {
    const r = document.createElement("div");
    r.className = "pl-info-row";
    r.innerHTML = "<span></span><span></span>";
    r.children[0].textContent = k;
    r.children[1].textContent = v;
    ui.menu.appendChild(r);
  }
  if (!rows.length) ui.menu.insertAdjacentHTML("beforeend", "<p>ننتظر يبدأ التشغيل…</p>");
  ui.menu.hidden = false;
  box.classList.remove("idle");
});
let upNextTicks = 0;
let frames = { n: -1, t: 0, fps: 0 };

setInterval(() => {
  if (box.hidden || !current) return;
  // Quality: picture size, and frames shown per second.
  const h = video.videoHeight;
  if (h > 0) {
    const q = video.getVideoPlaybackQuality?.();
    const now = performance.now();
    if (q) {
      if (frames.n >= 0 && now - frames.t >= 1500 && !video.paused) {
        frames.fps = ((q.totalVideoFrames - frames.n) * 1000) / (now - frames.t);
        frames.n = q.totalVideoFrames;
        frames.t = now;
      } else if (frames.n < 0 || q.totalVideoFrames < frames.n) {
        frames = { n: q.totalVideoFrames, t: now, fps: frames.fps };
      }
    }
    ui.quality.textContent = qualityText();
    ui.quality.hidden = false;
  }
  // Sleep timer.
  if (sleep) {
    const d0 = total();
    const due = sleep.at ? Date.now() >= sleep.at : isFinite(d0) && d0 - posNow() < 1.5;
    if (sleep.atEnd && !ui.upnext.hidden) upNextDismissed = current.entry.key;
    if (due) {
      sleep = null;
      $("pl-sleep").classList.remove("on");
      video.pause();
      close();
      return;
    }
  }

  const e = current.entry;
  const d = total();
  const t = posNow();
  if (current.live || !["episode", "anime_episode"].includes(e.kind) || !current.started || !isFinite(d) || d <= 0) {
    ui.skip.hidden = true;
    ui.upnext.hidden = true;
    return;
  }
  // Intro: the first four minutes of an episode longer than ten.
  ui.skip.hidden = !(t >= 5 && t <= 240 && d > 600) || !ui.upnext.hidden;
  // End credits: the last ~3.5 % (between 30 s and 2 min), then a 10 s countdown.
  const credits = Math.min(120, Math.max(30, d * 0.035));
  const next = queue[qIndex + 1];
  if (next && d > 300 && d - t <= credits && upNextDismissed !== e.key) {
    if (ui.upnext.hidden) upNextTicks = 0;
    ui.upnextName.textContent = next.entry.subtitle || next.entry.title || "";
    ui.upnext.hidden = false;
    if (!video.paused) upNextTicks++;
    const left = Math.max(0, 10 - Math.floor(upNextTicks / 2));
    $("pl-upnext-go").textContent = `شغّلها الحين (${left})`;
    ui.upnextBar.style.width = `${((10 - left) / 10) * 100}%`;
    if (left <= 0) step(1);
  } else {
    ui.upnext.hidden = true;
  }
}, 500);

ui.skip.addEventListener("click", () => {
  if (!isFinite(total())) return;
  seekTo(Math.min(total() - 1, posNow() + 85));
  ui.skip.hidden = true;
});
$("pl-upnext-go").addEventListener("click", () => step(1));
$("pl-upnext-cancel").addEventListener("click", () => {
  upNextDismissed = current?.entry.key;
  ui.upnext.hidden = true;
});
ui.prev.addEventListener("click", () => step(-1));
ui.next.addEventListener("click", () => step(1));

async function playHere(urls, entry) {
  if (current?.entry.key !== entry.key) clearExternalCaptions();
  const live = entry.kind === "live";
  const mine = ++session;
  remux = null;
  remuxProblem = "";
  reset();
  current = { urls, entry, live, started: false };
  openOverlay(entry, live);
  ui.spin.hidden = false;
  // Another device may have moved on a moment ago: take the newest spot from the account first.
  await cloud.refreshEntry(entry.key);
  if (mine !== session) return;
  const saved = history.get(entry.key);
  history.save(entry, true);
  const startAt = !live && saved && !isFinished(saved) && saved.positionMs > 10000 ? saved.positionMs / 1000 : 0;
  ui.spin.hidden = false;
  const downloaded = await offlineUrl(entry.key);
  if (mine !== session) return;
  if (downloaded) urls = [downloaded];
  else if (entry.animeRef || entry.sourceRef || entry.fourURef) {
    try { urls = await resolveEntry(entry); if (mine !== session) return; current.urls = urls; }
    catch (e) { if (mine !== session) return; ui.spin.hidden = true; errorEl.hidden = false; errorEl.textContent = e.message; return; }
  } else if (entry.accountId) {
    try { urls = await resolvePlayback(entry, urls); if (mine !== session) return; current.urls = urls; }
    catch (error) { if (mine !== session) return; ui.spin.hidden = true; errorEl.hidden = false; errorEl.textContent = error.message; return; }
  }
  const candidates = urls.flatMap((url) => {
    const secure = !downloaded && secureDirectUrl(entry, url);
    return [...(secure ? [{ url: secure, direct: true }] : []), { url, direct: false }];
  });
  // Apple: IPTV panels refuse the hosted proxy (a data-centre address) but serve the viewer's own
  // connection, so files Safari can play are tried straight from the device first.
  if (appleSafari() && !downloaded) {
    const own = urls.filter((u) => /^http:/i.test(u) && !needsRemux(u)).reverse();
    for (const u of own) candidates.unshift({ url: u, direct: true });
  }
  // Apple cannot open MKV/AVI: first try the server's rewrapped stream of the same film.
  const film = !live && !downloaded && urls.find(needsRemux);
  if (film) candidates.unshift({ url: film, direct: false, remux: true });
  for (const { url, direct, remux: viaRemux } of candidates) {
    try {
      remux = null;
      if (viaRemux) {
        const r = await openRemux(url, startAt);
        if (mine !== session) return;
        remux = { src: url, duration: r.duration, offset: startAt, retries: 0 };
        await tryOne(r.playlist, 0, mine, true);
      } else {
        await tryOne(url, startAt, mine, direct);
      }
      if (mine !== session) return;
      current.started = true;
      current.playbackUrl = url; current.direct = direct;
      engine?.onFatal?.(() => { if (mine === session) { ui.spin.hidden = true; showFailure(current.playbackUrl, current.direct); } });
      if (startAt) flash(`نكمل من ${fmt(startAt)}`);
      clearInterval(saveTimer);
      saveTimer = setInterval(saveProgress, 5000);
      return;
    } catch {
      remux = null;
      if (mine !== session) return;
    }
  }
  if (mine !== session) return;
  ui.spin.hidden = true;
  const diagnostic = candidates.find((candidate) => candidate.direct && !/\.m3u8$/i.test(new URL(candidate.url).pathname));
  showFailure(diagnostic?.url || fileUrl(urls), diagnostic?.direct || false);
}

/** A small, bounded read checks the actual media response without exposing the URL/login. */
// IPTV panels often serve only the home IP: the hosted proxy (a data-centre IP) is refused even
// though the same link plays in VLC on the user's own network. The hint stays hedged.
function iptvHint(viaProxy) {
  return viaProxy ? "" : " إذا كان هذا اشتراك IPTV فقد يرفض سيرفره الوصول من خارج شبكتك المنزلية؛ جرّب نفس الرابط في VLC أو من تطبيق أندرويد.";
}

async function playbackDiagnosis(url, direct = false) {
  const controller = new AbortController(), timer = setTimeout(() => controller.abort(), 8000);
  let reader;
  try {
    let response = await fetch(direct ? url : via(url), { headers: { Range: "bytes=0-65535" }, signal: controller.signal });
    // Some panels reject a Range probe despite accepting an ordinary video GET.
    if ([404, 405, 416].includes(response.status)) {
      await response.body?.cancel();
      response = await fetch(direct ? url : via(url), { signal: controller.signal });
    }
    if (!response.ok) {
      if (response.status === 401) return "طلب الفيديو مرفوض (HTTP 401). قد يكون الرفض من مفتاح البروكسي أو بيانات الاشتراك؛ لا يثبت ذلك وجود مشكلة ترميز.";
      if (response.status === 403) return "طلب الفيديو مرفوض (HTTP 403). قد يكون الرفض من البروكسي أو سيرفر الاشتراك؛ لا يثبت ذلك وجود مشكلة ترميز." + iptvHint(!direct);
      if (response.status === 404) return "المصدر أعاد HTTP 404 عند طلب رابط الملف؛ لم يصل فيديو في هذا الطلب." + iptvHint(!direct);
      if (response.status === 456) return "سيرفر الاشتراك رفض الطلب (HTTP 456)." + iptvHint(!direct);
      return `تعذّر جلب ملف الفيديو (HTTP ${response.status}).` + iptvHint(!direct);
    }
    reader = response.body?.getReader();
    if (!reader) return "وصل رد من المصدر دون بيانات فيديو.";
    const bytes = new Uint8Array(65536); let size = 0;
    while (size < bytes.length) {
      const { value, done } = await reader.read(); if (done) break;
      const count = Math.min(value.length, bytes.length - size); bytes.set(value.subarray(0, count), size); size += count;
    }
    if (!size) return "وصل رد من المصدر لكن ملف الفيديو فارغ.";
    const type = response.headers.get("content-type") || "";
    if (/text\/html|application\/json/i.test(type)) return "المصدر أعاد صفحة أو رسالة بدل ملف فيديو.";
    if (bytes[0] === 0x1a && bytes[1] === 0x45 && bytes[2] === 0xdf && bytes[3] === 0xa3) {
      const codecs = [];
      const labels = { "V_MPEG4/ISO/AVC": "H.264", "V_MPEGH/ISO/HEVC": "HEVC / H.265", "V_VP9": "VP9", "V_AV1": "AV1", "A_AAC": "AAC", "A_AC3": "AC3", "A_EAC3": "E-AC3", "A_DTS": "DTS", "A_OPUS": "Opus" };
      // CodecID is an EBML string element (0x86), with a short string length.
      for (let i = 4; i + 2 < size; i++) if (bytes[i] === 0x86 && bytes[i + 1] >= 0x80) {
        const length = bytes[i + 1] & 0x7f;
        if (length && length <= 32 && i + 2 + length <= size) {
          const id = new TextDecoder().decode(bytes.subarray(i + 2, i + 2 + length));
          if (typeof labels[id] === "string" && !codecs.includes(labels[id])) codecs.push(labels[id]);
        }
      }
      return `وصل ملف Matroska/WebM من السيرفر (HTTP ${response.status})${codecs.length ? `؛ الترميزات: ${codecs.join("، ")}` : ""}. وصول الملف لا يضمن أن المتصفح يستطيع تشغيل ترميزاته.`;
    }
    return `وصلت بيانات من المصدر (HTTP ${response.status})، لكن المتصفح لم يبدأ تشغيلها. لا يمكن تحديد الترميز من هذه القراءة القصيرة.`;
  } catch {
    return "لم يكتمل فحص رابط الفيديو؛ قد يكون الاتصال بطيئًا أو متوقفًا. لم نتأكد من وجود مشكلة ترميز.";
  } finally { clearTimeout(timer); await reader?.cancel().catch(() => {}); controller.abort(); }
}

function showFailure(url, direct = false) {
  if (!url) return panel("لم يصل رابط تشغيل من المصدر. افتح صفحة العمل واختر السيرفر مرة ثانية.", [["إغلاق", close]]);
  const ext = (new URL(url, location.href).pathname.split(".").pop() || "").toUpperCase();
  const ios = isIOS(), mine = session;
  saveProgress(true);
  if (current) current.started = false;
  reset();
  const text = `تعذّر التشغيل${ext ? ` (${ext})` : ""}. جاري فحص استجابة المصدر…`;
  const buttons = [
    ["حاول مرة ثانية", () => playAt(qIndex)],
    ["افتح في VLC", () => (location.href = vlcLink(url)), true],
    ...(ios ? [["افتح في Infuse", () => (location.href = `infuse://x-callback-url/play?url=${encodeURIComponent(url)}`)]] : []),
    ["انسخ الرابط", async () => {
      try { await navigator.clipboard.writeText(url); msg.textContent = "✓ نُسخ الرابط؛ يمكنك فتحه في مشغّل فيديو."; }
      catch { prompt("انسخ الرابط:", url); }
    }],
  ];
  const msg = panel(text, buttons);
  playbackDiagnosis(url, direct).then((diagnosis) => {
    if (mine === session && msg.isConnected) msg.textContent = `تعذّر التشغيل${ext ? ` (${ext})` : ""}. ${diagnosis}${ios && ext === "MKV" ? " دعم MKV على الآيفون محدود؛ يمكنك تجربة مشغّل خارجي." : ""}${remuxProblem ? ` (تحويل السيرفر: ${remuxProblem})` : ""}`;
  });
}

/**
 * Hands the file to VLC. A page cannot ask whether an app is installed, so if Safari is
 * still showing this page shortly after, VLC is taken to be missing and the App Store is offered.
 */
function openInVlc(urls, entry) {
  const mine = ++session;
  reset();
  current = null;
  openOverlay(entry, entry.kind === "live");
  ui.spin.hidden = true;
  const link = fileUrl(urls);
  let left = false;
  const away = () => {
    if (document.visibilityState === "hidden") left = true;
  };
  document.addEventListener("visibilitychange", away);
  window.addEventListener("pagehide", away);
  panel("نفتحها في VLC…", [["شغّل هنا في المتصفح", () => playHere(urls, entry)]]);
  // Leave for VLC only once "تابع المشاهدة" is safely stored (here and in the account).
  Promise.all([historySaved(), Promise.race([cloud.flush(), new Promise((r) => setTimeout(r, 1500))])]).then(() => {
    if (mine === session) location.href = vlcLink(link);
  });
  setTimeout(() => {
    document.removeEventListener("visibilitychange", away);
    window.removeEventListener("pagehide", away);
    if (mine !== session) return;
    if (left) {
      panel("فتحناها في VLC ✓", [["افتح في VLC مرة ثانية", () => (location.href = vlcLink(link)), true], ["إغلاق", close]]);
      return;
    }
    panel("يبدو إن تطبيق VLC مو مثبت على جهازك. ثبّته (مجاني) وارجع اضغط «افتح في VLC».", [
      ["ثبّت VLC من App Store", () => (location.href = VLC_STORE), true],
      ["افتح في VLC", () => openInVlc(urls, entry)],
      ["شغّل هنا في المتصفح", () => playHere(urls, entry)],
    ]);
  }, 3500);
}

export function close() {
  saveProgress(true);
  sleep = null;
  $("pl-sleep").classList.remove("on");
  ui.skip.hidden = true;
  ui.upnext.hidden = true;
  ui.banner.hidden = true;
  clearInterval(saveTimer);
  opening++;
  session++;
  current = null;
  reset();
  box.hidden = true;
  document.body.style.overflow = "";
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
  window.dispatchEvent(new Event("watchhub:history"));
}

// ---------- controls ----------

function togglePlay() {
  if (video.paused) video.play().catch(() => {});
  else video.pause();
  wake();
}

function seekBy(sec) {
  if (!current || current.live || !isFinite(total())) return;
  seekTo(Math.max(0, Math.min(total() - 1, posNow() + sec)));
  flash(`${sec < 0 ? "↺ -" : "↻ +"}${Math.abs(sec)} ث`);
  wake();
}

function setVolume(v) {
  video.volume = Math.max(0, Math.min(1, v));
  video.muted = video.volume === 0;
  flash(`🔊 الصوت ${Math.round(video.volume * 100)}%`, video.volume);
}

function applyPicture() {
  const [fit, , ratio] = ASPECTS[aspect];
  video.style.objectFit = fit;
  if (ratio) {
    const bw = box.clientWidth || innerWidth;
    const bh = box.clientHeight || innerHeight;
    let w = bw;
    let h = bw / ratio;
    if (h > bh) {
      h = bh;
      w = bh * ratio;
    }
    video.style.width = `${w}px`;
    video.style.height = `${h}px`;
  } else {
    video.style.width = "";
    video.style.height = "";
  }
  video.style.transform = zoom !== 1 || stretch !== 1 ? `scale(${zoom * stretch}, ${zoom})` : "";
  video.style.filter = brightness !== 1 ? `brightness(${brightness})` : "";
  ui.aspect.title = `حجم الصورة: ${ASPECTS[aspect][1]}`;
}

function toggleFullscreen() {
  if (document.fullscreenElement) {
    document.exitFullscreen().catch(() => {});
  } else if (box.requestFullscreen) {
    box.requestFullscreen().catch(() => video.webkitEnterFullscreen?.());
  } else {
    video.webkitEnterFullscreen?.();
  }
}

let scrubbing = false;

function render() {
  ui.play.textContent = video.paused ? "▶" : "❚❚";
  if (current?.live) return;
  const d = total();
  const t = posNow();
  ui.cur.textContent = fmt(t);
  ui.dur.textContent = isFinite(d) ? `-${fmt(d - t)}` : "--:--";
  const f = isFinite(d) && d > 0 ? t / d : 0;
  if (!scrubbing) {
    ui.fill.style.width = `${f * 100}%`;
    ui.thumb.style.left = `${f * 100}%`;
  }
  if (video.buffered.length && isFinite(d) && d > 0) ui.buf.style.width = `${(video.buffered.end(video.buffered.length - 1) / d) * 100}%`;
}

["timeupdate", "play", "pause", "durationchange", "progress"].forEach((e) => video.addEventListener(e, render));
video.addEventListener("waiting", () => (ui.spin.hidden = false));
video.addEventListener("error", () => {
  // The rewrapped stream dropped: reopen it where the viewer is (twice at most).
  if (current?.started && remux && remux.retries < 2) { remux.retries++; restartRemux(Math.floor(posNow())); return; }
  if (current?.started) { ui.spin.hidden = true; showFailure(current.playbackUrl || fileUrl(current.urls), current.direct || false); }
});
video.addEventListener("playing", () => {
  ui.spin.hidden = true;
  wake();
});
video.addEventListener("canplay", () => (ui.spin.hidden = true));
video.addEventListener("pause", () => {
  saveProgress(true);
  wake();
});
video.addEventListener("ended", () => {
  saveProgress(true);
  // A series goes on to the next episode; anything else closes.
  if (["episode", "anime_episode"].includes(current?.entry.kind) && qIndex < queue.length - 1) step(1);
  else close();
});

// Timeline: click or drag.
function barFraction(e) {
  const r = ui.bar.getBoundingClientRect();
  return Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
}
function showTip(f) {
  ui.tip.hidden = false;
  ui.tip.style.left = `${f * 100}%`;
  ui.tip.textContent = fmt(f * total());
}
ui.bar.addEventListener("pointerdown", (e) => {
  if (!isFinite(total())) return;
  scrubbing = true;
  ui.bar.setPointerCapture(e.pointerId);
  const f = barFraction(e);
  ui.fill.style.width = `${f * 100}%`;
  ui.thumb.style.left = `${f * 100}%`;
  showTip(f);
  wake();
});
ui.bar.addEventListener("pointermove", (e) => {
  if (!isFinite(total())) return;
  const f = barFraction(e);
  if (scrubbing) {
    ui.fill.style.width = `${f * 100}%`;
    ui.thumb.style.left = `${f * 100}%`;
    wake();
  }
  showTip(f);
});
ui.bar.addEventListener("pointerleave", () => {
  if (!scrubbing) ui.tip.hidden = true;
});
ui.bar.addEventListener("pointerup", (e) => {
  if (!scrubbing) return;
  scrubbing = false;
  ui.tip.hidden = true;
  seekTo(barFraction(e) * total());
});

ui.play.addEventListener("click", togglePlay);
ui.back10.addEventListener("click", () => seekBy(-10));
ui.fwd10.addEventListener("click", () => seekBy(10));
$("pl-back").addEventListener("click", close);
$("pl-full").addEventListener("click", toggleFullscreen);
$("pl-pip").addEventListener("click", () => {
  if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {});
  else video.requestPictureInPicture?.().catch(() => {});
});
if (!document.pictureInPictureEnabled) $("pl-pip").hidden = true;
addEventListener("resize", () => !box.hidden && applyPicture());

/** "حجم الصورة": every fit and shape, plus zoom and width by steps; stays open while adjusting. */
function pictureMenu() {
  ui.menu.innerHTML = "<h3>حجم الصورة</h3>";
  const step = (label, value, change) => {
    const h = document.createElement("h4");
    h.textContent = `${label}: ${Math.round(value * 100)}%`;
    const row = document.createElement("div");
    row.style.cssText = "display:flex;gap:8px;padding:0 4px";
    for (const [t, d] of [["− تصغير", -0.05], ["+ تكبير", 0.05]]) {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "btn ghost small";
      b.style.flex = "1";
      b.textContent = t;
      b.addEventListener("click", (ev) => {
        ev.stopPropagation();
        change(d);
        applyPicture();
        savePicture();
        pictureMenu();
      });
      row.appendChild(b);
    }
    ui.menu.append(h, row);
  };
  step("التكبير", zoom, (d) => (zoom = Math.max(0.5, Math.min(4, zoom + d))));
  step("العرض (تمديد أفقي)", stretch, (d) => (stretch = Math.max(0.5, Math.min(2, stretch + d))));
  const add = (label, on, pick) => {
    const b = document.createElement("button");
    b.type = "button";
    b.className = on ? "on" : "";
    b.innerHTML = `<span></span>${on ? "<span>✓</span>" : ""}`;
    b.firstElementChild.textContent = label;
    b.addEventListener("click", (ev) => {
      ev.stopPropagation();
      pick();
      applyPicture();
      savePicture();
      pictureMenu();
    });
    ui.menu.appendChild(b);
  };
  add("رجّع كل شي للأصل", false, () => {
    aspect = 0;
    zoom = 1;
    stretch = 1;
  });
  const h = document.createElement("h4");
  h.textContent = "طريقة العرض";
  ui.menu.appendChild(h);
  ASPECTS.forEach(([, label], i) => add(label, i === aspect, () => (aspect = i)));
  ui.menu.hidden = false;
  box.classList.remove("idle");
}
ui.aspect.addEventListener("click", pictureMenu);
ui.vol.addEventListener("input", () => setVolume(Number(ui.vol.value)));
ui.mute.addEventListener("click", () => {
  video.muted = !video.muted;
  flash(video.muted ? "🔇 بدون صوت" : "🔊 الصوت شغّال");
});
video.addEventListener("volumechange", () => {
  ui.vol.value = video.muted ? 0 : video.volume;
  ui.mute.textContent = video.muted || video.volume === 0 ? "🔇" : "🔊";
});

// External SRT/WebVTT stays in this browser session and is rendered by the existing video element.
let captionDelay = Number(localStorage.getItem("pl.captionDelay") || 0);
let captionColor = localStorage.getItem("pl.captionColor") || "white";
let cueTimes = new WeakMap();
const captionUrls = new Set();
function shiftCaptions() {
  for (const track of video.textTracks) for (const cue of track.cues || []) {
    if (!cueTimes.has(cue)) cueTimes.set(cue, [cue.startTime, cue.endTime]);
    const [start, end] = cueTimes.get(cue); cue.startTime = Math.max(0, start + captionDelay); cue.endTime = Math.max(cue.startTime + .01, end + captionDelay);
  }
}
function clearExternalCaptions() {
  video.querySelectorAll("track[data-user-subtitle]").forEach((t) => t.remove());
  for (const url of captionUrls) URL.revokeObjectURL(url); captionUrls.clear(); cueTimes = new WeakMap();
}
async function attachCaptions(text, label) {
  text = text.replace(/^\uFEFF/, "");
  if (!text.startsWith("WEBVTT")) text = "WEBVTT\n\n" + text.replace(/(\d{2}:\d{2}:\d{2}),(\d{3})/g, "$1.$2");
  const url = URL.createObjectURL(new Blob([text], { type: "text/vtt" })); captionUrls.add(url);
  const element = document.createElement("track"); element.kind = "subtitles"; element.label = label; element.srclang = "ar"; element.src = url; element.dataset.userSubtitle = "true";
  element.onload = () => { for (const track of video.textTracks) track.mode = track === element.track ? "showing" : "disabled"; shiftCaptions(); flash("أُضيفت الترجمة"); };
  element.onerror = () => { element.remove(); URL.revokeObjectURL(url); captionUrls.delete(url); flash("تعذّر قراءة ملف الترجمة"); };
  video.appendChild(element); element.track.mode = "showing";
}
function pickCaptionFile() {
  const input = document.createElement("input"); input.type = "file"; input.accept = ".srt,.vtt,text/vtt";
  input.onchange = async () => { const file = input.files?.[0]; if (!file) return; if (file.size > 5 * 1024 * 1024) return flash("ملف الترجمة أكبر من الحد المسموح"); try { await attachCaptions(await file.text(), file.name); } catch { flash("تعذّر قراءة ملف الترجمة"); } }; input.click();
}
async function pickCaptionUrl() {
  const value = prompt("رابط ملف SRT أو WebVTT"); if (!value) return;
  try { const url = new URL(value); if (!["http:", "https:"].includes(url.protocol)) throw new Error();
    const response = await fetch(via(url.href)); if (!response.ok) throw new Error();
    const reader = response.body.getReader(), decoder = new TextDecoder(); let text = "", size = 0;
    try { while (true) { const { value, done } = await reader.read(); if (done) break; size += value.byteLength; if (size > 5 * 1024 * 1024) throw new Error(); text += decoder.decode(value, { stream: true }); } text += decoder.decode(); }
    finally { await reader.cancel().catch(() => {}); }
    await attachCaptions(text, "ترجمة خارجية");
  } catch { flash("تعذّر تحميل الترجمة من هذا الرابط"); }
}
box.style.setProperty("--caption-color", captionColor);

// ---------- menus: subtitles, audio, speed ----------

function openMenu(title, items, emptyText) {
  ui.menu.innerHTML = "<h3></h3>";
  ui.menu.firstElementChild.textContent = title;
  for (const [label, on, pick] of items) {
    const b = document.createElement("button");
    b.type = "button";
    b.className = on ? "on" : "";
    b.innerHTML = `<span></span>${on ? "<span>✓</span>" : ""}`;
    b.firstElementChild.textContent = label;
    b.addEventListener("click", () => {
      pick();
      ui.menu.hidden = true;
      wake();
    });
    ui.menu.appendChild(b);
  }
  if (emptyText) {
    const p = document.createElement("p");
    p.textContent = emptyText;
    ui.menu.appendChild(p);
  }
  ui.menu.hidden = false;
  ui.menu.querySelector("button")?.focus();
  box.classList.remove("idle");
}

const langName = (code) => {
  if (!code || code === "und") return "";
  try {
    return new Intl.DisplayNames(["ar"], { type: "language" }).of(code) || code;
  } catch {
    return code;
  }
};

$("pl-subs").addEventListener("click", () => {
  const tracks = [...video.textTracks].filter((t) => t.kind === "subtitles" || t.kind === "captions");
  const any = tracks.some((t) => t.mode === "showing");
  openMenu("الترجمة", [
    ["أضف ملف SRT / WebVTT", false, pickCaptionFile],
    ["أضف رابط ترجمة", false, pickCaptionUrl],
    [`تأخير الترجمة: ${captionDelay} ثانية`, false, () => { captionDelay = Math.round((captionDelay + .5) * 10) / 10; if (captionDelay > 5) captionDelay = 0; localStorage.setItem("pl.captionDelay", String(captionDelay)); shiftCaptions(); flash(`تأخير الترجمة: ${captionDelay} ثانية`); }],
    ...[["white", "أبيض"], ["yellow", "أصفر"], ["#a5ff9c", "أخضر"]].map(([color, label]) => [`لون الترجمة: ${label}`, captionColor === color, () => { captionColor = color; localStorage.setItem("pl.captionColor", color); box.style.setProperty("--caption-color", color); }]),
    ["بدون ترجمة", !any, () => {
      tracks.forEach((t) => (t.mode = "disabled"));
      if (engine?.hls) engine.hls.subtitleTrack = -1;
    }],
    ...tracks.map((t, i) => [t.label || langName(t.language) || `ترجمة ${i + 1}`, t.mode === "showing", () => {
      tracks.forEach((x) => (x.mode = x === t ? "showing" : "disabled"));
      if (engine?.hls) engine.hls.subtitleTrack = i;
    }]),
    ...SUB_SIZES.map(([k, label]) => [`الحجم: ${label}`, subSize === k, () => {
      subSize = k;
      try { localStorage.setItem("pl.subSize", k); } catch { /* private mode */ }
      applySubs();
    }]),
    ["خلفية داكنة ورا الترجمة", subBg, () => {
      subBg = !subBg;
      try { localStorage.setItem("pl.subBg", subBg ? "1" : "0"); } catch { /* private mode */ }
      applySubs();
    }],
  ], tracks.length ? "" : "هذا الملف ما فيه ترجمات يقدر المتصفح يقراها");
});

$("pl-audio").addEventListener("click", () => {
  let items = [];
  if (engine?.hls && engine.hls.audioTracks.length) {
    items = engine.hls.audioTracks.map((t, i) => [t.name || langName(t.lang) || `صوت ${i + 1}`, engine.hls.audioTrack === i, () => (engine.hls.audioTrack = i)]);
  } else if (video.audioTracks && video.audioTracks.length) {
    const list = [...video.audioTracks];
    items = list.map((t, i) => [t.label || langName(t.language) || `صوت ${i + 1}`, t.enabled, () => list.forEach((x) => (x.enabled = x === t))]);
  }
  openMenu("الصوت المعلّق", items, items.length > 1 ? "" : "هذا الملف فيه صوت واحد بس (أو المتصفح ما يقدر يغيّره)");
});

ui.speed.addEventListener("click", () => {
  openMenu("سرعة التشغيل", SPEEDS.map((s) => [s === 1 ? "عادي" : `×${s}`, video.playbackRate === s, () => {
    video.playbackRate = s;
    ui.speed.textContent = `${s}×`;
  }]));
});

// ---------- mouse, keyboard and touch ----------

box.addEventListener("pointermove", (e) => {
  if (e.pointerType === "mouse") wake();
});

document.addEventListener("keydown", (e) => {
  if (box.hidden) return;
  if (e.target.closest?.("input")) return;
  const k = e.key;
  if (k === "Escape") {
    if (!ui.menu.hidden) ui.menu.hidden = true;
    else if (!document.fullscreenElement) close();
    return;
  }
  if (!ui.menu.hidden) return;
  const handled = {
    " ": togglePlay, k: togglePlay, K: togglePlay,
    ArrowLeft: () => (current?.live ? step(-1) : seekBy(-10)), ArrowRight: () => (current?.live ? step(1) : seekBy(10)),
    PageUp: () => step(1), PageDown: () => step(-1), N: () => step(1), n: () => step(1),
    j: () => seekBy(-10), l: () => seekBy(10),
    ArrowUp: () => setVolume(video.volume + 0.05), ArrowDown: () => setVolume(video.volume - 0.05),
    f: toggleFullscreen, F: toggleFullscreen,
    m: () => ui.mute.click(), M: () => ui.mute.click(),
  }[k];
  if (handled) {
    e.preventDefault();
    handled();
    wake();
  }
});

// Gestures on the picture: tap toggles controls, double tap skips (sides) or plays (middle),
// drag sideways seeks, drag up/down on the left = brightness, on the right = volume, pinch = zoom.
// With a mouse: click = play/pause, double click = full screen, wheel = volume.
const pointers = new Map();
let gesture = null;
let lastTap = 0;
let tapTimer = null;

ui.gest.addEventListener("pointerdown", (e) => {
  ui.gest.setPointerCapture(e.pointerId);
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
  if (pointers.size === 2) {
    const [a, b] = [...pointers.values()];
    gesture = { mode: "pinch", dist: Math.hypot(a.x - b.x, a.y - b.y) || 1, zoom };
  } else if (pointers.size === 1) {
    gesture = { mode: null, x: e.clientX, y: e.clientY, t: posNow(), vol: video.volume, bright: brightness, type: e.pointerType };
  }
});

ui.gest.addEventListener("pointermove", (e) => {
  if (!pointers.has(e.pointerId) || !gesture) return;
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
  const w = ui.gest.clientWidth;
  const h = ui.gest.clientHeight;
  if (gesture.mode === "pinch" && pointers.size === 2) {
    const [a, b] = [...pointers.values()];
    zoom = Math.max(0.5, Math.min(4, (gesture.zoom * Math.hypot(a.x - b.x, a.y - b.y)) / gesture.dist));
    applyPicture();
    flash(`تكبير ×${zoom.toFixed(1)}`);
    return;
  }
  if (gesture.type === "mouse") return;
  const dx = e.clientX - gesture.x;
  const dy = e.clientY - gesture.y;
  if (!gesture.mode && Math.hypot(dx, dy) > 12) {
    gesture.mode = Math.abs(dx) > Math.abs(dy) ? (current?.live ? "channel" : "seek") : gesture.x < w / 2 ? "bright" : "volume";
  }
  if (gesture.mode === "seek" && isFinite(total())) {
    const span = Math.min(total(), 180);
    gesture.target = Math.max(0, Math.min(total() - 1, gesture.t + (dx / w) * span));
    const delta = gesture.target - gesture.t;
    flash(`${delta < 0 ? "↺ -" : "↻ +"}${fmt(Math.abs(delta))} · ${fmt(gesture.target)}`);
  } else if (gesture.mode === "bright") {
    brightness = Math.max(0.15, Math.min(1.6, gesture.bright - (dy / h) * 1.4));
    applyPicture();
    flash(`☀ الإضاءة ${Math.round(brightness * 100)}%`, Math.min(1, brightness / 1.6));
  } else if (gesture.mode === "volume") {
    setVolume(gesture.vol - (dy / h) * 1.3);
  }
});

function endPointer(e) {
  if (!pointers.has(e.pointerId)) return;
  pointers.delete(e.pointerId);
  if (pointers.size > 0) return;
  const g = gesture;
  gesture = null;
  if (!g) return;
  // Live: a swipe of a fifth of the screen changes channel (to the left = next).
  if (g.mode === "channel") {
    const dx = e.clientX - g.x;
    if (Math.abs(dx) > ui.gest.clientWidth / 5) step(dx < 0 ? 1 : -1);
    return;
  }
  if (g.mode === "seek" && g.target !== undefined) {
    seekTo(g.target);
    return;
  }
  if (g.mode) return;
  // A tap or click.
  const now = Date.now();
  const w = ui.gest.clientWidth;
  if (!ui.menu.hidden) {
    ui.menu.hidden = true;
    return;
  }
  if (now - lastTap < 300) {
    clearTimeout(tapTimer);
    lastTap = 0;
    if (g.type === "mouse") toggleFullscreen();
    else if (e.clientX < w / 3) seekBy(-10);
    else if (e.clientX > (w * 2) / 3) seekBy(10);
    else togglePlay();
    return;
  }
  lastTap = now;
  tapTimer = setTimeout(() => {
    if (g.type === "mouse") togglePlay();
    else if (box.classList.contains("idle")) wake();
    else box.classList.add("idle");
  }, 280);
}
ui.gest.addEventListener("pointerup", endPointer);
ui.gest.addEventListener("pointercancel", endPointer);
ui.gest.addEventListener("wheel", (e) => {
  e.preventDefault();
  setVolume(video.volume + (e.deltaY < 0 ? 0.05 : -0.05));
}, { passive: false });

window.addEventListener("pagehide", () => saveProgress(true));
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "hidden") saveProgress(true);
});

$("pl-download")?.addEventListener("click", async () => { if (!current) return; try { await addDownload(current.entry, current.urls); flash("أُضيف إلى التنزيلات"); } catch (e) { flash(e.message); } });
