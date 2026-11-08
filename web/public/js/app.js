// Watch Hub on the web: the same hub as the Android app, for iPhone, iPad and computers.
import * as tmdb from "./tmdb.js";
import * as xt from "./xtream.js";
import { play, fmt } from "./player.js";
import { settings, watchlist, favorites, recent, xtreamReady, history, isFinished, progressOf, accounts } from "./store.js";

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

// ---------- router ----------

const routes = [
  [/^#?\/?$/, home, "home"],
  [/^#\/live$/, live, "live"],
  [/^#\/search(?:\?q=(.*))?$/, searchPage, "search"],
  [/^#\/title\/(movie|tv)\/(\d+)$/, titlePage, null],
  [/^#\/list$/, listPage, "list"],
  [/^#\/xtream(?:\/(movie|series))?$/, xtreamPage, "xtream"],
  [/^#\/xseries\/(.+)$/, xseriesPage, "xtream"],
  [/^#\/settings$/, settingsPage, "settings"],
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
    view.innerHTML = tabs + continueRow() + message("خطوة وحدة قبل نبدأ: حط مفتاح TMDB المجاني في الإعدادات عشان نعرض لك أفضل الأعمال في كل منصة.", goSettings);
    bindTypeTabs();
    bindContinue();
    return;
  }

  const [trend, ids] = await Promise.all([tmdb.trending(type), tmdb.regionServiceIds(type)]);
  if (stale(id)) return;
  const hero = trend.find((r) => r.backdrop_path) || trend[0];
  const word = type === "tv" ? "المسلسلات" : "الأفلام";

  view.innerHTML = `${tabs}
    ${continueRow()}
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

// ---------- search ----------

async function searchPage(id, q = "") {
  view.innerHTML = `<label class="pill" style="margin-top:10px">${SEARCH_ICON}
      <input id="q" type="search" placeholder="ابحث عن فيلم أو مسلسل" value="${esc(q)}" autocomplete="off"></label>
    <div id="results"></div>`;
  const input = $("#q");
  const results = $("#results");
  let timer;
  let seq = 0;
  const run = async (text) => {
    const mine = ++seq;
    if (!text.trim()) {
      results.innerHTML = "";
      return;
    }
    results.innerHTML = loading();
    // TMDB (where it streams) and the user's own Xtream accounts, side by side.
    const [tm, mine2] = await Promise.all([
      settings.tmdbKey ? tmdb.search(text).then((l) => l.filter((r) => r.poster_path)).catch((e) => e) : Promise.resolve([]),
      xtreamReady() ? xt.searchAll(text).catch(() => []) : Promise.resolve([]),
    ]);
    if (mine !== seq) return;
    const kindName = { movie: "فيلم", series: "مسلسل", live: "قناة" };
    const many = accounts.all().length > 1;
    const xHtml = mine2.length ? `<h2 class="section-title">في حساباتك <small>${mine2.length}</small></h2>
      <div class="grid">${mine2.map((x, i) => `<button class="card" style="background:none;border:0;text-align:right;padding:0" data-xi="${i}">
        <div class="poster" ${x.kind === "live" ? 'style="aspect-ratio:16/10"' : ""}>${x.image ? `<img loading="lazy" src="${esc(xt.img(x.image))}" alt="" style="${x.kind === "live" ? "object-fit:contain;padding:12px" : ""}" onerror="this.remove()">` : ""}
          <span class="badge">${kindName[x.kind]}</span></div>
        <div class="name" dir="auto">${esc(x.name)}</div>${many ? `<small style="color:var(--gold);font-size:12px">${esc(accounts.label(accounts.get(x.accountId)))}</small>` : ""}</button>`).join("")}</div>` : "";
    const tmHtml = tm instanceof Error ? `<p class="muted">${esc(tm.message)}</p>`
      : tm.length ? `<h2 class="section-title">كل الأعمال <small>${tm.length}</small></h2><div class="grid">${tm.map((r) => card(r)).join("")}</div>` : "";
    results.innerHTML = xHtml + tmHtml || (settings.tmdbKey || xtreamReady() ? message("ما لقينا شي بهذا الاسم") : message("أضف مفتاح TMDB أو حساب Xtream من الإعدادات أول", goSettings));
    results.querySelectorAll("[data-xi]").forEach((b) => b.addEventListener("click", () => {
      const x = mine2[Number(b.dataset.xi)];
      if (x.kind === "series") {
        accounts.setActive(x.accountId);
        location.hash = `#/xseries/${encodeURIComponent(x.id)}`;
      } else {
        play(xt.playUrls(x), x.kind === "live" ? xt.entryLive(x) : xt.entryMovie(x));
      }
    }));
  };
  input.addEventListener("input", () => {
    clearTimeout(timer);
    timer = setTimeout(() => {
      window.history.replaceState(null, "", `#/search?q=${encodeURIComponent(input.value)}`);
      run(input.value);
    }, 400);
  });
  input.focus();
  run(q);
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
  const cast = ((d.credits || {}).cast || []).slice(0, 14);
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
    ${overview ? `<h2 class="section-title">القصة</h2><p class="overview">${esc(overview)}</p>` : ""}
    ${trailers.length ? `<h2 class="section-title">الإعلانات</h2><div class="trailers">${trailers.map((v) =>
      `<iframe loading="lazy" src="https://www.youtube-nocookie.com/embed/${esc(v.key)}" allow="encrypted-media; fullscreen; picture-in-picture" allowfullscreen title="${esc(v.name)}"></iframe>`).join("")}</div>` : ""}
    ${cast.length ? `<h2 class="section-title">الممثلين</h2><div class="cast">${cast.map((c) => `<div>
        <div class="face">${c.profile_path ? `<img loading="lazy" src="${tmdb.image(c.profile_path, "w185")}" alt="">` : ""}</div>
        <div>${esc(c.name)}</div><div class="muted">${esc(c.character || "")}</div></div>`).join("")}</div>` : ""}
    ${rowSection("إذا عجبك، بيعجبك", recs)}
    <p class="muted" style="font-size:12px;margin-top:30px">البيانات من TMDB، وبيانات التوفر من JustWatch.</p>`;

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

// ---------- my list ----------

const STATUSES = { want: "أبي أشوفه", watching: "أتابعه", done: "خلصته" };

async function listPage() {
  let filter = sessionStorage.getItem("listFilter") || "all";
  const render = () => {
    const items = watchlist.all().filter((w) => filter === "all" || w.status === filter);
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

  function renderCats() {
    const fixed = catQuery ? [] : [
      [RECENT, "شوهد مؤخراً", recent.all().filter((x) => byId.has(x)).length],
      [ALL, "الكل", channels.length],
      [FAV, "المفضلة", favorites.all().filter((x) => byId.has(x)).length],
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
    if (category === RECENT) return recent.all().map((x) => byId.get(x)).filter(Boolean);
    return channels.filter((c) => c.categoryId === category);
  }

  const rowHtml = (c, i) => `<button class="ch ${selected && selected.id === c.id && wide() ? "on" : ""}" data-id="${esc(c.id)}">
      <span class="num">${c.num || i + 1}</span>
      <span class="logo">${c.image ? `<img loading="lazy" src="${esc(xt.img(c.image))}" alt="" onerror="this.remove()">` : "📺"}</span>
      <span class="nm" dir="auto">${esc(c.name)}</span>
      ${favorites.has(c.id) ? `<span class="star">★</span>` : ""}
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
    $("#pv-fav").addEventListener("click", () => {
      const on = favorites.toggle(selected.id);
      toast(on ? "أُضيفت للمفضلة" : "انشالت من المفضلة");
      renderCats();
      renderPreview(guide);
      const row = listEl.querySelector(`[data-id="${CSS.escape(selected.id)}"]`);
      if (row) row.outerHTML = rowHtml(selected, shown.indexOf(selected));
    });
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
    play(xt.playUrls(c), xt.entryLive(c));
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
    const c = byId.get(b.dataset.id);
    const on = favorites.toggle(c.id);
    toast(on ? `«${c.name}» أُضيفت للمفضلة` : `«${c.name}» انشالت من المفضلة`);
    b.outerHTML = rowHtml(c, shown.indexOf(c));
    renderCats();
  });

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
  const playEp = (e) => play(xt.episodeUrls(e, acc), xt.entryEpisode(acc, seriesId, s.name, s.cover, e));
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
    view.querySelectorAll("[data-e]").forEach((b) => b.addEventListener("click", () => {
      playEp(s.episodes.find((x) => x.id === b.dataset.e));
    }));
    $("#resume")?.addEventListener("click", () => playEp(resume));
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

function continueRow() {
  if (!xtreamReady()) return "";
  const list = history.continueList().slice(0, 20);
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

/** Long press / right click / ⋯ on a card: open its page, or remove it from the row. */
function entryMenu(e, card) {
  document.querySelector(".sheet-wrap")?.remove();
  const label = e.kind === "episode" ? "افتح صفحة المسلسل" : e.kind === "movie" ? "افتح صفحة الفيلم" : "افتح القنوات المباشرة";
  const wrap = document.createElement("div");
  wrap.className = "sheet-wrap";
  wrap.innerHTML = `<div class="sheet" role="dialog" aria-modal="true">
      <b class="sheet-title"></b>
      <button class="btn ghost" data-a="open"></button>
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

// Refresh the row after watching something.
window.addEventListener("watchhub:history", () => {
  const root = $("#continue");
  const html = continueRow();
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

  view.querySelectorAll("[data-r]").forEach((b) => b.addEventListener("click", () => {
    settings.region = b.dataset.r;
    view.querySelectorAll("[data-r]").forEach((x) => x.classList.toggle("on", x === b));
  }));

  renderAccounts();

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

route();

// Open instantly from the device's copy, even while the free server wakes up.
if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("sw.js").catch(() => {});
}
