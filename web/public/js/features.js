import * as tmdb from "./tmdb.js?v=20261118d";
import * as src from "./sources.js?v=20261118d";
import { settings, watchlist, history, isFinished } from "./store.js?v=20261118d";
import { play } from "./player.js?v=20261127a";
import { addDownload } from "./downloads.js?v=20261118d";
import { fillTranslated } from "./translate.js?v=20261118d";

const view = document.getElementById("view");
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const $ = (s, root = view) => root.querySelector(s);
const busy = () => '<div class="center"><div class="spinner"></div></div>';
const message = (s) => `<p class="muted center">${esc(s)}</p>`;
let active = () => true, refresh = () => {}, notify = () => {};
export function configure(options) { active = options.active; refresh = options.refresh; notify = options.notify; }
export const routeFor = (name, value) => `#/${name}/${encodeURIComponent(JSON.stringify(value))}`;
const cover = (title, image, overview = "") => `<section class="title-head"><div class="inner"><div class="poster">${image ? `<img src="${esc(image)}" alt="">` : ""}</div><div class="meta"><h1>${esc(title)}</h1>${overview ? `<p class="overview" id="source-overview">${esc(overview)}</p>` : ""}</div></div></section>`;
const animeRoute = (title, extra = {}) => routeFor("anime-watch", { title: title.title, searchTitle: title.title, image: title.poster, sourceTitle: title, type: title.type === "MOVIE" ? "movie" : "tv", detailType: "anime", detailId: title.malId || 0, malId: title.malId, ...extra });
const animeCard = (title, label = "", extra = {}) => `<a class="poster-card" href="${esc(animeRoute(title, extra))}"><div class="poster"><img loading="lazy" src="${esc(title.poster || "")}" alt=""></div><b>${esc(title.title)}</b>${label ? `<small class="muted">${esc(label)}</small>` : ""}</a>`;

export function lookupFor(d, en, type) {
  return { type, tmdbId: Number(d.id), title: d.title || d.name, searchTitle: en?.title || en?.name || d.original_title || d.original_name,
    originalTitle: d.original_title || d.original_name || en?.title || en?.name, year: Number((d.release_date || d.first_air_date || "").slice(0, 4)),
    imdbId: d.imdb_id || d.external_ids?.imdb_id, image: tmdb.image(d.poster_path, "w500"), backdrop: tmdb.image(d.backdrop_path, "w1280"), overview: d.overview || en?.overview || "",
    seasons: (d.seasons || []).filter((s) => s.season_number > 0).map((s) => ({ number: s.season_number, episodes: s.episode_count, year: Number((s.air_date || "").slice(0, 4)) || null })) };
}
export function attachTitleSources(d, en, type) {
  const slot = $("#source-actions"); if (!slot) return;
  const lookup = lookupFor(d, en, type);
  const anime = (d.genres || []).some((g) => g.id === 16) && (d.original_language === "ja" || (d.origin_country || []).includes("JP"));
  slot.innerHTML = `<button id="fouru-watch" class="btn">4U</button>${anime ? `<a class="btn" href="${esc(routeFor("anime-watch", lookup))}">▶ تشغيل أنمي فاي</a>` : ""}
    ${src.PROVIDERS.map(([id, name]) => `<a class="btn" href="${esc(routeFor("watch-source", { ...lookup, provider: id }))}">▶ تشغيل ${esc(name)}</a>`).join("")}
    ${settings.sourceCatalogue ? `<a class="btn ghost" href="${esc(routeFor("watch-source", { ...lookup, provider: "catalogue" }))}">كتالوج المصادر</a>` : ""}`;
}

