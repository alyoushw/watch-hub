// Everything the user saves stays in this browser (localStorage), like the Android app keeps it on the phone.

const KEY = "watchhub";

function read() {
  try {
    return JSON.parse(localStorage.getItem(KEY) || "{}");
  } catch {
    return {};
  }
}

const data = read();
data.settings ??= {};
data.watchlist ??= [];
data.favorites ??= [];
data.recent ??= [];

function save() {
  try {
    localStorage.setItem(KEY, JSON.stringify(data));
  } catch {
    // Private mode or full storage: keep working in memory.
  }
}

export const settings = new Proxy(data.settings, {
  set(target, prop, value) {
    target[prop] = value;
    save();
    return true;
  },
});

settings.region ??= "SA";
settings.proxy ??= "/proxy";

// ---------- Xtream accounts (several, each with the user's name for it) ----------

// The single account of earlier versions becomes the first one in the list.
if (!Array.isArray(settings.xAccounts)) {
  settings.xAccounts = settings.xHost && settings.xUser && settings.xPass
    ? [{ id: "a1", name: "حسابي", host: settings.xHost, user: settings.xUser, pass: settings.xPass }]
    : [];
}

export const accounts = {
  all: () => settings.xAccounts || [],
  get: (id) => accounts.all().find((a) => a.id === id) || null,
  /** The account the channels / films / series pages show. */
  active: () => accounts.get(settings.xActive) || accounts.all()[0] || null,
  setActive(id) {
    settings.xActive = id;
  },
  /** Adds the account, or replaces the one with the same id. Returns the saved copy. */
  save(acc) {
    const saved = { ...acc, id: acc.id || `a${Date.now().toString(36)}` };
    const list = accounts.all();
    settings.xAccounts = list.some((a) => a.id === saved.id) ? list.map((a) => (a.id === saved.id ? saved : a)) : [...list, saved];
    if (!list.length) settings.xActive = saved.id;
    return saved;
  },
  remove(id) {
    settings.xAccounts = accounts.all().filter((a) => a.id !== id);
    if (settings.xActive === id) settings.xActive = accounts.all()[0]?.id || null;
  },
  label: (a) => (a ? a.name || a.host.replace(/^https?:\/\//, "").split("/")[0] : ""),
};

export const xtreamReady = () => accounts.all().length > 0;

export const watchlist = {
  all: () => data.watchlist,
  get: (key) => data.watchlist.find((w) => w.key === key),
  upsert(item) {
    data.watchlist = [item, ...data.watchlist.filter((w) => w.key !== item.key)];
    save();
  },
  remove(key) {
    data.watchlist = data.watchlist.filter((w) => w.key !== key);
    save();
  },
};

export const favorites = {
  has: (id) => data.favorites.includes(id),
  all: () => data.favorites,
  toggle(id) {
    data.favorites = data.favorites.includes(id) ? data.favorites.filter((x) => x !== id) : [...data.favorites, id];
    save();
    return data.favorites.includes(id);
  },
};

export const recent = {
  all: () => data.recent,
  add(id) {
    data.recent = [id, ...data.recent.filter((x) => x !== id)].slice(0, 30);
    save();
  },
};

/**
 * Watch history across all Xtream accounts: what was played (live, film, episode) and how far,
 * for "تابع المشاهدة" and resuming. Each entry remembers its account. Same shape as the Android app's.
 */
if (!Array.isArray(data.watchHistory)) data.watchHistory = [];
delete data.history;

// A second copy of the history in IndexedDB. Safari can drop a localStorage write when the
// page is suspended right after it (opening VLC does exactly that); an IndexedDB write is on
// disk once its transaction completes, so the player waits for it before leaving the page.
let idb = null;
function openIdb() {
  idb ??= new Promise((resolve, reject) => {
    const r = indexedDB.open("watchhub", 1);
    r.onupgradeneeded = () => r.result.createObjectStore("kv");
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
  return idb;
}

let lastWrite = Promise.resolve();
function persistHistory() {
  const copy = data.watchHistory.slice();
  lastWrite = openIdb().then((db) => new Promise((resolve) => {
    const tx = db.transaction("kv", "readwrite", { durability: "strict" });
    tx.objectStore("kv").put(copy, "watchHistory");
    tx.oncomplete = () => resolve(true);
    tx.onerror = tx.onabort = () => resolve(false);
  })).catch(() => false);
  return lastWrite;
}

/** Resolves once the latest history change is safely stored (at most ~1.5 s). */
export const historySaved = () => Promise.race([lastWrite, new Promise((r) => setTimeout(r, 1500))]);

/** On start: merge in the IndexedDB copy (it may hold what localStorage lost). */
export async function restoreHistory() {
  try {
    const db = await openIdb();
    const saved = await new Promise((resolve) => {
      const req = db.transaction("kv").objectStore("kv").get("watchHistory");
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => resolve(null);
    });
    if (!Array.isArray(saved) || !saved.length) {
      if (data.watchHistory.length) persistHistory();
      return;
    }
    const byKey = new Map();
    for (const e of [...saved, ...data.watchHistory]) {
      const old = byKey.get(e.key);
      if (!old || (e.updatedAt || 0) >= (old.updatedAt || 0)) byKey.set(e.key, e);
    }
    const removed = new Set(data.removedHistory || []);
    data.watchHistory = [...byKey.values()].filter((e) => !removed.has(e.key))
      .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0)).slice(0, 80);
    save();
    persistHistory();
  } catch {
    // No IndexedDB (private mode): localStorage alone.
  }
}

export const history = {
  list: () => data.watchHistory,
  get: (key) => data.watchHistory.find((e) => e.key === key),
  save(entry) {
    const old = history.get(entry.key);
    const merged = {
      ...entry,
      positionMs: entry.positionMs > 0 || entry.durationMs > 0 ? entry.positionMs || 0 : old?.positionMs || 0,
      durationMs: entry.durationMs > 0 ? entry.durationMs : old?.durationMs || 0,
      updatedAt: Date.now(),
    };
    data.watchHistory = [merged, ...data.watchHistory.filter((e) => e.key !== entry.key)].slice(0, 80);
    data.removedHistory = (data.removedHistory || []).filter((k) => k !== entry.key);
    save();
    persistHistory();
  },
  remove(key) {
    data.watchHistory = data.watchHistory.filter((e) => e.key !== key);
    // Remembered so an older copy cannot bring it back.
    data.removedHistory = [key, ...(data.removedHistory || []).filter((k) => k !== key)].slice(0, 200);
    save();
    persistHistory();
  },
  /** One card per series (its latest episode); finished films left out. */
  continueList() {
    const series = new Set();
    const ids = new Set(accounts.all().map((a) => a.id));
    return history.list().filter((e) => {
      if (!ids.has(e.accountId)) return false;
      if (e.kind === "episode" && e.seriesId) {
        const k = `${e.accountId}:${e.seriesId}`;
        if (series.has(k)) return false;
        series.add(k);
        return true;
      }
      return e.kind !== "movie" || !isFinished(e);
    });
  },
};

export const isFinished = (e) => e.durationMs > 0 && e.positionMs >= e.durationMs * 0.95;
export const progressOf = (e) => (e.durationMs > 0 ? Math.min(1, e.positionMs / e.durationMs) : 0);
