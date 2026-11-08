// Full-screen player. Safari (iPhone, iPad, Mac) plays HLS itself; other browsers use hls.js,
// and MPEG-TS live streams use mpegts.js. Each link is tried in turn until one plays.
import { via } from "./xtream.js";
import { settings } from "./store.js";

const HLS_JS = "https://cdn.jsdelivr.net/npm/hls.js@1.5.17/dist/hls.min.js";
const MPEGTS_JS = "https://cdn.jsdelivr.net/npm/mpegts.js@1.7.3/dist/mpegts.js";

const box = document.getElementById("player");
const video = document.getElementById("video");
const titleEl = document.getElementById("player-title");
const errorEl = document.getElementById("player-error");
let engine = null;
let session = 0;

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
}

/** Resolves once the video starts, rejects on an error or after a timeout. */
function waitForStart(timeoutMs) {
  return new Promise((resolve, reject) => {
    const done = (ok) => {
      clearTimeout(timer);
      video.removeEventListener("playing", onPlay);
      video.removeEventListener("loadeddata", onPlay);
      video.removeEventListener("error", onError);
      ok ? resolve() : reject(new Error("failed"));
    };
    const onPlay = () => done(true);
    const onError = () => done(false);
    const timer = setTimeout(() => done(false), timeoutMs);
    video.addEventListener("playing", onPlay);
    video.addEventListener("loadeddata", onPlay);
    video.addEventListener("error", onError);
    engine?.onFatal?.(() => done(false));
  });
}

const nativeHls = () => video.canPlayType("application/vnd.apple.mpegurl") !== "";

async function tryOne(url) {
  reset();
  const src = via(url);
  const path = new URL(url).pathname.toLowerCase();

  if (path.endsWith(".m3u8") && !nativeHls()) {
    await loadScript(HLS_JS);
    const Hls = window.Hls;
    if (!Hls || !Hls.isSupported()) throw new Error("hls");
    const hls = new Hls({ enableWorker: true, lowLatencyMode: false });
    let fatal = null;
    hls.on(Hls.Events.ERROR, (_e, d) => {
      if (d.fatal) fatal?.();
    });
    engine = { destroy: () => hls.destroy(), onFatal: (fn) => (fatal = fn) };
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
  const started = waitForStart(20000);
  video.play().catch(() => { /* autoplay may need a tap; controls are shown */ });
  await started;
}

const VLC_STORE = "https://apps.apple.com/app/vlc-media-player/id650377962";

/** iPhone, iPad (iPadOS reports itself as a Mac with touch) and iPod. */
export const isIOS = () =>
  /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);

/** The provider's own file: what outside players get (not the HLS guess, which many panels lack). */
const fileUrl = (urls) => urls.find((u) => !/\.m3u8$/i.test(new URL(u).pathname)) || urls[0];

const vlcLink = (url) => `vlc-x-callback://x-callback-url/stream?url=${encodeURIComponent(url)}`;

function openOverlay(title) {
  box.hidden = false;
  errorEl.hidden = true;
  titleEl.textContent = title || "";
  document.body.style.overflow = "hidden";
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

/**
 * Hands the file to VLC. A page cannot ask whether an app is installed, so if Safari is
 * still showing this page shortly after, VLC is taken to be missing and the App Store is offered.
 */
function openInVlc(urls, title) {
  const mine = ++session;
  reset();
  openOverlay(title);
  const link = fileUrl(urls);
  let left = false;
  const away = () => {
    if (document.visibilityState === "hidden") left = true;
  };
  document.addEventListener("visibilitychange", away);
  window.addEventListener("pagehide", away);
  panel("نفتحها في VLC…", [
    ["شغّل هنا في المتصفح", () => playInBrowser(urls, title)],
  ]);
  location.href = vlcLink(link);
  setTimeout(() => {
    document.removeEventListener("visibilitychange", away);
    window.removeEventListener("pagehide", away);
    if (mine !== session) return;
    if (left) {
      // VLC opened; leave a way back in case the user returns here.
      panel("فتحناها في VLC ✓", [
        ["افتح في VLC مرة ثانية", () => (location.href = vlcLink(link)), true],
        ["إغلاق", close],
      ]);
      return;
    }
    panel("يبدو إن تطبيق VLC مو مثبت على جهازك. ثبّته (مجاني) وارجع اضغط «افتح في VLC».", [
      ["ثبّت VLC من App Store", () => (location.href = VLC_STORE), true],
      ["افتح في VLC", () => openInVlc(urls, title)],
      ["شغّل هنا في المتصفح", () => playInBrowser(urls, title)],
    ]);
  }, 2500);
}

export function play(urls, title) {
  // On iPhone and iPad everything (live, films, episodes) goes straight to VLC: Safari cannot
  // play MKV and many live streams. Live channels hand VLC the MPEG-TS link.
  if (isIOS() && settings.vlcOnIos !== false) return openInVlc(urls, title);
  return playInBrowser(urls, title);
}

async function playInBrowser(urls, title) {
  const mine = ++session;
  openOverlay(title);
  for (const url of urls) {
    try {
      await tryOne(url);
      return;
    } catch {
      if (mine !== session) return;
    }
  }
  if (mine !== session) return;
  showFailure(fileUrl(urls), title);
}

/** The browser could not play it: offer players that handle any format (MKV, AVI, …). */
function showFailure(url, title) {
  // Apps like VLC and Infuse reach the server directly, so they skip the proxy (faster, no limits).
  const link = url;
  const ext = (new URL(url).pathname.split(".").pop() || "").toUpperCase();
  const enc = encodeURIComponent(link);
  errorEl.innerHTML = "";
  const msg = document.createElement("p");
  msg.style.margin = "0 0 12px";
  msg.textContent = `المتصفح ما قدر يشغّل هذا الملف${ext ? ` (صيغة ${ext})` : ""}. شغّله في تطبيق VLC أو Infuse:`;
  const row = document.createElement("div");
  row.style.cssText = "display:flex;gap:8px;flex-wrap:wrap;justify-content:center";
  const button = (label, onClick, primary) => {
    const b = document.createElement("button");
    b.type = "button";
    b.className = primary ? "btn small" : "btn ghost small";
    b.textContent = label;
    b.addEventListener("click", onClick);
    row.appendChild(b);
  };
  button("افتح في VLC", () => (location.href = `vlc-x-callback://x-callback-url/stream?url=${enc}`), true);
  button("افتح في Infuse", () => (location.href = `infuse://x-callback-url/play?url=${enc}`));
  button("انسخ الرابط", async () => {
    try {
      await navigator.clipboard.writeText(link);
      msg.textContent = "✓ نسخنا الرابط. الصقه في أي مشغّل فيديو (في VLC: Network ← Network Stream).";
    } catch {
      prompt("انسخ الرابط:", link);
    }
  });
  errorEl.append(msg, row);
  const tip = document.createElement("p");
  tip.className = "muted";
  tip.style.cssText = "margin:10px 0 0;font-size:12px";
  tip.textContent = `«${title}»`;
  errorEl.appendChild(tip);
  errorEl.hidden = false;
}

export function close() {
  session++;
  reset();
  box.hidden = true;
  document.body.style.overflow = "";
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
}

document.getElementById("player-close").addEventListener("click", close);
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && !box.hidden) close();
});
