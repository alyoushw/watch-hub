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

/**
 * Changes made in this browser, for the account sync (cloud.js):
 * ("history" | "watchlist", item or null, key, removedAt, important) and ("xtream", list, updatedAt).
 */
const changeListeners = [];
export const onLocalChange = (fn) => changeListeners.push(fn);
const emit = (...args) => changeListeners.forEach((fn) => {
  try {
    fn(...args);
  } catch { /* a listener problem must not break saving */ }
});

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

const normalizeHost = (raw) => {
  let h = (raw || "").trim().replace(/\/+$/, "").replace(/\/?player_api\.php$/i, "");
  if (h && !/^https?:\/\//i.test(h)) h = `http://${h}`;
  return h;
};

/**
 * The same id for the same login on every device (phone, TV, web), so synced history points at
 * the right account everywhere: FNV-1a of "host|username", base 36. Same as XtreamAccount.stableId.
 */
export function stableAccountId(host, user) {
  const text = `${normalizeHost(host).toLowerCase()}|${(user || "").trim()}`;
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return `x${h.toString(36)}`;
}

// The single account of earlier versions becomes the first one in the list.
if (!Array.isArray(settings.xAccounts)) {
  settings.xAccounts = settings.xHost && settings.xUser && settings.xPass
    ? [{ id: "a1", name: "حسابي", host: settings.xHost, user: settings.xUser, pass: settings.xPass }]
    : [];
}

// Old per-device ids become stable ones; the history is re-pointed below.
const accountIdChanges = {};
{
  const fixed = [];
  for (const a of settings.xAccounts) {
    const id = stableAccountId(a.host, a.user);
    if (id !== a.id) accountIdChanges[a.id] = id;
    if (!fixed.some((x) => x.id === id)) fixed.push({ ...a, id });
  }
  if (Object.keys(accountIdChanges).length) {
    settings.xAccounts = fixed;
    if (accountIdChanges[settings.xActive]) settings.xActive = accountIdChanges[settings.xActive];
  }
}

export const accounts = {
  all: () => settings.xAccounts || [],
  get: (id) => accounts.all().find((a) => a.id === id) || null,
  /** The account the channels / films / series pages show. */
  active: () => accounts.get(settings.xActive) || accounts.all()[0] || null,
  setActive(id) {
    settings.xActive = id;
  },
  /** Adds the account, or replaces the one being edited. Returns the saved copy. */
  save(acc) {
    const host = normalizeHost(acc.host);
    const user = (acc.user || "").trim();
    const saved = { ...acc, host, user, id: stableAccountId(host, user) };
    const list = accounts.all();
    const editing = acc.id && list.some((a) => a.id === acc.id);
    const next = editing ? list.map((a) => (a.id === acc.id ? saved : a)) : [...list.filter((a) => a.id !== saved.id), saved];
    store(next.filter((a, i) => next.findIndex((x) => x.id === a.id) === i));
    if (!list.length || settings.xActive === acc.id) settings.xActive = saved.id;
    return saved;
  },
  remove(id) {
    store(accounts.all().filter((a) => a.id !== id));
    if (settings.xActive === id) settings.xActive = accounts.all()[0]?.id || null;
  },
  /** When the list last changed (here, or through sync on another device). */
  updatedAt: () => settings.xAccountsUpdatedAt || 0,
  /** The list as synced from another device. */
  setFromCloud(list, updatedAt) {
    const fixed = list.map((a) => ({ ...a, id: stableAccountId(a.host, a.user) }))
      .filter((a, i, arr) => arr.findIndex((x) => x.id === a.id) === i);
    settings.xAccounts = fixed;
    settings.xAccountsUpdatedAt = updatedAt;
  },
  label: (a) => (a ? a.name || a.host.replace(/^https?:\/\//, "").split("/")[0] : ""),
};

function store(list) {
  const at = Date.now();
  settings.xAccounts = list;
  settings.xAccountsUpdatedAt = at;
  emit("xtream", list, at);
}

export const xtreamReady = () => accounts.all().length > 0;

data.removedWatch ??= {};

/** What other devices should see change: adding, removing, the status. */
const visibleChange = (old, item) => !old || old.status !== item.status;

export const watchlist = {
  all: () => data.watchlist,
  get: (key) => data.watchlist.find((w) => w.key === key),
  upsert(item) {
    const old = watchlist.get(item.key);
    const changed = visibleChange(old, item);
    const stamped = { ...item, updatedAt: changed ? Date.now() : old?.updatedAt || item.updatedAt || 0 };
    data.watchlist = [stamped, ...data.watchlist.filter((w) => w.key !== item.key)];
    delete data.removedWatch[item.key];
    save();
    if (changed) emit("watchlist", stamped, stamped.key, null);
  },
  remove(key) {
    const at = Date.now();
    data.watchlist = data.watchlist.filter((w) => w.key !== key);
    data.removedWatch[key] = at;
    save();
    emit("watchlist", null, key, at);
  },
  removed: () => data.removedWatch,
  /** A change from another device, kept when newer than this browser's copy. */
  applyRemote(key, item, updatedAt) {
    const local = watchlist.get(key);
    if (local && (local.updatedAt || 0) >= updatedAt) return false;
    if (!local && (data.removedWatch[key] || 0) >= updatedAt) return false;
    if (!item) {
      if (!local) return false;
      data.watchlist = data.watchlist.filter((w) => w.key !== key);
      data.removedWatch[key] = updatedAt;
    } else {
      data.watchlist = [{ ...local, ...item, updatedAt }, ...data.watchlist.filter((w) => w.key !== key)];
      delete data.removedWatch[key];
    }
    save();
    return true;
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
// Removed keys → when (older versions kept a plain list).
if (!data.removedHistory || Array.isArray(data.removedHistory)) {
  data.removedHistory = Object.fromEntries((data.removedHistory || []).map((k) => [k, Date.now()]));
}
// Entries re-pointed to the stable account ids.
if (Object.keys(accountIdChanges).length) {
  data.watchHistory = data.watchHistory.map((e) => {
    const to = accountIdChanges[e.accountId];
    return to ? { ...e, accountId: to, key: to + e.key.slice(String(e.accountId).length) } : e;
  }).filter((e, i, arr) => arr.findIndex((x) => x.key === e.key) === i);
  save();
}

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
    const removed = data.removedHistory || {};
    data.watchHistory = [...byKey.values()].filter((e) => !(removed[e.key] >= (e.updatedAt || 0)))
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
  save(entry, important = false) {
    const old = history.get(entry.key);
    const merged = {
      ...entry,
      positionMs: entry.positionMs > 0 || entry.durationMs > 0 ? entry.positionMs || 0 : old?.positionMs || 0,
      durationMs: entry.durationMs > 0 ? entry.durationMs : old?.durationMs || 0,
      updatedAt: Date.now(),
    };
    data.watchHistory = [merged, ...data.watchHistory.filter((e) => e.key !== entry.key)].slice(0, 80);
    delete data.removedHistory[entry.key];
    save();
    persistHistory();
    emit("history", merged, merged.key, null, important);
    return merged;
  },
  remove(key) {
    const at = Date.now();
    data.watchHistory = data.watchHistory.filter((e) => e.key !== key);
    // Remembered so an older copy (another device, or the IndexedDB copy) cannot bring it back.
    data.removedHistory[key] = at;
    save();
    persistHistory();
    emit("history", null, key, at, true);
  },
  removed: () => data.removedHistory,
  /** A change from another device (through the account), kept when newer. Nothing is sent back. */
  applyRemote(key, entry, updatedAt) {
    const local = history.get(key);
    if (local && (local.updatedAt || 0) >= updatedAt) return false;
    if (!local && (data.removedHistory[key] || 0) >= updatedAt) return false;
    if (!entry) {
      if (!local) return false;
      data.watchHistory = data.watchHistory.filter((e) => e.key !== key);
      data.removedHistory[key] = updatedAt;
    } else {
      data.watchHistory = [...data.watchHistory.filter((e) => e.key !== key), { ...entry, updatedAt }]
        .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0)).slice(0, 80);
      delete data.removedHistory[key];
    }
    save();
    persistHistory();
    return true;
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
