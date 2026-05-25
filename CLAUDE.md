# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

LexML Parser Projeto Lei — a Scala 2.13 library that parses Brazilian legal documents (laws, bills, regulations) into structured LexML XML. Built with Maven, targets Java 11.

## Build Commands

```bash
mvn compile -nsu          # Compile
mvn test                  # Run all tests
mvn test -Dtest=TestClassName  # Run a single test class
mvn -Ponejar package      # Build executable JAR
```

Run the CLI: `java -jar target/lexml-parser-projeto-lei-1.15.0-onejar.jar [parse|parseArticulacao|dumpProfiles] [args]`

## Test invocations

Run from the repo root (`lexml-parser-projeto-lei/`). Always set
`LC_ALL=C.UTF-8 LANG=C.UTF-8` so the parser handles Portuguese characters
correctly. Sample DOCX inputs live at
`../novas_normas_20260420/manual_20260421/`.

### Decreto

```bash
LC_ALL=C.UTF-8 LANG=C.UTF-8 java -jar target/lexml-parser-projeto-lei-1.15.0-onejar.jar parse \
   -m application/vnd.openxmlformats-officedocument.wordprocessingml.document \
   -i ../novas_normas_20260420/manual_20260421/decreto_2338_1997.docx \
   -o ../novas_normas_20260420/teste_decreto/decreto_2338_1997.xml \
   --write-errors-to-file ../novas_normas_20260420/teste_decreto/decreto_2338_1997.err.log \
   -t decreto -a federal -n 2338 --data 1997-10-07 \
   --linker /usr/local/bin/linkertool
```

### Resolução

Resoluções need extra `--prof-regex-*` overrides because the default
profile expects a different epigraph/header pattern:

```bash
LC_ALL=C.UTF-8 LANG=C.UTF-8 java -jar target/lexml-parser-projeto-lei-1.15.0-onejar.jar parse \
   -m application/vnd.openxmlformats-officedocument.wordprocessingml.document \
   -i ../novas_normas_20260420/manual_20260421/res_anatel_612_2015.docx \
   -o ../novas_normas_20260420/teste_resolucoes/res_anatel_612_2015.docx.xml \
   --write-errors-to-file ../novas_normas_20260420/teste_resolucoes/res_anatel_612_2015.docx.err.log \
   -t resolucao \
   --prof-regex-epigrafe '^resolucao' \
   --prof-regex-epigrafe-continuacao '^resolucao%^n[oº°˚]' \
   --prof-regex-pos-epigrafe '^publicado:%^left\d%^acessos:%^prazos%^observacao' \
   --prof-regex-preambulo '^o conselho diretor' \
   --prof-epigrafe-head 'RESOLUÇÃO' \
   --linker /usr/local/bin/linkertool
```

### Portaria

`Portaria` has a registered profile under the generic `federal` authority, so
`-a federal -t portaria` works with no `--prof-*` flags. Per-ministry documents
carry a distinct authority URN (resolved from the filename agency token via
`scripts/agency_authority.json`, e.g. `mjsp →
ministerio.justica.seguranca.publica`) that misses the registry and falls back
to the `Lei` profile, so they need the same `--prof-*` overrides
`batch_parse.py` passes:

```bash
LC_ALL=C.UTF-8 LANG=C.UTF-8 java -jar target/lexml-parser-projeto-lei-1.15.0-onejar.jar parse \
   -m application/vnd.openxmlformats-officedocument.wordprocessingml.document \
   -i ../novas_normas_20260420/manual_20260421/portaria_mjsp_502_2021.docx \
   -o ../novas_normas_20260420/teste_portaria/portaria_mjsp_502_2021.docx.xml \
   --write-errors-to-file ../novas_normas_20260420/teste_portaria/portaria_mjsp_502_2021.docx.err.log \
   -a ministerio.justica.seguranca.publica -t portaria -n 502 --data 2021-11-23 \
   --prof-regex-epigrafe '^portaria' \
   --prof-regex-epigrafe-continuacao '^portaria%^n[oº°˚]' \
   --prof-regex-pos-epigrafe '^diario oficial%^publicado em%^orgao:%^edicao%^secao' \
   --prof-regex-preambulo '^o ministro de estado%^a ministra de estado' \
   --prof-epigrafe-head 'PORTARIA' \
   --linker /usr/local/bin/linkertool
```

## Helper scripts (`scripts/`)

Two Python 3 helpers automate the bulk steps that bookend the Scala parser:
turning a folder of DOCX into LexML XML, then turning that XML into segmentation
CSVs. Run both from the repo root with `LC_ALL=C.UTF-8 LANG=C.UTF-8`.

### `batch_parse.py` — DOCX folder → LexML XML

Walks an input folder, infers each document's Brazilian-law profile from its
filename (e.g. `decreto_*`, `res_<agency>_*`), and invokes the onejar with the
matching `-a`/`-t` flags. Agencies for `res_*` files are resolved via
`scripts/agency_authority.json` (acronym → authority URN fragment); documents
whose type/agency isn't supported are skipped and listed in a report. Only the
onejar is required (no extra pip deps).

