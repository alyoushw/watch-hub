// Watch Hub on the web: the same hub as the Android app, for iPhone, iPad and computers.
import * as tmdb from "./tmdb.js";
import * as foryou from "./foryou.js";
import * as xt from "./xtream.js";
import { play, fmt } from "./player.js";
import * as smart from "./smart.js";
import { fillTranslated } from "./translate.js";
import * as cloud from "./cloud.js";
import { settings, watchlist, favorites, folders, recent, xtreamReady, history, isFinished, progressOf, accounts, restoreHistory } from "./store.js";

const view = document.getElementById("view");

// ---------- helpers ----------

const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const $ = (sel, root = document) => root.querySelector(sel);
const SEARCH_ICON = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>`;
const loading = () => `<div class="center"><div class="spinner"></div></div>`;

function message(text, action) {
  return `<div class="center"><p class="muted" style="max-width:460px;line-height:1.8">${esc(text)}</p>${action || ""}</div>`;
}

const goSettings = `<a class="btn" href="#/settings">افتح الإعدادات</a>`;

let toastTimer;
function toast(text) {
  const t = $("#toast");
  t.textContent = text;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.hidden = true), 2200);
}

const fmtTime = (d) => d ? d.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" }) : "";
const fmtDate = (d) => d ? d.toLocaleDateString("en-CA").replace(/-/g, "/") : "";
const titleOf = (r) => r.title || r.name || "";
const yearOf = (r) => (r.release_date || r.first_air_date || "").slice(0, 4);

function card(r, rank) {
  const type = r.media_type || (r.title ? "movie" : "tv");
  const poster = tmdb.image(r.poster_path);
  const rating = r.vote_average ? `<span class="badge">★ ${r.vote_average.toFixed(1)}</span>` : "";
  return `<a class="card" href="#/title/${type}/${r.id}">
    <div class="poster">${poster ? `<img loading="lazy" src="${poster}" alt="">` : ""}${rating}${rank ? `<span class="rank">${rank}</span>` : ""}</div>
    <div class="name">${esc(titleOf(r))}</div>
  </a>`;
}

function rowSection(title, items, ranked = false) {
  const list = items.filter((r) => r.poster_path);
  if (!list.length) return "";
  return `<h2 class="section-title">${esc(title)}</h2>
    <div class="row">${list.slice(0, 20).map((r, i) => card(r, ranked && i < 10 ? i + 1 : 0)).join("")}</div>`;
}

/** Long-press on touch screens (iOS has no contextmenu on links). */
function onLongPress(el, fn) {
  let timer = null;
  let fired = false;
  el.addEventListener("pointerdown", (e) => {
    fired = false;
    timer = setTimeout(() => {
      fired = true;
      fn(e);
    }, 550);
  });
  ["pointerup", "pointerleave", "pointercancel", "pointermove"].forEach((t) =>
    el.addEventListener(t, (e) => {
      if (t === "pointermove" && Math.abs(e.movementX) + Math.abs(e.movementY) < 4) return;
      clearTimeout(timer);
    }));
  el.addEventListener("click", (e) => {
    if (fired) {
      e.preventDefault();
      e.stopImmediatePropagation();
    }
  }, true);
}

// ---------- look: dark / light, accent colour (same choices as the Android app) ----------

const ACCENTS = [["gold", "ذهبي", "#e8a33d"], ["red", "أحمر", "#e5484d"], ["blue", "أزرق", "#3b8beb"],
  ["green", "أخضر", "#2fb36b"], ["purple", "بنفسجي", "#9b6bef"], ["pink", "وردي", "#e5578f"]];

function applyTheme() {
  const mode = settings.themeMode || "dark";
  const dark = mode === "dark" || (mode === "system" && matchMedia("(prefers-color-scheme: dark)").matches);
  const root = document.documentElement;
  root.dataset.theme = dark ? "dark" : "light";
  const hex = (ACCENTS.find((a) => a[0] === settings.accent) || ACCENTS[0])[2];
  let [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
  // A deeper accent on the light look so it reads on white (as the Android app does).
  if (!dark) [r, g, b] = [r, g, b].map((v) => Math.round(v * 0.82));
  root.style.setProperty("--gold", `rgb(${r}, ${g}, ${b})`);
  root.style.setProperty("--gold-rgb", `${r}, ${g}, ${b}`);
  document.querySelector('meta[name="theme-color"]')?.setAttribute("content", dark ? "#0e0d0b" : "#faf8f5");
}
applyTheme();
matchMedia("(prefers-color-scheme: dark)").addEventListener?.("change", applyTheme);

// ---------- router ----------

const routes = [
  [/^#?\/?$/, home, "home"],
  [/^#\/live$/, live, "live"],
  [/^#\/search(?:\?q=(.*))?$/, searchPage, "search"],
  [/^#\/title\/(movie|tv)\/(\d+)$/, titlePage, null],
  [/^#\/person\/(\d+)$/, personPage, null],
  [/^#\/list$/, listPage, "list"],
  [/^#\/xtream(?:\/(movie|series))?$/, xtreamPage, "xtream"],
  [/^#\/xseries\/(.+)$/, xseriesPage, "xtream"],
  [/^#\/settings$/, settingsPage, "settings"],
  [/^#\/pair\?s=(.*)$/, pairPage, null],
];

let renderId = 0;
async function route() {
  const hash = location.hash || "#/";
  const id = ++renderId;
  for (const [re, fn, tab] of routes) {
    const m = hash.match(re);
    if (!m) continue;
    document.querySelectorAll("#tabs a").forEach((a) => a.classList.toggle("on", a.dataset.tab === tab));
    view.innerHTML = loading();
    window.scrollTo(0, 0);
    try {
      await fn(id, ...m.slice(1).map((x) => (x === undefined ? x : decodeURIComponent(x))));
    } catch (e) {
      if (id === renderId) view.innerHTML = message(e.message || "صار خطأ", `<button class="btn" onclick="location.reload()">حاول مرة ثانية</button>`);
    }
    return;
  }
  location.hash = "#/";
}
const stale = (id) => id !== renderId;

window.addEventListener("hashchange", route);

// ---------- home ----------

async function home(id) {
  const type = sessionStorage.getItem("homeType") || "tv";
  const tabs = `<div class="typetabs">
      <button data-go="live">مباشر</button>
      <button data-type="tv" class="${type === "tv" ? "on" : ""}">مسلسلات</button>
      <button data-type="movie" class="${type === "movie" ? "on" : ""}">أفلام</button>
    </div>`;

  if (!settings.tmdbKey) {
    view.innerHTML = tabs + continueRow(type) + message("خطوة وحدة قبل نبدأ: حط مفتاح TMDB المجاني في الإعدادات عشان نعرض لك أفضل الأعمال في كل منصة.", goSettings);
    bindTypeTabs();
    bindContinue();
    return;
  }

  // "تابع المشاهدة" shows at once, before (and whatever happens to) the TMDB rows.
  view.innerHTML = `${tabs}${continueRow(type)}<div id="home-rest">${loading()}</div>`;
  bindTypeTabs();
  bindContinue();
  let trend;
  let ids;
  try {
    [trend, ids] = await Promise.all([tmdb.trending(type), tmdb.regionServiceIds(type)]);
  } catch (e) {
    if (!stale(id)) $("#home-rest").innerHTML = message(e.message || "صار خطأ", `<button class="btn" onclick="location.reload()">حاول مرة ثانية</button>`);
    return;
  }
  if (stale(id)) return;
  const hero = trend.find((r) => r.backdrop_path) || trend[0];
  const word = type === "tv" ? "المسلسلات" : "الأفلام";

  view.innerHTML = `${tabs}
    ${continueRow(type)}
    <div id="foryou"></div>
    ${hero ? `<section class="hero">
      ${hero.backdrop_path ? `<img class="bg" src="${tmdb.image(hero.backdrop_path, "w1280")}" alt="">` : ""}
      <div class="info">
        <span class="muted">الأكثر رواجاً هذا الأسبوع</span>
        <h1>${esc(titleOf(hero))}</h1>
        <p>${esc(hero.overview || "")}</p>
        <div class="actions"><a class="btn" href="#/title/${type}/${hero.id}">▶ التفاصيل والمشاهدة</a></div>
      </div>
    </section>` : ""}
    ${rowSection(`${word} الرائجة`, trend.slice(1))}
    <div id="rows">${loading()}</div>`;
  bindTypeTabs();
  bindContinue();
  renderForYou(type, id);

  const services = tmdb.SERVICES
    .map((s) => ({ ...s, all: [...new Set([...s.ids, ...(ids[s.key] || [])])] }))
    .filter((s) => s.all.length && (type === "tv" || s.key !== "crunchyroll"));
  const jobs = [
    ...services.map((s) => tmdb.discover(type, { with_watch_providers: s.all.join("|"), sort_by: "popularity.desc" })
      .then((r) => rowSection(`أفضل 10 على ${s.label}`, r, true)).catch(() => "")),
    tmdb.discover(type, { with_original_language: "ar", sort_by: "popularity.desc" })
      .then((r) => rowSection(`${word} العربية`, r)).catch(() => ""),
    tmdb.discover(type, { sort_by: "vote_average.desc", "vote_count.gte": "400" })
      .then((r) => rowSection("الأعلى تقييماً", r)).catch(() => ""),
  ];
  const html = await Promise.all(jobs);
  if (stale(id)) return;
  $("#rows").innerHTML = html.join("");
}

function bindTypeTabs() {
  view.querySelectorAll(".typetabs button").forEach((b) =>
    b.addEventListener("click", () => {
      if (b.dataset.go) return (location.hash = "#/live");
      sessionStorage.setItem("homeType", b.dataset.type);
      route();
    }));
}

// A sideways swipe on empty space of the home page moves between مباشر · مسلسلات · أفلام
// (like the Android app). Rows that scroll sideways keep their own swipe.
(() => {
  let start = null;
  const tabs = ["live", "tv", "movie"];
  const current = () => (location.hash === "#/live" ? 0 : tabs.indexOf(sessionStorage.getItem("homeType") || "tv"));
  view.addEventListener("touchstart", (e) => {
    const onHome = location.hash === "#/live" || /^#?\/?$/.test(location.hash);
    const t = e.touches[0];
    start = onHome && e.touches.length === 1 && !e.target.closest(".row, .chips, .typetabs, input, video") ? { x: t.clientX, y: t.clientY } : null;
  }, { passive: true });
  view.addEventListener("touchend", (e) => {
    if (!start) return;
    const t = e.changedTouches[0];
    const dx = t.clientX - start.x;
    const dy = t.clientY - start.y;
    start = null;
    if (Math.abs(dx) < 90 || Math.abs(dx) < Math.abs(dy) * 2) return;
    // Right-to-left tabs: a swipe to the right goes to the next one (on the left).
    const next = Math.max(0, Math.min(tabs.length - 1, current() + (dx > 0 ? 1 : -1)));
    if (next === current()) return;
    if (next === 0) {
      location.hash = "#/live";
      return;
    }
    sessionStorage.setItem("homeType", tabs[next]);
    if (location.hash === "#/live") location.hash = "#/";
    else route();
  }, { passive: true });
})();

// ---------- search ----------

/** Saved filter presets ("أنماطي"), like the Android app. */
const presets = {
  all: () => settings.savedFilters || [],
  save(name, filter) {
    settings.savedFilters = [{ name, filter }, ...presets.all().filter((p) => p.name !== name)].slice(0, 20);
  },
  remove(name) {
    settings.savedFilters = presets.all().filter((p) => p.name !== name);
  },
};

async function searchPage(id, q = "") {
  let filter = smart.emptyFilter();
  view.innerHTML = `<h2 class="section-title" style="margin-top:6px">وش تبي تشوف؟</h2>
    <div style="display:flex;gap:8px;align-items:center">
      <label class="pill" style="flex:1">${SEARCH_ICON}
        <input id="q" type="search" placeholder="اكتب: «فيلم رعب إندونيسي» أو اسم عمل" value="${esc(q)}" autocomplete="off" enterkeyhint="search"></label>
      <button class="btn ghost" id="open-filters">⚙ الفلاتر</button>
    </div>
    <div class="chips scroll" id="quick" style="margin-top:10px"></div>
    <div id="filters" hidden></div>
    <div id="understood"></div>
    <div id="results"></div>`;
  const input = $("#q");
  const results = $("#results");
  const understoodEl = $("#understood");
  let seq = 0;

  const renderQuick = () => {
    $("#quick").innerHTML = [
      ...(watchlist.all().length ? [`<button class="chip" data-ask="رشّح لي من قائمتي">✨ من قائمتي</button>`] : []),
      ...presets.all().map((p, i) => `<button class="chip" data-preset="${i}">★ ${esc(p.name)}</button>`),
    ].join("");
    $("#quick").querySelectorAll("[data-ask]").forEach((b) => b.addEventListener("click", () => ask(b.dataset.ask)));
    $("#quick").querySelectorAll("[data-preset]").forEach((b) => b.addEventListener("click", () => {
      const p = presets.all()[Number(b.dataset.preset)];
      applyFilter(p.filter, `نمطك «${p.name}»`);
    }));
  };

  const idle = () => {
    understoodEl.innerHTML = "";
    results.innerHTML = `<div style="margin-top:20px"><b>اطلب بكلامك العادي</b>
      <p class="muted" style="margin:6px 0 12px">جرّب:</p>
      <div class="chips">${smart.EXAMPLES.map((e) => `<button class="chip" data-ex="${esc(e)}">${esc(e)}</button>`).join("")}</div></div>`;
    results.querySelectorAll("[data-ex]").forEach((b) => b.addEventListener("click", () => {
      input.value = b.dataset.ex;
      ask(b.dataset.ex);
    }));
  };

  const showUnderstood = (chips, f) => {
    understoodEl.innerHTML = `<div class="understood"><span class="muted">فهمت:</span>
      ${chips.map((c) => `<span class="chip on">${esc(c)}</span>`).join("")}
      ${f ? `<button class="linklike" id="edit-f">عدّل</button><button class="linklike" id="save-f">★ احفظه كنمط</button>` : ""}</div>`;
    $("#edit-f")?.addEventListener("click", () => openFilters(f));
    $("#save-f")?.addEventListener("click", () => {
      const name = prompt("اسم النمط:", smart.summary(f).slice(1).join(" ") || "نمطي");
      if (name && name.trim()) {
        presets.save(name.trim(), f);
        renderQuick();
        toast("انحفظ النمط ✓");
      }
    });
  };

  const grid = (list, title) => list.length
    ? `<h2 class="section-title">${esc(title)} <small>${list.length}</small></h2><div class="grid">${list.map((r) => card(r)).join("")}</div>`
    : "";

  /** Several discover pages, keeping only titles that really fit (TMDB's filter is loose). */
  async function strict(f, wanted = 40) {
    const ids = f.services.some((k) => k === "shahid" || k === "stctv") ? await tmdb.regionServiceIds(f.type) : {};
    const params = smart.toParams(f, ids);
    const out = [];
    for (let page = 1; page <= 4 && out.length < wanted; page++) {
      const r = await tmdb.discoverPage(f.type, params, page, f.services.length > 0);
      out.push(...r.results.filter((x) => x.poster_path && smart.matches(f, x)));
      if (page >= r.total_pages) break;
    }
    return out.filter((x, i, a) => a.findIndex((y) => y.id === x.id) === i);
  }

  async function applyFilter(f, heading) {
    const mine = ++seq;
    filter = f;
    $("#filters").hidden = true;
    showUnderstood(smart.summary(f), f);
    results.innerHTML = loading();
    try {
      const list = await strict(f);
      if (mine !== seq) return;
      results.innerHTML = grid(list, heading || "النتائج حسب الفلتر") || message("ما لقينا أعمال بهذي الفلاتر. جرّب تخفف شوي.");
    } catch (e) {
      if (mine === seq) results.innerHTML = message(e.message);
    }
  }

  async function similar(qq) {
    const mine = ++seq;
    showUnderstood([`مشابه لـ «${qq.title}»`]);
    results.innerHTML = loading();
    try {
      const words = qq.title.split(/\s+/);
      let seed = null;
      for (let n = words.length; n >= 1 && !seed; n--) {
        seed = (await tmdb.search(words.slice(0, n).join(" "))).find((r) => !qq.type || r.media_type === qq.type) || null;
      }
      if (mine !== seq) return;
      if (!seed) {
        results.innerHTML = message(`ما لقينا «${qq.title}». تأكد من الاسم.`);
        return;
      }
      const type = seed.media_type;
      const main = (seed.genre_ids || []).slice(0, 2);
      const lang = qq.origin ? smart.ORIGINS.find((o) => o.key === qq.origin).lang : seed.original_language;
      const [recs, close] = await Promise.all([
        tmdb.recommendations(type, seed.id).catch(() => []),
        main.length ? tmdb.discoverPage(type, { with_genres: main.join(","), with_original_language: lang, sort_by: "popularity.desc", "vote_count.gte": "20" }, 1, false).then((r) => r.results).catch(() => []) : [],
      ]);
      if (mine !== seq) return;
      const sameLang = (r) => r.original_language === lang;
      const list = [...recs.filter(sameLang), ...close, ...recs]
        .filter((r, i, a) => r.id !== seed.id && r.poster_path && a.findIndex((x) => x.id === r.id) === i).slice(0, 40);
      results.innerHTML = grid(list, `الأقرب لـ «${titleOf(seed)}»`) || message("ما لقينا أعمال مشابهة");
    } catch (e) {
      if (mine === seq) results.innerHTML = message(e.message);
    }
  }

  async function fromWatchlist() {
    const mine = ++seq;
    showUnderstood(["✨ من قائمتي"]);
    const items = watchlist.all().slice(0, 6);
    if (!items.length) {
      results.innerHTML = message("قائمتك فاضية. أضف أعمال تحبها وبعدين اطلب ترشيحات.");
      return;
    }
    results.innerHTML = loading();
    const lists = await Promise.all(items.map((w) => tmdb.recommendations(w.type, w.id).catch(() => [])));
    if (mine !== seq) return;
    const have = new Set(watchlist.all().map((w) => `${w.type}:${w.id}`));
    const score = new Map();
    lists.flat().forEach((r) => {
      const k = `${r.media_type}:${r.id}`;
      if (have.has(k) || !r.poster_path) return;
      const e = score.get(k) || { r, n: 0 };
      e.n++;
      score.set(k, e);
    });
    const list = [...score.values()].sort((a, b) => b.n - a.n || (b.r.popularity || 0) - (a.r.popularity || 0)).map((e) => e.r).slice(0, 40);
    results.innerHTML = grid(list, "مرشّح لك حسب قائمتك") || message("ما لقينا ترشيحات");
  }

  async function plain(text) {
    const mine = ++seq;
    understoodEl.innerHTML = "";
    if (!settings.tmdbKey) {
      results.innerHTML = message("أضف مفتاح TMDB من الإعدادات أول", goSettings);
      return;
    }
    results.innerHTML = loading();
    try {
      const tm = (await tmdb.search(text)).filter((r) => r.poster_path);
      if (mine !== seq) return;
      results.innerHTML = grid(tm, "النتائج") || message("ما لقينا شي بهذا الاسم");
    } catch (e) {
      if (mine === seq) results.innerHTML = message(e.message);
    }
  }

  function ask(text) {
    window.history.replaceState(null, "", `#/search?q=${encodeURIComponent(text)}`);
    if (!text.trim()) return idle();
    const qq = smart.parse(text);
    if (!settings.tmdbKey && qq.kind !== "plain") {
      results.innerHTML = message("الفلاتر والترشيحات تحتاج مفتاح TMDB من الإعدادات", goSettings);
      return;
    }
    if (qq.kind === "filter") applyFilter(qq.filter);
    else if (qq.kind === "similar") similar(qq);
    else if (qq.kind === "watchlist") fromWatchlist();
    else plain(qq.text);
  }

  // ---- the filters panel (same groups as the phone)
  function openFilters(start) {
    let f = JSON.parse(JSON.stringify(start || filter));
    if (f.type === "anime") f.type = "tv";
    const box = $("#filters");
    const chip = (on, label, attrs) => `<button class="chip ${on ? "on" : ""}" ${attrs}>${esc(label)}</button>`;
    const draw = () => {
      box.innerHTML = `<div class="filters">
        <div class="fg"><b>النوع</b><div class="chips">
          ${chip(f.type === "tv", "مسلسلات", 'data-type="tv"')}${chip(f.type === "movie", "أفلام", 'data-type="movie"')}</div></div>
        <div class="fg"><b>التصنيف <small class="muted">(تقدر تختار أكثر من واحد)</small></b><div class="chips">
          ${smart.GENRES.map((g) => chip(f.genres.includes(g.key), g.label, `data-genre="${g.key}"`)).join("")}</div></div>
        <div class="fg"><b>البلد / اللغة</b><div class="chips">
          ${chip(!f.origin, "الكل", 'data-origin=""')}${smart.ORIGINS.map((o) => chip(f.origin === o.key, o.label, `data-origin="${o.key}"`)).join("")}</div></div>
        <div class="fg"><b>المنصة</b><div class="chips">
          ${chip(!f.services.length, "أي منصة", 'data-service=""')}${tmdb.SERVICES.map((sv) => chip(f.services.includes(sv.key), sv.label, `data-service="${sv.key}"`)).join("")}</div></div>
        <div class="fg"><b>الترتيب</b><div class="chips">
          ${smart.SORTS.map(([k, l]) => chip(f.sort === k, l, `data-sort="${k}"`)).join("")}</div></div>
        <div class="fg"><b>الفترة</b><div class="chips">
          ${smart.ERAS.map(([l, a, b], i) => chip(f.yearFrom === a && f.yearTo === b, l, `data-era="${i}"`)).join("")}</div></div>
        <div class="fg"><b>أقل تقييم</b><div class="chips">
          ${[null, 6, 7, 8].map((r) => chip(f.minRating === r, r ? `${r}+` : "الكل", `data-rating="${r ?? ""}"`)).join("")}</div></div>
        <div style="display:flex;gap:8px;flex-wrap:wrap">
          <button class="btn" id="f-apply" style="flex:1">اعرض النتائج</button>
          <button class="btn ghost" id="f-save">★ احفظ</button>
          <button class="btn ghost" id="f-clear">مسح</button>
          <button class="btn ghost" id="f-close">إغلاق</button>
        </div>
        ${presets.all().length ? `<div class="fg"><b>أنماطي المحفوظة</b>${presets.all().map((p, i) => `<div class="preset">
            <button class="linklike" data-use="${i}">★ ${esc(p.name)}</button><small class="muted">${esc(smart.summary(p.filter).join(" · "))}</small>
            <button class="linklike danger" data-del="${i}">حذف</button></div>`).join("")}</div>` : ""}
      </div>`;
      const on = (sel, fn) => box.querySelectorAll(sel).forEach((b) => b.addEventListener("click", () => {
        fn(b);
        draw();
      }));
      on("[data-type]", (b) => (f.type = b.dataset.type));
      on("[data-genre]", (b) => (f.genres = f.genres.includes(b.dataset.genre) ? f.genres.filter((k) => k !== b.dataset.genre) : [...f.genres, b.dataset.genre]));
      on("[data-origin]", (b) => (f.origin = b.dataset.origin || null));
      on("[data-service]", (b) => (f.services = !b.dataset.service ? [] : f.services.includes(b.dataset.service) ? f.services.filter((k) => k !== b.dataset.service) : [...f.services, b.dataset.service]));
      on("[data-sort]", (b) => (f.sort = b.dataset.sort));
      on("[data-era]", (b) => ([, f.yearFrom, f.yearTo] = smart.ERAS[Number(b.dataset.era)]));
      on("[data-rating]", (b) => (f.minRating = b.dataset.rating ? Number(b.dataset.rating) : null));
      on("#f-clear", () => (f = smart.emptyFilter(f.type)));
      box.querySelector("#f-close").addEventListener("click", () => (box.hidden = true));
      box.querySelector("#f-apply").addEventListener("click", () => {
        if (!settings.tmdbKey) return toast("أضف مفتاح TMDB من الإعدادات أول");
        applyFilter(f);
      });
      box.querySelector("#f-save").addEventListener("click", () => {
        const name = prompt("اسم النمط:", smart.summary(f).slice(1).join(" ") || "نمطي");
        if (!name || !name.trim()) return;
        presets.save(name.trim(), f);
        renderQuick();
        draw();
        toast("انحفظ النمط ✓");
      });
      box.querySelectorAll("[data-use]").forEach((b) => b.addEventListener("click", () => {
        const p = presets.all()[Number(b.dataset.use)];
        applyFilter(p.filter, `نمطك «${p.name}»`);
      }));
      box.querySelectorAll("[data-del]").forEach((b) => b.addEventListener("click", () => {
        presets.remove(presets.all()[Number(b.dataset.del)].name);
        renderQuick();
        draw();
      }));
    };
    draw();
    box.hidden = false;
  }

  $("#open-filters").addEventListener("click", () => {
    const box = $("#filters");
    if (box.hidden) openFilters();
    else box.hidden = true;
  });

  let timer;
  input.addEventListener("input", () => {
    clearTimeout(timer);
    // Requests like "فيلم رعب إندونيسي" wait for Enter or a pause; plain titles search as you type.
    timer = setTimeout(() => ask(input.value), 700);
  });
  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      clearTimeout(timer);
      input.blur();
      ask(input.value);
    }
  });
  renderQuick();
  if (q) ask(q);
  else idle();
  input.focus();
}

