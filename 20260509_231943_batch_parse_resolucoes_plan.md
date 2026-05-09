# Plan: handle regulatory-agency resoluções in `batch_parse.py`

## Context

`scripts/batch_parse.py` currently produces `Unsupported agency resolution: <agency>` for filenames like `res_anatel_396_2005.docx` and `res_anpd_2_2022.docx`, and the parser is never invoked. This skip was put in place because `(autoridade=federal, tipoNorma=resolucao)` is **not** a registered `DocumentProfile` in the Scala parser (only Senado/Câmara/Congresso variants exist — see `DocumentProfile.scala:524–532`), so a naive `-a federal -t resolucao` falls back to the `Lei` profile and fails with "Epígrafe ausente".

We verified manually that the parser **can** handle these documents when invoked with `-t resolucao` (no `-a`, so the metadado autoridade defaults to `federal`) plus four profile-override flags that teach the fallback profile the resolução epigraph pattern and skip Anatel page boilerplate. The script needs to (a) stop skipping these files and (b) emit those four flags when it detects an agency-resolution input.

### Reference: working manual invocation (from `res_anatel_396_2005.docx`)

```bash
LC_ALL=C.UTF-8 LANG=C.UTF-8 java -jar target/lexml-parser-projeto-lei-1.15.0-onejar.jar parse \
  -m application/vnd.openxmlformats-officedocument.wordprocessingml.document \
  -i ../novas_normas_20260420/manual_20260421/res_anatel_396_2005.docx \
  -o ../novas_normas_20260420/teste_resolucoes/res_anatel_396_2005.docx.xml \
  --write-errors-to-file ../novas_normas_20260420/teste_resolucoes/res_anatel_396_2005.docx.err.log \
  -t resolucao \
  --prof-regex-epigrafe '^resolucao' \
  --prof-regex-epigrafe-continuacao '^resolucao%^n[oº°˚]' \
  --prof-regex-pos-epigrafe '^publicado:%^left\d%^acessos:' \
  --prof-epigrafe-head 'RESOLUÇÃO' \
  --linker /usr/local/bin/linkertool
```

Result: clean `<Epigrafe>RESOLUÇÃO Nº 396, DE 31 DE MARÇO DE 2005</Epigrafe>` and `<Ementa>Aprova o Regulamento ...` (boilerplate stripped).

### Why each piece is needed

- **No `-a`**: the metadado defaults to `federal`, which keeps the URN form `urn:lex:br:federal:resolucao:...`.
- **`-t resolucao`**: sets the URN tipoNorma fragment.
- **`--prof-regex-epigrafe '^resolucao'`**: teaches the (fallback) profile to recognize the epigraph's first line. Required because `getProfile("federal","resolucao")` returns `None` (only Senado/Câmara/Congresso variants are registered) and the parser falls back to `Lei`, whose `regexEpigrafe1` is `^lei`.
- **`--prof-regex-epigrafe-continuacao '^resolucao%^n[oº°˚]'`**: matches the same line if it starts with `Nº ...` instead.
- **`--prof-regex-pos-epigrafe '^publicado:%^left\d%^acessos:'`**: skips Anatel website boilerplate that sits between the epigraph and the ementa. Harmless on documents that don't contain those lines (no match → no skip).
- **`--prof-epigrafe-head 'RESOLUÇÃO'`**: rendering string for the `<Epigrafe>` element.
- **`LC_ALL=C.UTF-8`**: this system has no `en_US.UTF-8` locale; without `C.UTF-8` the JVM picks `sun.jnu.encoding=ANSI_X3.4-1968` and corrupts the non-ASCII argv (`RESOLUÇÃO` → `RESOLU����O`). Already set in `process_file()` line 277.

## Files to change

- `scripts/batch_parse.py` — only this file.

## Changes

### 1. Stop skipping agency `res_*` filenames (`detect()`)

Currently `detect()` (lines 164–212) skips when `tokens[0] == "res"` and `tokens[1]` is not in `{senado, camara, congresso}` (lines 170–177).

Replace that skip block with a fall-through: when `tokens[0] == "res"` and the second token isn't one of the legislative chambers, infer:

- `autoridade = "federal"` (matches the manual invocation that worked).
- `tipo_norma = "resolucao"`.
- Set `det.agency = tokens[1]` so downstream we know it's an agency resolution.

Add an `agency: str | None = None` field to `@dataclass Detection` (line 70). The rule-engine path remains unchanged for `res_senado_*` etc. — they continue to flow through the existing `RULES`.

