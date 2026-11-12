// The Watch Hub account on the web: Google sign-in, and keeping "قائمتي", "تابع المشاهدة"
// (with the exact position) and the Xtream accounts the same as on the phone and TV.
//
// Same Firestore documents as the Android app (CloudSync.kt), under users/<uid>/:
//   history/<entry key>, watchlist/<type:id>, meta/xtream — each with updatedAt (ms); the newest
//   copy wins; removals are { deleted: true }. Settings come from the site's /config.json.
import { history, watchlist, accounts, onLocalChange, currentProfile, labelProfileIfNew, switchProfile, clearProfileData } from "./store.js";

const SDK = "https://www.gstatic.com/firebasejs/10.14.1";
const PROGRESS_EVERY_MS = 10000;

let cfg = null;
let fb = null; // { auth, db, fs: firestore module, au: auth module }
let uid = null;
let unsubs = [];
let state = { status: "loading" }; // loading | off | signedOut | signedIn
let lastSync = 0;
const listeners = new Set();

export const getState = () => ({ ...state, lastSync });
export function onStateChange(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}
function setState(s) {
  state = s;
  listeners.forEach((fn) => fn(getState()));
}
function synced() {
  lastSync = Date.now();
  listeners.forEach((fn) => fn(getState()));
  window.dispatchEvent(new Event("watchhub:history"));
}

const docId = (key) => String(key).replace(/\//g, "_");

// ---------- start ----------

export async function start() {
  try {
    const res = await fetch("/config.json", { cache: "no-store" });
    cfg = await res.json();
  } catch {
    cfg = null;
  }
  if (!cfg || !cfg.enabled || !cfg.firebase) {
    setState({ status: "off" });
    return;
  }
  try {
    const [app, au, fs] = await Promise.all([
      import(`${SDK}/firebase-app.js`),
      import(`${SDK}/firebase-auth.js`),
      import(`${SDK}/firebase-firestore.js`),
    ]);
    const firebaseApp = app.initializeApp({
      apiKey: cfg.firebase.apiKey,
      authDomain: cfg.firebase.authDomain,
      projectId: cfg.firebase.projectId,
      appId: cfg.firebase.appId,
    });
    const auth = au.getAuth(firebaseApp);
    const db = fs.getFirestore(firebaseApp);
    if (cfg.emulatorHost) {
      // Local testing only (see server.ts WATCHHUB_EMULATOR_HOST).
      au.connectAuthEmulator(auth, `http://${cfg.emulatorHost}:9099`, { disableWarnings: true });
      fs.connectFirestoreEmulator(db, cfg.emulatorHost, 8085);
    }
    await au.setPersistence(auth, au.browserLocalPersistence).catch(() => {});
    fb = { auth, db, fs, au };
    // Stays "loading" until Firebase says who is signed in (it restores the session first).
    au.onAuthStateChanged(auth, (user) => onUser(user));
    // A finished redirect sign-in (when the popup was blocked) lands here.
    au.getRedirectResult(auth).catch(() => {});
  } catch (e) {
    console.warn("account start failed", e);
    setState({ status: "off" });
  }
  onLocalChange(onLocal);
}

export const deviceLoginAvailable = () => Boolean(cfg && cfg.deviceLogin);

function profileChanged() {
  window.dispatchEvent(new Event("watchhub:profile"));
}

async function onUser(user) {
  labelProfileIfNew(user ? `u:${user.uid}` : "guest");
  if (!user) {
    stop();
    uid = null;
    setState({ status: "signedOut" });
    // Nothing of the account stays in this browser: back to the guest's own data.
    if (currentProfile() !== "guest") {
      await switchProfile("guest", true);
      profileChanged();
    }
    return;
  }
  setState({ status: "signedIn", name: user.displayName, email: user.email, photo: user.photoURL });
  if (uid === user.uid) return;
  uid = user.uid;
  const mine = `u:${user.uid}`;
  if (currentProfile() !== mine) {
    // The guest's data is put aside, never copied into the account.
    await switchProfile(mine, currentProfile() !== "guest");
    profileChanged();
  }
  if (uid === user.uid) startSyncing();
}

// ---------- sign in / out ----------

export async function signInWithGoogle() {
  if (!fb) throw new Error("إعدادات الحساب ما وصلت");
  const { au, auth } = fb;
  const provider = new au.GoogleAuthProvider();
  provider.setCustomParameters({ prompt: "select_account" });
  try {
    await au.signInWithPopup(auth, provider);
  } catch (e) {
    if (e && (e.code === "auth/popup-blocked" || e.code === "auth/operation-not-supported-in-this-environment")) {
      await au.signInWithRedirect(auth, provider);
      return;
    }
    if (e && (e.code === "auth/popup-closed-by-user" || e.code === "auth/cancelled-popup-request")) throw new Error("انلغى تسجيل الدخول");
    if (e && e.code === "auth/unauthorized-domain") throw new Error("أضف رابط موقعك في Firebase › Authentication › Settings › Authorized domains");
    throw new Error(`ما ضبط الدخول (${(e && e.code) || "خطأ"}). جرّب «دخول برمز».`);
  }
}

/** Sign-in with a code at google.com/device (for when the popup can't work, e.g. an iPhone home-screen app). */
export async function startDeviceLogin() {
  const res = await fetch("/auth/device/start", { method: "POST" });
  const data = await res.json().catch(() => ({}));
  if (!data.userCode) throw new Error("الدخول برمز غير مفعّل في موقعك (GOOGLE_TV_CLIENT_ID)");
  return data;
}

export async function finishDeviceLogin(code, isCancelled) {
  let wait = Math.max(3, code.interval || 5) * 1000;
  const until = Date.now() + (code.expiresIn || 1800) * 1000;
  while (Date.now() < until && !isCancelled()) {
    await new Promise((r) => setTimeout(r, wait));
    if (isCancelled()) return false;
    let data;
    try {
      const res = await fetch("/auth/device/poll", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ deviceCode: code.deviceCode }),
      });
      data = await res.json();
    } catch {
      continue;
    }
    if (data.status === "ok") {
      await fb.au.signInWithCredential(fb.auth, fb.au.GoogleAuthProvider.credential(data.idToken));
      return true;
    }
    if (data.status === "slow_down") wait += 2000;
    if (data.status === "denied" || data.status === "expired") return false;
  }
  return false;
}