// ---------- title page ----------

async function titlePage(id, type, tmdbId) {
  const [d, en] = await Promise.all([tmdb.details(type, tmdbId), tmdb.englishDetails(type, tmdbId)]);
  if (stale(id)) return;
  const title = titleOf(d);
  const enTitle = en ? titleOf(en) : title;
  const year = yearOf(d);
  const providers = ((d["watch/providers"] || {}).results || {})[settings.region] || {};
  const offered = [...(providers.flatrate || []), ...(providers.free || []), ...(providers.ads || [])];
  const services = [...new Set(offered.map(tmdb.serviceFor).filter(Boolean))];
  const genres = (d.genres || []).slice(0, 3).map((g) => g.name).join("، ");
  const kind = type === "tv" ? "مسلسل" : "فيلم";
  const key = `${type}:${tmdbId}`;
  const saved = watchlist.get(key);
  const trailers = ((d.videos || {}).results || []).filter((v) => v.site === "YouTube" && /Trailer|Teaser/.test(v.type)).slice(0, 4);
  const aggregate = ((d.aggregate_credits || {}).cast || []).slice().sort((a, b) => (a.order ?? 999) - (b.order ?? 999))
    .map((c) => ({ ...c, character: (c.roles || []).find((r) => r.character)?.character || "" }));
  const cast = (aggregate.length ? aggregate : ((d.credits || {}).cast || [])).slice(0, 25);
  const recs = ((d.recommendations || {}).results || []).map((r) => ({ ...r, media_type: r.media_type || type }));
  const overview = d.overview || (en && en.overview) || "";

  view.innerHTML = `
    <section class="title-head">
      ${d.backdrop_path ? `<img class="bg" src="${tmdb.image(d.backdrop_path, "w1280")}" alt="">` : ""}
      <div class="inner">
        <div class="poster">${d.poster_path ? `<img src="${tmdb.image(d.poster_path, "w500")}" alt="">` : ""}</div>
        <div class="meta">
          <h1>${esc(title)}</h1>
          ${enTitle && enTitle !== title ? `<div class="muted">${esc(enTitle)}</div>` : ""}
          <div class="muted">${[kind, year, genres].filter(Boolean).map(esc).join(" · ")}</div>
          ${d.vote_average ? `<div class="rating">★ ${d.vote_average.toFixed(1)} / 10 · ${(d.vote_count || 0).toLocaleString("en")} تقييم</div>` : ""}
          <div class="actions" style="display:flex;gap:10px;flex-wrap:wrap;margin-top:6px">
            <span id="xwatch"></span>
            <button id="save" class="btn ghost">${saved ? "✓ في قائمتي" : "+ أضفه لقائمتي"}</button>
          </div>
        </div>
      </div>
    </section>
    <div class="service-buttons">
      ${services.map((s) => `<a class="btn service" style="background:${s.color}" target="_blank" rel="noopener" href="${s.search}${encodeURIComponent(enTitle)}">▶ شاهد على ${esc(s.label)}</a>`).join("")}
      ${services.length ? "" : `<span class="muted">ما لقينا هذا العمل على منصات منطقتك (${esc(settings.region)}).</span>`}
    </div>
    ${d.tagline || (en && en.tagline) ? `<p class="muted" style="font-style:italic;margin:14px 0 0" id="tagline"></p>` : ""}
    ${overview ? `<h2 class="section-title">القصة</h2><p class="overview" id="overview"></p>` : ""}
    ${trailers.length ? `<h2 class="section-title">الإعلانات</h2><div class="trailers">${trailers.map((v) =>
      `<iframe loading="lazy" src="https://www.youtube-nocookie.com/embed/${esc(v.key)}" allow="encrypted-media; fullscreen; picture-in-picture" allowfullscreen title="${esc(v.name)}"></iframe>`).join("")}</div>` : ""}
    ${cast.length ? `<h2 class="section-title">الممثلين</h2><div class="cast">${cast.map((c) => `<a href="#/person/${c.id}" class="person">
        <div class="face">${c.profile_path ? `<img loading="lazy" src="${tmdb.image(c.profile_path, "w185")}" alt="">` : ""}</div>
        <div>${esc(c.name)}</div><div class="muted">${esc(c.character || "")}</div></a>`).join("")}</div>` : ""}
    ${rowSection("إذا عجبك، بيعجبك", recs)}
    <p class="muted" style="font-size:12px;margin-top:30px">البيانات من TMDB، وبيانات التوفر من JustWatch.</p>`;

  // English-only story or tagline: shown in Arabic (with a link to the original).
  if (overview) fillTranslated($("#overview"), overview);
  if ($("#tagline")) fillTranslated($("#tagline"), d.tagline || en.tagline);

  $("#save").addEventListener("click", (e) => {
    if (watchlist.get(key)) {
      watchlist.remove(key);
      e.target.textContent = "+ أضفه لقائمتي";
    } else {
      watchlist.upsert({ key, type, id: Number(tmdbId), title, poster: d.poster_path, year, status: "want", added: Date.now() });
      e.target.textContent = "✓ في قائمتي";
    }
  });

  if (xtreamReady()) xtreamWatchButton($("#xwatch"), type, tmdbId, [title, enTitle, d.original_title || d.original_name], year);
}

