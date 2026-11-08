# Runs the Watch Hub web app and its proxy on any host that runs containers.
FROM denoland/deno:2.1.4
WORKDIR /app
COPY . .
RUN deno cache server.ts
EXPOSE 8000
CMD ["run", "--allow-net", "--allow-read", "--allow-env", "server.ts"]
