// Plays MKV/AVI films on iPhone, iPad and Safari, which cannot open those files: ffmpeg rewraps the
// provider's file as HLS while it streams (the picture is copied, not re-encoded; the sound becomes
// AAC so Apple devices always play it). One film at a time: opening another stops the previous one.
//
//   GET /remux/open?url=<film>&t=<start seconds>&key=<PROXY_KEY>  → { playlist, duration }
//   GET /remux/<id>/index.m3u8 | init.mp4 | segNNNNN.m4s

const PROXY_KEY = Deno.env.get("PROXY_KEY") ?? "";
const ROOT = "/tmp/remux";
const UA = "Mozilla/5.0 (WatchHub web)";
const CORS = { "access-control-allow-origin": "*" };
const SEGMENT = 6;

type Job = { id: string; dir: string; proc: Deno.ChildProcess; touched: number; exited: boolean };
let job: Job | null = null;

function stop() {
  const j = job;
  job = null;
  if (!j) return;
  try { j.proc.kill("SIGKILL"); } catch { /* already gone */ }
  Deno.remove(j.dir, { recursive: true }).catch(() => {});
}

// Nobody watching for three minutes: free the server.
setInterval(() => { if (job && Date.now() - job.touched > 3 * 60_000) stop(); }, 30_000);

const fail = (status: number, error: string) => Response.json({ error }, { status, headers: CORS });

async function probe(src: string): Promise<{ duration: number; codec: string }> {
  try {
    const out = await new Deno.Command("ffprobe", {
      args: ["-v", "error", "-user_agent", UA, "-show_entries", "format=duration:stream=codec_name,codec_type", "-of", "json", src],
      stdout: "piped", stderr: "null", signal: AbortSignal.timeout(20_000),
    }).output();
    const j = JSON.parse(new TextDecoder().decode(out.stdout));
    const video = (j.streams || []).find((s: { codec_type?: string }) => s.codec_type === "video");
    return { duration: Number(j.format?.duration) || 0, codec: video?.codec_name || "" };
  } catch {
    return { duration: 0, codec: "" };
  }
}

async function open(url: URL): Promise<Response> {
  if (PROXY_KEY && url.searchParams.get("key") !== PROXY_KEY) return fail(401, "wrong proxy key");
  const src = url.searchParams.get("url") || "";
  if (!/^https?:\/\//i.test(src)) return fail(400, "bad url");
  const start = Math.max(0, Math.floor(Number(url.searchParams.get("t")) || 0));
  stop();

  const info = await probe(src);
  const id = crypto.randomUUID();
  const dir = `${ROOT}/${id}`;
  await Deno.mkdir(dir, { recursive: true });
  const args = [
    "-nostdin", "-loglevel", "error", "-user_agent", UA,
    "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5",
    // Twice real time: always ahead of the viewer without downloading the whole film at once.
    "-readrate", "2",
    ...(start > 0 ? ["-ss", String(start)] : []),
    "-i", src,
    "-map", "0:v:0", "-map", "0:a:0?",
    "-c:v", "copy", ...(info.codec === "hevc" ? ["-tag:v", "hvc1"] : []),
    "-c:a", "aac", "-ac", "2", "-b:a", "160k",
    "-f", "hls", "-hls_time", String(SEGMENT), "-hls_list_size", "0", "-hls_playlist_type", "event",
    "-hls_segment_type", "fmp4", "-hls_fmp4_init_filename", "init.mp4",
    "-hls_segment_filename", `${dir}/seg%05d.m4s`, `${dir}/index.m3u8`,
  ];
  const proc = new Deno.Command("ffmpeg", { args, stdin: "null", stdout: "null", stderr: "piped" }).spawn();
  const current: Job = { id, dir, proc, touched: Date.now(), exited: false };
  job = current;
  proc.status.then(() => { current.exited = true; });
  // Keep ffmpeg's last complaint: it is the reason shown when the film cannot be read.
  let complaint = "";
  (async () => {
    const decoder = new TextDecoder();
    for await (const chunk of proc.stderr) complaint = (complaint + decoder.decode(chunk)).slice(-600);
  })().catch(() => {});

  // Ready once the playlist lists its first segment.
  const until = Date.now() + 45_000;
  while (Date.now() < until) {
    if (job !== current) return fail(409, "replaced");
    const text = await Deno.readTextFile(`${dir}/index.m3u8`).catch(() => "");
    if (/\.m4s/.test(text)) return Response.json({ playlist: `/remux/${id}/index.m3u8`, duration: info.duration, start }, { headers: { ...CORS, "cache-control": "no-store" } });
    if (current.exited) break;
    await new Promise((r) => setTimeout(r, 400));
  }
  if (job === current) stop();
  const reason = complaint.trim().split("\n").pop() || (current.exited ? "ffmpeg stopped" : "timed out");
  console.error("WATCHHUB_REMUX_FAILED", new URL(src).host, reason);
  return fail(502, reason.replace(/https?:\/\/\S+/g, "[url]").slice(0, 200));
}

const TYPES: Record<string, string> = { m3u8: "application/vnd.apple.mpegurl", mp4: "video/mp4", m4s: "video/iso.segment" };

async function file(path: string): Promise<Response> {
  const [, , id, name] = path.split("/");
  if (!job || job.id !== id || !/^(index\.m3u8|init\.mp4|seg\d{5}\.m4s)$/.test(name || "")) return fail(404, "gone");
  job.touched = Date.now();
  // Keep the disk small: segments a quarter of an hour behind the one being watched go.
  const n = name.match(/^seg(\d{5})\.m4s$/)?.[1];
  if (n) {
    const old = Number(n) - Math.ceil(15 * 60 / SEGMENT);
    if (old >= 0) Deno.remove(`${job.dir}/seg${String(old).padStart(5, "0")}.m4s`).catch(() => {});
  }
  try {
    const body = await Deno.readFile(`${job.dir}/${name}`);
    const ext = name.split(".").pop() || "";
    return new Response(body, { headers: { ...CORS, "content-type": TYPES[ext] || "application/octet-stream", "cache-control": ext === "m3u8" ? "no-store" : "max-age=3600" } });
  } catch {
    return fail(404, "not ready");
  }
}

export function handleRemux(_req: Request, url: URL): Promise<Response> {
  if (url.pathname === "/remux/open") return open(url);
  return file(url.pathname);
}
