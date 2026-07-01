# Self-hosted UI font — Inter (subset)

`inter-variable-subset.woff2` is the dashboard's UI typeface: **Inter, variable weight axis**,
tightly subset to the glyphs the dashboard renders (~25 KB). Self-hosted (FE-3 / D-FE-10) and
served from the app's own loopback origin — **never a CDN / Google Fonts** (INV-LF-01). Wired in
`../fonts.css` (`@font-face`, `font-display: swap`); `--hs-font-sans` (tokens) leads with it and
keeps the system stack as the fallback.

## Provenance (reproducible)

- Source: `@fontsource-variable/inter` v5.2.8 → `files/inter-latin-wght-normal.woff2` (Inter, Latin
  subset, variable `wght` axis). Inter is licensed OFL-1.1.
- Subset tool: `fonttools` (`pyftsubset`) + `brotli`.

Regenerate the exact file:

```bash
npm pack @fontsource-variable/inter           # → fontsource-variable-inter-5.2.8.tgz
tar xzf fontsource-variable-inter-*.tgz
pip install fonttools brotli
python3 - <<'PY'
ascii_print = ''.join(chr(c) for c in range(0x20, 0x7F))
extra = "‘’“”–—←→•·°…×≈±≤≥●✓✕◆▸▾"     # UI punctuation + decorative chain markers
import subprocess
subprocess.run([
    "pyftsubset", "package/files/inter-latin-wght-normal.woff2",
    f"--text={ascii_print + extra}", "--flavor=woff2",
    "--layout-features=kern,liga,calt,tnum", "--no-hinting",
    "--output-file=inter-variable-subset.woff2",
], check=True)
PY
```

Notes:
- The variable `wght` axis is **retained** — one file covers every weight the UI uses (400/500/600).
- The geometric marker glyphs (`●✓✕◆▸▾`) are **not** in Inter's Latin set, so the subsetter skips
  them; they fall back per-glyph and are decorative (`aria-hidden`), which is intentional.
- Keep the subset **tight** (UI glyphs only). If the rendered glyph set grows, extend `extra` and
  re-measure against the bundle/asset budget.