/** "مشاهدة" by the poster: one button for each Xtream account that has this title. */
async function xtreamWatchButton(slot, type, tmdbId, titles, year) {
  const list = accounts.all();
  const many = list.length > 1;
  slot.innerHTML = `<button class="btn" disabled>${many ? "نبحث في حساباتك…" : "نبحث في حسابك…"}</button>`;
  const kind = type === "tv" ? "series" : "movie";
  const found = (await Promise.all(list.map(async (acc) => {
    try {
      const m = await xt.findMatch(kind, tmdbId, titles, year, acc);
      return m ? { acc, m } : null;
    } catch {
      return null;
    }
  }))).filter(Boolean);
  if (!slot.isConnected) return;
  if (!found.length) {
    slot.innerHTML = `<button class="btn" disabled>${many ? "مو موجود في حساباتك" : "مو موجود في حسابك"}</button>`;
    return;
  }
  slot.style.display = "flex";
  slot.style.gap = "8px";
  slot.style.flexWrap = "wrap";
  slot.innerHTML = found.map(({ acc }, i) => `<button class="btn" data-i="${i}">▶ مشاهدة${many ? ` · ${esc(accounts.label(acc))}` : ""}</button>`).join("");
  slot.querySelectorAll("[data-i]").forEach((b) => b.addEventListener("click", () => {
    const { acc, m } = found[Number(b.dataset.i)];
    if (m.kind === "series") {
      accounts.setActive(acc.id);
      location.hash = `#/xseries/${encodeURIComponent(m.id)}`;
    } else {
      play(xt.playUrls(m), xt.entryMovie(m, Number(tmdbId)));
    }
  }));
}

// ---------- actor page ----------

/** A person's Wikipedia article (Arabic first, then English), as plain text. */
async function wikiBio(arabic, english) {
  const tries = [...[...new Set(arabic)].map((n) => ["ar", n]), ...[...new Set(english)].map((n) => ["en", n])];
  for (const [lang, title] of tries) {
    try {
      const u = `https://${lang}.wikipedia.org/w/api.php?action=query&prop=extracts|pageprops&explaintext=1&redirects=1&format=json&origin=*&titles=${encodeURIComponent(title)}`;
      const o = await (await fetch(u)).json();
      const page = Object.values(o.query?.pages || {})[0];
      if (!page || page.pageprops?.disambiguation !== undefined) continue;
      const text = (page.extract || "").trim();
      if (text.length < 80) continue;
      return { text: text.replace(/\n{3,}/g, "\n\n").slice(0, 6000), url: `https://${lang}.wikipedia.org/wiki/${encodeURIComponent(page.title.replace(/ /g, "_"))}` };
    } catch { /* next */ }
  }
  return null;
}

async function personPage(id, personId) {
  const [p, en] = await Promise.all([tmdb.person(personId), tmdb.personEnglish(personId)]);
  if (stale(id)) return;
  const credits = ((p.combined_credits || {}).cast || [])
    .filter((c) => (c.media_type === "movie" || c.media_type === "tv") && c.poster_path)
    .reduce((acc, c) => (acc.some((x) => x.id === c.id && x.media_type === c.media_type) ? acc : [...acc, c]), [])
    .sort((a, b) => (b.popularity || 0) - (a.popularity || 0));
  let bio = p.biography || (en && en.biography) || "";
  let wikiUrl = null;
  // Little or nothing on TMDB (common for Arab actors): the Wikipedia article.
  if (bio.length < 400) {
    const arabic = [p.name, ...(p.also_known_as || [])].filter((n) => /[\u0600-\u06FF]/.test(n));
    const english = [en?.name, p.name].filter((n) => n && !/[\u0600-\u06FF]/.test(n));
    const w = await wikiBio(arabic, english);
    if (stale(id)) return;
    if (w && w.text.length > bio.length) {
      bio = w.text;
      wikiUrl = w.url;
    }
  }
  const born = [p.birthday, p.place_of_birth].filter(Boolean).join(" · ");
  let tab = "all";
  const render = () => {
    const list = credits.filter((c) => tab === "all" || c.media_type === tab);
    $("#works").innerHTML = `<div class="grid">${list.map((c) => `<div>${card(c)}${c.character ? `<div class="muted" style="font-size:12px">${esc(c.character)}</div>` : ""}</div>`).join("")}</div>`;
    view.querySelectorAll("[data-t]").forEach((b) => b.classList.toggle("on", b.dataset.t === tab));
  };
  view.innerHTML = `<section class="title-head"><div class="inner">
      <div class="poster" style="width:180px">${p.profile_path ? `<img src="${tmdb.image(p.profile_path, "h632")}" alt="">` : ""}</div>
      <div class="meta">
        <h1>${esc(p.name)}</h1>
        ${en && en.name && en.name !== p.name ? `<div class="muted">${esc(en.name)}</div>` : ""}
        ${born ? `<div class="muted">${esc(born)}</div>` : ""}
        <div class="muted">${credits.length} عمل</div>
      </div></div></section>
    ${bio ? `<h2 class="section-title">نبذة</h2><p class="overview" id="bio" style="white-space:pre-line"></p>${wikiUrl ? `<p class="muted" style="font-size:12px">المصدر: <a href="${esc(wikiUrl)}" target="_blank" rel="noopener" style="color:var(--gold)">ويكيبيديا</a></p>` : ""}` : `<p class="muted">ما لقينا سيرة لهذا الشخص في TMDB ولا ويكيبيديا.</p>`}
    <h2 class="section-title">أعماله</h2>
    <div class="chips" style="margin-bottom:14px">
      <button class="chip" data-t="all">الكل</button><button class="chip" data-t="movie">أفلام</button><button class="chip" data-t="tv">مسلسلات</button>
    </div>
    <div id="works"></div>`;
  if (bio) fillTranslated($("#bio"), bio);
  view.querySelectorAll("[data-t]").forEach((b) => b.addEventListener("click", () => {
    tab = b.dataset.t;
    render();
  }));
  render();
}