For the agency case we don't run the `RULES` loop, so `matched_len` should be set to `2` (consume `res` + `<agency>` tokens) before the date/numero scan at line 198 so it doesn't try to interpret `anatel` / `anpd` as a number.

### 2. Add a profile-overrides table and wire it into `build_cli_args()`

Introduce a small constant at module level:

```python
# Extra CLI flags appended when the detected (autoridade, tipoNorma) tuple
# isn't backed by a registered DocumentProfile and needs runtime overrides
# to teach the fallback profile (Lei) about the actual epigraph format.
PROFILE_OVERRIDES: dict[tuple[str, str], list[str]] = {
    ("federal", "resolucao"): [
        "--prof-regex-epigrafe", "^resolucao",
        "--prof-regex-epigrafe-continuacao", r"^resolucao%^n[oº°˚]",
        "--prof-regex-pos-epigrafe", r"^publicado:%^left\d%^acessos:",
        "--prof-epigrafe-head", "RESOLUÇÃO",
    ],
}
```

In `build_cli_args()` (lines 223–242), after building the base `args`, append `PROFILE_OVERRIDES.get((det.autoridade, det.tipo_norma), [])`. Keep the linker append at the end.

The pos-epigrafe regex (`^publicado:%^left\d%^acessos:`) is Anatel-page-derived but harmless on documents that don't contain those lines. If it later turns out an agency uses different boilerplate, we extend the regex with extra `%`-separated alternatives in this same dict.

### 3. UTF-8 locale already handled

`process_file()` at line 277 already sets `LC_ALL=C.UTF-8` / `LANG=C.UTF-8` in the subprocess env. No change needed.

### 4. DOCX metadata extraction already works

`parse_docx_metadata()` (line 132) and `_EPIGRAFE_RE` (line 101) already match `Resolução nº 396, de 31 de março de 2005` after accent-folding. The number and date are extracted from DOCX content, overriding the filename — no change needed.

## Verification

From the repo root:

1. **Single-file dry-run** to confirm the new CLI flags are emitted:
   ```bash
   python3 scripts/batch_parse.py \
     ../novas_normas_20260420/manual_20260421 \
     /tmp/lexml_out --dry-run --linker /usr/local/bin/linkertool
   ```
   Expect a `DRY-RUN ... -a federal -t resolucao ... --prof-regex-epigrafe ^resolucao ... --prof-epigrafe-head RESOLUÇÃO ...` line for each `res_*.docx`, and **no** `SKIP res_anatel_*` / `SKIP res_anpd_*` entries.

2. **Real run** on the same folder:
   ```bash
   python3 scripts/batch_parse.py \
     ../novas_normas_20260420/manual_20260421 \
     ../novas_normas_20260420/teste_resolucoes_batch \
     --linker /usr/local/bin/linkertool
   ```
   Expect:
   - `OK res_anatel_396_2005.docx [federal / resolucao]` (the manually verified case).
   - `OK` for the other 6 Anatel files and 2 ANPD files, OR `FAIL` with the specific err.log content visible.
   - In any `OK` output, the produced `.xml` should contain `<Epigrafe id="epigrafe">RESOLUÇÃO Nº ...` (clean accents) and `<Ementa>` should not start with `Publicado:` / `Acessos:` / `left<digits>` boilerplate.

3. **Spot-check** the err.log header: each file's `*.err.log` is rewritten to begin with `# CMD: ...` (existing behavior at line 284). Confirm the dumped command includes the four override flags.

4. **Regression**: run the same script over a folder that contains a non-resolução file (e.g., a `lei_*.docx` from `../test_docs/`) and confirm it still parses without the override flags being emitted (the `PROFILE_OVERRIDES` dict only has the `(federal, resolucao)` key).

## Out of scope

- Adding a registered `(federal, resolucao)` profile in Scala. That would be the cleaner long-term fix, but it requires a code change + rebuild of the onejar; the override-flag approach gets us working immediately and keeps the change contained to the Python harness.
- Per-agency override variations (e.g., ANPD-specific pos-epigrafe filters). We start with the Anatel-derived filter, run the verification step on ANPD, and only refine if `<Ementa>` content shows ANPD-specific boilerplate leaking in.
- The unrelated `§2º`/`§3º` numbering errors observed in the manual run — those are document-content issues, not parser-config issues.

## Inventory of agency resolution files (as of plan time)

Under `../novas_normas_20260420/`:

- `manual_20260421/` and `test_docs/` contain 11 `res_*.docx` files total.
- Agencies present: `anatel` (7 files: 396/2005, 589/2012, 612/2015, 671/2016, 740/2020, 765/2023, 777/2025) and `anpd` (2 files: 2/2022, 4/2022; some appear in both directories).
