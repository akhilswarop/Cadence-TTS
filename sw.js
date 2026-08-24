// Service worker: makes the installed app work offline and gives it a
// standalone window instead of just a bookmark. Deliberately narrow in
// scope — it only owns the three files that make up the app shell.
//
// pdf.js and JSZip are fetched from a CDN at import time, not here.
// Cross-origin requests are left alone entirely (see the origin check in
// fetch below), so opening a PDF or EPUB offline correctly fails with a
// network error instead of silently serving a stale cached copy of the
// library.

// Bumped deliberately, twice now: v2 shipped a real fix for a stale cache
// that was serving old app code to returning visitors (see below), and v3
// is the Cadence TTS -> WordBeat rebrand — the activate handler already
// deletes every cache except this name, so bumping it is what actually
// clears the previous name's cache out from under someone who has the site
// installed. There is no other reason to bump this on routine changes; the
// network-first fetch handler already keeps this entry current on every
// successful load.
const CACHE = "wordbeat-v3";
const SHELL = ["./index.html", "./manifest.json", "./icon.svg"];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE).then((cache) =>
      Promise.all(
        // Not cache.addAll(SHELL): that fetches with default caching, which
        // can silently be satisfied by the browser's own HTTP cache and
        // precache an already-stale response. no-store forces a real
        // network round-trip for the one-time seed too.
        SHELL.map((url) =>
          fetch(url, { cache: "no-store" }).then((res) => cache.put(url, res))
        )
      )
    )
  );
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
  //
  // { cache: "no-store" } here is load-bearing, not defensive. Without it
  // this fetch() is still subject to the browser's ordinary HTTP cache —
  // a layer entirely separate from the Cache Storage API above — so
  // "network-first" can silently resolve to a cached HTTP response
  // without a real request ever reaching the server. That's exactly what
  // happened in production: a page load can look identical whether it
  // served fresh code or code from hours earlier, and there is no visible
  // sign anything is wrong — the app just quietly runs stale.
  event.respondWith(
    fetch(event.request, { cache: "no-store" })
      .then((response) => {
        const copy = response.clone();
        caches.open(CACHE).then((cache) => cache.put(event.request, copy));
        return response;
      })
      .catch(() => caches.match(event.request))
  );
});
