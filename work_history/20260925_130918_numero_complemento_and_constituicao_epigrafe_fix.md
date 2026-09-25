# Número com complemento (MP 2.228-1) and Constituição epígrafe: plan

**Created:** 2026-09-25 13:09:18
**Found in:** `../br-taxqa-r_v2.0` while preparing the embedding step. See
`../br-taxqa-r_v2.0/docs/20260925_125514_constitution_metadata_fix.md` (the manual XML fix of the
Constitution that this plan replaces) and
`../br-taxqa-r_v2.0/docs/20260925_122058_embedding_implementation_plan.md` §2 step 0.

Status: **done** (2026-09-25). Results in §6.

---

## 1. Problem

Five documents of the BR-TaxQA-R v2.0 corpus got a **wrong number** in their LexML metadata. The
wrong number shows up in both the URN (`Metadado/Identificacao/@URN`) and the epígrafe, which the
parser always regenerates from the metadata (`ProjetoLei.remakeEpigrafe`).

| Document | `# CMD` numbering | URN / epígrafe produced | Correct |
|---|---|---|---|
| `mp_2158-35_20010824` | `-n 35` | `…:2001-08-24;35` / `MEDIDA PROVISÓRIA Nº 35, …` | `;2158-35` / `Nº 2.158-35` |
| `mp_2159-70_20010824` | `-n 70` | `;70` / `Nº 70` | `;2159-70` / `Nº 2.159-70` |
| `mp_2189-49_20010823` | `-n 49` | `;49` / `Nº 49` | `;2189-49` / `Nº 2.189-49` |
| `mp_2228-1_20010906` | `-n 1` | `;1` / `Nº 1` | `;2228-1` / `Nº 2.228-1` |
| `constituição_1988_19881005` | *(none)* | `…constituicao:1988-10-05;1` / `CONSTITUICAO Nº 1, DE 5 DE OUTUBRO DE 1988` | `;1988` / `CONSTITUIÇÃO DA REPÚBLICA FEDERATIVA DO BRASIL` |

The correct URNs are the forms `lexml-linker` produces. In `../br-taxqa-r_v2.0/lexml/` the linker
references them 148 (`;2158-35`), 4 (`;2159-70`), 25 (`;2189-49`), 79 (`;2228-1`) and 1,303
(`;1988`) times. With the wrong URNs, none of those references can be matched to the documents'
own dispositivos.

I found these five by comparing, for every `*.err.log` in `../br-taxqa-r_v2.0/lexml/articulados`,
the `-n` of the `# CMD:` header with the number in the filename. No other document differs.

## 2. Root causes

### 2.1 `scripts/batch_parse.py`: numbers with a complement ("2.228-1")

MPs re-edited before EC 32/2001 are numbered `<number>-<reedition>`. The parser supports this
through `--complemento` (URN `;2228-1`, epígrafe `Nº 2.228-1`), but the driver never passes it:

1. **Epígrafe read from the DOCX** (`parse_docx_metadata`, `_EPIGRAFE_RE`). The number group is
   `([\d\.]+)` followed by `[^0-9a-z]+de`. For `nº 2.228-1, de`, the `-1` makes the match fail, so
   the function returns `(None, None)`.
2. **Filename fallback** (`detect`, `tokenize`). `re.split(r"[\s_\-]+", …)` splits `2228-1` into
   `2228` and `1`. `2228` fits the 4-digit year rule (`"1000" <= t <= "2999"`), so it becomes
   `ano`, and `1` becomes `numero`. The date token then supersedes `ano`, which leaves `-n 1`.

### 2.2 `scripts/batch_parse.py`: the Constitution has no number

The DOCX starts with `CONSTITUIÇÃO DA REPÚBLICA FEDERATIVA DO BRASIL`, which has no number, and
the filename has no number token either. So no `-n` is passed, and `Id.num` defaults to `1`
(`Metadado.scala`, `case class Id(num : Int = 1, …)`). The LexML convention for constitutions
uses the year as the number: `urn:lex:br:federal:constituicao:1988-10-05;1988`.