export async function watchSourcePage(id, encoded) {
  const lookup = JSON.parse(encoded), provider = src.PROVIDERS.find(([p]) => p === lookup.provider);
  const name = provider?.[1] || "مصادر المشاهدة", series = lookup.type === "tv";
  let seasons = lookup.seasons || [], standard = seasons, partSeasons = null, useParts = true, season = seasons[0]?.number || 1;
  if (series && provider && lookup.provider !== "torrent") {
    view.innerHTML = cover(lookup.title, lookup.image) + message("جاري التحقق من ترقيم المواسم والأجزاء…");
    try {
      const expected = seasons.reduce((n, s) => n + s.episodes, 0);
      const groups = (await tmdb.get(`tv/${lookup.tmdbId}/episode_groups`)).results || [];
      const choice = groups.filter((g) => g.type === 4 && g.episode_count === expected && (/^parts$/i.test(g.name) || /netflix/i.test(g.name))).sort((a, b) => (/^parts$/i.test(a.name) ? -1 : 1))[0];
      if (choice) {
        const data = await tmdb.get(`tv/episode_group/${choice.id}`);
        const ordered = (data.groups || []).sort((a, b) => a.order - b.order), eps = ordered.flatMap((g) => g.episodes);
        if (ordered.length === choice.group_count && eps.length === expected && new Set(eps.map((e) => e.id)).size === expected && ordered.every((g) => g.episodes.length) && eps.every((e) => e.id > 0 && e.season_number > 0)) {
          const parts = ordered.map((g, i) => ({ number: i + 1, episodes: g.episodes.length, year: Number(g.episodes.map((e) => e.air_date).filter(Boolean).sort()[0]?.slice(0, 4)) || null }));
          if (JSON.stringify(parts.map((p) => p.episodes)) !== JSON.stringify(standard.map((p) => p.episodes))) partSeasons = parts;
        }
      }
    } catch { /* The normal TMDB order remains available when alternative metadata fails. */ }
  }
  if (!active(id)) return;
  const searchEpisode = async (episode) => {
    const q = src.sourceQuery({ ...lookup, seasons }, season, episode);
    const overlay = document.createElement("div"); overlay.className = "sheet-wrap";
    overlay.innerHTML = `<div class="sheet" role="dialog" aria-modal="true"><h2>${esc(series ? `الحلقة ${episode}` : lookup.title)} · ${esc(name)}</h2><div id="source-results">${busy()}</div><button class="btn ghost" data-close>إغلاق</button></div>`;
    document.body.append(overlay); overlay.querySelector("[data-close]").onclick = () => overlay.remove();
    try {
      const rows = await src.read("search", { provider: lookup.provider, query: JSON.stringify(q), ...(settings.sourceCatalogue ? { catalogue: settings.sourceCatalogue } : {}) });
      if (!overlay.isConnected || !active(id)) return;
      const results = overlay.querySelector("#source-results");
      results.innerHTML = rows.map((source, i) => `<section><h3>${esc(source.providerName)}</h3>${source.variants.map((v, n) => `<button class="btn" data-source="${i}" data-variant="${n}">▶ ${esc(v.label)}</button>${v.transport !== "HLS" ? `<button class="btn ghost" data-source-download="${i}" data-variant="${n}">↓ تنزيل</button>` : ""}`).join("")}${(source.webLinks || []).filter((l) => /^(https?:|magnet:)/.test(l.url)).map((l) => `<a class="btn ghost" href="${esc(l.url)}" target="_blank" rel="noopener">${l.download ? "↓ " : "↗ "}${esc(l.label)}</a>`).join("")}</section>`).join("") || message("لم نجد نسخة مطابقة في هذا المصدر حالياً");
      if (lookup.provider === "torrent") results.insertAdjacentHTML("afterbegin", message("روابط تورنت تفتح في تطبيق تورنت على جهازك؛ لا تُرسل روابط Magnet إلى مشغّل المتصفح."));
      results.querySelectorAll("[data-source]").forEach((b) => b.onclick = () => {
        const source = rows[Number(b.dataset.source)], variant = source.variants[Number(b.dataset.variant)];
        overlay.remove(); play([], src.sourceEntry(lookup, source, variant, q));
      });
      results.querySelectorAll("[data-source-download]").forEach((b) => b.onclick = async () => {
        const source = rows[Number(b.dataset.sourceDownload)], variant = source.variants[Number(b.dataset.variant)];
        try { await addDownload(src.sourceEntry(lookup, source, variant, q)); overlay.remove(); notify("أُضيف إلى التنزيلات"); } catch (e) { notify(e.message); }
      });
      if (provider) results.insertAdjacentHTML("beforeend", `<a class="btn ghost" target="_blank" rel="noopener" href="${esc(provider[2] + "?s=" + encodeURIComponent(lookup.searchTitle || lookup.title))}">افتح بحث ${esc(name)} في الموقع</a>`);
    } catch (e) {
      if (!overlay.isConnected) return;
      overlay.querySelector("#source-results").innerHTML = message(e.message) + (provider ? `<a class="btn ghost" target="_blank" rel="noopener" href="${esc(provider[2] + "?s=" + encodeURIComponent(lookup.searchTitle || lookup.title))}">افتح الموقع</a>` : "");
    }
  };
  const render = () => {
    seasons = useParts && partSeasons ? partSeasons : standard;
    const word = useParts && partSeasons ? "الجزء" : "الموسم", count = series ? seasons.find((s) => s.number === season)?.episodes || 0 : 1;
    view.innerHTML = cover(`${lookup.title} · ${name}`, lookup.image, lookup.overview) + `${partSeasons ? `<p class="muted">قد يجمع الموسم أكثر من جزء. اختر الترقيم الموافق لمصدر المشاهدة. بيانات الترتيبين من TMDB.</p><div class="chips"><button class="chip ${useParts ? "on" : ""}" data-order="parts">الأجزاء · ${partSeasons.length}</button><button class="chip ${!useParts ? "on" : ""}" data-order="seasons">المواسم · ${standard.length}</button></div>` : ""}
      ${series ? `<div class="chips scroll">${seasons.map((s) => `<button class="chip ${season === s.number ? "on" : ""}" data-season="${s.number}">${word} ${s.number}</button>`).join("")}</div>` : ""}
      <div class="episodes">${Array.from({ length: Math.min(count, 2000) }, (_, i) => `<button class="episode" data-episode="${i + 1}">▶ ${series ? `الحلقة ${i + 1}` : "الفيلم"}</button>`).join("")}</div>${count ? "" : message("لم تتوفر حلقات هذا الموسم بعد")}`;
    view.querySelectorAll("[data-order]").forEach((b) => b.onclick = () => { useParts = b.dataset.order === "parts"; season = 1; render(); });
    view.querySelectorAll("[data-season]").forEach((b) => b.onclick = () => { season = Number(b.dataset.season); render(); });
    view.querySelectorAll("[data-episode]").forEach((b) => b.onclick = () => searchEpisode(Number(b.dataset.episode)));
  };
  render();
}

