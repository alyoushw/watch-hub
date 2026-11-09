// Watch Hub (web): serves the app from ./public and proxies the user's Xtream Codes server.
//
// Browsers on an https page cannot reach most Xtream servers (plain http, no CORS), so the
// page sends those requests through /proxy?url=… . HLS playlists are rewritten so every
// segment also goes through the proxy. Runs on Deno anywhere (Render, Docker, your own machine).
//
// Optional environment variables:
//   PROXY_KEY    a password the page must send (set it in the app's settings too)
//   PROXY_HOSTS  comma-separated hosts the proxy may reach, e.g. "example.com:8080"
import { serveDir } from "jsr:@std/http@1.0.10/file-server";

const PROXY_KEY = Deno.env.get("PROXY_KEY") ?? "";
const PROXY_HOSTS = (Deno.env.get("PROXY_HOSTS") ?? "")
  .split(",").map((h) => h.trim().toLowerCase()).filter(Boolean);
const PUBLIC_DIR = new URL("./public", import.meta.url).pathname;

const CORS: Record<string, string> = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET, HEAD, POST, OPTIONS",
  "access-control-allow-headers": "range, content-type",
  "access-control-expose-headers": "content-length, content-range, accept-ranges, content-type",
};
const VIDEO_TYPES: Record<string, string> = {
  mp4: "video/mp4",
  m4v: "video/mp4",
  mov: "video/quicktime",
  mkv: "video/x-matroska",
  ts: "video/mp2t",
  avi: "video/x-msvideo",
};
const PASS_HEADERS = ["content-type", "content-length", "content-range", "accept-ranges", "last-modified", "etag"];

function fail(status: number, message: string): Response {
  return new Response(message, { status, headers: { ...CORS, "content-type": "text/plain; charset=utf-8" } });
}

async function proxy(req: Request, self: URL): Promise<Response> {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  if (PROXY_KEY && self.searchParams.get("key") !== PROXY_KEY) return fail(401, "wrong proxy key");

  let target: URL;
  try {
    target = new URL(self.searchParams.get("url") ?? "");
  } catch {
    return fail(400, "missing or bad url");
  }
  if (target.protocol !== "http:" && target.protocol !== "https:") return fail(400, "only http(s)");
  if (PROXY_HOSTS.length && !PROXY_HOSTS.includes(target.host.toLowerCase()) &&
    !PROXY_HOSTS.includes(target.hostname.toLowerCase())) {
    return fail(403, "host not allowed");
  }

  const headers = new Headers({ "user-agent": "Mozilla/5.0 (WatchHub web)" });
  const range = req.headers.get("range");
  if (range) headers.set("range", range);

  let upstream: Response;
  try {
    upstream = await fetch(target, { headers, redirect: "follow", signal: req.signal });
  } catch (e) {
    return fail(502, `could not reach server: ${e instanceof Error ? e.message : e}`);
  }

  const out = new Headers(CORS);
  for (const h of PASS_HEADERS) {
    const v = upstream.headers.get(h);
    if (v) out.set(h, v);
  }

  const finalUrl = upstream.url || target.href;
  const type = upstream.headers.get("content-type") ?? "";
  // Safari will not play an MP4 sent as "application/octet-stream", which many panels do.
  if (!type || /octet-stream|text\/plain/i.test(type)) {
    const ext = target.pathname.split(".").pop()?.toLowerCase() ?? "";
    const named = VIDEO_TYPES[ext];
    if (named) out.set("content-type", named);
  }
  if (/mpegurl/i.test(type) || /\.m3u8?(\?|$)/i.test(new URL(finalUrl).pathname)) {
    const text = await upstream.text();
    out.delete("content-length");
    out.set("content-type", "application/vnd.apple.mpegurl");
    out.set("cache-control", "no-store");
    return new Response(rewritePlaylist(text, finalUrl, self), { status: upstream.status, headers: out });
  }
  return new Response(upstream.body, { status: upstream.status, headers: out });
}