### 2.3 Scala: `DocumentProfileOverride` drops the profile's epígrafe templates

`ConstituicaoFederalProfile` sets
`epigrafeTemplateCode = epigrafeSemIdTemplateCode = "CONSTITUIÇÃO DA REPÚBLICA FEDERATIVA DO BRASIL"`,
but the output uses the generic `<epigrafeHead> <epigrafeRepr> <epigrafeTail>`, which gives
`CONSTITUICAO Nº 1, DE …`. The cause is `DocumentProfileOverride`, the wrapper that CLI profile
overrides are applied through. It forwards every profile member to `base` except
`epigrafeTemplateCode` and `epigrafeSemIdTemplateCode`, so those fall back to the defaults in
`TipoNormaProfile`. Passing `-n 1988` fixes the URN but still gives
`CONSTITUICAO Nº 1.988, DE 5 DE OUTUBRO DE 1988` (checked on 2026-09-25 with the current jar).

## 3. Changes

### 3.1 Scala: forward the epígrafe templates (`profile/DocumentProfile.scala`)

In `DocumentProfileOverride`:

```scala
// Base profiles with a custom epígrafe template (Constituição, Decreto Legislativo, EC, ...) keep
// it; when the head or tail is overridden, the generic template is used so the override shows.
private def headOrTailOverridden = overrideEpigrafeHead.isDefined || overrideEpigrafeTail.isDefined
override def epigrafeTemplateCode: String =
  if (headOrTailOverridden) super.epigrafeTemplateCode else base.epigrafeTemplateCode
override def epigrafeSemIdTemplateCode: String =
  if (headOrTailOverridden) super.epigrafeSemIdTemplateCode else base.epigrafeSemIdTemplateCode
```

- **Why the head/tail condition:** agency documents (INs, ADEs, resoluções, portarias) are parsed
  with the `Lei` fallback profile plus `--prof-epigrafe-head`. They depend on the generic template
  to show the overridden head, and they must stay byte-identical.
- **Side effect to check:** registered profiles with a custom template (Decreto, Decreto
  Legislativo, EC, Portaria federal, Resolução CN, ...) now use it when they are wrapped. For
  documents whose template renders the same text as the generic one (e.g. `DECRETO Nº …, DE …`),
  nothing changes. The full-corpus diff (§4.3) shows every document that does change.
- **Unit test:** `src/test/scala/.../profile/DocumentProfileOverrideTest.scala` (JUnit 4, as in
  `AlteracaoFragmentosTest`) covers three cases:
  - `ConstituicaoFederal + Overrides()` keeps the Constitution template;
  - an overridden head uses the generic template with that head;
  - `Lei + head override` renders the same epígrafe as before.

### 3.2 `scripts/batch_parse.py`

1. **`Detection.complemento: str | None`.** `build_cli_args` adds `--complemento <n>` when it is
   set.
2. **`_EPIGRAFE_RE`:** the number becomes `([\d\.]+)(?:\s*[-‐‑–]\s*(\d+))?`, so an optional
   complement is accepted, with ASCII and Unicode hyphens. `parse_docx_metadata` returns
   `(numero, complemento, data)`, and the capture-group indices after the number shift by one.
3. **Filename:** in `detect`, before tokenizing, a `<digits>-<digits>` token next to `_` (e.g.
   `_2228-1_`) is read as `numero` + `complemento` and removed from the tokens, so it can't be
   mistaken for a year. This applies to both the agency path and the RULES path.
4. **`process_file`:** the DOCX epígrafe still takes precedence. If it has a number, it replaces
   `numero` **and** `complemento`, including resetting a filename complement when the epígrafe
   has none.
5. **Constitution:** after detection, if `tipo_norma == "constituicao"` and there is no `numero`,
   use the year (from `data`, otherwise `ano`) as `numero`, giving `-n 1988`.
