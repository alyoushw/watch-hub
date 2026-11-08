// Watch Hub's own player for computers (and Android browsers): custom controls, gestures,
// subtitle and audio-track menus, picture size, speed, resume. On iPhone and iPad everything
// opens in VLC instead (Safari cannot play MKV and many streams).
//
// Safari plays HLS itself; other browsers use hls.js, and MPEG-TS live streams use mpegts.js.
import { via } from "./xtream.js";
import { settings, history, isFinished } from "./store.js";

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
};

let engine = null; // { destroy, onFatal, hls? }
let session = 0;
let current = null; // { urls, entry, live }
let saveTimer = null;

const ASPECTS = [["contain", "ملاءمة الشاشة"], ["cover", "ملء وقص"], ["fill", "تمديد"]];
const SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 2];
let aspect = 0;
let zoom = 1;
let brightness = 1;

// ---------- helpers ----------

const scripts = new Map();
function loadScript(src) {
  if (!scripts.has(src)) {
    scripts.set(src, new Promise((resolve, reject) => {
      const s = document.createElement("script");
      s.src = src;
      s.onload = resolve;
      s.onerror = () => {
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

const fileUrl = (urls) => urls.find((u) => !/\.m3u8$/i.test(new URL(u).pathname)) || urls[0];
const vlcLink = (url) => `vlc-x-callback://x-callback-url/stream?url=${encodeURIComponent(url)}`;

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
      video.removeEventListener("loadeddata", onOk);
      video.removeEventListener("error", onErr);
      ok ? resolve() : reject(new Error("failed"));
    };
    const onOk = () => done(true);
    const onErr = () => done(false);
    const timer = setTimeout(() => done(false), timeoutMs);
    video.addEventListener("loadeddata", onOk);
    video.addEventListener("error", onErr);
    engine?.onFatal?.(() => done(false));
  });
}

const nativeHls = () => video.canPlayType("application/vnd.apple.mpegurl") !== "";

async function tryOne(url, startAt) {
  reset();
  const src = via(url);
  const path = new URL(url).pathname.toLowerCase();
  if (path.endsWith(".m3u8") && !nativeHls()) {
    await loadScript(HLS_JS);
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
  const started = waitForStart(25000);
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
  zoom = 1;
  brightness = 1;
  applyPicture();
  wake();
}

function saveProgress() {
  if (!current || current.live) return;
  const d = video.duration;
  if (!isFinite(d) || d <= 0) return;
  history.save({ ...current.entry, positionMs: Math.round(video.currentTime * 1000), durationMs: Math.round(d * 1000) });
}

/** Plays [urls] (tried in order); [entry] names it and keeps its place in the watch history. */
export function play(urls, entry) {
  history.save(entry);
  if (isIOS() && settings.vlcOnIos !== false) return openInVlc(urls, entry);
  return playHere(urls, entry);
}

async function playHere(urls, entry) {
  const live = entry.kind === "live";
  const mine = ++session;
  current = { urls, entry, live };
  openOverlay(entry, live);
  const saved = history.get(entry.key);
  const startAt = !live && saved && !isFinished(saved) && saved.positionMs > 10000 ? saved.positionMs / 1000 : 0;
  ui.spin.hidden = false;
  for (const url of urls) {
    try {
      await tryOne(url, startAt);
      if (mine !== session) return;
      if (startAt) flash(`نكمل من ${fmt(startAt)}`);
      clearInterval(saveTimer);
      saveTimer = setInterval(saveProgress, 5000);
      return;
    } catch {
      if (mine !== session) return;
    }
  }
  if (mine !== session) return;
  ui.spin.hidden = true;
  showFailure(fileUrl(urls));
}

/** The browser could not play it: offer players that handle any format (MKV, AVI, …). */
function showFailure(url) {
  const ext = (new URL(url).pathname.split(".").pop() || "").toUpperCase();
  const msg = panel(`المتصفح ما قدر يشغّل هذا الملف${ext ? ` (صيغة ${ext})` : ""}. شغّله في VLC أو Infuse:`, [
    ["افتح في VLC", () => (location.href = vlcLink(url)), true],
    ["افتح في Infuse", () => (location.href = `infuse://x-callback-url/play?url=${encodeURIComponent(url)}`)],
    ["انسخ الرابط", async () => {
      try {
        await navigator.clipboard.writeText(url);
        msg.textContent = "✓ نسخنا الرابط. الصقه في أي مشغّل فيديو (في VLC: Network ← Network Stream).";
      } catch {
        prompt("انسخ الرابط:", url);
      }
    }],
  ]);
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
  location.href = vlcLink(link);
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
  }, 2500);
}

export function close() {
  saveProgress();
  clearInterval(saveTimer);
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
  if (!current || current.live || !isFinite(video.duration)) return;
  video.currentTime = Math.max(0, Math.min(video.duration - 1, video.currentTime + sec));
  flash(`${sec < 0 ? "↺ -" : "↻ +"}${Math.abs(sec)} ث`);
  wake();
}

function setVolume(v) {
  video.volume = Math.max(0, Math.min(1, v));
  video.muted = video.volume === 0;
  flash(`🔊 الصوت ${Math.round(video.volume * 100)}%`, video.volume);
}

function applyPicture() {
  video.style.objectFit = ASPECTS[aspect][0];
  video.style.transform = zoom !== 1 ? `scale(${zoom})` : "";
  video.style.filter = brightness !== 1 ? `brightness(${brightness})` : "";
  ui.aspect.title = `حجم الصورة: ${ASPECTS[aspect][1]}`;
}

function toggleFullscreen() {
  if (document.fullscreenElement) {
    document.exitFullscreen().catch(() => {});
  } else if (box.requestFullscreen) {
    box.requestFullscreen().catch(() => {});
  } else {
    video.webkitEnterFullscreen?.();
  }
}

let scrubbing = false;

function render() {
  ui.play.textContent = video.paused ? "▶" : "❚❚";
  if (current?.live) return;
  const d = video.duration;
  const t = video.currentTime;
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
video.addEventListener("playing", () => {
  ui.spin.hidden = true;
  wake();
});
video.addEventListener("canplay", () => (ui.spin.hidden = true));
video.addEventListener("pause", () => {
  saveProgress();
  wake();
});
video.addEventListener("ended", () => {
  saveProgress();
  close();
});

// Timeline: click or drag.
function barFraction(e) {
  const r = ui.bar.getBoundingClientRect();
  return Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
}
function showTip(f) {
  ui.tip.hidden = false;
  ui.tip.style.left = `${f * 100}%`;
  ui.tip.textContent = fmt(f * video.duration);
}
ui.bar.addEventListener("pointerdown", (e) => {
  if (!isFinite(video.duration)) return;
  scrubbing = true;
  ui.bar.setPointerCapture(e.pointerId);
  const f = barFraction(e);
  ui.fill.style.width = `${f * 100}%`;
  ui.thumb.style.left = `${f * 100}%`;
  showTip(f);
  wake();
});
ui.bar.addEventListener("pointermove", (e) => {
  if (!isFinite(video.duration)) return;
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
  video.currentTime = barFraction(e) * video.duration;
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
ui.aspect.addEventListener("click", () => {
  aspect = (aspect + 1) % ASPECTS.length;
  zoom = 1;
  applyPicture();
  flash(`⛶ ${ASPECTS[aspect][1]}`);
});
ui.vol.addEventListener("input", () => setVolume(Number(ui.vol.value)));
ui.mute.addEventListener("click", () => {
  video.muted = !video.muted;
  flash(video.muted ? "🔇 بدون صوت" : "🔊 الصوت شغّال");
});
video.addEventListener("volumechange", () => {
  ui.vol.value = video.muted ? 0 : video.volume;
  ui.mute.textContent = video.muted || video.volume === 0 ? "🔇" : "🔊";
});

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
    ["بدون ترجمة", !any, () => {
      tracks.forEach((t) => (t.mode = "disabled"));
      if (engine?.hls) engine.hls.subtitleTrack = -1;
    }],
    ...tracks.map((t, i) => [t.label || langName(t.language) || `ترجمة ${i + 1}`, t.mode === "showing", () => {
      tracks.forEach((x) => (x.mode = x === t ? "showing" : "disabled"));
      if (engine?.hls) engine.hls.subtitleTrack = i;
    }]),
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
    ArrowLeft: () => seekBy(-10), ArrowRight: () => seekBy(10),
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
    gesture = { mode: null, x: e.clientX, y: e.clientY, t: video.currentTime, vol: video.volume, bright: brightness, type: e.pointerType };
  }
});

ui.gest.addEventListener("pointermove", (e) => {
  if (!pointers.has(e.pointerId) || !gesture) return;
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
  const w = ui.gest.clientWidth;
  const h = ui.gest.clientHeight;
  if (gesture.mode === "pinch" && pointers.size === 2) {
    const [a, b] = [...pointers.values()];
    zoom = Math.max(1, Math.min(4, (gesture.zoom * Math.hypot(a.x - b.x, a.y - b.y)) / gesture.dist));
    applyPicture();
    flash(`تكبير ×${zoom.toFixed(1)}`);
    return;
  }
  if (gesture.type === "mouse") return;
  const dx = e.clientX - gesture.x;
  const dy = e.clientY - gesture.y;
  if (!gesture.mode && Math.hypot(dx, dy) > 12) {
    gesture.mode = Math.abs(dx) > Math.abs(dy) ? (current?.live ? "none" : "seek") : gesture.x < w / 2 ? "bright" : "volume";
  }
  if (gesture.mode === "seek" && isFinite(video.duration)) {
    const span = Math.min(video.duration, 180);
    gesture.target = Math.max(0, Math.min(video.duration - 1, gesture.t + (dx / w) * span));
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
  if (g.mode === "seek" && g.target !== undefined) {
    video.currentTime = g.target;
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

window.addEventListener("pagehide", saveProgress);
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "hidden") saveProgress();
});
