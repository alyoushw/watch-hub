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

export const xtreamReady = () => Boolean(settings.xHost && settings.xUser && settings.xPass);

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
 * Watch history for the current Xtream login: what was played (live, film, episode) and how far,
 * for "تابع المشاهدة" and resuming. Same shape as the Android app's history.
 */
data.history ??= {};
const account = () => `${settings.xHost || ""}|${settings.xUser || ""}`;

export const history = {
  list: () => data.history[account()] || [],
  get: (key) => history.list().find((e) => e.key === key),
  save(entry) {
    const old = history.get(entry.key);
    const merged = {
      ...entry,
      positionMs: entry.positionMs > 0 || entry.durationMs > 0 ? entry.positionMs || 0 : old?.positionMs || 0,
      durationMs: entry.durationMs > 0 ? entry.durationMs : old?.durationMs || 0,
      updatedAt: Date.now(),
    };
    data.history[account()] = [merged, ...history.list().filter((e) => e.key !== entry.key)].slice(0, 80);
    save();
  },
  remove(key) {
    data.history[account()] = history.list().filter((e) => e.key !== key);
    save();
  },
  /** One card per series (its latest episode); finished films left out. */
  continueList() {
    const series = new Set();
    return history.list().filter((e) => {
      if (e.kind === "episode" && e.seriesId) {
        if (series.has(e.seriesId)) return false;
        series.add(e.seriesId);
        return true;
      }
      return e.kind !== "movie" || !isFinished(e);
    });
  },
};

export const isFinished = (e) => e.durationMs > 0 && e.positionMs >= e.durationMs * 0.95;
export const progressOf = (e) => (e.durationMs > 0 ? Math.min(1, e.positionMs / e.durationMs) : 0);