/** Points every URL in an HLS playlist (segments, keys, sub-playlists) back at this proxy. */
function rewritePlaylist(text: string, base: string, self: URL): string {
  const wrap = (u: string) => {
    const abs = new URL(u, base).href;
    const key = PROXY_KEY ? `&key=${encodeURIComponent(PROXY_KEY)}` : "";
    return `${self.origin}/proxy?url=${encodeURIComponent(abs)}${key}`;
  };
  return text.split("\n").map((line) => {
    const l = line.trim();
    if (!l) return line;
    if (l.startsWith("#")) return line.replace(/URI="([^"]+)"/g, (_m, u) => `URI="${wrap(u)}"`);
    return wrap(l);
  }).join("\n");
}

// Hosts like Render pass the port to listen on in $PORT.
const port = Number(Deno.env.get("PORT") ?? 8000);

// ---------- Watch Hub account (Google sign-in + sync through Firebase) ----------
//
// Set on the host (Render › Environment). Only the public values are ever sent to devices;
// GOOGLE_TV_CLIENT_SECRET stays on this server.
//   FIREBASE_API_KEY, FIREBASE_PROJECT_ID, FIREBASE_APP_ID (web app), FIREBASE_ANDROID_APP_ID,
//   GOOGLE_WEB_CLIENT_ID, GOOGLE_TV_CLIENT_ID, GOOGLE_TV_CLIENT_SECRET
const env = (k: string) => (Deno.env.get(k) ?? "").trim();

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...CORS, "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

/** Public settings every device (phone, TV, browser) reads to sign in and sync. */
function config(): Response {
  const projectId = env("FIREBASE_PROJECT_ID");
  const apiKey = env("FIREBASE_API_KEY");
  if (!projectId || !apiKey) return json({ enabled: false });
  return json({
    enabled: true,
    firebase: {
      apiKey,
      projectId,
      appId: env("FIREBASE_APP_ID"),
      androidAppId: env("FIREBASE_ANDROID_APP_ID") || env("FIREBASE_APP_ID"),
      authDomain: `${projectId}.firebaseapp.com`,
    },
    googleWebClientId: env("GOOGLE_WEB_CLIENT_ID"),
    deviceLogin: Boolean(env("GOOGLE_TV_CLIENT_ID") && env("GOOGLE_TV_CLIENT_SECRET")),
    qrLogin: pairingReady(),
    // Only for testing against the local Firebase emulators (never set on the real site).
    ...(env("WATCHHUB_EMULATOR_HOST") ? { emulatorHost: env("WATCHHUB_EMULATOR_HOST") } : {}),
  });
}

/**
 * Sign-in with a code, for TVs (and any device): Google's "limited input device" flow.
 * start → the device shows user_code and google.com/device; poll → once approved, an ID token.
 */
async function deviceStart(): Promise<Response> {
  const clientId = env("GOOGLE_TV_CLIENT_ID");
  if (!clientId) return json({ error: "not_configured" }, 501);
  const res = await fetch("https://oauth2.googleapis.com/device/code", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ client_id: clientId, scope: "openid email profile" }),
  });
  const data = await res.json();
  if (!res.ok) return json({ error: data.error || "google_error" }, 502);
  return json({
    deviceCode: data.device_code,
    userCode: data.user_code,
    verificationUrl: data.verification_url,
    expiresIn: data.expires_in,
    interval: data.interval,
  });
}

