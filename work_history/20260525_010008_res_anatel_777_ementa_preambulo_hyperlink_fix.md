# Plan — Fix ementa, preâmbulo, and HYPERLINK-field issues in res_anatel_777_2025

## Context

Parsing `../novas_normas_20260420/manual_20260421/res_anatel_777_2025.docx`
(via the agency-resolution path: `-t resolucao -a agencia.nacional.telecomunicacoes`
plus the `PROFILE_OVERRIDES["resolucao"]` flags from `scripts/batch_parse.py`)
produces `../novas_normas_20260420/lexml_manual_20260524/res_anatel_777_2025.xml`
with three defects:

1. **Ementa contaminated.** The `<Ementa>` should be exactly
   `"Revoga e altera Resoluções expedidas pela Anatel e aprova o Regulamento
   Geral dos Serviços de Telecomunicações - RGST."` Instead it begins with a
   long "Prazos definidos para os arts. … prorrogados conforme o Acórdão …"
   block and ends with an `Observação: Este texto não substitui o publicado no
   DOU …` note — both editorial annotations exported from the Anatel website,
   not the ementa.
2. **Preâmbulo not recognized.** The paragraph
   `"O CONSELHO DIRETOR DA AGÊNCIA NACIONAL DE TELECOMUNICAÇÕES, no uso das
   atribuições …"` (plus its CONSIDERANDO clauses and `RESOLVE:`) is emitted as
   an empty `<Preambulo id="preambulo"></Preambulo>`; the text was swallowed
   into the contaminated ementa instead.
3. **HYPERLINK field-code garbage in text.** Throughout the document, Word
   complex-field instructions leak in as literal text, e.g. in art. 2 inciso
   XXXII: `o HYPERLINK "https://informacoes.anatel.gov.br/legislacao/resolucoes/2019/1371-resolucao-717" \l "art5"`
   appears before the real link text "art. 5º da Resolução nº 717 …".

## Source-document anatomy (DOCX body, top level)

Confirmed by reading `word/document.xml` of the DOCX:

| # | kind | text (truncated) |
|---|------|------------------|
| 0 | `<w:p>` | `Resolução Anatel nº 777, de 28 de abril de 2025` (epígrafe) |
| 1 | `<w:p>` | `Publicado: … \| Última atualização: … \| Acessos: 83629…` (website boilerplate) |
| 2 | `<w:tbl>` | **2 cells** — cell0 `Prazos definidos para os arts. 6º,7º,… prorrogados conforme o Acórdão …`; cell1 `Revoga e altera Resoluções expedidas pela Anatel e aprova o Regulamento Geral dos Serviços de Telecomunicações - RGST.` |
| 3 | `<w:p>` | (empty / nbsp) |
| 4 | `<w:p>` | `Observação: Este texto não substitui o publicado no DOU de 30/4/2025.` |
| 5 | `<w:p>` | (empty / nbsp) |
| 6 | `<w:p>` | `O CONSELHO DIRETOR DA AGÊNCIA NACIONAL DE TELECOMUNICAÇÕES, no uso das atribuições …` (preâmbulo) |
| 7 | `<w:p>` | `CONSIDERANDO a deliberação tomada em sua Reunião nº 942 …` |
| 8 | `<w:p>` | `CONSIDERANDO o que consta dos autos do Processo nº 53500.059638/2017-39,` |
| 10 | `<w:p>` | `RESOLVE:` |
| 11+ | `<w:p>` | `Art. 1º …` (articulação starts) |

The **real ementa is only cell1 of table [2]**. cell0 (prazos) and the
Observação line [4] are website-export annotations.

There are 5 top-level `<w:tbl>` in the document; **only table [2] precedes the
articulação**. The other four (indices ~169–175) sit inside the Anexo and are
genuine data tables (frequency bands, service grids) — they must keep
rendering as `<table>` and are out of scope for any leading-table change.