export async function signOut() {
  if (!fb) return;
  await flush(); // positions still waiting go out first
  await fb.au.signOut(fb.auth);
}

export const isGuest = () => currentProfile() === "guest";

/** Clears the guest's history, list, Xtream accounts and favourite channels in this browser. */
export async function clearGuestData() {
  if (!isGuest()) return;
  await clearProfileData();
  profileChanged();
}

// ---------- syncing ----------

const col = (name) => fb.fs.collection(fb.db, "users", uid, name);
const ref = (name, id) => fb.fs.doc(fb.db, "users", uid, name, docId(id));

function stop() {
  unsubs.forEach((u) => u());
  unsubs = [];
  Object.values(timers).forEach(clearTimeout);
  for (const k of Object.keys(timers)) delete timers[k];
}

async function startSyncing() {
  stop();
  const mine = uid;
  const { fs } = fb;
  try {
    const [h, w, x] = await Promise.all([fs.getDocs(col("history")), fs.getDocs(col("watchlist")), fs.getDoc(ref("meta", "xtream"))]);
    if (uid !== mine) return;
    await mergeHistory(h.docs);
    await mergeWatchlist(w.docs);
    await mergeXtream(x);
    synced();
  } catch (e) {
    console.warn("first sync failed", e);
  }
  if (uid !== mine) return;
  unsubs.push(fs.onSnapshot(col("history"), (snap) => {
    if (snap.metadata.hasPendingWrites) return;
    let any = false;
    snap.docChanges().forEach((c) => (any = applyHistoryDoc(c.doc.data()) || any));
    if (any || !snap.metadata.fromCache) synced();
  }));
  unsubs.push(fs.onSnapshot(col("watchlist"), (snap) => {
    if (snap.metadata.hasPendingWrites) return;
    snap.docChanges().forEach((c) => applyWatchDoc(c.doc.data()));
    synced();
  }));
  unsubs.push(fs.onSnapshot(ref("meta", "xtream"), (snap) => {
    if (snap.metadata.hasPendingWrites) return;
    if (applyXtreamDoc(snap)) synced();
  }));
}

