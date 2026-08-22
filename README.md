# Cadence

A text-to-speech reader that highlights each word as it's spoken.

Single HTML file, no build step, no dependencies, no backend. Open `index.html` in a browser and it works.

## Quick start

```bash
git clone https://github.com/akhilswarop/Cadence.git
cd Cadence
start index.html      # Windows;  macOS: open index.html
```

Or serve it:

```bash
python -m http.server 8000
```

## Using it

| Action | How |
|---|---|
| Play / pause | `Play` button or **Space** |
| Jump to a word | Click any word |
| Change speed | Rate slider (0.5×–5×) |
| Load a file | `Open file`, or drag a `.txt` / `.md` onto the window |
| Paste text | `Paste` button, or `Edit text` for a textarea |
| Follow along | `Follow` keeps the spoken word in view |

## How it works

Everything hinges on one abstraction — a **timing track** that maps character
offsets in the source text to moments in the audio. Every TTS engine is a
*producer* of that track; the highlighter is its only *consumer*.

```
Source text
   → Tokenizer      words + exact char offsets
   → Chunker        sentence-sized utterances, each carrying its absolute offset
   → TTS Engine     emits absolute char positions as it speaks
   → Renderer       binary-searches the position back to a word, paints it
```

That seam is the whole design. Swapping Web Speech for Amazon Polly speech
marks, Azure `wordBoundary` events, or a forced aligner means writing a new
producer — the UI never changes.

### Why the fiddly bits exist

- **Chunking is mandatory, not an optimization.** Chrome silently stops
  synthesis after roughly 15 seconds on long utterances. Chunks are capped at
  ~240 characters to stay well inside that.
- **`charIndex` is utterance-relative.** Each chunk stores its absolute
  `charOffset`; adding them is what keeps highlighting aligned across a long
  document. Getting this wrong is the classic "highlight drifts halfway
  through" bug.
- **A generation counter guards every async handler.** `speechSynthesis.cancel()`
  fires `onend` in some browsers, which would otherwise advance the queue.
- **There's an estimator fallback.** Chrome's network voices frequently never
  fire `onboundary` at all. If no boundary arrives shortly after a chunk
  starts, the highlight advances on an estimated speaking rate instead — and
  the status bar says `word timing: estimated`, because it's a guess and
  shouldn't be mistaken for real timing data.
- **Rate changes restart the current chunk.** Web Speech ignores rate changes
  mid-utterance, so there's no way to apply one without re-speaking.

## Browser support

| Browser | Word timing |
|---|---|
| Chrome / Edge, local voices | Native `onboundary` — accurate |
| Chrome / Edge, network voices | Usually falls back to the estimator |
| Safari | Native, occasionally sentence-granularity |
| Firefox | Varies by platform voice |

The status pill in the footer always reports which mode is active.

## Roadmap

Importers are text producers, so new formats slot in without touching the
tokenizer or the engine.

- [ ] **PDF** via `pdf.js` — needs a de-hyphenation pass, or `inter-\nruption`
      gets read aloud as two broken words
- [ ] **EPUB** via `epub.js`, with chapter navigation
- [ ] **URL / article mode** — requires a proxy endpoint; CORS blocks fetching
      arbitrary pages from a static file
- [ ] **Amazon Polly engine** — precomputed word timings, no estimator needed
- [ ] **CSS Custom Highlight API** rendering — avoids per-word DOM nodes on
      book-length documents

## License

Not yet chosen — add one before making the repo public if that matters to you.