// ---------- my list ----------

const STATUSES = { want: "أبي أشوفه", watching: "أتابعه", done: "خلصته" };

async function listPage() {
  let filter = sessionStorage.getItem("listFilter") || "all";
  const render = () => {
    // Anime (MyAnimeList ids, from the Android app) has no page on the web yet.
    const items = watchlist.all().filter((w) => w.type !== "anime" && (filter === "all" || w.status === filter));
    view.innerHTML = `<h2 class="section-title">قائمتي</h2>
      <div class="chips">${[["all", "الكل"], ...Object.entries(STATUSES)].map(([k, v]) =>
        `<button class="chip ${filter === k ? "on" : ""}" data-f="${k}">${v}</button>`).join("")}</div>
      ${items.length ? `<div class="grid" style="margin-top:18px">${items.map((w) => `<div>
          ${card({ id: w.id, media_type: w.type, title: w.title, poster_path: w.poster })}
          <select class="input" data-key="${esc(w.key)}" style="margin-top:6px;padding:6px 8px">
            ${Object.entries(STATUSES).map(([k, v]) => `<option value="${k}" ${w.status === k ? "selected" : ""}>${v}</option>`).join("")}
            <option value="remove">✕ شيله</option>
          </select></div>`).join("")}</div>`
        : message("قائمتك فاضية. افتح أي فيلم أو مسلسل واضغط «أضفه لقائمتي».")}`;
    view.querySelectorAll("[data-f]").forEach((b) => b.addEventListener("click", () => {
      filter = b.dataset.f;
      sessionStorage.setItem("listFilter", filter);
      render();
    }));
    view.querySelectorAll("select[data-key]").forEach((s) => s.addEventListener("change", () => {
      const w = watchlist.get(s.dataset.key);
      if (s.value === "remove") watchlist.remove(w.key);
      else watchlist.upsert({ ...w, status: s.value });
      render();
    }));
  };
  render();
}

// ---------- live channels ----------

const RECENT = "__recent";
const FOLDER = "__folder:";
const FOLDER_HINTS = [
  ["رياضة", ["sport", "bein", "ssc", "dazn", "espn", "kass", "football", "كورة", "كوره", "رياض", "nba", "ufc", "wwe", " f1", "arryadia", "eurosport", "on time"]],
  ["أخبار", ["news", "اخبار", "أخبار", "jazeera", "الجزيرة", "العربية", "alarabiya", "cnn", "bbc", "france 24", "الحدث", "alhadath"]],
  ["أطفال", ["kids", "cartoon", "spacetoon", "سبيستون", "mbc 3", "mbc3", "baraem", "براعم", "jeem", "disney", "nick", "majid", "ماجد", "طيور", "toyor", "اطفال", "أطفال"]],
  ["أفلام", ["movie", "cinema", "film", "افلام", "أفلام", "سينما", "mbc 2", "mbc2", "mbc max", "action"]],
  ["مسلسلات", ["drama", "دراما", "series", "مسلسل", "mbc 4", "mbc4", "shahid", "شاهد"]],
  ["دينية", ["quran", "قرآن", "قران", "sunnah", "iqraa", "اقرأ", "majd", "المجد", "makkah", "مكة", "islam", "اسلام"]],
  ["موسيقى", ["music", "موسيقى", "اغاني", "أغاني", "clip", "كليب", "mazzika", "مزيكا", "mtv", "wanasah", "وناسة"]],
  ["وثائقي", ["documentary", "وثائقي", "nat geo", "national geographic", "discovery", "animal planet", "history"]],
];
/** Folder names the app suggests for a channel, from its name and category. */
const suggestFolders = (name, cat) => {
  const t = ` ${String(name).toLowerCase()} ${String(cat || "").toLowerCase()} `;
  return FOLDER_HINTS.filter(([, words]) => words.some((w) => t.includes(w))).map(([n]) => n);
};
const ALL = "__all";
const FAV = "__fav";

/** Chips to pick which Xtream account the page shows (only with 2+ accounts). */
function accountChips() {
  const list = accounts.all();
  if (list.length < 2) return "";
  const active = accounts.active();
  return `<div class="chips scroll" style="margin:6px 0 4px">${list.map((a) =>
    `<button class="chip ${a.id === active?.id ? "on" : ""}" data-acc="${esc(a.id)}">${esc(accounts.label(a))}</button>`).join("")}</div>`;
}

function bindAccountChips() {
  view.querySelectorAll("[data-acc]").forEach((b) => b.addEventListener("click", () => {
    accounts.setActive(b.dataset.acc);
    route();
  }));
}

