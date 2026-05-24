# Fix authority & date in agency-resolution URNs (batch_parse.py)

## Context

`res_anpd_4_2022.docx` produces the LexML URN
`urn:lex:br:federal:resolucao:2022;4`, which is wrong on two counts:

1. **Authority** is `federal`; it should be `agencia.nacional.protecao.dados`
   (the ANPD).
2. **Date** is `2022` (year-only, taken from the filename) but the norm was
   actually published on **2023-02-24**, and the URN should carry the full
   `2023-02-24` date.

Both values are present in the document's own epigraph — paragraph 0 reads:

```
RESOLUÇÃO CD/ANPD Nº 4, DE 24 DE FEVEREIRO DE 2023
```

The root cause is entirely in **`scripts/batch_parse.py`**, the wrapper that
infers CLI flags and invokes the parser. The Scala parser is already correct:

- `Metadado.scala:142` — `urnFragAutoridade = autoridade.getOrElse(profile.urnFragAutoridade)`,
  so `-a <anything>` sets the URN authority verbatim.
- `Metadado.scala:145` — `urn = s"urn:lex:$loc:$autoridade:$tipoNorma:$id"`,
  where `$id` includes the full date when `--data AAAA-MM-DD` is passed
  (`FECmdLine.scala:271-282`, validated by `Data.fromString`).
- `FECmdLine.scala:446` — when no profile is registered for the
  `(autoridade, tipoNorma)` pair it falls back to the `Lei` profile and applies
  `--prof-regex-*` overrides. This already happens for `(federal, resolucao)`;
  using a different authority string keeps the identical fallback. **No Scala
  change is required.**

Two distinct bugs in `batch_parse.py` cause the bad output:

- **Authority bug** — the `res_<agency>` branch of `detect()`
  (`batch_parse.py:192-215`) hardcodes `autoridade="federal"`. It captures the
  agency token into `det.agency` (`anpd`) but never maps it to an authority.
- **Date bug** — for `res_anpd_4_2022` the post-prefix tokens are `4` and
  `2022`, so the branch sets `numero=4`, `ano=2022` → `--ano 2022`.
  `process_file()` *tries* to override this from the DOCX via
  `parse_docx_metadata()` (`batch_parse.py:295-300`), but `_EPIGRAFE_RE`
  (`batch_parse.py:120-129`) requires the type keyword immediately followed by
  `nº` (`resolucao\s*n[oº]`). The agency segment `CD/ANPD` sits in between, so
  the regex fails, extraction returns `(None, None)`, and the code keeps the
  filename's year `2022`. (Verified: the regex matches the ANATEL epigraph
  `Resolução nº 612, ...` but not either ANPD epigraph.)

## Approach

All changes are in **`scripts/batch_parse.py`** plus one new config file. No
Scala/parser changes.

### 1. Externalize the acronym → authority map (Issue 1)

The mapping lives in an **external JSON config file**, loaded via a new CLI
flag, so it can be extended without editing code.

- New default file `scripts/agency_authority.json`:
  ```json
  {
    "anpd": "agencia.nacional.protecao.dados",
    "anatel": "agencia.nacional.telecomunicacoes",
    "mjsp": "ministerio.justica.seguranca.publica"
  }
  ```
  (keys lowercased/accent-folded to match `tokenize()` output.)
- New CLI option in `main()`: `--agency-authority-map PATH`, defaulting to
  `Path(__file__).resolve().parent / "agency_authority.json"`. Load it once,
  fold/lowercase keys, and thread the resulting dict through to `detect()` (and
  therefore `process_file`). If the flag points to a missing file, exit with a
  clear error (mirror the existing `--linker` not-found check at
  `batch_parse.py:380-382`); if the *default* file is absent, treat the map as
  empty so the script still runs (all agencies then skip — see below).

### 2. Look up authority; skip unmapped agencies (Issue 1)

In the `res_<agency>` branch of `detect()` (`batch_parse.py:192-215`), replace
the hardcoded `autoridade="federal"`:

