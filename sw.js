// Service worker: makes the installed app work offline and gives it a
// standalone window instead of just a bookmark. Deliberately narrow in
// scope — it only owns the three files that make up the app shell.
//
// pdf.js is fetched from a CDN at PDF-import time, not here. Cross-origin
// requests are left alone entirely (see the origin check in fetch below),
// so a PDF opened offline still correctly fails with a network error
// instead of silently serving a stale cached copy of the library.

const CACHE = "cadence-tts-v1";
const SHELL = ["./index.html", "./manifest.json", "./icon.svg"];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)));
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((names) =>
      Promise.all(names.filter((n) => n !== CACHE).map((n) => caches.delete(n)))
    )
  );
  self.clients.claim();
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin || event.request.method !== "GET") return;

  // Network-first: an online user always gets the current build, and the
  // cache is refreshed on every successful load. Offline falls back to
  // whatever shell was cached at install time.
  event.respondWith(
    fetch(event.request)
      .then((response) => {
        const copy = response.clone();
        caches.open(CACHE).then((cache) => cache.put(event.request, copy));
        return response;
      })
      .catch(() => caches.match(event.request))
  );
});