## Pipeline trace (why each defect happens)

Entry point `ProjetoLei.fromBlocks` (`ProjetoLei.scala:408`). Block text is
matched against profile regexes after `normalizer.normalize` (NFD →
strip-diacritics → lowercase → collapse-space, see
`text/normalizer.scala`), so all profile patterns are written lowercase &
accent-free.

1. `unwrapLeadingLayoutTables` (`ProjetoLei.scala:390`) — flattens every
   `<table>` before the first articulação block, turning **each `<td>` into its
   own `<p>`** (added by note
   `20260509_151100_unwrap_leading_layout_tables_for_ementa.md`). Table [2]
   therefore becomes two paragraphs: `[prazos]`, `[RGST ementa]`.
2. `spanEpigrafe` (`ProjetoLei.scala:225`) — splits epigrafe = [0]; the
   remainder `pos` = `[boilerplate[1], prazos, RGST, empty[3], Observação[4],
   empty[5], preambulo[6], …]`. Line 245 drops **leading** pos-epigrafe /
   empty blocks only (`pos.dropWhile(isPosEpigrafe || isEmptyPar)`); the
   `^publicado:` override matches [1] and drops it, but `prazos` is not a
   pos-epigrafe match so `dropWhile` stops there.
3. `reconhecePreambulo(posEpigrafe)` (`ProjetoLei.scala:269`) — splits at the
   first `regexPreambulo` match. The default `regexPreambulo`
   (`DocumentProfile.scala:87`) only knows "O CONGRESSO NACIONAL", "O PRESIDENTE
   DA REPÚBLICA", "AS MESAS", etc. — **nothing matches "O CONSELHO DIRETOR DA
   AGÊNCIA …"**. So `isPreambulo` never fires; `prePreambulo` (→ `ementa1`)
   captures **everything up to the articulação**: prazos + RGST + Observação +
   preâmbulo + CONSIDERANDOs. `preambulo` comes back empty. → **Defect 2**, and
   the bulk of **Defect 1**.
4. `ementa = Block.joinParagraphs(ementa2).head` (`ProjetoLei.scala:432`,
   `Block.scala:743`) — concatenates all those paragraphs into one ementa
   string. → **Defect 1**.
5. The HYPERLINK garbage (**Defect 3**) is independent of the above; it enters
   far upstream in the DOCX reader (next section).

### Defect 3 root cause — `<w:instrText>` not skipped

`.docx` is always read by `DOCXReader.readDOCX` (`xhtml/XHTML.scala:52`,
`DOCXConverter`), never AbiWord. A Word hyperlink is a *complex field*:

```
<w:r><w:fldChar w:fldCharType="begin"/></w:r>
<w:r><w:instrText xml:space="preserve"> HYPERLINK "https://…/1371-resolucao-717" \l "art5"</w:instrText></w:r>
<w:r><w:fldChar w:fldCharType="separate"/></w:r>
<w:r><w:t>art. 5º da Resolução nº 717, de 23 de dezembro de 2019</w:t></w:r>   ← visible text
<w:r><w:fldChar w:fldCharType="end"/></w:r>
```

In `DOCXReader.processEvent` (`DOCXReader.scala:167`), the StAX `Characters`
case (line 187) treats **all** character data identically — it has no idea
whether the enclosing element is `<w:t>` (display text, keep) or
`<w:instrText>` (field instruction, drop). The instruction text is therefore
emitted verbatim. This doc has 104 `instrText` runs, all HYPERLINK, all leaking
(`fldChar` count 312 = 104×3 begin/separate/end). The visible text after
`separate` is also present, which is why the output shows the garbage
*followed by* the correct `<span xlink:href=…>art. 5º …</span>` (the linker
recognized the real text fine).

## Verification already done (pre-fix)

Reproduced the contaminated parse and confirmed each hypothesis with the
1.15.0 onejar (`target/lexml-parser-projeto-lei-1.15.0-onejar.jar`):