- Look up `agency_map.get(tokens[1])`.
- If found → set `det.autoridade` to the mapped value, keep
  `tipo_norma="resolucao"`, `agency=tokens[1]`.
- If **not found** → return
  `Detection(skip_reason=f"Unmapped agency acronym: {tokens[1]}")` (chosen
  "skip the file" behavior). `process_file()` already short-circuits on
  `skip_reason` (`batch_parse.py:287-289`) and `write_report()` records it.

`PROFILE_OVERRIDES` is keyed on `("federal","resolucao")`
(`batch_parse.py:78-85`) and looked up in `build_cli_args()`
(`batch_parse.py:278`). Since the authority key changes, change the lookup so
any `(*, "resolucao")` document receives the resolução `--prof-regex-*`
overrides. Fallback `Lei` profile + overrides is unchanged from today.

### 3. Make the epigraph regex tolerate an agency segment (Issue 2)

Update `_EPIGRAFE_RE` (`batch_parse.py:120-129`) to allow an optional, bounded
agency segment between the type keyword and `nº`:

```python
    r"(?:lei\s+complementar| ... |resolucao|lei|decreto)"
    r"(?:\s+[a-z][a-z./-]*)?"          # optional agency segment, e.g. "cd/anpd"
    r"\s*n[o°º]?\s*"
    r"([\d\.]+)"
    r"[^0-9a-z]+de\s+(\d{1,2})[oa°º]?\s+de\s+([a-z]+)\s+de\s+(\d{4})"
```

The segment is non-capturing and limited to a single short token (letters plus
`. / -`), so it cannot swallow ementa text. With this,
`parse_docx_metadata()` returns `numero=4, data=2023-02-24` for the ANPD doc,
and `process_file()` (`batch_parse.py:295-300`) already prefers content-derived
values over the filename, also clearing `det.ano`. This simultaneously fixes the
wrong number extraction for any agency resolution.

**Verified** against samples: the new regex extracts
`res_anpd_4_2022 → 4 / 2023-02-24`, `res_anpd_2_2022 → 2 / 2022-01-27`,
`res_anatel_612_2015 → 612 / 2013-04-29`, and (via the multi-paragraph scan)
`decreto_2338_1997 → 2338 / 1997-10-07` — no regression on existing matches.

### Resulting CLI for the ANPD doc

```
-a agencia.nacional.protecao.dados -t resolucao -n 4 --data 2023-02-24
   <resolução --prof-regex-* overrides>
```
→ URN `urn:lex:br:agencia.nacional.protecao.dados:resolucao:2023-02-24;4`.

## Files

- `scripts/batch_parse.py` — edit `detect()` agency branch, `_EPIGRAFE_RE`,
  `PROFILE_OVERRIDES` lookup, `main()` arg parsing + map loading, and thread the
  map into `detect`/`process_file`.
- `scripts/agency_authority.json` — **new** default mapping file.

## Verification

1. **Unit-level (fast):** run the extraction logic against the four sample DOCX
   files and assert the expected `(numero, data)` tuples above, plus that
   `detect("res_xyz_1_2020", map)` returns a `skip_reason`.

2. **End-to-end:** reuse the current onejar (no Scala change). Convert the ANPD
   doc:
   ```bash
   LC_ALL=C.UTF-8 LANG=C.UTF-8 python3 scripts/batch_parse.py \
     ../novas_normas_20260420/test_docs \
     ../novas_normas_20260420/teste_anpd_fix \
     --linker /usr/local/bin/linkertool
   ```
   (or `--dry-run` first to inspect the generated CLI).

3. Confirm the output `res_anpd_4_2022.xml` `<Identificacao URN=...>` reads
   `urn:lex:br:agencia.nacional.protecao.dados:resolucao:2023-02-24;4` and the
   `ReferenciaAnexo AlvoURN` mirrors it (`...;4!anexo1`).

4. **No regression:** re-run on a decreto and an ANATEL resolution; confirm
   `decreto_2338_1997` URN is unchanged and `res_anatel_612_2015` now carries
   authority `agencia.nacional.telecomunicacoes` and date `2013-04-29`.