6. **Tests:** `scripts/tests/test_batch_parse.py` (pytest, pure functions only, no jar):
   - `detect()` on `mp_2228-1_20010906`, `mp_2158-35_20010824`, `constituição_1988_19881005`,
     `lei_9250_19951226`, `in_rfb_1131_20110221`;
   - `_EPIGRAFE_RE` on `MEDIDA PROVISÓRIA Nº 2.228-1, DE 6 DE SETEMBRO DE 2001` and
     `DECRETO Nº 2.338, DE 7 DE OUTUBRO DE 1997`.

### 3.3 Docs

- Update `CLAUDE.md` (the `batch_parse.py` section) to mention `--complemento` and the
  Constitution number rule.

## 4. Verification

The current `target/lexml-parser-projeto-lei-1.15.0-onejar.jar` reproduces
`../br-taxqa-r_v2.0/lexml/articulados` **byte for byte**; this was checked on `lei_9250_19951226`
and `mp_2158-35_20010824`, and the 2026-09-15 execution note found re-runs byte-identical. So the
current corpus is the baseline, and any difference after the change comes from this fix.

1. `mvn test` (all tests, including the new one), then `mvn -Ponejar package`.
2. `pytest scripts/tests`.
3. **Command diff:** `batch_parse.py --dry-run` over `../br-taxqa-r_v2.0/original/articulados`.
   Compare each planned command with the `# CMD:` header of the matching `.err.log`, ignoring the
   jar path and output folder. Expected: only the 5 documents differ, and only in
   `-n`/`--complemento`.
4. **Full re-parse** of `original/articulados` with the new jar + driver into a scratch folder,
   then `diff` every `.xml` against `lexml/articulados`. Expected:
   - the 5 documents: only `Identificacao/@URN`, `Epigrafe`, and their annex files' URN lines
     change;
   - every other document is byte-identical, unless the §3.1 template forwarding changes its
     epígrafe. Each such change is listed and reviewed in §6; if one is wrong, restrict §3.1.
5. **Promote only the 5 documents** (their `.xml`, `.anexoN.xml` and `.err.log`) into
   `../br-taxqa-r_v2.0/lexml/articulados`. This replaces the manual Constitution edit of
   2026-09-25 with parser output. Documents that change only through §3.1 are promoted only if
   the review in step 4 accepts them.
6. **Re-segment** `../br-taxqa-r_v2.0` (`segment_all.py --overwrite`). Check that only the rows
   of the promoted documents change, and that their `urn`s now match the linker's references.
   Record the results in a new dated note in `../br-taxqa-r_v2.0/docs/`, and update the embedding
   plan's step 0.

## 5. Out of scope

- `ProjetoLei.remakeEpigrafe` always replaces the DOCX epígrafe with the one built from the
  metadata. Keeping the original text is a bigger change; the fixes above make the rebuilt
  epígrafe correct for these cases.
- MPs numbered with a complement in the linker's own code (`;2158-35--`, 14 references to
  `2228-1--`) look like a linker artefact of trailing text. They are not handled here.

## 6. Execution (2026-09-25)

### 6.1 Code changes

- **`profile/DocumentProfile.scala`:**
  - `DocumentProfileOverride` forwards `epigrafeTemplateCode` / `epigrafeSemIdTemplateCode` to
    `base` unless the head or tail is overridden (§3.1).
  - **Not in the plan:** the `ConstituicaoFederal` object sets its own template,
    `Constituição da República Federativa do Brasil.`, with a trailing period. It overrides the
    trait's upper-case one. The period was dropped to match the title decided in `br-taxqa-r_v2.0`:
    **`Constituição da República Federativa do Brasil`**.
- **`src/test/scala/.../profile/DocumentProfileOverrideTest.scala`:** 5 JUnit tests covering:
  - the Constitution with no override, and with a locality override (the profile template is
    used);
  - an overridden head (the generic template is used);
  - `Lei` + an IN head (unchanged);
  - MP 2.228-1 with a complement.