export async function animeWatchPage(id, encoded) {
  const lookup = JSON.parse(encoded);
  let titles = [], selected = lookup.sourceTitle || null, related = [], episodes = [];
  const error = (e, retry) => { if (!active(id)) return; view.innerHTML = cover(lookup.title, lookup.image) + message(e.message) + '<button class="btn" id="retry-source">حاول مرة ثانية</button>'; $("#retry-source").onclick = retry; };
  const pickEpisode = async (episode, action = "play") => {
    const ref = { animeId: selected.id, type: selected.type, episode: episode.number };
    const overlay = document.createElement("div"); overlay.className = "sheet-wrap";
    overlay.innerHTML = `<div class="sheet" role="dialog" aria-modal="true"><h2>الحلقة ${esc(episode.number)} · اختر السيرفر والجودة</h2><div id="anime-servers">${busy()}</div><button class="btn ghost" data-close>إغلاق</button></div>`;
    document.body.append(overlay); overlay.querySelector("[data-close]").onclick = () => overlay.remove();
    try {
      const servers = await src.animeServers(ref); if (!overlay.isConnected || !active(id)) return;
      overlay.querySelector("#anime-servers").innerHTML = servers.map((s, i) => `<button class="btn" data-server="${i}">▶ ${esc(s.label)}</button><button class="btn ghost" data-server-download="${i}">↓ تنزيل</button>`).join("") || message("لا يوجد سيرفر متاح لهذه الحلقة");
      overlay.querySelectorAll("[data-server]").forEach((b) => b.onclick = () => {
        const server = servers[Number(b.dataset.server)].key, entries = episodes.map((ep) => src.animeEntry(lookup, selected, ep, server));
        overlay.remove();
        if (action !== "play") { const selectedEntries = action === "season" ? entries : [src.animeEntry(lookup, selected, episode, server)]; Promise.all(selectedEntries.map((entry) => addDownload(entry))).then(() => notify("أُضيف إلى التنزيلات")).catch((e) => notify(e.message)); }
        else play([], src.animeEntry(lookup, selected, episode, server), entries.map((e) => ({ urls: [], entry: e })), episodes.indexOf(episode));
      });
      overlay.querySelectorAll("[data-server-download]").forEach((b) => b.onclick = async () => {
        const server = servers[Number(b.dataset.serverDownload)].key;
        try { const wanted = action === "season" ? episodes : [episode]; for (const ep of wanted) await addDownload(src.animeEntry(lookup, selected, ep, server)); overlay.remove(); notify("أُضيف إلى التنزيلات"); } catch (e) { notify(e.message); }
      });
    } catch (e) { if (overlay.isConnected) overlay.querySelector("#anime-servers").innerHTML = message(e.message); }
  };
  const select = async (title) => {
    selected = title; view.innerHTML = cover(title.title, title.poster || lookup.image) + busy();
    try {
      [related, episodes] = await Promise.all([src.animeRelated(title).catch(() => []), src.animeEpisodes(title.id)]);
      if (!active(id)) return;
      const seasons = [title, ...related].filter((x, i, a) => a.findIndex((y) => y.id === x.id) === i).sort((a, b) => a.season - b.season);
      view.innerHTML = cover(title.title, title.poster || lookup.image, lookup.overview) + `<div class="chips scroll">${seasons.map((s, i) => `<button class="chip ${s.id === title.id ? "on" : ""}" data-related="${i}">${esc(s.title)} · ${s.type === "MOVIE" ? "فيلم" : `الموسم ${s.season}`}</button>`).join("")}</div>
        <button class="btn ghost" id="anime-download-season">↓ تنزيل الموسم</button><div class="episodes">${episodes.map((ep, i) => { const h = history.get(`animeify:${title.id}:${ep.number}`); return `<button class="episode ${h && isFinished(h) ? "done" : ""}" data-anime-episode="${i}">${h && isFinished(h) ? "✓" : "▶"} الحلقة ${esc(ep.number)} ${esc(ep.extra)}</button>`; }).join("")}</div>${episodes.length ? "" : message("لم تتوفر حلقات هذا الأنمي بعد")}`;
      view.querySelectorAll("[data-related]").forEach((b) => b.onclick = () => select(seasons[Number(b.dataset.related)]));
      view.querySelectorAll("[data-anime-episode]").forEach((b) => { b.onclick = () => pickEpisode(episodes[Number(b.dataset.animeEpisode)]); b.oncontextmenu = (event) => { event.preventDefault(); pickEpisode(episodes[Number(b.dataset.animeEpisode)], "download"); }; });
      $("#anime-download-season").onclick = () => { if (episodes.length) pickEpisode(episodes[0], "season"); };
      if (lookup.initialEpisode) { const ep = episodes.find((e) => e.number === String(lookup.initialEpisode)); lookup.initialEpisode = null; if (ep) pickEpisode(ep); }
    } catch (e) { error(e, () => select(title)); }
  };
  const search = async () => {
    view.innerHTML = cover(lookup.title, lookup.image) + busy();
    try {
      titles = await src.animeSearch(lookup.searchTitle || lookup.title, lookup.type === "movie");
      if (!active(id)) return;
      const exact = lookup.malId ? titles.find((t) => t.malId === lookup.malId) : null;
      if (exact) return select(exact);
      view.innerHTML = cover(lookup.title, lookup.image) + '<p class="muted">اختر العمل المطابق في أنمي فاي</p><div class="episodes">' + titles.map((t, i) => `<button class="episode" data-anime-choice="${i}">${esc(t.title)} · ${t.type === "MOVIE" ? "فيلم" : `الموسم ${t.season}`}</button>`).join("") + '</div>' + (titles.length ? "" : message("لم نجد هذا العمل في أنمي فاي"));
      view.querySelectorAll("[data-anime-choice]").forEach((b) => b.onclick = () => select(titles[Number(b.dataset.animeChoice)]));
    } catch (e) { error(e, search); }
  };
  if (selected) await select(selected); else await search();
}