- Adding **only** `--prof-regex-preambulo '^o conselho diretor da agencia
  nacional de telecomunicacoes'` → preâmbulo now correctly contains the
  CONSELHO paragraph + CONSIDERANDOs + RESOLVE, and they leave the ementa.
  **(Defect 2 fixed by config alone.)**
- Additionally adding `^prazos` to the pos-epigrafe override → the prazos
  paragraph (cell0) drops out of the ementa, which then starts correctly with
  "Revoga e altera … RGST." But the trailing `Observação:` line **remains**,
  because `dropWhile` only removes *leading* pos-epigrafe blocks and Observação
  sits after the real ementa cell1. → confirms Defect 1 needs a Scala change
  (mid-region filtering), not just config.
- The HYPERLINK garbage is unaffected by any regex override (it is upstream of
  block processing). → confirms Defect 3 is a Scala change in `DOCXReader`.

## Fix plan

### Fix A — Defect 3: drop `<w:instrText>` content in DOCXReader (Scala)

In `DOCXReader.processEvent` (`docx/DOCXReader.scala:187`), guard the
`Characters` case so it ignores character data whose enclosing element is a
field-instruction element. The current open element is `ctx.head` (pushed by
the StartElement → `ctx.enter` on line 169), so the test is local and cheap:

```scala
case (ev : Characters,_) =>
  ctx.head match {
    case Some(e) if e.ns == Some(XElem.wNs) &&
                    (e.label == "instrText" || e.label == "delInstrText") =>
      ctx                     // field instruction codes are not document text
    case _ =>
      val brokenText = breakText(ctx.style, ev.getData)
      ctx.add(brokenText : _*)
  }
```

Notes:
- Cover both `instrText` and `delInstrText` (the tracked-change variant) for
  completeness; this doc only has `instrText`, but the cost is one extra
  comparison.
- `fldChar` runs carry no character data, so they need no handling. The
  display text after `<w:fldChar w:fldCharType="separate"/>` is ordinary
  `<w:t>` and is **kept**, so visible hyperlink text and link recognition are
  unaffected.
- `<w:fldSimple w:instr="…">` (the simple-field form, instruction in an
  attribute) does not occur here and never produced leaked text — the StAX
  attribute is not a `Characters` event — so it is out of scope.
- Place this near the `EntityReference` case so the field-instruction skip is
  visible alongside the other character-source handling.

### Fix B — Defect 1 (Observação tail): filter pos-epígrafe blocks across the whole ementa region (Scala)

The ementa region (`ementa1` from `reconhecePreambulo`) currently keeps any
paragraph that isn't *leading* pos-epígrafe. Make the pos-epígrafe filter apply
to the entire region so an annotation appearing *after* the real ementa
sentence is also removed. Smallest change is in `ProjetoLei.fromBlocks` right
after line 422:

```scala
val (ementa1Raw, preambulo, posPreambulo) = reconhecePreambulo(posEpigrafe)
val isPosEpigrafe = matchesOneOf(profile.regexPosEpigrafe)
val ementa1 = ementa1Raw.filterNot(isPosEpigrafe)   // drop annotations anywhere in the ementa region
val ementa2 = trimEmptyPars(ementa1)
```

(`matchesOneOf` is already a private method on the class — `ProjetoLei.scala:669`.)

Rationale for filtering vs. `dropWhile`:
- `spanEpigrafe` already establishes that pos-epígrafe paragraphs are
  "not-ementa boilerplate"; extending that judgement to the interior of the
  ementa region is consistent, and it's the mechanism the resolução profile
  override already targets.
- Filtering (not `dropWhile`) is required because the noise (`Observação`) is
  *after* the real ementa sentence.