// ---- history

const HISTORY_FIELDS = ["key", "kind", "itemId", "title", "subtitle", "image", "ext", "seriesId", "positionMs", "durationMs", "updatedAt", "accountId", "tmdbId", "animeRef"];

function historyDoc(e) {
  const out = { deleted: false, device: "المتصفح" };
  for (const f of HISTORY_FIELDS) out[f] = e[f] ?? null;
  return out;
}

function applyHistoryDoc(d) {
  if (!d || !d.key || !d.updatedAt) return false;
  if (d.deleted) return history.applyRemote(d.key, null, d.updatedAt);
  const e = {};
  for (const f of HISTORY_FIELDS) if (d[f] !== null && d[f] !== undefined) e[f] = d[f];
  return history.applyRemote(d.key, e, d.updatedAt);
}

async function mergeHistory(docs) {
  const remote = new Map(docs.map((d) => [d.data().key, d.data()]));
  docs.forEach((d) => applyHistoryDoc(d.data()));
  const writes = [];
  for (const e of history.list()) {
    const r = remote.get(e.key);
    if (!r || (r.updatedAt || 0) < (e.updatedAt || 0)) writes.push(fb.fs.setDoc(ref("history", e.key), historyDoc(e)));
  }
  for (const [key, at] of Object.entries(history.removed())) {
    const r = remote.get(key);
    if (r && !r.deleted && (r.updatedAt || 0) < at) writes.push(fb.fs.setDoc(ref("history", key), { key, deleted: true, updatedAt: at }));
  }
  await Promise.all(writes);
}

const pending = {};
const timers = {};
const lastPush = {};

function onLocal(kind, item, key, removedAt, important) {
  if (!fb || !uid) return;
  if (kind === "history") {
    pending[key] = { item, removedAt };
    const since = Date.now() - (lastPush[key] || 0);
    if (important || since >= PROGRESS_EVERY_MS) {
      clearTimeout(timers[key]);
      delete timers[key];
      sendHistory(key);
    } else if (!timers[key]) {
      timers[key] = setTimeout(() => {
        delete timers[key];
        sendHistory(key);
      }, PROGRESS_EVERY_MS - since);
    }
  } else if (kind === "watchlist") {
    const data = item ? watchDoc(item) : { key, deleted: true, updatedAt: removedAt || Date.now() };
    fb.fs.setDoc(ref("watchlist", key), data, { merge: true }).then(synced).catch((e) => console.warn("watchlist push", e));
  } else if (kind === "xtream") {
    // (kind, list, updatedAt)
    pushXtream(item, key);
  }
}

function sendHistory(key) {
  const p = pending[key];
  if (!p) return Promise.resolve();
  delete pending[key];
  lastPush[key] = Date.now();
  const data = p.item ? historyDoc(p.item) : { key, deleted: true, updatedAt: p.removedAt || Date.now() };
  return fb.fs.setDoc(ref("history", key), data).then(synced).catch((e) => console.warn("history push", e));
}

/** Sends any position still waiting (before leaving the page for VLC, for example). */
export async function flush() {
  if (!fb || !uid) return;
  await Promise.all(Object.keys(pending).map((k) => {
    clearTimeout(timers[k]);
    delete timers[k];
    return sendHistory(k);
  }));
}

/** Right before playing: the newest position from the account (another device may have moved on). */
export async function refreshEntry(key, timeoutMs = 2000) {
  if (!fb || !uid) return;
  try {
    const snap = await Promise.race([fb.fs.getDoc(ref("history", key)), new Promise((_, rej) => setTimeout(() => rej(new Error("timeout")), timeoutMs))]);
    if (snap.exists()) applyHistoryDoc(snap.data());
  } catch { /* offline or slow: use this browser's copy */ }
}