- **`scripts/batch_parse.py`:** `Detection.complemento`, the `_EPIGRAFE_RE` complement group
  (the date groups shift to 3–5 / 6–8), `_NUMERO_COMPLEMENTO_RE` in `detect`, `--complemento` in
  `build_cli_args`, and the constitution year rule in `process_file` (§3.2).
- **`scripts/tests/test_batch_parse.py`:** 8 pytest tests.
- **`CLAUDE.md`:** a `batch_parse.py` bullet on complements and the constitution number.

### 6.2 Verification

1. `mvn test`: **27 tests, 0 failures** (22 existing + 5 new). `pytest scripts/tests`: **8 passed**.
   `mvn -Ponejar package` was rebuilt at 13:14.
2. **Command diff** (`--dry-run` over `original/articulados`, 225 documents, compared with each
   `# CMD:` header after normalizing the jar path and output folder): **exactly 5 differ**, and
   only in the numbering, as planned:
   - `constituição_1988_19881005`: `+ -n 1988`;
   - `mp_2158-35`, `mp_2159-70`, `mp_2189-49`, `mp_2228-1`: `-n <complement>` →
     `-n <number> --complemento <complement>`.
3. **Full re-parse** into a scratch folder: 225 converted, 0 skipped, 0 failed. Diffed against
   `lexml/articulados`, 468 XML files: **426 byte-identical, 42 changed**:

   | Group | Files | Change |
   |---|---|---|
   | 4 MPs | 4 main + 3 annexes | URN `;35`→`;2158-35`, `;70`→`;2159-70`, `;49`→`;2189-49`, `;1`→`;2228-1` in `Identificacao`, `ReferenciaAnexo/@AlvoURN` and the annexes' `Identificacao`. Epígrafe `Nº 35` → `Nº 2.158-35` etc. |
   | Constitution | 0 | Identical to the manual edit made earlier that day (`;1988`, `Constituição da República Federativa do Brasil`), now produced by the parser |
   | 34 `decreto_*` | 34 main | **Side effect of §3.1:** the `Decreto` template has no trailing space, so the epígrafe loses one, e.g. `DECRETO Nº 3.000, DE 26 DE MARÇO DE 1999 ` → `…1999`. Segmentation normalizes whitespace, so the chunk text is unchanged |
   | `decreto_legislativo_92_19751105` | 1 main | **Side effect of §3.1:** the `DecretoLegislativoFederal` template (`… Nº <n>, DE <ano>`) gives `DECRETO LEGISLATIVO Nº 92, DE 1975` instead of `…, DE 5 DE NOVEMBRO DE 1975`. **This is exactly the DOCX's own epígrafe**, so it is an improvement and was accepted |

   - The `.err.log` bodies of 13 of the promoted documents differ only in schema-validation
     messages. Line numbers moved with the shorter epígrafes, and `mp_2158-35` has fewer
     `cvc-pattern` errors now that its URN is valid.
4. **Promoted:** all 42 changed XML files, plus the `.err.log` of the 40 affected documents (the
   `# CMD:` header points to `lexml/articulados/`), went into
   `../br-taxqa-r_v2.0/lexml/articulados`. Afterwards **all 468 XML files there are byte-identical
   to the fixed parser's output**.
5. **Re-segmentation** of `br-taxqa-r_v2.0` (all checks OK) is recorded in
   `../br-taxqa-r_v2.0/docs/20260925_125514_constitution_metadata_fix.md` §5. In short:
   - only the rows of the 4 MPs (and their annexes) and of `decreto_legislativo_92` changed;
   - the MP chunks' `doc_urn` now match the linker's references: 295 / 27 / 54 / 412 chunks
     for `;2158-35` / `;2159-70` / `;2189-49` / `;2228-1`.
