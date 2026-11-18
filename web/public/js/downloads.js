import { resolveEntry } from "./sources.js?v=20261118d";
import { urlsFor, via, resolvePlayback } from "./xtream.js?v=20261118d";
let dbPromise, running = false;
const active = new Map();
const removed = new Set();
const open = () => dbPromise ||= new Promise((resolve, reject) => {
  const request = indexedDB.open("watchhub-downloads", 1);
  request.onupgradeneeded = () => { request.result.createObjectStore("items"); request.result.createObjectStore("chunks"); };
  request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
});
async function request(store, mode, run) {
  const db = await open();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(store, mode), operation = run(tx.objectStore(store)); let result;
    if (operation) operation.onsuccess = () => { result = operation.result; };
    tx.oncomplete = () => resolve(result); tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error || new Error("توقف حفظ التنزيل"));
  });
}
const changed = () => window.dispatchEvent(new Event("watchhub:downloads"));
const put = async (item) => { if (removed.has(item.entry.key)) return; await request("items", "readwrite", (s) => s.put(item, item.entry.key)); changed(); };
export const listDownloads = () => request("items", "readonly", (s) => s.getAll());
const itemFor = (key) => request("items", "readonly", (s) => s.get(key));
export async function offlineUrl(key) {
  try { const item = await itemFor(key); return item?.state === "complete" && navigator.serviceWorker?.controller ? new URL(`/offline/${encodeURIComponent(key)}/video.${item.ext || "mp4"}`, location.href).href : null; }
  catch { return null; }
}
export async function addDownload(entry, urls = []) {
  if (entry.kind === "live") throw new Error("تنزيل البث المباشر غير متاح");
  const old = await itemFor(entry.key); if (old?.state === "complete" || active.has(entry.key)) return;
  removed.delete(entry.key);
  await put({ ...old, entry, urls: entry.animeRef || entry.sourceRef || entry.fourURef ? [] : urls, state: "queued", bytes: old?.bytes || 0, total: old?.total || 0, at: Date.now(), error: null });
  processQueue();
}
export async function pauseDownload(key) { active.get(key)?.abort(); const item = await itemFor(key); if (item && item.state !== "complete") await put({ ...item, state: "paused" }); }
export async function resumeDownload(key) { const item = await itemFor(key); if (item && item.state !== "complete") { await put({ ...item, state: "queued", error: null }); processQueue(); } }
export async function removeDownload(key) {
  removed.add(key);
  active.get(key)?.abort();
  await request("chunks", "readwrite", (s) => s.delete(IDBKeyRange.bound([key, 0], [key, Number.MAX_SAFE_INTEGER])));
  await request("items", "readwrite", (s) => s.delete(key)); changed();
}
async function saveChunk(item, blob) {
  if (removed.has(item.entry.key)) throw new DOMException("removed", "AbortError");
  const db = await open();
  await new Promise((resolve, reject) => {
    const tx = db.transaction(["items", "chunks"], "readwrite");
    tx.objectStore("chunks").put(blob, [item.entry.key, item.bytes]); item.bytes += blob.size;
    tx.objectStore("items").put(item, item.entry.key);
    tx.oncomplete = resolve; tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error || new Error("توقف حفظ التنزيل"));
  }); changed();
}
async function transfer(item) {
  const key = item.entry.key, controller = new AbortController(); active.set(key, controller);
  let received = 0, buffers = [];
  try {
    const urls = item.entry.animeRef || item.entry.sourceRef || item.entry.fourURef ? await resolveEntry(item.entry) : await resolvePlayback(item.entry, item.urls?.length ? item.urls : urlsFor(item.entry));
    const raw = urls[0]; if (!raw || /\.m3u8(?:\?|$)|\.ts(?:\?|$)/i.test(new URL(raw).pathname)) throw new Error("اختر سيرفر MP4 لتنزيل الفيديو");
    const response = await fetch(via(raw), { headers: item.bytes ? { Range: `bytes=${item.bytes}-`, ...(item.etag ? { "If-Range": item.etag } : {}) } : {}, signal: controller.signal });
    if (!response.ok) throw new Error(`تعذّر التنزيل (${response.status})`);
    const contentType = response.headers.get("content-type") || "video/mp4";
    if (/text\/html|json|mpegurl/i.test(contentType)) throw new Error("هذا الرابط لا يقدّم ملف فيديو قابلاً للتنزيل");
    if (item.bytes && response.status !== 206) { await request("chunks", "readwrite", (s) => s.delete(IDBKeyRange.bound([key, 0], [key, Number.MAX_SAFE_INTEGER]))); item.bytes = 0; }
    if (response.status === 206 && Number(/bytes\s+(\d+)-/i.exec(response.headers.get("content-range") || "")?.[1]) !== item.bytes) throw new Error("تعذّر استكمال التنزيل من موضعه السابق");
    item.total = Number(/\/(\d+)$/.exec(response.headers.get("content-range") || "")?.[1]) || (Number(response.headers.get("content-length")) || 0) + item.bytes;
    item.type = contentType; item.ext = /matroska/i.test(contentType) || /\.mkv$/i.test(new URL(raw).pathname) ? "mkv" : "mp4";
    item.etag = response.headers.get("etag"); item.state = "downloading"; item.error = null; await put(item);
    const reader = response.body.getReader();
    try {
      while (true) {
        const { value, done } = await reader.read(); if (done) break;
        buffers.push(value); received += value.byteLength;
        if (received >= 1024 * 1024) { if (controller.signal.aborted) throw new DOMException("paused", "AbortError"); await saveChunk(item, new Blob(buffers)); buffers = []; received = 0; }
      }
      if (controller.signal.aborted) throw new DOMException("paused", "AbortError");
      if (received) await saveChunk(item, new Blob(buffers));
    } finally { await reader.cancel().catch(() => {}); }
    if (item.total && item.bytes !== item.total) throw new Error("انقطع التنزيل قبل اكتمال الملف؛ اضغط استكمال");
    item.total = item.bytes; item.state = "complete"; await put(item);
  } catch (e) {
    const current = await itemFor(key); if (!current) return;
    item.state = controller.signal.aborted ? current.state === "queued" ? "queued" : "paused" : "error";
    item.error = controller.signal.aborted ? null : e.name === "QuotaExceededError" ? "مساحة المتصفح لا تكفي لهذا الملف" : e.message;
    await put(item);
  } finally { controller.abort(); active.delete(key); }
}
async function processQueue() {
  if (running) return; running = true;
  try { while (true) { const next = (await listDownloads()).filter((x) => x.state === "queued").sort((a, b) => a.at - b.at)[0]; if (!next) break; await transfer(next); } }
  finally { running = false; }
}
export async function restoreDownloads() {
  try { for (const item of await listDownloads()) if (item.state === "downloading") await put({ ...item, state: "paused" }); processQueue(); } catch { /* Private browsing may disable local video storage. */ }
}
