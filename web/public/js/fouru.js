// Built-in 4U source; credentials and channel headers stay on the server.
import { settings } from "./store.js?v=20261117b";
export function endpoint(path, params = {}) {
  const query = new URLSearchParams(params);
  if (settings.proxyKey) query.set("key", settings.proxyKey);
  return `/fouru/${path}?${query}`;
}
async function read(path, params) {
  const response = await fetch(endpoint(path, params));
  if (!response.ok) throw new Error(`تعذّر الاتصال بـ4U (${response.status})`);
  return response.json();
}
export async function matches(series, names, year) {
  return read("match", { series: String(series), names: JSON.stringify(names.filter(Boolean)), year: String(year || 0) });
}
export const series = (id) => read("catalog", { action: "get_series_info", series_id: id });
export const channels = () => read("live");
export const categories = async () => (await read("categories")).map((c) => ({ id: String(c.id), name: c.name }));
export const urls = (ref) => [new URL(endpoint(`stream.${ref.extension || "mp4"}`, { kind: ref.kind, id: ref.id, ext: ref.extension || "mp4" }), location.href).href];
export function entry(row, title, episode = false, tmdbId = null, detailType = null, detailId = null) {
  const itemId = String(episode ? row.id : row.stream_id);
  const kind = episode ? "episode" : "movie";
  return { key: `fouru:${kind}:${itemId}`, kind, itemId, title: title.name, image: title.cover || title.stream_icon || null,
    subtitle: episode ? `الموسم ${row.season} · الحلقة ${row.episode_num}` : "4U", seriesId: episode ? String(title.series_id) : null,
    accountId: "fouru", tmdbId, fourURef: { kind: episode ? "series" : "movie", id: itemId, extension: row.container_extension || "mp4", detailType, detailId } };
}
export const liveEntry = (row) => ({ key: `fouru:live:${row.id}`, kind: "live", itemId: row.id, title: row.name, accountId: "fouru", fourURef: { kind: "live", id: row.id, extension: row.ext || "ts" } });
