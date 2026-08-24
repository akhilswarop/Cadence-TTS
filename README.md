# WordBeat

A text-to-speech reader that highlights each word as it's spoken, with a soft
band marking the sentence around it.

Single HTML file, no build step, no backend. Open `index.html` in a browser and
it works.

Grab a build from the [Releases page](../../releases) — desktop is that same
HTML file zipped with the README and license; Android is an installable APK
built from `android/`.

**Or install it as an app** if it's served over HTTPS (or you're running it
locally): Chrome and Edge show an "Install" icon in the address bar once the
page has loaded, since it ships a manifest and a service worker. Installed,
it opens in its own window with its own icon and keeps working offline —
no download, no build. This needs actual hosting, not the `file://` path;
see the note in [Releasing](#releasing) below.

## Quick start

```bash
git clone https://github.com/akhilswarop/WordBeat.git
cd WordBeat
start index.html      # Windows;  macOS: open index.html
```

Or serve it:

```bash
python -m http.server 8000
```

## Using it

| Action | How |
|---|---|
| Play / pause | Centre button, or **Space** |
| Jump to a word | Click any word |
| Skip a sentence | The `<<` and `>>` buttons |
| Scrub | Click anywhere on the progress bar |
| Change speed | The dial — drag it, scroll it, tap it to cycle, or focus it and use arrow keys (0.75x to 5x) |
| Open a file | Folder icon, or drag a `.txt` / `.md` / `.pdf` / `.epub` onto the window |
| Switch chapters | Chapter dropdown in the toolbar (EPUB only, when a book has more than one chapter) |
| Paste text | Clipboard icon |
| Edit text | Pencil icon |
| Text size | `Aa`, cycles four sizes |
| Clean markup | The list icon, on by default — strips Markdown so symbols aren't read aloud |
| Follow along | The eye icon, keeps the spoken word in view |

## How it works

Everything hinges on one abstraction: a **timing track** mapping character
offsets in the source text to moments in playback. Every TTS engine is a
*producer* of that track; the renderer is its only *consumer*.

```
Source text
   -> Tokenizer      words + exact char offsets
   -> Chunker        sentence-sized utterances, each carrying its absolute offset
   -> TTS Engine     emits absolute char positions as it speaks
   -> Renderer       binary-searches the position back to a word, paints it
```

That seam is the whole design. Swapping Web Speech for Amazon Polly speech
marks, Azure `wordBoundary` events, or a forced aligner means writing a new
producer — the UI never changes.

### Why the fiddly bits exist

- **Chunking is mandatory, not an optimisation.** Chrome silently stops
  synthesis after roughly 15 seconds on long utterances. Chunks are capped near
  240 characters to stay well inside that.
- **`charIndex` is utterance-relative.** Each chunk stores its absolute
  `charOffset`; adding them is what keeps highlighting aligned across a long
  document. Getting this wrong is the classic "highlight drifts halfway
  through" bug.
- **Sentences are tracked separately from chunks.** A chunk is a unit of
  speech and may hold several sentences; the highlight band follows real
  sentence boundaries, or it stretches across a paragraph break and stops
  meaning anything.
- **A generation counter guards every async handler.**
  `speechSynthesis.cancel()` fires `onend` in some browsers, which would
  otherwise advance the queue.
- **There's an estimator fallback.** Chrome's network voices frequently never
  fire `onboundary`. If no boundary arrives shortly after a chunk starts, the
  highlight advances on an estimated speaking rate instead, and a badge on the
  player says so — it's a guess and shouldn't be mistaken for real timing.
- **The clock is an estimate.** Web Speech never reports how long an utterance
  will take, so elapsed and total are derived from word count and rate.
- **Rate changes restart the current chunk.** Web Speech ignores rate changes
  mid-utterance, so there's no way to apply one without re-speaking.
- **`<meta charset="utf-8">` is load-bearing.** Served without a charset
  header, the browser decodes the file as latin-1, which turns a ligature
  character class into an out-of-order range — a `SyntaxError` that kills the
  entire script. Non-ASCII inside the script is written as escapes for the
  same reason.

## Formats

`.txt` is read directly, no network needed.

`.md` is rendered with [marked](https://marked.js.org/), loaded from a CDN on
first use, rather than a hand-rolled parser — CommonMark and GFM (tables,
task lists, nested lists, autolinks, strikethrough) have enough edge cases
that a library already tested against them beats re-discovering those edge
cases one bug report at a time. Its HTML output is parsed into a DOM and run
through the same sanitiser as an EPUB chapter (never trusted via innerHTML)
before word-spans are threaded through it for highlighting, so headings,
emphasis, lists, tables, and images survive into the reader instead of being
flattened to text.

`.pdf` uses [pdf.js](https://mozilla.github.io/pdf.js/), loaded from a CDN on
first use only. PDFs store glyphs at
coordinates rather than sentences, so fragments are reassembled into lines by
baseline, lines into paragraphs by vertical gap, hyphenated words rejoined, and
running heads, footers, and page numbers dropped by detecting repetition across
pages.

Scanned PDFs contain no text layer and will report that they need OCR.

`.epub` uses [JSZip](https://stuk.github.io/jszip/), loaded from a CDN on
first use only, to read the container/OPF/spine and pull out each chapter's
HTML plus its images. Unlike PDF, EPUB chapters are already real (X)HTML, so
they go through the same DOM-to-word-spans pipeline as Markdown rather than
needing PDF's reflow step. Chapter titles come from the book's table of
contents — the EPUB3 nav document if present, the EPUB2 NCX otherwise, since
most real-world EPUB files (including most Calibre conversions) are still
EPUB2. A chapter picker appears in the toolbar whenever a book has more than
one chapter. Because a chapter's HTML comes from outside this app, it's
parsed and rebuilt from scratch rather than trusted: script, style, and
event-handler content is dropped entirely, links are limited to
http/https/mailto, and images are shown only when they resolve to a real
file inside the EPUB's own archive.

## Browser support

| Browser | Word timing |
|---|---|
| Chrome / Edge, local voices | Native `onboundary` — accurate |
| Chrome / Edge, network voices | Usually falls back to the estimator |
| Safari | Native, occasionally sentence-granularity |
| Firefox | Varies by platform voice |

Voices come from the visitor's own OS and browser, so the reading experience
differs per machine.

## Roadmap

Importers are text producers, so new formats slot in without touching the
tokenizer or the engine.

- [ ] **URL / article mode** — requires a proxy endpoint; CORS blocks fetching
      arbitrary pages from a static file
- [ ] **Amazon Polly engine** — precomputed word timings, no estimator needed
- [ ] **CSS Custom Highlight API** rendering — avoids per-word DOM nodes on
      book-length documents, and paints the sentence band as one continuous
      range instead of bridging gaps with box-shadow
- [ ] Vendor pdf.js locally so PDFs work offline too

## Android

`android/` holds a native Android wrapper. It is not a plain WebView port,
because it could not be: **Android WebView does not implement the Web Speech
API**, so the engine the browser build relies on is simply absent there. Wrap
`index.html` in a stock WebView and you get an app that renders perfectly and
cannot speak.

So the app bridges to Android's own `TextToSpeech` instead. That turns out to
be the better fit: `UtteranceProgressListener.onRangeStart` reports the
character range of every word as it is spoken, which is precisely the timing
track the renderer already consumes. `AndroidTtsEngine` in `index.html` is a
second producer next to `WebSpeechEngine`, and nothing downstream changes —
no estimator fallback is needed, because ranges are always real.

Two platform gaps are absorbed by the engine rather than leaked upward:
`TextToSpeech` has no pause, so pausing stops and remembers the position and
resuming re-speaks from that word; and rate applies per utterance, so a rate
change restarts from the current word.

`minSdk` is 26 — `onRangeStart` landed there, and without it there is no word
highlighting at all.

There is one source of truth for the web app: the Gradle `syncWebApp` task
copies `index.html` into assets at build time, so the APK cannot drift from
the browser version.

### Building

**CI (recommended, no local setup):** push to `main` or run the
[Android CI](.github/workflows/android.yml) workflow manually from the
Actions tab. GitHub's runner has a real JDK and Gradle install, so it needs
none of the workarounds below. The signed-with-debug-key APK is attached to
the run as the `wordbeat-debug` artifact.

**Locally, with Android Studio:** open `android/` and let Studio generate the
Gradle wrapper on sync, then

```bash
cd android
./gradlew assembleDebug
```

The APK lands in `android/app/build/outputs/apk/debug/`. Requires JDK 17+ and
an Android SDK with platform 35.

**Locally, without Gradle at all:** `android/build-direct.ps1` drives the SDK
command-line tools directly — `aapt2`, `javac`, `d8`, `apksigner`,
`zipalign` — bypassing Gradle entirely. It exists for exactly one situation:
Android Studio is installed but its bundled Gradle isn't exposed as a plain
`gradle` binary, and no standalone Gradle distribution is present, so there
is no `gradle wrapper` to run in the first place.

```powershell
cd android
.\build-direct.ps1 -Install    # -Install pushes to a connected device over adb
```

It has no dependency resolution and no incremental compilation, and won't
scale past what this single-module app currently is — reach for the CI
workflow or a real Gradle install once either is available. See the comment
at the top of the script for the full explanation.

Note that PDF and Markdown both fetch a library from a CDN on first use
(pdf.js, marked), so opening either needs a network connection; only plain
text works fully offline. If you care about offline PDFs or Markdown — or
about the fact that a CDN script shares the page with a JavaScript bridge —
vendor the relevant library into the assets instead.

## Releasing

Push a tag matching `v*` (e.g. `v1.0.0`) and
[Release](.github/workflows/release.yml) builds both artifacts and publishes
them together as a GitHub Release: the desktop zip (`index.html` + README +
license) and the Android APK, version-stamped from the tag.

```bash
git tag v1.0.0
git push origin v1.0.0
```

The Android build there is debug-signed, the same as CI — see the note at
the top of the workflow if you want a real release signing key instead.

**Hosting the installable app:** this needs GitHub Pages (or any static
host) — a service worker won't register over `file://`. Pages works out of
the box on a public repo; a private one may need a paid plan depending on
your GitHub account type. `Settings → Pages → Deploy from a branch → main
→ / (root)`.

## License

MIT — see [LICENSE](LICENSE).