async function live(id) {
  if (!xtreamReady()) {
    view.innerHTML = message("أضف حساب Xtream Codes من الإعدادات عشان تطلع لك القنوات المباشرة هنا", goSettings);
    return;
  }
  const [cats, channels] = await Promise.all([xt.categories("live"), xt.all("live")]);
  if (stale(id)) return;

  const byId = new Map(channels.map((c) => [c.id, c]));
  const counts = {};
  for (const c of channels) counts[c.categoryId] = (counts[c.categoryId] || 0) + 1;
  const wide = () => matchMedia("(min-width: 981px)").matches;
  let category = sessionStorage.getItem("liveCat") || ALL;
  let query = "";
  let catQuery = "";
  let selected = null;
  let shown = [];
  let renderedCount = 0;
  let epgSeq = 0;

  view.innerHTML = `<div class="live">
      ${accountChips()}
      <label class="pill">${SEARCH_ICON}<input id="chq" type="search" placeholder="البحث في القنوات" autocomplete="off"></label>
      <div class="live-panes">
        <section class="pane preview-pane"><div id="preview" class="preview" style="display:grid;align-content:start;height:100%;overflow-y:auto"></div></section>
        <section class="pane"><div id="channels" class="scroll"></div></section>
        <section class="pane">
          <div style="padding:8px"><label class="pill">${SEARCH_ICON}<input id="catq" type="search" placeholder="البحث في الفئات" autocomplete="off"></label></div>
          <div id="cats" class="scroll"></div>
        </section>
      </div>
    </div>`;

  bindAccountChips();
  const catsEl = $("#cats");
  const listEl = $("#channels");
  const previewEl = $("#preview");

  // "سجل المشاهدة": this account's channels played lately (synced with the account), newest first.
  const watched = () => {
    const acc = accounts.active()?.id;
    return [...new Set(history.list().filter((h) => h.kind === "live" && h.accountId === acc)
      .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0)).map((h) => h.itemId))].filter((x) => byId.has(x));
  };
  function renderCats() {
    const fixed = catQuery ? [] : [
      [RECENT, "🕘 سجل المشاهدة", watched().length],
      [ALL, "الكل", channels.length],
      [FAV, "⭐ المفضلة", favorites.all().filter((x) => byId.has(x)).length],
      ...Object.entries(folders.all()).map(([name, ids]) => [FOLDER + name, `📁 ${name}`, ids.filter((x) => byId.has(x)).length]),
    ];
    const rest = cats.filter((c) => !catQuery || c.name.toLowerCase().includes(catQuery.toLowerCase()))
      .map((c) => [c.id, c.name, counts[c.id] || 0]);
    catsEl.innerHTML = [...fixed, ...rest].map(([cid, name, n]) =>
      `<button class="cat ${!query && cid === category ? "on" : ""}" data-cat="${esc(cid)}"><span dir="auto">${esc(name)}</span><span class="n">${n}</span></button>`).join("");
  }

  function computeShown() {
    const q = query.trim().toLowerCase();
    if (q) return channels.filter((c) => c.name.toLowerCase().includes(q));
    if (category === ALL) return channels;
    if (category === FAV) return channels.filter((c) => favorites.has(c.id));
    if (category === RECENT) return watched().map((x) => byId.get(x));
    if (category.startsWith(FOLDER)) return (folders.all()[category.slice(FOLDER.length)] || []).map((x) => byId.get(x)).filter(Boolean);
    return channels.filter((c) => c.categoryId === category);
  }

  const rowHtml = (c, i) => `<button class="ch ${selected && selected.id === c.id && wide() ? "on" : ""}" data-id="${esc(c.id)}">
      <span class="num">${c.num || i + 1}</span>
      <span class="logo">${c.image ? `<img loading="lazy" src="${esc(xt.img(c.image))}" alt="" onerror="this.remove()">` : "📺"}</span>
      <span class="nm" dir="auto">${esc(c.name)}</span>
      ${favorites.has(c.id) || folders.inAny(c.id) ? `<span class="star">★</span>` : ""}
    </button>`;

  // Thousands of channels: add rows in batches as the list scrolls.
  function renderMore() {
    const next = shown.slice(renderedCount, renderedCount + 150);
    listEl.insertAdjacentHTML("beforeend", next.map((c, i) => rowHtml(c, renderedCount + i)).join(""));
    renderedCount += next.length;
  }

  function renderList() {
    shown = computeShown();
    renderedCount = 0;
    listEl.scrollTop = 0;
    listEl.innerHTML = shown.length ? "" : `<p class="muted center" style="min-height:200px">${category === FAV && !query
      ? (wide() ? "اختر قناة واضغط «المفضلة» عشان تضيفها هنا" : "اضغط مطولاً على أي قناة عشان تضيفها للمفضلة")
      : "ما فيه قنوات هنا"}</p>`;
    renderMore();
  }

  listEl.addEventListener("scroll", () => {
    if (listEl.scrollTop + listEl.clientHeight > listEl.scrollHeight - 600 && renderedCount < shown.length) renderMore();
  });

  function renderPreview(guide) {
    if (!selected) {
      previewEl.innerHTML = `<p class="muted center">اختر قناة</p>`;
      return;
    }
    const cat = cats.find((c) => c.id === selected.categoryId);
    const fav = favorites.has(selected.id);
    previewEl.innerHTML = `
      <div class="screen">${selected.image ? `<img src="${esc(xt.img(selected.image))}" alt="" onerror="this.remove()">` : `<span style="font-size:48px">📺</span>`}<span class="live-tag">● مباشر</span></div>
      <h2 style="font-size:22px">${esc(selected.name)}</h2>
      ${cat ? `<div class="muted">${esc(cat.name)}</div>` : ""}
      <div class="epg">${guide === undefined ? `<span class="muted">…</span>` : guide.length
        ? guide.slice(0, 2).map((p, i) => `<div><b>${i ? "بعده" : "الحين"}</b><span>${esc(p.title)}</span><small>${fmtTime(p.start)}${p.end ? " - " + fmtTime(p.end) : ""}</small></div>`).join("")
        : `<span class="muted">ما فيه دليل برامج لهذي القناة</span>`}</div>
      <div style="display:flex;gap:8px;margin-top:8px">
        <button class="btn" id="pv-play" style="flex:1">▶ تشغيل</button>
        <button class="btn ghost" id="pv-fav">${fav ? "♥" : "♡"} المفضلة</button>
      </div>`;
    $("#pv-play").addEventListener("click", () => start(selected));
    $("#pv-fav").addEventListener("click", () => folderSheet(selected, () => renderPreview(guide)));
  }

  function select(c) {
    if (selected && selected.id === c.id) return;
    listEl.querySelector(".ch.on")?.classList.remove("on");
    selected = c;
    if (wide()) listEl.querySelector(`[data-id="${CSS.escape(c.id)}"]`)?.classList.add("on");
    renderPreview(undefined);
    const mine = ++epgSeq;
    setTimeout(async () => {
      if (mine !== epgSeq) return;
      let guide = [];
      try {
        guide = await xt.shortEpg(c.id);
      } catch { /* no guide */ }
      if (mine === epgSeq) renderPreview(guide);
    }, 300);
  }

  function start(c) {
    recent.add(c.id);
    renderCats();
    // The list it was opened from: ←/→ in the player go through it.
    const list = (shown.length ? shown : [c]).map((x, i) => ({ urls: xt.playUrls(x), entry: xt.entryLive(x), number: x.num ?? i + 1 }));
    play(xt.playUrls(c), xt.entryLive(c), list, Math.max(0, (shown.length ? shown : [c]).indexOf(c)));
  }

  catsEl.addEventListener("click", (e) => {
    const b = e.target.closest("[data-cat]");
    if (!b) return;
    category = b.dataset.cat;
    sessionStorage.setItem("liveCat", category);
    query = "";
    $("#chq").value = "";
    renderCats();
    renderList();
  });

  listEl.addEventListener("click", (e) => {
    const b = e.target.closest("[data-id]");
    if (!b) return;
    const c = byId.get(b.dataset.id);
    // On a big screen the first click previews, the second plays.
    if (wide() && (!selected || selected.id !== c.id)) select(c);
    else start(c);
  });
  listEl.addEventListener("dblclick", (e) => {
    const b = e.target.closest("[data-id]");
    if (b) start(byId.get(b.dataset.id));
  });
  listEl.addEventListener("focusin", (e) => {
    const b = e.target.closest("[data-id]");
    if (b && wide() && e.target.matches(":focus-visible")) select(byId.get(b.dataset.id));
  });
  onLongPress(listEl, (e) => {
    const b = e.target.closest("[data-id]");
    if (!b) return;
    folderSheet(byId.get(b.dataset.id));
  });

  // Right click on a computer does the same.
  listEl.addEventListener("contextmenu", (e) => {
    const b = e.target.closest("[data-id]");
    if (!b) return;
    e.preventDefault();
    folderSheet(byId.get(b.dataset.id));
  });

  /** Which lists a channel is in: المفضلة, the user's folders, suggested ones, or a new folder. */
  function folderSheet(c, after) {
    document.querySelector(".sheet-wrap")?.remove();
    const cat = cats.find((x) => x.id === c.categoryId)?.name;
    const wrap = document.createElement("div");
    wrap.className = "sheet-wrap";
    const draw = () => {
      const mine = Object.keys(folders.all());
      const sug = suggestFolders(c.name, cat).filter((n) => !mine.includes(n));
      const row = (a, label, on) => `<button class="btn ${on ? "" : "ghost"}" data-a="${esc(a)}">${esc(label)}${on ? " ✓" : ""}</button>`;
      wrap.innerHTML = `<div class="sheet" role="dialog" aria-modal="true">
          <b class="sheet-title"></b>
          ${row("fav", "⭐ المفضلة", favorites.has(c.id))}
          ${mine.map((n) => row("f:" + n, `📁 ${n}`, folders.has(n, c.id))).join("")}
          ${sug.length ? `<small style="color:var(--gold)">مقترح لهالقناة</small>${sug.map((n) => row("f:" + n, `＋ مجلد «${n}»`, false)).join("")}` : ""}
          <div style="display:flex;gap:8px"><input id="newfolder" class="input" placeholder="اسم مجلد جديد" maxlength="30" style="flex:1"><button class="btn ghost" data-a="new">أضف</button></div>
          <button class="btn ghost" data-a="x">تم</button>
        </div>`;
      wrap.querySelector(".sheet-title").textContent = c.name;
    };
    draw();
    wrap.addEventListener("click", (ev) => {
      const a = ev.target.closest("[data-a]")?.dataset.a;
      if (ev.target === wrap || a === "x") {
        wrap.remove();
        renderCats();
        const row = listEl.querySelector(`[data-id="${CSS.escape(c.id)}"]`);
        if (row) row.outerHTML = rowHtml(c, shown.indexOf(c));
        after?.();
        return;
      }
      if (!a) return;
      if (a === "fav") favorites.toggle(c.id);
      else if (a === "new") {
        const n = wrap.querySelector("#newfolder").value.trim();
        if (!n) return;
        folders.set(n, c.id, true);
      } else if (a.startsWith("f:")) {
        const n = a.slice(2);
        folders.set(n, c.id, !folders.has(n, c.id));
      }
      draw();
    });
    document.body.appendChild(wrap);
    wrap.querySelector("[data-a]")?.focus();
  }

  let qTimer;
  $("#chq").addEventListener("input", (e) => {
    clearTimeout(qTimer);
    qTimer = setTimeout(() => {
      query = e.target.value;
      renderCats();
      renderList();
    }, 250);
  });
  $("#catq").addEventListener("input", (e) => {
    catQuery = e.target.value.trim();
    renderCats();
  });

  renderCats();
  renderList();
  if (shown[0] && wide()) select(shown[0]);
  else renderPreview();
}

// ---------- Xtream films and series ----------

async function xtreamPage(id, kind = "movie") {
  if (!xtreamReady()) {
    view.innerHTML = message("أضف حساب Xtream Codes من الإعدادات عشان يطلع لك محتواه هنا", goSettings);
    return;
  }
  const cats = await xt.categories(kind);
  if (stale(id)) return;
  let cat = sessionStorage.getItem(`xcat-${kind}`);
  if (!cats.some((c) => c.id === cat)) cat = cats[0]?.id;
  let items = [];
  let filter = "";

  view.innerHTML = `${accountChips()}<div class="typetabs">
      <button data-k="live">مباشر</button>
      <button data-k="movie" class="${kind === "movie" ? "on" : ""}">الأفلام</button>
      <button data-k="series" class="${kind === "series" ? "on" : ""}">المسلسلات</button>
    </div>
    <div class="chips scroll" style="margin:14px 0">${cats.map((c) =>
      `<button class="chip ${c.id === cat ? "on" : ""}" data-c="${esc(c.id)}">${esc(c.name)}</button>`).join("")}</div>
    <label class="pill">${SEARCH_ICON}<input id="xf" type="search" placeholder="ابحث في ${kind === "movie" ? "الأفلام" : "المسلسلات"}" autocomplete="off"></label>
    <div id="xgrid"></div>`;

  view.querySelectorAll("[data-k]").forEach((b) => b.addEventListener("click", () => {
    location.hash = b.dataset.k === "live" ? "#/live" : `#/xtream/${b.dataset.k}`;
  }));
  bindAccountChips();

  const grid = $("#xgrid");
  const renderGrid = () => {
    const q = filter.trim().toLowerCase();
    // A search looks through the whole account, not only the open category.
    const list = (q ? items.all : items.cat).filter((x) => !q || x.name.toLowerCase().includes(q)).slice(0, 600);
    grid.innerHTML = list.length
      ? `<h2 class="section-title">${q ? "نتائج البحث" : esc(cats.find((c) => c.id === cat)?.name || "")} <small>${list.length}</small></h2>
         <div class="grid">${list.map((x) => `<button class="card" style="background:none;border:0;text-align:right;padding:0" data-x="${esc(x.id)}">
           <div class="poster">${x.image ? `<img loading="lazy" src="${esc(xt.img(x.image))}" alt="" onerror="this.remove()">` : ""}</div>
           <div class="name">${esc(x.name)}</div></button>`).join("")}</div>`
      : message("ما فيه شي هنا");
  };

  const load = async () => {
    grid.innerHTML = loading();
    const all = await xt.all(kind);
    if (stale(id)) return;
    items = { all, cat: all.filter((x) => x.categoryId === cat), byId: new Map(all.map((x) => [x.id, x])) };
    renderGrid();
  };

  view.querySelectorAll("[data-c]").forEach((b) => b.addEventListener("click", () => {
    cat = b.dataset.c;
    sessionStorage.setItem(`xcat-${kind}`, cat);
    view.querySelectorAll("[data-c]").forEach((x) => x.classList.toggle("on", x === b));
    filter = "";
    $("#xf").value = "";
    items.cat = items.all.filter((x) => x.categoryId === cat);
    renderGrid();
  }));
  let t;
  $("#xf").addEventListener("input", (e) => {
    clearTimeout(t);
    t = setTimeout(() => {
      filter = e.target.value;
      renderGrid();
    }, 250);
  });
  grid.addEventListener("click", (e) => {
    const b = e.target.closest("[data-x]");
    if (!b) return;
    const x = items.byId.get(b.dataset.x);
    if (kind === "series") location.hash = `#/xseries/${encodeURIComponent(x.id)}`;
    else play(xt.playUrls(x), xt.entryMovie(x));
  });
  await load();
}