export async function animeHome(id) {
  view.innerHTML = '<h1>أنمي</h1><button class="btn ghost" id="refresh-anime">↻ تحديث الحلقات</button><div id="anime-continue"></div><form id="anime-search" class="searchbar"><input class="input" id="anime-query" placeholder="ابحث عن أنمي"><button class="btn">بحث</button></form><div id="anime-feed">' + busy() + '</div>';
  $("#anime-search").onsubmit = async (event) => {
    event.preventDefault(); const q = $("#anime-query").value.trim(); if (!q) return;
    const slot = $("#anime-feed"); slot.innerHTML = busy();
    try {
      const found = await Promise.allSettled([src.animeSearch(q, false), src.animeSearch(q, true)]);
      const rows = found.flatMap((r) => r.status === "fulfilled" ? r.value : []).filter((t, i, all) => all.findIndex((x) => x.id === t.id) === i);
      if (!active(id)) return;
      if (rows.length) slot.innerHTML = `<div class="grid">${rows.map((t) => animeCard(t)).join("")}</div>`;
      else {
        const response = await fetch(`https://api.jikan.moe/v4/anime?q=${encodeURIComponent(q)}&sfw=true&limit=24`);
        if (!response.ok) throw new Error("تعذّر البحث في هذا المصدر الآن؛ حاول مرة ثانية"); const data = await response.json(); if (!active(id)) return;
        slot.innerHTML = `<div class="grid">${data.data.map((a) => `<a class="poster-card" href="#/anime/${a.mal_id}"><div class="poster"><img loading="lazy" src="${esc(a.images?.jpg?.large_image_url || "")}" alt=""></div><b>${esc(a.title_english || a.title)}</b></a>`).join("")}</div>`;
      }
    } catch (e) { if (active(id)) slot.innerHTML = message(e.message); }
  };
  const continued = history.continueList().filter((e) => e.animeRef).slice(0, 20);
  if (continued.length) $("#anime-continue").innerHTML = '<h2>تابع المشاهدة</h2><div class="row">' + continued.map((entry) => {
    const ref = src.reference(entry.animeRef);
    return animeCard({ id: ref.animeId, malId: ref.malId, type: ref.type, season: ref.season, title: entry.title, poster: entry.image }, entry.subtitle, { initialEpisode: ref.episode, detailType: ref.detailType, detailId: ref.detailId });
  }).join("") + '</div>';
  let latestFrom = 0, currentFrom = 0;
  const load = async (append = false, only = null) => {
    const results = await Promise.allSettled([only === 1 ? Promise.resolve(null) : src.read("anime/latest", { from: latestFrom }), only === 0 ? Promise.resolve(null) : src.read("anime/current", { from: currentFrom })]);
    if (!active(id) || $("#anime-query")?.value.trim()) return;
    const feed = $("#anime-feed");
    if (!append) feed.innerHTML = '<section><h2>آخر الحلقات</h2><div class="row" id="latest-anime"></div><button class="btn ghost" id="more-latest">المزيد</button></section><section><h2>أنميات تُعرض الآن</h2><div class="row" id="current-anime"></div><button class="btn ghost" id="more-current">المزيد</button></section>';
    for (let i = 0; i < 2; i++) {
      const result = results[i], slot = i === 0 ? $("#latest-anime") : $("#current-anime"), button = i === 0 ? $("#more-latest") : $("#more-current");
      if (result.status === "fulfilled") {
        const data = result.value; if (!data) continue; slot.insertAdjacentHTML("beforeend", data.items.map((row) => i === 0 ? animeCard(row.title, `الحلقة ${row.episode.number}`, { initialEpisode: row.episode.number }) : animeCard(row)).join(""));
        if (i === 0) latestFrom = data.nextFrom; else currentFrom = data.nextFrom; button.hidden = !data.hasMore;
      } else { slot.innerHTML = message(result.reason.message); button.textContent = "حاول مرة ثانية"; }
      button.onclick = () => load(true, i);
    }
  };
  $("#refresh-anime").onclick = () => { $("#anime-query").value = ""; latestFrom = currentFrom = 0; load(); };
  await load();
}

