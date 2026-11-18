import { settings } from "./store.js?v=20261118a";
export const PROVIDERS = [
  ["tuktuk", "TukTuk", "https://zx33.tuktuk-sa.online/"],
  ["moviz-time", "Moviz Time", "https://moviz-time.buzz/"],
  ["topcinema", "TopCinema", "https://web6.topcinema.fan/"],
  ["egybest", "EgyBest", "https://egytbest.live/"],
  ["torrent", "تورنت", "https://thepiratebay.org/"],
];
export function endpoint(path, params = {}) {
  const p = new URLSearchParams(params);
  if (settings.proxyKey) p.set("key", settings.proxyKey);
  return new URL(`/sources/${path}?${p}`, location.href).href;
}
export async function read(path, params) {
  const response = await fetch(endpoint(path, params));
  let data;
  try { data = await response.json(); } catch { throw new Error("تعذّر الاتصال بالمصدر، حاول مرة ثانية"); }
  if (!response.ok) throw new Error(data.error || `المصدر غير متاح (${response.status})`);
  return data;
}
export function reference(value) { if (typeof value !== "string") return value; try { return JSON.parse(value); } catch { return null; } }
export async function resolveEntry(entry) {
  const anime = reference(entry.animeRef), source = reference(entry.sourceRef), fouru = reference(entry.fourURef);
  if (fouru) {
    const p = new URLSearchParams({ kind: fouru.kind, id: fouru.id, ext: fouru.extension || "mp4" });
    if (settings.proxyKey) p.set("key", settings.proxyKey);
    return [new URL(`/fouru/stream.${fouru.extension || "mp4"}?${p}`, location.href).href];
  }
  if (!anime && !source) throw new Error("مرجع المصدر غير متاح");
  const result = await read("resolve", { ref: JSON.stringify(anime || source), ...(settings.sourceCatalogue ? { catalogue: settings.sourceCatalogue } : {}) });
  if (!Array.isArray(result.urls) || !result.urls.length || result.urls.some((value) => typeof value !== "string" || !/^https?:\/\//i.test(value))) throw new Error("لم يصل رابط فيديو صالح من المصدر");
  return result.urls;
}
export const animeSearch = (title, movie) => read("anime/search", { title, movie: String(movie) });
export const animeRelated = (title) => title.relation ? read("anime/related", { relation: title.relation, id: title.id }) : Promise.resolve([]);
export const animeEpisodes = (id) => read("anime/episodes", { id });
export const animeServers = (ref) => read("anime/servers", { animeId: ref.animeId, type: ref.type, episode: ref.episode });
export function sourceQuery(lookup, season = 1, episode = 1) {
  return { type: lookup.type === "movie" ? "MOVIE" : "TV", tmdbId: lookup.tmdbId, originalTitle: lookup.originalTitle || lookup.searchTitle || lookup.title,
    searchTitle: lookup.searchTitle || lookup.title, year: Number(lookup.year), ...(lookup.imdbId ? { imdbId: lookup.imdbId } : {}),
    ...(lookup.type === "movie" ? {} : { season, episode, seasonYear: lookup.seasons?.find((s) => s.number === season)?.year }) };
}
export function sourceEntry(lookup, source, variant, query) {
  const episode = query.type === "TV";
  return { key: `source:${query.type.toLowerCase()}:${query.tmdbId}:${query.season || 0}:${query.episode || 0}`, kind: episode ? "episode" : "movie",
    itemId: String(episode ? query.episode : query.tmdbId), accountId: "watch-sources", title: lookup.title, subtitle: episode ? `الموسم ${query.season} · الحلقة ${query.episode}` : source.providerName,
    image: lookup.image, tmdbId: query.tmdbId, seriesId: episode ? String(query.tmdbId) : null,
    sourceRef: { providerId: source.providerId, sourceId: source.id, query, variantId: variant.id, providerName: source.providerName } };
}
export function animeEntry(lookup, title, episode, server) {
  const movie = title.type === "MOVIE";
  return { key: `animeify:${title.id}:${episode.number}`, kind: "anime_episode", itemId: String(episode.number), title: title.title,
    subtitle: movie ? "أنمي فاي" : `الموسم ${title.season} · الحلقة ${episode.number}`, image: title.poster || lookup.image, seriesId: title.id, accountId: "animeify",
    animeRef: { animeId: title.id, malId: title.malId || lookup.malId || 0, type: title.type, season: title.season, episode: String(episode.number), server,
      detailType: lookup.detailType || lookup.type || "anime", detailId: lookup.detailId || lookup.tmdbId || title.malId || 0 } };
}
