# Runs the Watch Hub web app and its proxy on any host that runs containers.
FROM denoland/deno:2.1.4
# ffmpeg rewraps MKV/AVI films as HLS for iPhone, iPad and Safari (web/remux.ts).
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY . .
RUN deno cache server.ts
EXPOSE 8000
CMD ["run", "--allow-net", "--allow-read", "--allow-env", "--allow-run=ffmpeg,ffprobe", "--allow-write=/tmp/remux", "server.ts"]