export async function animeDetail(id, animeId) {
  try {
    const response = await fetch(`https://api.jikan.moe/v4/anime/${animeId}/full`); if (!response.ok) throw new Error("تعذّر تحميل بيانات الأنمي؛ حاول مرة ثانية");
    const a = (await response.json()).data; if (!active(id)) return;
    const title = a.title_english || a.title, image = a.images?.jpg?.large_image_url;
    const lookup = { title, searchTitle: a.title, image, overview: a.synopsis || "", malId: a.mal_id, detailType: "anime", detailId: a.mal_id, type: a.type === "Movie" ? "movie" : "tv" };
    const key = `anime:${a.mal_id}`;
    view.innerHTML = cover(title, image, a.synopsis) + `<div class="source-actions"><a class="btn" href="${esc(routeFor("fouru", { ...lookup, names: [title, a.title], detailType: "anime", detailId: a.mal_id }))}">4U</a><a class="btn" href="${esc(routeFor("anime-watch", lookup))}">▶ تشغيل أنمي فاي</a><button class="btn ghost" id="save-anime">${watchlist.get(key) ? "✓ في قائمتي" : "+ أضفه لقائمتي"}</button></div><p class="muted">${esc(a.type)} · ${a.episodes || "؟"} حلقة · ★ ${a.score || "—"}</p>${(a.relations || []).map((r) => `<h2>${esc(r.relation)}</h2><div class="chips">${r.entry.filter((x) => x.type === "anime").map((e) => `<a class="chip" href="#/anime/${e.mal_id}">${esc(e.name)}</a>`).join("")}</div>`).join("")}`;
    if (a.synopsis) fillTranslated($("#source-overview"), a.synopsis);
    $("#save-anime").onclick = () => { if (watchlist.get(key)) watchlist.remove(key); else watchlist.upsert({ key, type: "anime", id: a.mal_id, title, poster: image, status: "want", added: Date.now() }); $("#save-anime").textContent = watchlist.get(key) ? "✓ في قائمتي" : "+ أضفه لقائمتي"; };
  } catch (e) {
    if (!active(id)) return;
    const saved = watchlist.get(`anime:${animeId}`);
    view.innerHTML = message(e.message) + '<button class="btn" id="retry-anime">حاول مرة ثانية</button><a class="btn ghost" href="#/anime">ابحث في أنمي فاي</a>';
    if (saved) { const lookup = { title: saved.title, searchTitle: saved.title, image: saved.poster, malId: Number(animeId), detailType: "anime", detailId: Number(animeId), type: "tv" }; view.insertAdjacentHTML("afterbegin", cover(saved.title, saved.poster) + `<a class="btn" href="${esc(routeFor("anime-watch", lookup))}">▶ تشغيل أنمي فاي</a>`); }
    $("#retry-anime").onclick = () => animeDetail(id, animeId);
  }
}

