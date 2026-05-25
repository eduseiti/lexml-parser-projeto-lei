# Plan — Batch-apply `GeraCSVporArtigoPorAgrupador.xsl` to a folder of LexML documents

**Date:** 2026-05-24
**Author:** Eduardo Seiti de Oliveira (with Claude Code)
**Goal:** A Python script that runs `scripts/GeraCSVporArtigoPorAgrupador.xsl` against every
LexML XML document in an input folder, writes the resulting CSV(s) into an output folder
(creating per-document subfolders when a document produces more than one output), and emits
a processing report with per-document success/failure details and overall rates.

---

## 1. Context & findings

### 1.1 The stylesheet
- `scripts/GeraCSVporArtigoPorAgrupador.xsl` is **XSLT 3.0** (`version="3.0"`, uses
  `xpath-default-namespace`, `document-uri()`, `replace()`).
- `xsl:output method="text"` — emits **plain text (CSV)**, not XML.
- It produces **exactly one output stream per input file** (no `xsl:result-document`), with
  header `Tipo,Rotulo,Num_Inicio,Num_Final,Texto,urn`.
- Two stylesheet params, both with defaults:
  - `sigla` — defaults to input filename minus `.xml`.
  - `escopo` — defaults to `//Ementa` of the document.
  We rely on the defaults; **`sigla` defaults from `document-uri()`**, so the script must pass
  the real source file to Saxon (not stdin) for the default to be meaningful.

### 1.2 The input folder (`../novas_normas_20260420/lexml_manual_20260524/`)
- LexML documents have extension **`.xml`** (not `.lexml`). Root element `<LexML>` in
  namespace `http://www.lexml.gov.br/1.0`.
- Folder also contains noise to **exclude**: `*.err.log` and `skipped_report.txt`.
- **Anexo families:** a logical document can span a main file plus anexo siblings, e.g.
  `decreto_2338_1997.xml`, `decreto_2338_1997.anexo1.xml`, `decreto_2338_1997.anexo2.xml`.
  These share the stem `decreto_2338_1997`. This is the practical source of "multiple
  outputs per document" (the XSL itself does not split a single file).

### 1.3 Tooling (decided)
- **Engine: `saxonche`** (SaxonC-HE Python package). `lxml` (XSLT 1.0 only) **cannot** run
  this stylesheet, so it is not an option for the transform.
- `python3` 3.10 and `java` 21 are available; only `saxonche` must be `pip install`ed.
- **Grouping: by stem.** Documents whose stem has anexo siblings get a subfolder named after
  the stem; standalone documents get a flat CSV in the output root.

---

## 2. Behaviour specification

### 2.1 CLI
```
python3 scripts/run_segmentation_csv.py \
    --input-dir  ../novas_normas_20260420/lexml_manual_20260524 \
    --output-dir ../novas_normas_20260420/lexml_manual_20260524/segmentation \
    [--xsl scripts/GeraCSVporArtigoPorAgrupador.xsl]   # default: this path relative to repo
    [--report segmentation_report.json]                # written into output-dir
    [--recursive]                                      # default: top-level only
    [--overwrite]                                      # default: skip existing CSV, mark "skipped"
```
- `--xsl` defaults to `scripts/GeraCSVporArtigoPorAgrupador.xsl`.
- Output dir is created if missing (`mkdir -p` semantics).

### 2.2 Input selection
- Glob `*.xml` in input dir (top level; `--recursive` walks subdirs).
- **Exclude** `*.err.log`, `*.txt`, and anything that is not well-formed `<LexML>` — a
  non-LexML `.xml` is recorded as a `failure` (reason: not a LexML document) rather than
  silently dropped.

### 2.3 Stem grouping → output layout
For each input file, compute the **stem** = filename with `.xml` stripped, then with a
trailing `.anexoN` segment stripped if present (regex `\.anexo\d+$`). Group inputs by stem.

- **Single-file group** (one input, no anexos) → write CSV directly in output root:
  `<output-dir>/<stem>.csv`
- **Multi-file group** (main + anexos, or any stem with >1 input) → create a subfolder and
  write one CSV per member, named after the member's full base name:
  `<output-dir>/<stem>/<member_basename>.csv`
  e.g. `segmentation/decreto_2338_1997/decreto_2338_1997.csv`,
       `segmentation/decreto_2338_1997/decreto_2338_1997.anexo1.csv`, …

This satisfies "if the .xsl produces multiple output files for a single document, create a
sub-folder" — generalised to "a document family with multiple parts."