async function xseriesPage(id, seriesId) {
  const acc = accounts.active();
  const s = await xt.series(seriesId, acc);
  if (stale(id)) return;
  const seasons = [...new Set(s.episodes.map((e) => e.season))];
  // Where the user is in this series: the last episode played, or the next one if it was finished.
  const resumeEp = () => {
    const last = history.list().find((h) => h.kind === "episode" && h.seriesId === seriesId && h.accountId === acc.id);
    const i = s.episodes.findIndex((e) => e.id === last?.itemId);
    if (i < 0) return null;
    return isFinished(last) ? s.episodes[i + 1] || null : s.episodes[i];
  };
  let season = resumeEp()?.season ?? seasons[0];
  // The whole series as a queue: "الحلقة الجاية" goes on through the episodes.
  const playEp = (e) => play(
    xt.episodeUrls(e, acc), xt.entryEpisode(acc, seriesId, s.name, s.cover, e),
    s.episodes.map((x) => ({ urls: xt.episodeUrls(x, acc), entry: xt.entryEpisode(acc, seriesId, s.name, s.cover, x) })),
    s.episodes.findIndex((x) => x.id === e.id),
  );
  const render = () => {
    const resume = resumeEp();
    view.innerHTML = `<section class="title-head"><div class="inner">
        <div class="poster" style="width:170px">${s.cover ? `<img src="${esc(xt.img(s.cover))}" alt="" onerror="this.remove()">` : ""}</div>
        <div class="meta"><h1>${esc(s.name)}</h1>${s.plot ? `<p class="overview">${esc(s.plot)}</p>` : ""}
          ${resume ? `<div><button class="btn" id="resume">▶ كمّل: الموسم ${resume.season} · الحلقة ${resume.number}</button></div>` : ""}</div>
      </div></section>
      ${seasons.length > 1 ? `<div class="chips scroll" style="margin:18px 0">${seasons.map((n) =>
        `<button class="chip ${n === season ? "on" : ""}" data-s="${n}">الموسم ${n}</button>`).join("")}</div>` : `<div style="height:18px"></div>`}
      ${s.episodes.length ? `<div class="episodes">${s.episodes.filter((e) => e.season === season).map((e) => {
        const h = history.get(xt.episodeKey(acc, e));
        const done = h && isFinished(h);
        const current = !done && (e.id === resume?.id || (h && h.durationMs > 0));
        const tag = done ? `<span class="ep-tag done">✓ خلصتها</span>` : current ? `<span class="ep-tag now">▶ تشاهدها</span>` : "";
        const note = h && !done && h.durationMs ? `وصلت ${fmt(h.positionMs / 1000)} من ${fmt(h.durationMs / 1000)}` : "";
        return `<button class="episode ${done ? "done" : ""} ${current ? "now" : ""}" data-e="${esc(e.id)}">
          <span class="thumb" style="position:relative">${e.image ? `<img loading="lazy" src="${esc(xt.img(e.image))}" alt="" onerror="this.remove()">` : ""}
            <span class="ep-icon">${done ? "✓" : "▶"}</span>
            ${h && h.durationMs && !done ? `<span class="progress"><i style="width:${progressOf(h) * 100}%"></i></span>` : ""}</span>
          <span><b style="color:var(--gold);font-size:13px">الحلقة ${e.number}</b> ${tag}<br>${esc(e.title)}${note ? `<br><small class="muted">${note}</small>` : ""}</span>
        </button>`;
      }).join("")}</div>` : message("ما فيه حلقات لهذا المسلسل")}`;
    view.querySelectorAll("[data-s]").forEach((b) => b.addEventListener("click", () => {
      season = Number(b.dataset.s);
      render();
    }));
    view.querySelectorAll("[data-e]").forEach((b) => {
      const ep = () => s.episodes.find((x) => x.id === b.dataset.e);
      let pressTimer = null;
      let pressed = false;
      b.addEventListener("click", (ev) => {
        if (pressed) {
          pressed = false;
          ev.preventDefault();
          return;
        }
        playEp(ep());
      });
      // Long press (touch) or right click: watched / not watched.
      b.addEventListener("contextmenu", (ev) => {
        ev.preventDefault();
        markMenu(ep());
      });
      b.addEventListener("pointerdown", (ev) => {
        if (ev.pointerType === "mouse") return;
        pressTimer = setTimeout(() => {
          pressed = true;
          markMenu(ep());
        }, 550);
      });
      ["pointerup", "pointercancel", "pointerleave"].forEach((n) => b.addEventListener(n, () => clearTimeout(pressTimer)));
    });
    $("#resume")?.addEventListener("click", () => playEp(resume));
  };
  const markMenu = (e) => {
    if (!e) return;
    document.querySelector(".sheet-wrap")?.remove();
    const key = xt.episodeKey(acc, e);
    const h = history.get(key);
    const state = h && isFinished(h) ? "تم المشاهدة" : h && h.durationMs ? "جاري المشاهدة" : "ما شفتها";
    const wrap = document.createElement("div");
    wrap.className = "sheet-wrap";
    wrap.innerHTML = `<div class="sheet" role="dialog" aria-modal="true">
        <b class="sheet-title">الموسم ${e.season} · الحلقة ${e.number}</b>
        <small class="muted">حالتها: ${state}</small>
        <button class="btn ghost" data-a="done">✓ تم المشاهدة</button>
        <button class="btn ghost" data-a="undo">لم أشاهدها</button>
        <button class="btn ghost" data-a="x">إلغاء</button>
      </div>`;
    wrap.addEventListener("click", (ev) => {
      const a = ev.target.closest("[data-a]")?.dataset.a;
      if (ev.target !== wrap && !a) return;
      wrap.remove();
      if (a === "done") {
        const d = h?.durationMs > 0 ? h.durationMs : 1;
        history.save({ ...(h || xt.entryEpisode(acc, seriesId, s.name, s.cover, e)), positionMs: d, durationMs: d }, true);
        toast("✓ تعلّمت كمشاهدة");
        render();
      } else if (a === "undo") {
        history.remove(key);
        toast("تعلّمت إنك ما شفتها");
        render();
      }
    });
    document.body.appendChild(wrap);
    wrap.querySelector('[data-a="done"]').focus();
  };
  render();
  // Refresh the bars after the player closes.
  const onHistory = () => {
    if (stale(id)) return window.removeEventListener("watchhub:history", onHistory);
    render();
  };
  window.addEventListener("watchhub:history", onHistory);
}

// ---------- continue watching ----------

/** "تابع المشاهدة" for the open Home tab: episodes under series, films under films. */
function continueRow(type) {
  if (!xtreamReady()) return "";
  const kind = type === "movie" ? "movie" : "episode";
  const list = history.continueList().filter((e) => e.kind === kind).slice(0, 20);
  if (!list.length) return "";
  const many = accounts.all().length > 1;
  return `<section id="continue"><h2 class="section-title">تابع المشاهدة</h2>
    <div class="cw">${list.map((e) => {
      const live = e.kind === "live";
      const sub = e.subtitle || (live ? "قناة مباشرة" : e.durationMs ? `باقي ${fmt((e.durationMs - e.positionMs) / 1000)}` : "");
      return `<div class="cw-card" role="button" tabindex="0" data-k="${esc(e.key)}">
        <div class="cw-thumb">${e.image ? `<img class="${live ? "logo" : ""}" loading="lazy" src="${esc(xt.img(e.image))}" alt="" onerror="this.remove()">` : ""}
          <span class="cw-play">▶</span>${live ? `<span class="cw-tag">● مباشر</span>` : ""}
          ${!live && e.durationMs ? `<span class="progress"><i style="width:${progressOf(e) * 100}%"></i></span>` : ""}
          <button class="cw-more" data-m="${esc(e.key)}" title="خيارات" aria-label="خيارات">⋯</button></div>
        <b>${esc(e.title)}</b>${sub ? `<small>${esc(sub)}</small>` : ""}
        ${many ? `<small style="color:var(--gold)">${esc(accounts.label(accounts.get(e.accountId)))}</small>` : ""}</div>`;
    }).join("")}</div></section>`;
}

/** Opens a title's own page from the history: the series, the film's TMDB page, or the channels. */
async function openEntryPage(e) {
  if (e.kind === "episode") {
    accounts.setActive(e.accountId);
    location.hash = `#/xseries/${encodeURIComponent(e.seriesId)}`;
  } else if (e.kind === "live") {
    accounts.setActive(e.accountId);
    location.hash = "#/live";
  } else if (e.tmdbId) {
    location.hash = `#/title/movie/${e.tmdbId}`;
  } else {
    // Not matched yet: look the film up on TMDB by its name.
    try {
      const hit = (await tmdb.search(xt.normalizeTitle(e.title))).find((r) => r.media_type === "movie");
      if (hit) location.hash = `#/title/movie/${hit.id}`;
      else toast("ما لقينا صفحة لهذا الفيلم");
    } catch {
      toast("ما لقينا صفحة لهذا الفيلم");
    }
  }
}

/** The title's page on the platform side (TMDB): its known id, else found by its name. */
async function openPlatformPage(e) {
  const type = e.kind === "episode" ? "tv" : "movie";
  if (type === "movie" && e.tmdbId) {
    location.hash = `#/title/movie/${e.tmdbId}`;
    return;
  }
  const name = xt.normalizeTitle(e.title);
  try {
    const hit = (await tmdb.search(name)).find((r) => r.media_type === type);
    if (hit) location.hash = `#/title/${type}/${hit.id}`;
    else toast(`ما لقينا صفحة «${name}» في المنصة`);
  } catch {
    toast(`ما لقينا صفحة «${name}» في المنصة`);
  }
}

/**
 * Shares a film or series: its name and its page on this site (never a stream link from the
 * Xtream account, which carries the password). The phone's share sheet, else the link is copied.
 */
async function shareTitle(title, type, id) {
  const url = id ? `${location.origin}/#/title/${type}/${id}` : location.origin;
  const text = `🎬 أنصحك بـ${type === "movie" ? "فيلم" : "مسلسل"} «${title}»`;
  try {
    if (navigator.share) {
      await navigator.share({ title, text, url });
      return;
    }
  } catch (e) {
    if (e?.name === "AbortError") return;
  }
  try {
    await navigator.clipboard.writeText(`${text}\n${url}`);
    toast("✓ نسخنا الرابط، الصقه وين ما تبي");
  } catch {
    prompt("انسخ الرابط:", url);
  }
}

async function shareEntry(e) {
  const type = e.kind === "episode" ? "tv" : "movie";
  let id = type === "movie" ? e.tmdbId : null;
  let title = e.title;
  if (!id) {
    try {
      const hit = (await tmdb.search(xt.normalizeTitle(e.title))).find((r) => r.media_type === type);
      if (hit) {
        id = hit.id;
        title = titleOf(hit);
      }
    } catch { /* share the name only */ }
  }
  shareTitle(title, type, id);
}

// Long press on any poster (or right click on a computer): share it.
(() => {
  const cardOf = (e) => e.target.closest?.("a.card[href^='#/title/']");
  const share = (a) => {
    const [, , type, id] = a.getAttribute("href").split("/");
    shareTitle(a.querySelector(".name")?.textContent || "", type, Number(id));
  };
  let timer = null;
  let fired = false;
  document.addEventListener("pointerdown", (e) => {
    const a = cardOf(e);
    fired = false;
    clearTimeout(timer);
    if (!a || e.pointerType === "mouse") return;
    timer = setTimeout(() => {
      fired = true;
      share(a);
    }, 550);
  });
  ["pointerup", "pointercancel", "pointermove"].forEach((t) => document.addEventListener(t, (e) => {
    if (t === "pointermove" && Math.abs(e.movementX) + Math.abs(e.movementY) < 4) return;
    clearTimeout(timer);
  }));
  // The press that shared does not also open the page, nor the browser's own link menu.
  document.addEventListener("click", (e) => {
    if (fired && cardOf(e)) {
      e.preventDefault();
      e.stopImmediatePropagation();
      fired = false;
    }
  }, true);
  document.addEventListener("contextmenu", (e) => {
    const a = cardOf(e);
    if (!a) return;
    e.preventDefault();
    if (!fired) share(a);
  });
})();

/** Long press / right click / ⋯ on a card: open its page, or remove it from the row. */
function entryMenu(e, card) {
  document.querySelector(".sheet-wrap")?.remove();
  const label = e.kind === "episode" ? "حلقات المسلسل في الحساب" : e.kind === "movie" ? "معلومات الفيلم (المنصة)" : "افتح القنوات المباشرة";
  const wrap = document.createElement("div");
  wrap.className = "sheet-wrap";
  wrap.innerHTML = `<div class="sheet" role="dialog" aria-modal="true">
      <b class="sheet-title"></b>
      <button class="btn ghost" data-a="open"></button>
      ${e.kind === "episode" ? '<button class="btn ghost" data-a="tmdb">معلومات المسلسل (المنصة)</button>' : ""}
      ${e.kind !== "live" ? '<button class="btn ghost" data-a="share">↗ مشاركة</button>' : ""}
      <button class="btn ghost" data-a="link">▶ مشاركة رابط التشغيل</button>
      <button class="btn ghost danger" data-a="del">احذف من تابع المشاهدة</button>
      <button class="btn ghost" data-a="x">إلغاء</button>
    </div>`;
  wrap.querySelector(".sheet-title").textContent = e.title;
  wrap.querySelector('[data-a="open"]').textContent = label;
  wrap.addEventListener("click", (ev) => {
    const a = ev.target.closest("[data-a]")?.dataset.a;
    if (ev.target === wrap || a === "x") return wrap.remove();
    if (a === "open") {
      wrap.remove();
      openEntryPage(e);
    } else if (a === "share") {
      wrap.remove();
      shareEntry(e);
    } else if (a === "link") {
      wrap.remove();
      // Plays at once in any player, but carries the account's user name and password.
      const url = xt.urlsFor(e)[0];
      if (!url || !confirm("الرابط يشغّل الفيديو على طول في أي مشغل (مثل VLC)، بس فيه اسم المستخدم وكلمة المرور حق حسابك: أي أحد يوصله يقدر يستخدم حسابك. تشاركه؟")) return;
      (navigator.share ? navigator.share({ title: e.title, text: `▶ ${e.title}`, url }) : Promise.reject(new Error("no share")))
        .catch((err) => {
          if (err?.name === "AbortError") return;
          return navigator.clipboard.writeText(url).then(() => toast("✓ نسخنا رابط التشغيل"), () => prompt("انسخ الرابط:", url));
        });
    } else if (a === "tmdb") {
      wrap.remove();
      openPlatformPage(e);
    } else if (a === "del") {
      wrap.remove();
      history.remove(e.key);
      const root = card.closest("#continue");
      card.remove();
      if (root && !root.querySelector(".cw-card")) root.remove();
      toast("انحذف من «تابع المشاهدة»");
    }
  });
  document.body.appendChild(wrap);
  wrap.querySelector('[data-a="open"]').focus();
}