```bash
LC_ALL=C.UTF-8 LANG=C.UTF-8 python3 scripts/batch_parse.py \
   ../novas_normas_20260420/manual_20260421 \
   ../novas_normas_20260420/lexml_manual_20260524 \
   --linker /usr/local/bin/linkertool
```

- Positional args: `input_dir`, `output_dir`.
- `--jar` defaults to the highest-versioned jar under `target/`.
- `--linker` is optional; omit it to skip link recognition.
- `--agency-authority-map` defaults to `scripts/agency_authority.json`.
- `--recursive` to descend into subfolders; `--dry-run` to print the planned
  invocations without running the parser.

### `run_segmentation_csv.py` — LexML XML folder → segmentation CSVs

Applies `scripts/GeraCSVporArtigoPorAgrupador.xsl` to every LexML `.xml` in a
folder, producing one CSV of dispositivos per document (columns
`Tipo,Rotulo,Num_Inicio,Num_Final,Texto,urn`). A document split across a main
file plus `.anexoN` siblings is grouped by stem into a subfolder
(`<out>/<stem>/...`); standalone documents get a flat `<out>/<stem>.csv`.
`*.err.log` and `*.txt` inputs are ignored. A `segmentation_report.{json,md}`
with per-document status and the overall success rate is written into the
output folder; exit code is non-zero if any document fails.

The stylesheet is **XSLT 3.0**, which `lxml` (XSLT 1.0 only) cannot run, so the
script drives SaxonC-HE via the `saxonche` package — install it once with
`pip install saxonche`.

```bash
LC_ALL=C.UTF-8 LANG=C.UTF-8 python3 scripts/run_segmentation_csv.py \
   --input-dir  ../novas_normas_20260420/lexml_manual_20260524 \
   --output-dir ../novas_normas_20260420/lexml_manual_20260524/segmentation
```

- `--input-dir` / `--output-dir` are required; the output folder is created.
- `--xsl` defaults to `scripts/GeraCSVporArtigoPorAgrupador.xsl`.
- `--recursive` to descend into subfolders; `--overwrite` to regenerate CSVs
  that already exist (otherwise they're skipped).

## Work history (`work_history/`)

`work_history/` holds dated `.md` notes (named
`<YYYYMMDD>_<HHMMSS>_<slug>.md`) documenting past fixes and implementation
plans. Whenever the user refers to a previous fix or implementation, check this
folder for the relevant note before answering or making changes.

**IMPORTANT:** Any `.md` file created inside `work_history/` must be prefixed
with a `YYYYMMDD_HHMMSS_` timestamp (e.g. `20260525_143000_<slug>.md`). Use the
current date and time when creating the note.

## Architecture

Source lives under `src/main/scala/br/gov/lexml/parser/pl/`. The parsing pipeline flows through:

1. **Input conversion** — `xhtml/` converts RTF/DOCX/text to XHTML; `docx/` handles DOCX specifically
2. **Block extraction** — `xhtml/` produces `Block` objects from XHTML nodes
3. **Block processing** — `ProjetoLei.scala` orchestrates a multi-stage pipeline: text normalization, amendment (alteracao) recognition, dispositivo recognition, hierarchy organization, link recognition
4. **Output** — `output/LexmlRenderer` produces LexML XML; `output/HtmlRenderer` produces HTML

### Key types (`block/Block.scala`)

`Block` trait hierarchy: `Paragraph`, `Dispositivo` (legal provisions with rotulo/label, content, sub-provisions, and path), `Alteracao` (amendments — content between quote marks), `Omissis`, `Table`, `OL`, `Image`.

### Rotulo system (`rotulo/`)

Rótulos are labels identifying provision types in the Brazilian legal hierarchy: Artigo > Paragrafo > Inciso > Alinea > Item. Aggregators: Parte > Livro > Titulo > Capitulo > Secao > SubSecao.

### Profiles (`profile/`)

`DocumentProfile` defines structural rules per legislation type (Lei, ProjetoDeLei, Emenda, etc.) — regex patterns for section recognition, epigraph format, authority URN fragments.

### Linker (`linker/`)

Optional component using Apache Pekko actors to identify and hyperlink legislative references.

## Domain Glossary

- **Articulacao**: main articulated body of a legal document
- **Dispositivo**: a legal provision (article, paragraph, item)
- **Alteracao**: amendment to existing legislation (detected by quote marks)
- **Rotulo**: label prefix (Art., §, Inc., etc.)
- **Ementa**: document summary; **Epigrafe**: document title; **Preambulo**: preamble
- **Omissis**: ellipsis indicating omitted text

## Notes

- Some input formats require AbiWord installed on the system
- The project uses Apache Pekko 1.0.0 (Akka successor)
- Text is normalized to Unicode NFC before processing
- See `ARCHITECTURE.md` for detailed component documentation and code patterns
