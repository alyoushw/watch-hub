// English → Arabic for stories and biographies TMDB only has in English. Uses Google's public
// translate endpoint through the site's proxy (it does not answer browsers directly), and
// MyMemory as a fallback. Results are kept on the device so each text is translated once.
import { via } from "./xtream.js";

const KEY = "watchhub-tr";
let memo;
try {
  memo = JSON.parse(localStorage.getItem(KEY) || "{}");
} catch {
  memo = {};
}

function remember(text, out) {
  memo[text] = out;
  const keys = Object.keys(memo);
  if (keys.length > 300) keys.slice(0, keys.length - 300).forEach((k) => delete memo[k]);
  try {
    localStorage.setItem(KEY, JSON.stringify(memo));
  } catch { /* full or private: memory only */ }
}

export const looksEnglish = (t) => !!t && !/[؀-ۿ]/.test(t) && /[a-z]/i.test(t);

async function google(text) {
  const url = `https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=ar&dt=t&q=${encodeURIComponent(text)}`;
  const res = await fetch(via(url));
  if (!res.ok) throw new Error("google");
  const data = await res.json();
  return data[0].map((part) => part[0]).join("");
}

async function myMemory(text) {
  const res = await fetch(`https://api.mymemory.translated.net/get?langpair=en|ar&q=${encodeURIComponent(text.slice(0, 500))}`);
  const data = await res.json();
  const out = data && data.responseData && data.responseData.translatedText;
  if (!out || /MYMEMORY WARNING/i.test(out)) throw new Error("mymemory");
  return out;
}

/** Arabic for [text], or null when no translation could be had. */
export async function toArabic(text) {
  if (!looksEnglish(text)) return null;
  if (memo[text]) return memo[text];
  for (const fn of [google, myMemory]) {
    try {
      const out = (await fn(text)).trim();
      if (out) {
        remember(text, out);
        return out;
      }
    } catch { /* try the next one */ }
  }
  return null;
}

/**
 * Fills [el] with [text], translated to Arabic when it is English, with a small link to
 * switch between the translation and the original.
 */
export async function fillTranslated(el, text) {
  el.textContent = text;
  if (!looksEnglish(text)) return;
  const note = document.createElement("small");
  note.className = "muted tr-note";
  note.textContent = " · نترجم…";
  el.after(note);
  const ar = await toArabic(text);
  if (!ar || !el.isConnected) {
    note.remove();
    return;
  }
  let showing = "ar";
  el.textContent = ar;
  el.dir = "rtl";
  note.innerHTML = "";
  const toggle = document.createElement("button");
  toggle.className = "linklike";
  toggle.textContent = "مترجم تلقائياً · اعرض الأصل";
  toggle.addEventListener("click", () => {
    showing = showing === "ar" ? "en" : "ar";
    el.textContent = showing === "ar" ? ar : text;
    el.dir = showing === "ar" ? "rtl" : "ltr";
    toggle.textContent = showing === "ar" ? "مترجم تلقائياً · اعرض الأصل" : "اعرض الترجمة";
  });
  note.appendChild(toggle);
}