function bindContinue() {
  const root = $("#continue");
  if (!root) return;
  const entryOf = (el) => history.get(el.closest("[data-k]")?.dataset.k);
  root.addEventListener("click", (ev) => {
    const more = ev.target.closest("[data-m]");
    if (more) {
      ev.stopPropagation();
      const e = entryOf(more);
      if (e) entryMenu(e, more.closest(".cw-card"));
      return;
    }
    const card = ev.target.closest("[data-k]");
    const e = card && entryOf(card);
    if (!e) return;
    if (e.kind === "episode" && isFinished(e) && e.seriesId) openEntryPage(e);
    else play(xt.urlsFor(e), e);
  });
  root.addEventListener("contextmenu", (ev) => {
    const card = ev.target.closest("[data-k]");
    const e = card && entryOf(card);
    if (!e) return;
    ev.preventDefault();
    entryMenu(e, card);
  });
  onLongPress(root, (ev) => {
    const card = ev.target.closest("[data-k]");
    const e = card && entryOf(card);
    if (e) entryMenu(e, card);
  });
  root.addEventListener("keydown", (ev) => {
    if (ev.key === "Enter" && ev.target.matches("[data-k]")) ev.target.click();
  });
}

// Signing in or out changes whose data is shown: draw the page again.
window.addEventListener("watchhub:profile", () => {
  xt.clearCache();
  route();
});