> **Note on a literal multi-`result-document` case:** the current XSL emits a single stream,
> so the per-member-CSV path above is what actually runs. The code is still written to handle
> a stylesheet that writes N files for one input: it will detect >1 produced output and place
> them in the stem subfolder. (See 3.3.)

### 2.4 Each output is identifiable
- CSV filename encodes the source base name (incl. `anexoN`).
- The XSL's default `sigla` already embeds the source filename in every row's `Rotulo`
  column, and every row carries the document URN — so rows are self-identifying too.

### 2.5 Report
Write **both** a machine-readable `segmentation_report.json` and a human-readable
`segmentation_report.md` into the output dir.

Per-document record:
- `source` (path), `stem`, `output` (path(s) written), `status`
  (`success` | `failure` | `skipped`), `rows` (CSV data-line count, success only),
  `duration_ms`, `error` (message + Saxon stderr excerpt, failure only).

Summary block:
- total inputs considered, # success / # failure / # skipped, success rate %,
  total rows emitted, wall-clock time, engine + versions, timestamp, invocation args.

A one-line-per-doc table goes to stdout as well.

---

## 3. Implementation outline

### 3.1 Structure (`scripts/run_segmentation_csv.py`)
1. `parse_args()` — argparse per §2.1.
2. `discover_inputs(input_dir, recursive)` → list of `.xml` paths, noise excluded.
3. `group_by_stem(paths)` → `dict[stem -> list[paths]]` using the `\.anexo\d+$` rule.
4. `load_saxon()` — `from saxonche import PySaxonProcessor`; build one
   `PyXslt30Processor` and `compile_stylesheet(stylesheet_file=xsl)` **once** (reused for all
   docs — compile is the expensive step).
5. `transform_one(executable, src, out_path)`:
   - `executable.transform_to_string(source_file=src)` (lets the XSL's `document-uri()`
     default for `sigla` resolve to the real path).
   - On success, write the returned text to `out_path` (UTF-8, newline preserved); count data
     rows = non-empty lines minus 1 (header).
   - Catch `saxonche` errors / check `proc.exception_occurred`; capture message.
6. `main()` — iterate groups, decide flat-vs-subfolder per §2.3, honour
   `--overwrite`/skip, accumulate records, write reports, print summary, exit non-zero if any
   failure (so it's CI-friendly).

### 3.2 Encoding / locale
- Force UTF-8 on all file writes; LexML is Portuguese. (Mirror the project's
  `LC_ALL=C.UTF-8` convention; saxonche handles Unicode internally.)

### 3.3 Forward-compatible multi-output detection
- `transform_to_string` returns a single stream. To stay robust to a future XSL using
  `xsl:result-document`, the function also supports `transform_to_file(output_file=...)` into
  a temp dir and, if >1 file lands there, copies all into the stem subfolder. Default path
  uses `transform_to_string` for simplicity/perf; the temp-dir path is a documented fallback
  flag (`--detect-multi-output`).

### 3.4 Dependency bootstrap
- Top-of-script check: `import saxonche`; on `ImportError`, print a clear message:
  `pip install saxonche` and exit 2. README/usage note added to the script docstring.

---

## 4. Validation steps (after writing the script)
1. `pip install saxonche` (one-time).
2. Dry run on the example folder:
   ```
   python3 scripts/run_segmentation_csv.py \
     --input-dir  ../novas_normas_20260420/lexml_manual_20260524 \
     --output-dir ../novas_normas_20260420/lexml_manual_20260524/segmentation
   ```
3. Manually confirm:
   - `segmentation/decreto_2338_1997/` subfolder holds main + anexo1 + anexo2 CSVs.
   - A standalone doc (e.g. `decreto_4901_2003.xml`) produced a flat
     `segmentation/decreto_4901_2003.csv`.
   - First line of a CSV is the expected header; a spot-checked row's `Texto`/`urn` look sane.
   - `*.err.log` and `skipped_report.txt` were ignored.
   - `segmentation_report.{json,md}` exist with correct counts; success rate matches reality.
4. Confirm exit code is 0 when all succeed, non-zero if any failure injected.

---

## 5. Open items / assumptions
- Assumes LexML docs use extension `.xml` (confirmed in the example folder). If real
  `.lexml`-extension files appear, add `*.lexml` to the glob.
- Assumes anexos are co-located with their main file under the same stem (confirmed).
- Report format JSON+MD chosen for both machine and human consumption; can drop one if the
  user prefers a single CSV-style report.