- Risk: a legitimate ementa whose own text happens to match a pos-epígrafe
  pattern would be dropped. Mitigated because pos-epígrafe patterns are
  caller-supplied per profile and intentionally specific
  (`^publicado:`, `^acessos:`, and the new `^prazos`/`^observacao`); the
  generic profiles ship an empty/narrow pos-epígrafe list, so federal
  decretos/leis are unaffected.

With Fix B, cell0 (`^prazos`) and the Observação line (`^observacao`) are both
removed once those patterns are present in the pos-epígrafe override (Fix C),
regardless of their position in the region.

> Alternative considered (rejected for now): make
> `unwrapLeadingLayoutTables` keep only the *last* cell of a leading table, on
> the theory that the ementa is the rightmost cell. Rejected — too
> document-specific (relies on cell ordering), and it would silently discard
> cell content that in other Casa Civil layouts is the actual ementa
> (image-left / ementa-right is the common case, but not universal). The
> pos-epígrafe filter is content-driven and reuses an existing, override-able
> mechanism.

### Fix C — Defects 1 & 2 config: extend the resolução overrides in batch_parse.py

`--prof-regex-preambulo` and `--prof-regex-pos-epigrafe` are both already wired
in the CLI (`FECmdLine.scala:342` and `:339`; values are `%`-separated regex
lists, `stringToRegexList` at `:142`). Update
`PROFILE_OVERRIDES["resolucao"]` in `scripts/batch_parse.py:92` so every agency
resolução gets:

- a preâmbulo pattern for the agency boilerplate opener
  `^o conselho diretor da agencia nacional de telecomunicacoes`. **Decision
  needed:** keep this Anatel-specific, or broaden to `^o conselho diretor`
  (covers other agencies — ANPD, ANEEL, etc. — whose resolutions open the same
  way). Broader is preferable for the batch tool's intent, but verify it
  doesn't false-match an agency that puts a "Conselho Diretor" mention inside
  the ementa.
- two extra pos-epígrafe patterns `^prazos` and `^observacao` appended to the
  existing `^publicado:%^left\d%^acessos:` list, to strip the Anatel
  prorrogação and "Este texto não substitui …" annotations.

These regexes are matched against `normalizer.normalize`d text (lowercase,
accent-folded), so write them lowercase and without accents — `^observacao`
matches "Observação:".

Update the `PROFILE_OVERRIDES` doc-comment (`batch_parse.py:82–91`) and the
`--prof-regex-*` example in `CLAUDE.md`'s "Resolução" test invocation to match.

## Critical files

- `src/main/scala/br/gov/lexml/parser/pl/docx/DOCXReader.scala` —
  `processEvent`, `Characters` case (line 187). Fix A.
- `src/main/scala/br/gov/lexml/parser/pl/ProjetoLei.scala` — `fromBlocks`,
  after line 422 (`reconhecePreambulo` call). Fix B. (`matchesOneOf` at :669.)
- `scripts/batch_parse.py` — `PROFILE_OVERRIDES["resolucao"]` (line 92) +
  doc-comment (82–91). Fix C.
- `CLAUDE.md` — Resolução test-invocation example, to mirror the new overrides.

## Verification

1. **Build:** `mvn -Ponejar package` (Scala fixes A & B).
2. **Defect 3:** parse res_anatel_777 and confirm no `HYPERLINK` / `\l`
   substrings remain anywhere in the output:
   `grep -c 'HYPERLINK' …/res_anatel_777_2025.xml` → 0. Confirm the art. 2
   inciso XXXII link text reads `art. 5º da Resolução nº 717 …` with its
   `<span xlink:href="urn:lex:…717!art5">` intact.
3. **Defects 1 & 2:** run via the updated `batch_parse.py` (or the explicit CLI
   with the new `--prof-regex-preambulo` / extended `--prof-regex-pos-epigrafe`)
   and assert:
   - `<Ementa>` text == exactly
     `"Revoga e altera Resoluções expedidas pela Anatel e aprova o Regulamento
     Geral dos Serviços de Telecomunicações - RGST."` (no prazos prefix, no
     Observação suffix).
   - `<Preambulo>` contains the "O CONSELHO DIRETOR …" paragraph, the two
     CONSIDERANDO paragraphs, and `RESOLVE:`.