// Refresh the row after watching something.
window.addEventListener("watchhub:history", () => {
  const root = $("#continue");
  const html = continueRow(sessionStorage.getItem("homeType") || "tv");
  if (root) {
    if (html) {
      root.outerHTML = html;
      bindContinue();
    } else root.remove();
  } else if (html && (location.hash || "#/").match(/^#?\/?$/)) {
    route();
  }
});

// ---------- settings ----------

const REGIONS = [["SA", "السعودية"], ["AE", "الإمارات"], ["KW", "الكويت"], ["QA", "قطر"], ["BH", "البحرين"], ["OM", "عُمان"], ["EG", "مصر"], ["US", "أمريكا"]];

function renderAccounts(editingId) {
  const listEl = $("#acc-list");
  const formEl = $("#acc-form");
  if (!listEl) return;
  const list = accounts.all();
  const active = accounts.active();
  listEl.innerHTML = list.map((a) => `<div class="acc-row">
      <div><b>${esc(accounts.label(a))}</b><small dir="ltr">${esc(a.host.replace(/^https?:\/\//, ""))}${list.length > 1 && a.id === active?.id ? " · المعروض الحين" : ""}</small></div>
      <button class="btn ghost small" data-edit="${esc(a.id)}">تعديل</button>
    </div>`).join("");
  listEl.querySelectorAll("[data-edit]").forEach((b) => b.addEventListener("click", () => renderAccounts(b.dataset.edit)));

  if (editingId === undefined) {
    formEl.innerHTML = `<button class="btn ghost" id="acc-add">${list.length ? "+ أضف حساب ثاني" : "+ أضف حساب"}</button>`;
    $("#acc-add").addEventListener("click", () => renderAccounts(""));
    return;
  }
  const acc = accounts.get(editingId) || { id: "", name: "", host: "", user: "", pass: "" };
  const isNew = !acc.id;
  formEl.innerHTML = `<div class="acc-form">
      <b>${isNew ? "حساب جديد" : `تعديل «${esc(accounts.label(acc))}»`}</b>
      <div class="field"><label>اسم الحساب (مثلاً: البيت، الرياضة)</label><input class="input" id="xn" value="${esc(acc.name)}" autocomplete="off"></div>
      <div class="field"><label>الهوست (الرابط)</label><input class="input" id="xh" placeholder="http://example.com:8080" value="${esc(acc.host)}" dir="ltr" autocomplete="off"></div>
      <div class="field"><label>اسم المستخدم</label><input class="input" id="xu" value="${esc(acc.user)}" dir="ltr" autocomplete="off"></div>
      <div class="field"><label>كلمة المرور</label><input class="input" id="xp" type="password" value="${esc(acc.pass)}" dir="ltr" autocomplete="off"></div>
      <div style="display:flex;gap:10px;flex-wrap:wrap">
        <button class="btn" id="x-save">احفظ وجرّب</button>
        <button class="btn ghost" id="x-close">إغلاق</button>
        ${isNew ? "" : `<button class="btn ghost danger" id="x-del" style="margin-inline-start:auto">احذف</button>`}
      </div>
      <pre class="msg" id="x-msg"></pre>
    </div>`;
  $("#x-close").addEventListener("click", () => renderAccounts());
  $("#x-del")?.addEventListener("click", () => {
    if (!confirm(`تحذف «${accounts.label(acc)}»؟`)) return;
    accounts.remove(acc.id);
    xt.clearCache();
    renderAccounts();
  });
  $("#x-save").addEventListener("click", async () => {
    const host = xt.normalizeHost($("#xh").value);
    const user = $("#xu").value.trim();
    const pass = $("#xp").value;
    const msg = $("#x-msg");
    if (!host || !user || !pass) {
      msg.className = "msg err";
      msg.textContent = "عبّ الهوست واسم المستخدم وكلمة المرور";
      return;
    }
    const name = $("#xn").value.trim() || `حساب ${accounts.all().length + (isNew ? 1 : 0)}`;
    const saved = accounts.save({ ...acc, name, host, user, pass });
    xt.clearCache();
    msg.className = "msg muted";
    msg.textContent = "جاري التحقق…";
    try {
      const info = await xt.check(saved);
      msg.className = "msg ok";
      msg.textContent = ["✓ تم الحفظ والحساب شغّال",
        info.status && `الحالة: ${info.status}`,
        info.expires && `ينتهي: ${fmtDate(info.expires)}`,
        info.max && `الاتصالات: ${info.active || 0} من ${info.max}`].filter(Boolean).join("\n");
    } catch (e) {
      msg.className = "msg err";
      msg.textContent = `تم الحفظ، بس: ${e.message}`;
    }
    // Refresh the list above; the form comes back with the same message.
    const [cls, text] = [msg.className, msg.textContent];
    renderAccounts(saved.id);
    $("#x-msg").className = cls;
    $("#x-msg").textContent = text;
  });
}

/** «حسابك»: Google sign-in so the list, "تابع المشاهدة" and the Xtream accounts follow you to every device. */
function renderCloud() {
  const box = $("#account-box");
  if (!box) return;
  const draw = (st) => {
    if (!box.isConnected) return;
    const head = `<h3>حسابك</h3>`;
    if (st.status === "loading") {
      box.innerHTML = head + `<p class="muted" style="margin:0">…</p>`;
    } else if (st.status === "off") {
      box.innerHTML = head + `<p class="muted" style="margin:0;line-height:1.8">الحساب يحتاج إعداد مرة وحدة في Render (خطوات «حساب Watch Hub» في ملف README). بعدها يطلع لك هنا زر تسجيل الدخول.</p>`;
    } else if (st.status === "signedOut") {
      box.innerHTML = head + `<div class="acc-row"><span style="font-size:22px">👤</span>
          <div><b>أنت الحين زائر</b><small style="white-space:normal">اللي تشوفه هنا خاص بهذا الجهاز. لما تسجّل دخول تطلع بياناتك أنت، ولما تطلع ترجع بيانات الزائر.</small></div>
          <button class="btn ghost small danger" id="cl-clear">امسحها</button></div>
        <p class="muted" style="margin:0;line-height:1.8">سجّل دخولك بحساب Google، وكل أجهزتك (الجوال والتلفاز والموقع) تصير على نفس القائمة ونفس «تابع المشاهدة». توقف حلقة في الجوال، وتكملها في التلفاز من نفس الثانية.</p>
        <div style="display:flex;gap:10px;flex-wrap:wrap">
          <button class="btn" id="cl-google">سجّل دخول بـ Google</button>
          ${cloud.deviceLoginAvailable() ? `<button class="btn ghost" id="cl-code">دخول برمز</button>` : ""}
        </div><pre class="msg err" id="cl-msg"></pre>`;
      $("#cl-google").addEventListener("click", async () => {
        $("#cl-msg").textContent = "";
        try {
          await cloud.signInWithGoogle();
        } catch (e) {
          if ($("#cl-msg")) $("#cl-msg").textContent = e.message;
        }
      });
      $("#cl-code")?.addEventListener("click", deviceLoginSheet);
      $("#cl-clear").addEventListener("click", async () => {
        if (!confirm("تمسح بيانات الزائر من هذا الجهاز؟ (تابع المشاهدة، قائمتي، حسابات Xtream، القنوات المفضلة). حسابك في Google ما يتأثر.")) return;
        await cloud.clearGuestData();
        toast("✓ انمسحت بيانات الزائر");
      });
    } else {
      const time = st.lastSync ? new Date(st.lastSync).toLocaleTimeString("en-GB") : "";
      box.innerHTML = head + `<div class="acc-row">
          ${st.photo ? `<img src="${esc(st.photo)}" alt="" style="width:44px;height:44px;border-radius:50%">` : ""}
          <div><b>${esc(st.name || st.email || "")}</b><small>${esc(st.email || "")}</small>
            <small style="color:var(--gold)">${time ? `✓ مزامن · آخر تحديث ${time}` : "✓ متصل · نجهّز المزامنة…"}</small></div>
          <button class="btn ghost small" id="cl-out">خروج</button>
        </div>
        <p class="muted" style="margin:0;font-size:13px">قائمتي، وتابع المشاهدة (لين الثانية اللي وقفت عندها)، وحسابات Xtream تتحدّث على كل جهاز داخل بنفس الحساب.</p>`;
      $("#cl-out").addEventListener("click", () => cloud.signOut());
    }
  };
  draw(cloud.getState());
  const off = cloud.onStateChange((st) => (box.isConnected ? draw(st) : off()));
}

/** A TV's "scan to sign in" QR code opened on this phone: approve it with one tap. */
async function pairPage(id, pairingId) {
  view.innerHTML = loading();
  const st = await cloud.ready();
  if (stale(id)) return;
  const device = await cloud.pairingDevice(pairingId);
  if (stale(id)) return;
  const box = (inner) => `<div class="settings" style="margin:30px auto"><section class="card-box" style="text-align:center">${inner}</section></div>`;
  if (!device) {
    view.innerHTML = box(`<h3>انتهت صلاحية الرمز</h3><p class="muted">اطلب رمز جديد من التلفاز وصوّره مرة ثانية.</p>`);
    return;
  }
  if (st.status !== "signedIn") {
    view.innerHTML = box(`<h3>«${esc(device)}» يبي يدخل حسابك</h3>
      <p class="muted">سجّل دخولك أول بحساب Google، وبعدها وافق.</p>
      <div><button class="btn" id="pr-in">سجّل دخول بـ Google</button></div><pre class="msg err" id="pr-msg"></pre>`);
    $("#pr-in").addEventListener("click", async () => {
      try {
        await cloud.signInWithGoogle();
        await new Promise((r) => setTimeout(r, 800));
        route();
      } catch (e) {
        $("#pr-msg").textContent = e.message;
      }
    });
    return;
  }
  view.innerHTML = box(`<h3>«${esc(device)}» يبي يدخل حسابك</h3>
    <p style="color:var(--gold);margin:0">${esc(st.email || st.name || "")}</p>
    <p class="muted" style="line-height:1.8">بيشوف قائمتك وتابع المشاهدة وحسابات Xtream. وافق بس إذا الجهاز قدامك.</p>
    <div style="display:flex;gap:10px;justify-content:center">
      <button class="btn" id="pr-yes">قبول</button><button class="btn ghost" id="pr-no">رفض</button>
    </div><pre class="msg err" id="pr-msg"></pre>`);
  $("#pr-yes").addEventListener("click", async () => {
    $("#pr-yes").disabled = true;
    try {
      await cloud.answerPairing(pairingId, true);
      view.innerHTML = box(`<h3>✓ وافقت</h3><p class="muted">التلفاز يدخل حسابك الحين. تقدر تسكّر هالصفحة.</p>`);
    } catch (e) {
      $("#pr-msg").textContent = e.message;
      $("#pr-yes").disabled = false;
    }
  });
  $("#pr-no").addEventListener("click", async () => {
    await cloud.answerPairing(pairingId, false).catch(() => {});
    view.innerHTML = box(`<h3>رفضت الدخول</h3><p class="muted">ما دخل أحد حسابك.</p>`);
  });
}

/** Code sign-in: enter the code at google.com/device (works where the Google popup can't). */
async function deviceLoginSheet() {
  document.querySelector(".sheet-wrap")?.remove();
  const wrap = document.createElement("div");
  wrap.className = "sheet-wrap";
  wrap.innerHTML = `<div class="sheet" style="text-align:center"><b class="sheet-title">دخول برمز</b><div id="dl-body">${loading()}</div>
    <button class="btn ghost" id="dl-x">إلغاء</button></div>`;
  document.body.appendChild(wrap);
  let cancelled = false;
  const close = () => {
    cancelled = true;
    wrap.remove();
  };
  wrap.querySelector("#dl-x").addEventListener("click", close);
  const body = wrap.querySelector("#dl-body");
  try {
    const code = await cloud.startDeviceLogin();
    if (cancelled) return;
    body.innerHTML = `<p class="muted" style="margin:6px 0">افتح هذا الرابط واختر حسابك، واكتب الرمز:</p>
      <a class="btn" href="${esc(code.verificationUrl)}" target="_blank" rel="noopener">${esc(code.verificationUrl.replace(/^https?:\/\//, ""))}</a>
      <div style="font-size:34px;font-weight:700;letter-spacing:4px;margin:14px 0;background:var(--surface);border-radius:12px;padding:8px" dir="ltr">${esc(code.userCode)}</div>
      <p class="muted" style="margin:0">ننتظر موافقتك…</p>`;
    const ok = await cloud.finishDeviceLogin(code, () => cancelled);
    if (cancelled) return;
    if (ok) {
      close();
      toast("✓ دخلت، ونزامن الحين");
    } else {
      body.innerHTML = `<p class="err">انتهت صلاحية الرمز أو انرفض. سكّر وجرّب مرة ثانية.</p>`;
    }
  } catch (e) {
    if (!cancelled) body.innerHTML = `<p class="err">${esc(e.message)}</p>`;
  }
}

async function settingsPage() {
  view.innerHTML = `<div class="settings">
    <h2 class="section-title" style="margin-bottom:0">الإعدادات</h2>

    <section class="card-box">
      <h3>مفتاح TMDB</h3>
      <p class="muted" style="margin:0;line-height:1.8">قاعدة بيانات مجانية للأفلام والمسلسلات، ومنها نعرف وين متوفر كل عمل.
        <a href="https://www.themoviedb.org/settings/api" target="_blank" rel="noopener" style="color:var(--gold)">افتح صفحة المفاتيح</a></p>
      <input class="input" id="tmdb" type="password" placeholder="API Key أو Read Access Token" value="${esc(settings.tmdbKey || "")}" autocomplete="off">
      <div><button class="btn" id="tmdb-save">احفظ وجرّب</button></div>
      <pre class="msg" id="tmdb-msg"></pre>
    </section>

    <section class="card-box" id="account-box"></section>

    <section class="card-box">
      <h3>المظهر</h3>
      <div class="chips">${[["dark", "🌙 داكن"], ["light", "☀️ نهاري"], ["system", "حسب الجهاز"]].map(([k, l]) =>
        `<button class="chip ${(settings.themeMode || "dark") === k ? "on" : ""}" data-mode="${k}">${l}</button>`).join("")}</div>
      <span class="muted" style="font-size:14px">اللون</span>
      <div class="swatches">${ACCENTS.map(([k, l, c]) => `<button class="swatch ${(settings.accent || "gold") === k ? "on" : ""}" data-accent="${k}" style="--c:${c}" title="${l}"><i></i><small>${l}</small></button>`).join("")}</div>
    </section>

    <section class="card-box">
      <h3>منطقتك</h3>
      <div class="chips">${REGIONS.map(([c, l]) => `<button class="chip ${settings.region === c ? "on" : ""}" data-r="${c}">${l}</button>`).join("")}</div>
    </section>

    <section class="card-box">
      <h3>حسابات Xtream Codes</h3>
      <p class="muted" style="margin:0;line-height:1.8">أضف حساب أو أكثر من مزوّدين رسميين، وسمّ كل واحد باسم تعرفه. زر «مشاهدة» في صفحة أي عمل يبحث في كل حساباتك. البيانات محفوظة في هذا المتصفح بس.</p>
      <div id="acc-list" style="display:grid;gap:8px"></div>
      <div id="acc-form"></div>
    </section>

    <section class="card-box">
      <h3>الآيفون والآيباد</h3>
      <label style="display:flex;gap:10px;align-items:center;cursor:pointer">
        <input type="checkbox" id="vlc" ${settings.vlcOnIos !== false ? "checked" : ""} style="width:20px;height:20px;accent-color:var(--gold)">
        <span>افتح القنوات والأفلام والحلقات في تطبيق VLC على طول</span>
      </label>
      <p class="muted" style="margin:0;line-height:1.8;font-size:13px">Safari ما يشغّل صيغ كثيرة (مثل MKV)، وVLC يشغّل كل شي.
        إذا مو مثبت نعرض لك رابط تثبيته.</p>
    </section>

    <section class="card-box">
      <h3>البروكسي</h3>
      <p class="muted" style="margin:0;line-height:1.8">المتصفح ما يقدر يوصل لسيرفرات Xtream مباشرة، فنمرّر الطلبات عن طريق البروكسي اللي مع الموقع.
        خلّه <code dir="ltr">/proxy</code> إذا الموقع شغال على سيرفر Watch Hub، أو حط رابط بروكسي ثاني.</p>
      <div class="field"><label>رابط البروكسي (خلّه /proxy)</label><input class="input" id="px" value="${esc(settings.proxy || "")}" dir="ltr" placeholder="/proxy"></div>
      <div class="field"><label>مفتاح البروكسي (إذا حطيت PROXY_KEY في السيرفر)</label><input class="input" id="pk" type="password" value="${esc(settings.proxyKey || "")}" dir="ltr" autocomplete="off"></div>
      <div><button class="btn ghost" id="p-save">احفظ</button></div>
    </section>

    <section class="card-box">
      <h3>تطبيق أندرويد</h3>
      <p class="muted" style="margin:0;line-height:1.8">للجوال والتلفاز بنظام أندرويد:
        <a style="color:var(--gold)" href="https://github.com/alyoushw/watch-hub/releases/latest/download/WatchHub.apk">حمّل WatchHub.apk</a></p>
      <p class="muted" style="margin:0;line-height:1.8;font-size:13px">على الآيفون: من Safari اضغط «مشاركة» ثم «إضافة إلى الشاشة الرئيسية» عشان يصير مثل التطبيق.</p>
      <p class="muted" style="margin:0;font-size:12px">This product uses the TMDB API but is not endorsed or certified by TMDB.</p>
    </section>
  </div>`;

  $("#tmdb-save").addEventListener("click", async () => {
    settings.tmdbKey = $("#tmdb").value.trim();
    const msg = $("#tmdb-msg");
    msg.className = "msg muted";
    msg.textContent = "جاري التحقق…";
    try {
      await tmdb.get("configuration");
      msg.className = "msg ok";
      msg.textContent = "✓ تم الحفظ والمفتاح شغّال";
    } catch (e) {
      msg.className = "msg err";
      msg.textContent = e.message;
    }
  });

  view.querySelectorAll("[data-mode]").forEach((b) => b.addEventListener("click", () => {
    settings.themeMode = b.dataset.mode;
    applyTheme();
    view.querySelectorAll("[data-mode]").forEach((x) => x.classList.toggle("on", x === b));
  }));
  view.querySelectorAll("[data-accent]").forEach((b) => b.addEventListener("click", () => {
    settings.accent = b.dataset.accent;
    applyTheme();
    view.querySelectorAll("[data-accent]").forEach((x) => x.classList.toggle("on", x === b));
  }));

  view.querySelectorAll("[data-r]").forEach((b) => b.addEventListener("click", () => {
    settings.region = b.dataset.r;
    view.querySelectorAll("[data-r]").forEach((x) => x.classList.toggle("on", x === b));
  }));

  renderAccounts();
  renderCloud();

  $("#vlc").addEventListener("change", (e) => {
    settings.vlcOnIos = e.target.checked;
    toast(e.target.checked ? "التشغيل بيفتح في VLC" : "التشغيل بيكون هنا في المتصفح");
  });

  $("#p-save").addEventListener("click", () => {
    const px = $("#px").value.trim();
    // Only a path ("/proxy") or a full link belongs here; anything else falls back to the built-in proxy.
    settings.proxy = !px || px.startsWith("/") || /^https?:\/\//i.test(px) ? px : "/proxy";
    $("#px").value = settings.proxy;
    settings.proxyKey = $("#pk").value;
    xt.clearCache();
    toast("تم الحفظ");
  });
}

restoreHistory().finally(() => {
  route();
  cloud.start();
});

// Open instantly from the device's copy, even while the free server wakes up.
if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("sw.js").catch(() => {});
}

// ---------- ✨ مختار لك ----------

async function renderForYou(type, id) {
  let list;
  try {
    list = await foryou.picks();
  } catch {
    return;
  }
  const box = $("#foryou");
  if (!box || stale(id)) return;
  const shown = list.filter((p) => p.r.media_type === type).slice(0, 15);
  if (!shown.length) {
    box.innerHTML = "";
    return;
  }
  box.innerHTML = `<h2 class="section-title">✨ مختار لك</h2>
    <p class="muted" style="margin:-6px 0 10px;font-size:13px">مبني على اللي تشوفه، وتحت كل واحد ليش يناسبك</p>
    <div class="row">${shown.map((p) => `<div class="pick">
        ${card(p.r)}
        <small class="pick-why">${esc(p.reason)}</small>
        <button class="pick-nope" data-nope="${p.r.media_type}:${p.r.id}">مو لي ✕</button>
      </div>`).join("")}</div>`;
  box.addEventListener("click", (e) => {
    const b = e.target.closest("[data-nope]");
    if (!b) return;
    const p = shown.find((x) => `${x.r.media_type}:${x.r.id}` === b.dataset.nope);
    if (p) foryou.notForMe(p.r);
    b.closest(".pick")?.remove();
    toast("تمام، بنقلل من هالنوع");
  }, { once: false });
}