async function devicePoll(req: Request): Promise<Response> {
  const clientId = env("GOOGLE_TV_CLIENT_ID");
  const secret = env("GOOGLE_TV_CLIENT_SECRET");
  if (!clientId || !secret) return json({ error: "not_configured" }, 501);
  let deviceCode = "";
  try {
    deviceCode = String((await req.json()).deviceCode || "");
  } catch { /* bad body */ }
  if (!deviceCode) return json({ status: "error" }, 400);
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: clientId,
      client_secret: secret,
      device_code: deviceCode,
      grant_type: "urn:ietf:params:oauth:grant-type:device_code",
    }),
  });
  const data = await res.json();
  if (res.ok && data.id_token) return json({ status: "ok", idToken: data.id_token });
  const map: Record<string, string> = {
    authorization_pending: "pending",
    slow_down: "slow_down",
    access_denied: "denied",
    expired_token: "expired",
  };
  return json({ status: map[data.error] || "error" });
}

// ---------- "scan to sign in": a TV shows a QR code, a signed-in phone approves ----------
//
// TV:    POST /pair/start {device}            → {id, secret, url, expiresIn}
//        POST /pair/poll  {id, secret}        → {status: pending|approved|denied|expired, token?}
// Phone: GET  /pair/info?id=                  → {device, status}
//        POST /pair/approve {id, idToken, approve}
// The phone proves who it is with its Firebase ID token; once approved, this server mints a
// Firebase custom token for that same user (needs FIREBASE_SERVICE_ACCOUNT, the JSON key from
// Firebase › Project settings › Service accounts). Sessions live 10 minutes, in memory, used once.

interface Pairing {
  secret: string;
  device: string;
  created: number;
  status: "pending" | "approved" | "denied";
  uid?: string;
}
const pairings = new Map<string, Pairing>();
const PAIR_TTL_MS = 10 * 60 * 1000;

const randomId = (bytes: number) =>
  Array.from(crypto.getRandomValues(new Uint8Array(bytes)), (b) => b.toString(16).padStart(2, "0")).join("");

function livePairing(id: string): Pairing | null {
  const p = pairings.get(id);
  if (!p) return null;
  if (Date.now() - p.created > PAIR_TTL_MS) {
    pairings.delete(id);
    return null;
  }
  return p;
}

async function readJson(req: Request): Promise<Record<string, unknown>> {
  try {
    return await req.json();
  } catch {
    return {};
  }
}

const pairingReady = () => Boolean(env("FIREBASE_SERVICE_ACCOUNT") && env("FIREBASE_API_KEY"));

async function pairStart(req: Request, self: URL): Promise<Response> {
  if (!pairingReady()) return json({ error: "not_configured" }, 501);
  for (const [k, p] of pairings) if (Date.now() - p.created > PAIR_TTL_MS) pairings.delete(k);
  if (pairings.size > 500) return json({ error: "busy" }, 503);
  const body = await readJson(req);
  const id = randomId(16);
  const secret = randomId(24);
  const device = String(body.device || "جهاز").slice(0, 60);
  pairings.set(id, { secret, device, created: Date.now(), status: "pending" });
  const origin = env("PUBLIC_URL") || `https://${self.host}`;
  return json({ id, secret, url: `${origin}/pair?s=${id}`, expiresIn: PAIR_TTL_MS / 1000 });
}

async function pairPoll(req: Request): Promise<Response> {
  const body = await readJson(req);
  const p = livePairing(String(body.id || ""));
  if (!p || p.secret !== body.secret) return json({ status: "expired" });
  if (p.status === "pending") return json({ status: "pending" });
  pairings.delete(String(body.id)); // used once
  if (p.status === "denied" || !p.uid) return json({ status: "denied" });
  try {
    return json({ status: "approved", token: await customToken(p.uid) });
  } catch (e) {
    console.error("custom token failed", e);
    return json({ status: "error" }, 500);
  }
}

function pairInfo(self: URL): Response {
  const p = livePairing(self.searchParams.get("id") || "");
  if (!p) return json({ status: "expired" });
  return json({ status: p.status, device: p.device });
}

async function pairApprove(req: Request): Promise<Response> {
  const body = await readJson(req);
  const p = livePairing(String(body.id || ""));
  if (!p || p.status !== "pending") return json({ status: "expired" });
  if (body.approve === false) {
    p.status = "denied";
    return json({ status: "denied" });
  }
  const uid = await uidFromIdToken(String(body.idToken || ""));
  if (!uid) return json({ status: "unauthorized" }, 401);
  p.status = "approved";
  p.uid = uid;
  return json({ status: "approved" });
}

