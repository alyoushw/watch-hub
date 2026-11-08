// Watch Hub on the web: the same hub as the Android app, for iPhone, iPad and computers.
import * as tmdb from "./tmdb.js";
import * as xt from "./xtream.js";
import { play } from "./player.js";
import { settings, watchlist, favorites, recent, xtreamReady } from "./store.js";

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
    view.innerHTML = tabs + message("خطوة وحدة قبل نبدأ: حط مفتاح TMDB المجاني في الإعدادات عشان نعرض لك أفضل الأعمال في كل منصة.", goSettings);
    bindTypeTabs();
    return;
  }

  const [trend, ids] = await Promise.all([tmdb.trending(type), tmdb.regionServiceIds(type)]);
  if (stale(id)) return;
  const hero = trend.find((r) => r.backdrop_path) || trend[0];
  const word = type === "tv" ? "المسلسلات" : "الأفلام";

  view.innerHTML = `${tabs}
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
    if (!settings.tmdbKey) {
      results.innerHTML = message("أضف مفتاح TMDB من الإعدادات أول", goSettings);
      return;
    }
    results.innerHTML = loading();
    try {
      const list = (await tmdb.search(text)).filter((r) => r.poster_path);
      if (mine !== seq) return;
      results.innerHTML = list.length
        ? `<h2 class="section-title">النتائج <small>${list.length}</small></h2><div class="grid">${list.map((r) => card(r)).join("")}</div>`
        : message("ما لقينا شي بهذا الاسم");
    } catch (e) {
      if (mine === seq) results.innerHTML = message(e.message);
    }
  };
  input.addEventListener("input", () => {
    clearTimeout(timer);
    timer = setTimeout(() => {
      history.replaceState(null, "", `#/search?q=${encodeURIComponent(input.value)}`);
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