// ---- watchlist (web items: key, type, id, title, poster, year, status, added)

function watchDoc(w) {
  return {
    key: w.key, type: w.type, id: Number(w.id), title: w.title || "", poster: w.poster || null,
    status: w.status || "want", addedAt: w.added || w.addedAt || Date.now(), updatedAt: w.updatedAt || Date.now(), deleted: false,
  };
}

function applyWatchDoc(d) {
  if (!d || !d.key || !d.updatedAt) return false;
  if (d.deleted) return watchlist.applyRemote(d.key, null, d.updatedAt);
  return watchlist.applyRemote(d.key, {
    key: d.key, type: d.type, id: d.id, title: d.title, poster: d.poster, status: d.status || "want", added: d.addedAt,
  }, d.updatedAt);
}

async function mergeWatchlist(docs) {
  const remote = new Map(docs.map((d) => [d.data().key, d.data()]));
  docs.forEach((d) => applyWatchDoc(d.data()));
  const writes = [];
  for (const w of watchlist.all()) {
    const stamped = { ...w, updatedAt: w.updatedAt || w.added || Date.now() };
    const r = remote.get(w.key);
    if (!r || (r.updatedAt || 0) < stamped.updatedAt) writes.push(fb.fs.setDoc(ref("watchlist", w.key), watchDoc(stamped), { merge: true }));
  }
  for (const [key, at] of Object.entries(watchlist.removed())) {
    const r = remote.get(key);
    if (r && !r.deleted && (r.updatedAt || 0) < at) writes.push(fb.fs.setDoc(ref("watchlist", key), { key, deleted: true, updatedAt: at }, { merge: true }));
  }
  await Promise.all(writes);
}

// ---- Xtream accounts

const accountDoc = (list, at) => ({
  accounts: list.map((a) => ({ id: a.id, name: a.name || "", host: a.host, user: a.user, pass: a.pass })),
  updatedAt: at,
});

function applyXtreamDoc(snap) {
  if (!snap.exists()) return false;
  const d = snap.data();
  if (!d.updatedAt || d.updatedAt <= accounts.updatedAt()) return false;
  accounts.setFromCloud((d.accounts || []).filter((a) => a.host && a.user), d.updatedAt);
  return true;
}

async function mergeXtream(snap) {
  const remoteAt = snap.exists() ? snap.data().updatedAt || 0 : 0;
  if (remoteAt > accounts.updatedAt()) return applyXtreamDoc(snap);
  if (accounts.all().length) {
    const at = accounts.updatedAt() || Date.now();
    await fb.fs.setDoc(ref("meta", "xtream"), accountDoc(accounts.all(), at), { merge: true });
  }
  return false;
}

function pushXtream(list, at) {
  fb.fs.setDoc(ref("meta", "xtream"), accountDoc(list, at)).then(synced).catch((e) => console.warn("xtream push", e));
}


// ---------- "scan to sign in": approving a TV from this browser ----------

/** Which device is asking (null when the link expired). */
export async function pairingDevice(id) {
  try {
    const res = await fetch(`/pair/info?id=${encodeURIComponent(id)}`, { cache: "no-store" });
    const data = await res.json();
    return data.status === "pending" ? data.device || "جهاز" : null;
  } catch {
    return null;
  }
}

/** Lets the device in to this account ([approve]) or refuses it. */
export async function answerPairing(id, approve) {
  const user = fb && fb.auth.currentUser;
  if (!user) throw new Error("سجّل دخولك أول");
  const idToken = await user.getIdToken();
  const res = await fetch("/pair/approve", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ id, idToken, approve }),
  });
  const data = await res.json().catch(() => ({}));
  if (approve && data.status !== "approved") throw new Error("انتهت صلاحية الرمز، اطلب رمز جديد من التلفاز");
}

/** Resolves once the account state is known (after start). */
export function ready() {
  if (state.status !== "loading") return Promise.resolve(getState());
  return new Promise((resolve) => {
    const off = onStateChange((st) => {
      if (st.status !== "loading") {
        off();
        resolve(st);
      }
    });
  });
}