/** The user behind a Firebase ID token (checked by Google), or null. */
async function uidFromIdToken(idToken: string): Promise<string | null> {
  if (!idToken) return null;
  const emu = env("WATCHHUB_EMULATOR_HOST");
  const base = emu ? `http://${emu}:9099/identitytoolkit.googleapis.com` : "https://identitytoolkit.googleapis.com";
  try {
    const res = await fetch(`${base}/v1/accounts:lookup?key=${encodeURIComponent(env("FIREBASE_API_KEY"))}`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ idToken }),
    });
    if (!res.ok) return null;
    const data = await res.json();
    return data.users?.[0]?.localId ?? null;
  } catch {
    return null;
  }
}

const b64url = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const b64urlText = (text: string) => b64url(new TextEncoder().encode(text));

let signingKey: CryptoKey | null = null;

/** A Firebase custom token for [uid], signed with the service account's key (RS256). */
async function customToken(uid: string): Promise<string> {
  const sa = JSON.parse(env("FIREBASE_SERVICE_ACCOUNT"));
  if (!signingKey) {
    const pem = String(sa.private_key).replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
    const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
    signingKey = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  }
  const now = Math.floor(Date.now() / 1000);
  const head = b64urlText(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64urlText(JSON.stringify({
    iss: sa.client_email,
    sub: sa.client_email,
    aud: "https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit",
    iat: now,
    exp: now + 3600,
    uid,
  }));
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", signingKey, new TextEncoder().encode(`${head}.${claims}`)));
  return `${head}.${claims}.${b64url(sig)}`;
}

/**
 * Android App Links: lets a phone with the app installed open /pair links straight in the app.
 * The fingerprint is the APK's signing key (SHA-256); ANDROID_CERT_SHA256 overrides it.
 */
function assetLinks(): Response {
  const sha = env("ANDROID_CERT_SHA256") ||
    "37:90:8B:06:67:09:2A:42:8D:D2:2A:7A:C4:06:5E:5C:CC:AA:FD:76:8F:50:02:12:19:FE:42:47:EA:69:BF:09";
  return json([{
    relation: ["delegate_permission/common.handle_all_urls"],
    target: { namespace: "android_app", package_name: "com.alyoushw.watchhub", sha256_cert_fingerprints: [sha] },
  }]);
}

Deno.serve({ port }, (req) => {
  const url = new URL(req.url);
  if (url.pathname === "/proxy") return proxy(req, url);
  if (req.method === "OPTIONS" && url.pathname.startsWith("/auth/")) return new Response(null, { status: 204, headers: CORS });
  if (url.pathname === "/config.json") return config();
  if (url.pathname === "/.well-known/assetlinks.json") return assetLinks();
  // Opened by a phone's camera without the app: the web app's approval page.
  if (url.pathname === "/pair") return Response.redirect(`${url.origin}/#/pair?s=${encodeURIComponent(url.searchParams.get("s") || "")}`, 302);
  if (url.pathname === "/pair/start" && req.method === "POST") return pairStart(req, url);
  if (url.pathname === "/pair/poll" && req.method === "POST") return pairPoll(req);
  if (url.pathname === "/pair/info") return pairInfo(url);
  if (url.pathname === "/pair/approve" && req.method === "POST") return pairApprove(req);
  if (req.method === "OPTIONS" && url.pathname.startsWith("/pair/")) return new Response(null, { status: 204, headers: CORS });
  if (url.pathname === "/auth/device/start" && req.method === "POST") return deviceStart();
  if (url.pathname === "/auth/device/poll" && req.method === "POST") return devicePoll(req);
  return serveDir(req, { fsRoot: PUBLIC_DIR, quiet: true });
});
