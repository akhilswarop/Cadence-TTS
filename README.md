# Cadence TTS

A text-to-speech reader that highlights each word as it's spoken, with a soft
band marking the sentence around it.

Single HTML file, no build step, no backend. Open `index.html` in a browser and
it works.

## Quick start

```bash
git clone https://github.com/akhilswarop/Cadence-TTS.git
cd Cadence-TTS
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
| Open a file | Folder icon, or drag a `.txt` / `.md` / `.pdf` onto the window |
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

`.txt` and `.md` are read directly. Markdown syntax is stripped so headings,
tables, and link URLs aren't read aloud as symbols.

`.pdf` uses [pdf.js](https://mozilla.github.io/pdf.js/), loaded from a CDN on
first use only — text and Markdown stay fully offline. PDFs store glyphs at
coordinates rather than sentences, so fragments are reassembled into lines by
baseline, lines into paragraphs by vertical gap, hyphenated words rejoined, and
running heads, footers, and page numbers dropped by detecting repetition across
pages.

Scanned PDFs contain no text layer and will report that they need OCR.

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

- [ ] **EPUB** via `epub.js`, with chapter navigation
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

The Gradle wrapper jar is a binary and is not committed, so generate it once:

```bash
cd android
gradle wrapper          # or open the android/ folder in Android Studio, which does this on sync
./gradlew assembleDebug
```

The APK lands in `android/app/build/outputs/apk/debug/`.

Requires JDK 17+ and an Android SDK with platform 35. Note that PDF import
fetches pdf.js from a CDN, so a PDF opened in the app needs a network
connection; text and Markdown work fully offline. If you care about offline
PDFs — or about the fact that a CDN script shares the page with a JavaScript
bridge — vendor pdf.js into the assets instead.

## License

MIT — see [LICENSE](LICENSE).
