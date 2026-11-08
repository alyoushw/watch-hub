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
