// Full-screen player. Safari (iPhone, iPad, Mac) plays HLS itself; other browsers use hls.js,
// and MPEG-TS live streams use mpegts.js. Each link is tried in turn until one plays.
import { via } from "./xtream.js";

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

export async function play(urls, title) {
  const mine = ++session;
  box.hidden = false;
  errorEl.hidden = true;
  titleEl.textContent = title || "";
  document.body.style.overflow = "hidden";
  for (const url of urls) {
    try {
      await tryOne(url);
      return;
    } catch {
      if (mine !== session) return;
    }
  }
  if (mine !== session) return;
  showFailure(urls[0], title);
}

/** The browser could not play it: offer players that handle any format (MKV, AVI, …). */
function showFailure(url, title) {
  const link = new URL(via(url), location.href).href;
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