async function xtreamWatchButton(slot, type, tmdbId, titles, year) {
  slot.innerHTML = `<button class="btn" disabled>نبحث في حسابك…</button>`;
  let match = null;
  try {
    match = await xt.findMatch(type === "tv" ? "series" : "movie", tmdbId, titles, year);
  } catch {
    match = null;
  }
  if (!slot.isConnected) return;
  if (!match) {
    slot.innerHTML = `<button class="btn" disabled>مو موجود في حسابك</button>`;
    return;
  }
  slot.innerHTML = `<button class="btn">▶ مشاهدة</button>`;
  slot.firstElementChild.addEventListener("click", () => {
    if (match.kind === "series") location.hash = `#/xseries/${encodeURIComponent(match.id)}`;
    else play(xt.playUrls(match), match.name);
  });
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
    play(xt.playUrls(c), c.name);
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

  view.innerHTML = `<div class="typetabs">
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
    else play(xt.playUrls(x), x.name);
  });
  await load();
}

async function xseriesPage(id, seriesId) {
  const s = await xt.series(seriesId);
  if (stale(id)) return;
  const seasons = [...new Set(s.episodes.map((e) => e.season))];
  let season = seasons[0];
  const render = () => {
    view.innerHTML = `<section class="title-head"><div class="inner">
        <div class="poster" style="width:170px">${s.cover ? `<img src="${esc(xt.img(s.cover))}" alt="" onerror="this.remove()">` : ""}</div>
        <div class="meta"><h1>${esc(s.name)}</h1>${s.plot ? `<p class="overview">${esc(s.plot)}</p>` : ""}</div>
      </div></section>
      ${seasons.length > 1 ? `<div class="chips scroll" style="margin:18px 0">${seasons.map((n) =>
        `<button class="chip ${n === season ? "on" : ""}" data-s="${n}">الموسم ${n}</button>`).join("")}</div>` : `<div style="height:18px"></div>`}
      ${s.episodes.length ? `<div class="episodes">${s.episodes.filter((e) => e.season === season).map((e) => `
        <button class="episode" data-e="${esc(e.id)}">
          <span class="thumb">${e.image ? `<img loading="lazy" src="${esc(xt.img(e.image))}" alt="" onerror="this.remove()">` : "▶"}</span>
          <span><b style="color:var(--gold);font-size:13px">الحلقة ${e.number}</b><br>${esc(e.title)}</span>
        </button>`).join("")}</div>` : message("ما فيه حلقات لهذا المسلسل")}`;
    view.querySelectorAll("[data-s]").forEach((b) => b.addEventListener("click", () => {
      season = Number(b.dataset.s);
      render();
    }));
    view.querySelectorAll("[data-e]").forEach((b) => b.addEventListener("click", () => {
      const e = s.episodes.find((x) => x.id === b.dataset.e);
      play(xt.episodeUrls(e), `${s.name} - ${e.title}`);
    }));
  };
  render();
}

// ---------- settings ----------

const REGIONS = [["SA", "السعودية"], ["AE", "الإمارات"], ["KW", "الكويت"], ["QA", "قطر"], ["BH", "البحرين"], ["OM", "عُمان"], ["EG", "مصر"], ["US", "أمريكا"]];

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
      <h3>Xtream Codes</h3>
      <p class="muted" style="margin:0;line-height:1.8">الهوست واسم المستخدم وكلمة المرور من مزوّدك. البيانات محفوظة في هذا المتصفح بس.</p>
      <div class="field"><label>الهوست (الرابط)</label><input class="input" id="xh" placeholder="http://example.com:8080" value="${esc(settings.xHost || "")}" dir="ltr" autocomplete="off"></div>
      <div class="field"><label>اسم المستخدم</label><input class="input" id="xu" value="${esc(settings.xUser || "")}" dir="ltr" autocomplete="off"></div>
      <div class="field"><label>كلمة المرور</label><input class="input" id="xp" type="password" value="${esc(settings.xPass || "")}" dir="ltr" autocomplete="off"></div>
      <div style="display:flex;gap:10px;flex-wrap:wrap">
        <button class="btn" id="x-save">احفظ وجرّب</button>
        ${xtreamReady() ? `<button class="btn ghost" id="x-clear">احذف الحساب</button>` : ""}
      </div>
      <pre class="msg" id="x-msg"></pre>
    </section>

    <section class="card-box">
      <h3>الآيفون والآيباد</h3>
      <label style="display:flex;gap:10px;align-items:center;cursor:pointer">
        <input type="checkbox" id="vlc" ${settings.vlcOnIos !== false ? "checked" : ""} style="width:20px;height:20px;accent-color:var(--gold)">
        <span>افتح الأفلام والحلقات في تطبيق VLC على طول</span>
      </label>
      <p class="muted" style="margin:0;line-height:1.8;font-size:13px">Safari ما يشغّل صيغ كثيرة (مثل MKV)، وVLC يشغّل كل شي.
        إذا مو مثبت نعرض لك رابط تثبيته. القنوات المباشرة تشتغل هنا في المتصفح.</p>
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
    Object.assign(settings, { xHost: host, xUser: user, xPass: pass });
    $("#xh").value = host;
    xt.clearCache();
    msg.className = "msg muted";
    msg.textContent = "جاري التحقق…";
    try {
      const info = await xt.check();
      msg.className = "msg ok";
      msg.textContent = ["✓ تم الحفظ والحساب شغّال",
        info.status && `الحالة: ${info.status}`,
        info.expires && `ينتهي: ${fmtDate(info.expires)}`,
        info.max && `الاتصالات: ${info.active || 0} من ${info.max}`].filter(Boolean).join("\n");
    } catch (e) {
      msg.className = "msg err";
      msg.textContent = e.message;
    }
  });

  $("#x-clear")?.addEventListener("click", () => {
    Object.assign(settings, { xHost: "", xUser: "", xPass: "" });
    xt.clearCache();
    route();
  });

  $("#vlc").addEventListener("change", (e) => {
    settings.vlcOnIos = e.target.checked;
    toast(e.target.checked ? "الأفلام والحلقات بتفتح في VLC" : "الأفلام والحلقات بتشتغل هنا");
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