export function attachTitleExtras(d, en, type) {
  const slot = $("#title-extra"); if (!slot) return;
  const seasons = (d.seasons || []).filter((s) => s.season_number > 0);
  slot.innerHTML = `${type === "tv" && seasons.length ? `<section class="card-box"><h2>المواسم</h2>${seasons.map((s) => `<details><summary>${esc(s.name || `الموسم ${s.season_number}`)} · ${s.episode_count} حلقة · ${esc((s.air_date || "").slice(0, 4))}</summary><p class="overview">${esc(s.overview || "")}</p></details>`).join("")}<h3>تتبع الحلقات</h3><p class="muted">التتبع حسب ترقيم مواسم TMDB. يمكنك اختيار ترقيم الأجزاء في صفحة مصدر المشاهدة.</p><div class="fields"><select class="input" id="track-season">${seasons.map((s) => `<option value="${s.season_number}">الموسم ${s.season_number}</option>`).join("")}</select><select class="input" id="track-episode"></select></div><button class="btn" id="track-save">شاهدت هذه الحلقة</button><button class="btn ghost" id="track-next">الحلقة التالية</button><p class="muted" id="tracked-position"></p></section>` : ""}
    ${(en?.reviews?.results || []).length ? `<section><h2>آراء المشاهدين</h2>${en.reviews.results.slice(0, 5).map((review, i) => `<details class="card-box"><summary>${esc(review.author)} ${review.author_details?.rating ? `· ★ ${review.author_details.rating}` : ""}</summary><p id="review-${i}">${esc(review.content)}</p></details>`).join("")}</section>` : ""}`;
  (en?.reviews?.results || []).slice(0, 5).forEach((r, i) => fillTranslated($("#review-" + i), r.content));
  if (type !== "tv" || !seasons.length) return;
  const key = `tv:${d.id}`, existing = () => watchlist.get(key), seasonSelect = $("#track-season"), episodeSelect = $("#track-episode");
  const renderEpisodes = () => { const season = seasons.find((s) => s.season_number === Number(seasonSelect.value)); episodeSelect.innerHTML = Array.from({ length: Math.min(season?.episode_count || 0, 2000) }, (_, i) => `<option value="${i + 1}">الحلقة ${i + 1}</option>`).join(""); };
  const renderPosition = () => { const p = existing()?.progress; $("#tracked-position").textContent = p ? `وصلت إلى الموسم ${p.season} · الحلقة ${p.episode}` : "لم تسجّل حلقة بعد"; };
  const mark = (season, episode) => { watchlist.upsert({ ...existing(), key, type: "tv", id: d.id, title: d.name, poster: d.poster_path, status: "watching", added: existing()?.added || Date.now(), seasons: seasons.map((s) => ({ number: s.season_number, episodes: s.episode_count })), progress: { season, episode } }); renderPosition(); notify("حفظنا تقدمك في المسلسل"); };
  if (existing()?.progress) seasonSelect.value = String(existing().progress.season);
  if (!seasonSelect.value) seasonSelect.value = String(seasons[0].season_number);
  renderEpisodes(); if (existing()?.progress) episodeSelect.value = String(existing().progress.episode); renderPosition();
  seasonSelect.onchange = renderEpisodes;
  $("#track-save").onclick = () => { if (Number(episodeSelect.value) > 0) mark(Number(seasonSelect.value), Number(episodeSelect.value)); };
  $("#track-next").onclick = () => {
    const p = existing()?.progress, index = p ? seasons.findIndex((s) => s.season_number === p.season) : 0;
    const next = !p ? { season: seasons[0].season_number, episode: 1 } : p.episode < seasons[index]?.episode_count ? { season: p.season, episode: p.episode + 1 } : seasons[index + 1] ? { season: seasons[index + 1].season_number, episode: 1 } : null;
    if (!next) { if (existing()) watchlist.upsert({ ...existing(), status: "done" }); notify("خلصت الحلقات المتاحة"); return; }
    seasonSelect.value = String(next.season); renderEpisodes(); episodeSelect.value = String(next.episode); mark(next.season, next.episode);
  };
}