4. **Regression — federal docs unaffected:** re-parse a Decreto and a Lei from
   `../novas_normas_20260420/test_docs/` (NOT res_anatel_*; see memory
   `feedback_decreto_regression_targets`). The empty/narrow default
   pos-epígrafe + preâmbulo lists mean Fix B is a no-op there; confirm ementa
   and preâmbulo are unchanged byte-for-byte vs. a pre-fix run.
5. **Regression — Anexo data tables:** confirm the four in-Anexo tables still
   render as `<table>` inside `<Articulacao>` (Fix B touches only the
   pre-articulação ementa region; `unwrapLeadingLayoutTables` is unchanged, and
   it already scopes to before the articulação).
6. **DOCXReader unit test (recommended):** feed a minimal `<w:p>` event stream
   containing a begin/instrText/separate/`<w:t>`display/end field and assert
   `collectText` yields only the display text, no `HYPERLINK`.

## Open question for the user

- **Preâmbulo regex scope (Fix C):** Anatel-specific
  (`^o conselho diretor da agencia nacional de telecomunicacoes`) or broadened
  to `^o conselho diretor` for all agency resolutions? Affects only
  `batch_parse.py`; the Scala fixes are agency-agnostic.

---

## Implementation outcome (2026-05-25)

All three fixes implemented as planned. User chose the **broad**
`^o conselho diretor` preâmbulo regex (covers Anatel/ANPD/ANEEL/…).

Changes made:
- **Fix A** — `DOCXReader.scala` `processEvent` `Characters` case: skip data
  whose enclosing element is `<w:instrText>`/`<w:delInstrText>`.
- **Fix B** — `ProjetoLei.scala` `fromBlocks`: `ementa1Raw.filterNot(isPosEpigrafe)`
  before `trimEmptyPars`, so pos-epígrafe annotations are dropped anywhere in
  the ementa region.
- **Fix C** — `scripts/batch_parse.py` `PROFILE_OVERRIDES["resolucao"]`:
  pos-epígrafe extended with `^prazos%^observacao`; added
  `--prof-regex-preambulo ^o conselho diretor`. Doc-comment + `CLAUDE.md`
  Resolução example updated to match.

Built with `mvn -Ponejar package`. Verification (res_anatel_777, linker on):
- Ementa == exactly "Revoga e altera Resoluções expedidas pela Anatel e aprova
  o Regulamento Geral dos Serviços de Telecomunicações - RGST." ✅
- Preâmbulo contains "O CONSELHO DIRETOR …" + 2 CONSIDERANDOs + RESOLVE: ✅
- `HYPERLINK`/`\l` leak count: 0; 61 real `xlink:href` links produced; art. 2
  inciso XXXII → `…717!art5">art. 5º da Resolução nº 717 …` intact. ✅
- Regression — federal `decreto_52795_1963`: ementa "Aprova o Regulamento dos
  Serviços de Radiodifusão.", preâmbulo "O PRESIDENTE DA REPÚBLICA … DECRETA:"
  unchanged (Fix B no-op under default narrow pos-epígrafe). ✅
- Regression — res777 err-log problem-code counts identical to the pre-fix
  committed run (23×[10], 2×[16], 1×[3]); the remaining [3]/[16]/[10] warnings
  are pre-existing articulação/alteração body-parsing issues, out of scope. ✅
- In-Anexo data tables still render as `<table>`. ✅

Regenerated the referenced output
`../novas_normas_20260420/lexml_manual_20260524/res_anatel_777_2025.{xml,err.log}`
with the fixes. `batch_parse.py --dry-run` confirmed the emitted CLI carries
both new overrides and the correct agency authority/date.