export async function subscriptionsPage() {
  let editing = null;
  const today = new Date(), localDay = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`;
  const nextRenewal = (sub) => {
    const start = new Date(sub.renewalEpochDay * 86400000), target = new Date(localDay + "T00:00:00Z");
    for (let n = 0; n < 24000; n++) {
      const y = start.getUTCFullYear() + (sub.cycle === "YEARLY" ? n : 0), m = start.getUTCMonth() + (sub.cycle === "YEARLY" ? 0 : n), end = new Date(Date.UTC(y, m + 1, 0));
      const date = new Date(Date.UTC(y, m, Math.min(start.getUTCDate(), end.getUTCDate()))); if (date >= target) return date;
    }
    return start;
  };
  const render = () => {
    const subs = settings.subscriptions || [], total = {};
    for (const sub of subs) total[sub.currency] = (total[sub.currency] || 0) + Number(sub.price) / (sub.cycle === "YEARLY" ? 12 : 1);
    view.innerHTML = `<h1>اشتراكاتي</h1><div class="cards">${Object.entries(total).map(([currency, price]) => `<section class="card-box"><h3>${esc(currency)}</h3><b>${price.toFixed(2)} شهرياً · ${(price * 12).toFixed(2)} سنوياً</b></section>`).join("")}</div><button class="btn" id="new-sub">＋ اشتراك</button>
      <div class="settings">${subs.slice().sort((a, b) => nextRenewal(a) - nextRenewal(b)).map((s) => `<section class="card-box"><h2>${esc(s.name)}</h2><p>${Number(s.price).toFixed(2)} ${esc(s.currency)} · ${s.cycle === "YEARLY" ? "سنوي" : "شهري"}</p><p class="muted">التجديد القادم: ${nextRenewal(s).toLocaleDateString("ar-SA", { calendar: "gregory" })}</p><button class="btn ghost" data-edit-sub="${esc(s.id)}">تعديل</button><button class="btn ghost" data-remove-sub="${esc(s.id)}">حذف</button></section>`).join("") || message("أضف اشتراكاتك لمتابعة المصروفات والتجديد")}</div><div id="sub-form"></div>`;
    $("#new-sub").onclick = () => form(null);
    view.querySelectorAll("[data-edit-sub]").forEach((b) => b.onclick = () => form(subs.find((s) => s.id === b.dataset.editSub)));
    view.querySelectorAll("[data-remove-sub]").forEach((b) => b.onclick = () => { settings.subscriptions = subs.filter((s) => s.id !== b.dataset.removeSub); render(); });
  };
  const form = (sub) => {
    editing = sub;
    $("#sub-form").innerHTML = `<form class="card-box" id="edit-sub"><h2>${sub ? "تعديل" : "إضافة"} اشتراك</h2><label>الاسم<input class="input" name="name" required value="${esc(sub?.name || "")}"></label><label>المبلغ<input class="input" name="price" type="number" step="0.01" min="0" required value="${sub?.price ?? ""}"></label><label>العملة<select class="input" name="currency">${["SAR","USD","AED","KWD","BHD","QAR","OMR","EGP","EUR"].map((c) => `<option ${c === (sub?.currency || "SAR") ? "selected" : ""}>${c}</option>`).join("")}</select></label><label>الدورة<select class="input" name="cycle"><option value="MONTHLY">شهري</option><option value="YEARLY" ${sub?.cycle === "YEARLY" ? "selected" : ""}>سنوي</option></select></label><label>تاريخ التجديد<input class="input" type="date" name="renewal" required value="${sub ? new Date(sub.renewalEpochDay * 86400000).toISOString().slice(0, 10) : localDay}"></label><button class="btn">احفظ</button><button class="btn ghost" type="button" id="cancel-sub">إلغاء</button></form>`;
    $("#sub-form").scrollIntoView({ behavior: "smooth" }); $("#cancel-sub").onclick = () => { $("#sub-form").innerHTML = ""; };
    $("#edit-sub").onsubmit = (event) => { event.preventDefault(); const f = new FormData(event.target), price = Number(f.get("price")); if (!Number.isFinite(price) || price < 0) return;
      const value = { id: editing?.id || crypto.randomUUID(), serviceKey: editing?.serviceKey || null, name: String(f.get("name")).trim(), price, currency: String(f.get("currency")), cycle: String(f.get("cycle")), renewalEpochDay: Math.floor(new Date(f.get("renewal") + "T00:00:00Z").getTime() / 86400000) };
      if (!value.name || !Number.isFinite(value.renewalEpochDay)) return;
      settings.subscriptions = [...(settings.subscriptions || []).filter((s) => s.id !== value.id), value]; render();
    };
  };
  render();
}

export async function downloadsPage(id) {
  const { listDownloads, offlineUrl, pauseDownload, resumeDownload, removeDownload } = await import("./downloads.js?v=20261118d");
  const size = (n) => n >= 1024 ** 3 ? `${(n / 1024 ** 3).toFixed(1)} GB` : `${(n / 1024 ** 2).toFixed(1)} MB`;
  const render = async () => {
    const items = await listDownloads(); if (!active(id)) return;
    const labels = { queued: "في الانتظار", downloading: "جاري التنزيل", paused: "متوقف", complete: "مكتمل", error: "تعذّر التنزيل" };
    view.innerHTML = `<h1>التنزيلات</h1><p class="muted">الملفات محفوظة في هذا المتصفح. مسح بيانات الموقع يحذف التنزيلات.</p><div class="settings">${items.map((item, i) => `<section class="card-box"><h3>${esc(item.entry.title)}</h3><p>${esc(item.entry.subtitle || "")}</p><p>${labels[item.state]} · ${size(item.bytes)}${item.total ? " / " + size(item.total) : ""}</p>${item.total ? `<progress max="${item.total}" value="${item.bytes}" style="width:100%"></progress>` : ""}${item.error ? `<p class="err">${esc(item.error)}</p>` : ""}<div class="chips">${item.state === "complete" ? `<button class="btn" data-local="${i}">▶ تشغيل</button>` : item.state === "downloading" || item.state === "queued" ? `<button class="btn ghost" data-pause="${i}">إيقاف مؤقت</button>` : `<button class="btn" data-resume="${i}">استكمال</button>`}<button class="btn ghost" data-delete="${i}">حذف</button></div></section>`).join("") || message("ما عندك تنزيلات بعد. زر التنزيل متاح من المشغّل وصفحات الحلقات.")}</div>`;
    view.querySelectorAll("[data-local]").forEach((b) => b.onclick = async () => { const item = items[Number(b.dataset.local)], url = await offlineUrl(item.entry.key); if (url) play([url], item.entry); else notify("أعد فتح الموقع لتفعيل تشغيل التنزيلات"); });
    view.querySelectorAll("[data-pause]").forEach((b) => b.onclick = () => pauseDownload(items[Number(b.dataset.pause)].entry.key));
    view.querySelectorAll("[data-resume]").forEach((b) => b.onclick = () => resumeDownload(items[Number(b.dataset.resume)].entry.key));
    view.querySelectorAll("[data-delete]").forEach((b) => b.onclick = () => removeDownload(items[Number(b.dataset.delete)].entry.key));
  };
  let timer = null;
  const onChange = () => { if (!active(id)) { window.removeEventListener("watchhub:downloads", onChange); return; } if (!timer) timer = setTimeout(() => { timer = null; render(); }, 700); };
  window.addEventListener("watchhub:downloads", onChange);
  try { await render(); } catch { view.innerHTML = message("هذا المتصفح لا يسمح بحفظ التنزيلات محلياً"); }
}

export async function websitePage(_id, kind) {
  const kora = kind === "kora", title = kora ? "كوره" : "يوتيوب", url = kora ? "http://majed-kooora.com/" : "https://www.youtube.com/";
  view.innerHTML = `<h1>${title}</h1><section class="card-box"><p class="muted">${kora ? "تابع المباريات والبث في موقع كورة. يفتح في تبويب جديد، وتبقى صفحة Watch Hub محفوظة هنا." : "افتح يوتيوب في تبويب جديد لمتابعة محتواه ومشغّله."}</p><a class="btn" href="${url}" target="_blank" rel="noopener noreferrer">${kora ? "افتح كورة والبث المباشر ↗" : "افتح يوتيوب ↗"}</a></section>`;
}
