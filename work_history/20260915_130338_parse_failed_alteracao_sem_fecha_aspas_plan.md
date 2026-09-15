# Plan: br-taxqa-r_v2.0 batch — fix the 5 `parse-failed` (error 17) and the 61 `skipped` documents

Status: **executed 2026-09-15**. The required items and A2 are done. See
`20260915_142517_parse_failed_alteracao_sem_fecha_aspas_execution.md`.

This file was amended on 2026-09-15. It originally covered only the 5 `parse-failed` documents
(**Part I**). It now also covers the 61 `skipped` documents (**Part II**): the two were evaluated
together and share code and verification (see §0). The file name is kept so that earlier
references still resolve.

Batch run that produced the report:

```bash
python3 scripts/batch_parse.py ../br-taxqa-r_v2.0/original/articulados ../br-taxqa-r_v2.0/lexml \
   --jar target/lexml-parser-projeto-lei-1.15.0-onejar.jar --linker /usr/local/bin/linkertool
```

`../br-taxqa-r_v2.0/lexml/articulados/skipped_report.txt` has 66 entries: **5 `parse-failed`**
(Part I) and **61 `skipped`** (Part II).

---

## 0. Why one plan (synergy evaluation)

**The parts are technically separable.** Part II's *unblocking* is Python-only
(`batch_parse.py` + `agency_authority.json`). A trial run with the proposed flags and no Scala
change already produced XML for 59 of the 61 (§II.4).

**Executing them together still pays off:**

1. **Same parser pre-pass.** Part I's Fix A adds a paragraph-normalization pass
   (`Block.juntaFragmentos`) before `reconheceAlteracoes`. Part II's two main output-quality fixes
   belong in that same pass:
   - **E** handles the Receita editorial notes `[Redação dada…]`: about 1,350 leaks across 24
     documents.
   - **A3** handles the hard-wrapped lines of `circ_bc_3432`.
2. **Same closing-quote regex.** Part I's Fix B rewrites `reFimAlteracao`. Part II's Fix E needs that
   same regex to also accept a trailing `[…]` note after a closing quote. Otherwise
   `…” (NR) [Redação dada…]` stops closing amendments.
3. **Quote fixes meet the newly parsed documents.**
   - The Part I simulation already touched `in_srf_23`, `in_rfb_1558` and `in_rfb_2172`, which are
     Part II documents.
   - `in_rfb_1131` fails with error 17 in the Part II trial. It is a source defect that Part I's
     fixes do not change.

   Part I's regression gate is incomplete unless it runs over the Part II documents too.
4. **One baseline/diff cycle** instead of two.

**Recommended execution order** (§III): Phase 1 is Part II's Python work alone. It doesn't change the
CLI of any already-converted document, and that is checkable by dry run. Its output becomes the new
full-corpus baseline. Phase 2 is all parser changes (Part I A/B/C + Part II E/A3), diffed against
that baseline. Phase 3 is the optional items.

---

# Part I — the 5 `parse-failed` documents

| Document | Profile |
|---|---|
| `decreto_59566_19661114.docx` | federal / decreto |
| `decreto_70951_19720809.docx` | federal / decreto |
| `decreto_95711_19880210.docx` | federal / decreto |
| `decreto_legislativo_92_19751105.docx` | congresso.nacional / decreto.legislativo |
| `decreto_lei_1510_19761227.docx` | federal / decreto.lei |

For all five the parser **exits 0 and writes no XML**. The report's `STDERR_TAIL` holds only DEBUG
noise, and each `.err.log` has one fatal problem:
`Problem type = [17: Alteração sem fecha-aspas, categoria: [1: Erro de técnica legislativa]]`.

### I.1 Failure mechanism (shared by all five)

1. `Block.reconheceAlteracoes` (`block/Block.scala:412`): `reconheceInicio` (`:470`, `OL` variant
   `:484`) opens an `Alteracao` for **any** paragraph whose text starts with `“`, `"`, `”` or `''`.
2. `procuraFim` (`:414`) scans forward for the first paragraph matching `reFimAlteracao` (`:401`):

   ```scala
   """ *(?:\((ac|nr)\))? *(?:”|“|"|'')(?: *\((ac|nr)\.?\))?(?: *\([^()]*\))?$"""
   ```

   The quote must be the last thing in the paragraph, apart from an optional `(NR)`/`(AC)` and one
   parenthetical.
3. If the list runs out first, it throws `ParseException(AlteracaoSemFechaAspas)`.
   `ProjetoLeiParser.fromBlocks` catches it (`ProjetoLei.scala:497`) and returns `(None, errors)`.
   `FECmdLine.process` (`fe/FECmdLine.scala:590`) then renders nothing and exits 0.

The regex runs on `Paragraph.text`, which `text/normalizer.scala` lowercases, strips of accents and
whitespace-collapses. Quote characters are left untouched.

### I.2 Verdict: one symptom, three distinct root causes

**A. Paragraph fragments starting with the closing half of an inline quote: 59566, 70951.** The
DOCX really has separate `<w:p>` elements that split one sentence around a short quoted token (an
HTML→DOCX artefact):

```xml
<w:p>…em obediência ao disposto na alínea " c</w:p>
<w:p>" do inciso III, do artigo 46 do Estatuto da Terra</w:p>
<w:p>, e de sua regulamentação no Decreto 55.891, de 31-3-65, …</w:p>
```

59566 has 6 such pairs plus stand-alone `:` / `, e de …` fragments. 70951 has
`recebimento de " royalties` + `", aluguéis…`.

**B. Closing quote followed by punctuation: 95711, 1510.** Amendments end with `".` (in the corpus
also `";` and `". (NR)`), which `reFimAlteracao` can't match. Examples: 95711 [9]
`…atue em seu nome". `, and 1510 [20], [24], [41], [43], [48].

**C. Paragraph starting with an inline quoted term: decreto_legislativo_92.** The treaty heading
`ARTIGO 29<w:br/>"Land" Berlim` becomes its own paragraph `"Land" Berlim`, a side effect of
`20260509_233043_docx_soft_linebreak_plan.md`. Its first quote closes inline, but the paragraph
still opens an amendment.

**Experimental confirmation.** Scratch copies with only the trigger removed (A: merge the fragment
`<w:p>`s; B: `".`→`"`; C: unquote the heading) all produced XML with no type 17. What remains is
non-fatal (types 3/4/5/7/10). Type-10 schema errors are baseline noise: 89 of the 165 err.logs of
already-converted docs have them. Out of scope:

- 1510's source lacks the opening quote at [22] (`§ 1º Para os efeitos…`).
- 92's treaty lands in `anexo1`, and its `ARTIGO n` / `1.` numbering yields schema errors.

### I.3 Fixes

**Fix B: accept punctuation after the closing quote (`Block.scala:401`).**

1. Extend the regex. The **`\[…\]` alternative is added for Part II's Fix E**:

   ```scala
   val reFimAlteracao: Regex =
     """ *(?:\((ac|nr)\))? *(?:”|“|"|'')[.;]?(?: *\((ac|nr)\.?\))?(?: *(?:\([^()]*\)|\[[^\[\]]*\]))*\.?$""".r
   ```

   This covers `".`, `";`, `". (NR)`, `" (NR).`, and trailing `(…)` / `[…]` notes. Group numbering
   is unchanged, so the `group(1)`/`group(2)` nota extraction (`:427`, `:442`) still works.
   `cutRight(len)` also drops the trailing punctuation, which belongs to the enclosing sentence.
2. Add a closer guard, `def fechaAlteracao(t: String): Option[Regex.Match]`:
   - Take `prefix = t.substring(0, m.start)`.
   - If `prefix` starts with `“` or `"` and has more text after it, drop that char.
   - The paragraph closes the amendment iff the number of `"“”` in `prefix` is even.

   Use it in `procuraFim` (`:421` and `:437`). The "leading opener" and "prefix-only" details are
   required: a naive whole-paragraph parity check breaks `mp_497` / `lei_11941`
   (`“Art. 2o …” (NR)`) and `in_rfb_1558` (a lone `” (NR)` line).

**Fix C: don't open an Alteração on an inline quoted term (`Block.scala:470`, `:484`).** Add
`def abreAlteracao(t: String): Boolean`: `t` starts with a quote **and** either:

- the total `"“”` count in `t` is odd, or
- `fechaAlteracao(rem)` is defined, where `rem` is `t` without its opening quote.

With this, `"Land" Berlim` is not an opener, while `"§ 1º …".`, `"II - …";` and
`"II - …;" (NR) (Revogado …)` still open.

**Fix A: paragraph normalization pass before amendment recognition.** Add
`Block.juntaFragmentos(blocks: List[Block]): List[Block]` and call it in
`ProjetoLeiParser.parseArticulacao` between `trimEmptyPars` and `reconheceAlteracoes`
(`ProjetoLei.scala:313-315`). Only adjacent `Paragraph`s merge, as
`prev.copy(nodes = prev.nodes ++ cur.nodes)`. Part II adds rules A3 and E (§II.5) to this same pass.

- **A1 (required).** Merge `cur` into `prev` when both hold:
  - `cur.unormalizedText` starts with `"`/`”` followed by optional space and `[,;:)]`, **or** by
    whitespace and a lowercase letter that doesn't begin an alínea rótulo `^[a-z]\)`.
  - `prev` has an odd `"“”` count and ends with `["“]\s*[^"“”]{1,40}$`.

  Use `unormalizedText`, because `text` is lowercased. `.` is excluded on purpose, so that omissis
  `"……… "` (lei_9779) doesn't merge.
- **A2 (recommended).** Merge a paragraph starting with `,`, `;` or `:`. This fixes the rest of 59566
  and touches `decreto_67542` / `decreto_71733`.

**Fix D (optional, tooling).** When the output XML is missing, `batch_parse.py` should put the
err.log problem lines (after the `# ---` header) in the report, instead of the DEBUG-only
`STDERR_TAIL`. Don't change `FECmdLine`'s exit code.

### I.4 Regression pre-check (simulation over the 226 corpus DOCX)

A Python emulation of `reconheceInicio`/`procuraFim`, old vs. new (A1+A2, B, C), was run. It splits
at `w:br`, treats tables as opaque, and uses the parser's normalization.

| Document | Old | New | Change |
|---|---|---|---|
| the 5 targets | ERR17 | ok | fixed |
| `lei_11472_20070502` | ok | ok | closer `…" (NR).` found at the real end |
| `lei_9532_19971210` | ok | ok | `"II - …contribuinte";` self-closes; no longer swallows `II - o § 1º do art. 9º:` |
| `mp_2228-1_20010906` | ok | ok | `…regulamento". (NR)` closes; Art. 52 no longer swallowed |
| `in_srf_23_19830325` (Part II) | ok | ok | closer `…subitem 4.3".` recognized |
| `in_rfb_1131_20110221` (Part II) | ERR17 | ERR17 | unchanged; source defect (`"Art. 58-A …` never closed) |

**No document goes from ok to ERR17**, and every changed span is an improvement. This is
approximate: it covers the whole document, doesn't model `OL`, and isn't the real pipeline. The
real-parser diff (§III) is the gate. In particular, the Part II trial shows `in_rfb_2172` does *not*
fail with the current parser, although the simulation predicted ERR17 there: its quote-led lines are
inside tables.

---

# Part II — the 61 `skipped` documents

### II.1 Root cause

None of the 61 reached the parser. `batch_parse.detect()` rejected their filename prefixes, since
they are not in `RULES` or `AGENCY_PREFIX_TIPONORMA`: `Unrecognized document type prefix: <p>`. Their
agency acronyms are also missing from `scripts/agency_authority.json`, which today has only `anpd`,
`anatel` and `mjsp`.

### II.2 Inventory and acronyms

| Prefix | # | Document type → LexML `tipoNorma` | Issuer tokens (meaning) |
|---|---|---|---|
| `in` | 40 | Instrução Normativa → `instrucao.normativa` | `rfb` ×23, `srf` ×17 |
| `adi` | 12 | Ato Declaratório Interpretativo → `ato.declaratorio.interpretativo` | `rfb` ×3, `srf` ×9 |
| `ade` | 5 | Ato Declaratório Executivo → `ato.declaratorio.executivo` | `rfb` ×2, `srf`, `cosit`, `codac` |
| `resol` | 2 | Resolução → `resolucao` | `cgpc`, `cgsn` |
| `circ` | 1 | Circular → `circular` | `bc` |
| `port_conj` | 1 | Portaria Conjunta → `portaria.conjunta` | `tse` + `srf` (joint act) |

What the acronyms mean:

- **SRF:** Secretaria da Receita Federal (Ministério da Fazenda), until it became the RFB under
  Lei 11.457/2007.
- **RFB:** Secretaria da Receita Federal do Brasil. Its head is styled "Secretário *Especial*" in the
  2019+ preambles.
- **Cosit:** Coordenação-Geral de Tributação. The 2001 preamble calls it "Coordenação-Geral do
  Sistema de Tributação".
- **Codac:** Coordenação-Geral de Arrecadação e Cobrança. Cosit and Codac are both RFB/SRF units.
- **BC:** Banco Central do Brasil. The circular is issued by its Diretoria Colegiada.
- **CGPC:** Conselho de Gestão da Previdência Complementar (Ministério da Previdência Social),
  succeeded by the CNPC in 2010.
- **CGSN:** Comitê Gestor do Simples Nacional.
- **TSE:** Tribunal Superior Eleitoral.

### II.3 Authority URNs (checked against the LexML catalog/resolver)

Rules from the LexML URN spec (Parte 2):

- `;` introduces a hierarchically inferior level, from general to particular (§8.3).
- For portarias, INs and similar lower-hierarchy acts, the authority must be unambiguous but kept
  to the minimum needed for the act type (§8.3.1), e.g.
  `ministerio.fazenda;secretaria.receita.federal:instrucao.normativa`.
- Joint acts list all issuers separated by `,`, **in alphabetical order** (§8.2).

The LexML resolver distinguishes "URN may be valid (document not catalogued)" from "invalid
according to the LexML vocabulary". That was used to test candidates.

| Token | Proposed authority fragment | Evidence |
|---|---|---|
| `srf` | `ministerio.fazenda;secretaria.receita.federal` | Catalog: IN SRF 459/2004, ADI SRF 2/2007 |
| `rfb` | `ministerio.fazenda;secretaria.receita.federal.brasil` | Catalog: IN RFB 1037/2010, ADE RFB 70/2009, ADI RFB 1/2016. `ministerio.economia;secretaria.especial.receita.federal.brasil` is **rejected** by the vocabulary, so the Fazenda form is used for 2019–2022 too. |
| `cgsn` | `ministerio.fazenda;comite.gestor.simples.nacional` | Catalog: Resoluções CGSN 5/2007 … 158/2021. Still `ministerio.fazenda` in 2020–21. |
| `tse` (+`srf`) | `ministerio.fazenda;secretaria.receita.federal,tribunal.superior.eleitoral` | **The exact document is catalogued:** `…:portaria.conjunta:2006-01-10;74` |
| `cosit`, `codac` | parent Receita authority **by date**: before 2007 → `srf` form, after → `rfb` form (cosit_30_2001 → SRF; codac_23_2019 → RFB) | `…brasil;coordenacao.geral.arrecadacao.cobranca` and the subtipo `ato.declaratorio.executivo;codac` are both **rejected** by the vocabulary |
| `cgpc` | `ministerio.previdencia.social` (default), or the spec-shaped `ministerio.previdencia.social;conselho.gestao.previdencia.complementar` | The council form is **rejected**; the parent `ministerio.previdencia.social:resolucao` is accepted |
| `bc` | `banco.central.brasil` (spec-derived) | **No accepted form found:** `banco.central.brasil`, `ministerio.fazenda;banco.central.brasil`, and even `ministerio.fazenda:circular` are rejected, so the tipo `circular` itself is outside the vocabulary |

**Policy proposed:** emit the most specific authority that the LexML vocabulary accepts. Where
nothing is accepted (BC circular), emit the spec-derived form and note it.

**Open decisions for the user:**

- **Cosit/Codac collapse to the parent.** An ADE Codac and an ADE RFB with the same date and number
  would collide. That is not the case in this corpus.
- **CGPC:** parent authority vs. council form.
- **BC circular:** outside the vocabulary.
- **SRF→RFB cut-over date** for Cosit/Codac: Lei 11.457 of 2007-03-16. Note that ADI SRF 2 of
  2007-03-27 still says "SRF". Any cut-over between 2007-03-16 and 2007-05-02 gives the same result
  for this corpus.

Parser compatibility: `Metadado.urn` concatenates `-a` verbatim, and the XSD types `URN` as
`xsd:anyURI`, so `;` and `,` pass through. The trial emitted exactly the catalogued joint-act URN.
In shell examples, `-a` values containing `;` must be quoted.

### II.4 Trial run (Python-side flags only; no Scala change)

The 61 documents were parsed into a scratch folder with the proposed `-a`/`-t`, filename
`-n`/`--data`, and the `--prof-*` overrides of §II.5 G1, with the linker on. Results:

- **All 61 exited 0; 59 produced XML.** The slowest took 25 s (`in_rfb_1911`, 14k paragraphs).
- **Epígrafe, ementa and preâmbulo are correct in all 59**, including:
  - bracketed pre-ementa lines (`[Revogado(a) pelo(a) …]`, `[Republicação …]`) and
    `Publicado no DOU…` lines, all skipped via pos-epígrafe
  - the article-less opener `SECRETÁRIO DA RECEITA FEDERAL, …` (in_srf_256)
  - `A SECRETÁRIA …` (in_rfb_936)
  - `A Diretoria Colegiada do Banco Central…`
  - `O MINISTRO PRESIDENTE DO TSE E O SECRETÁRIO…`
  - `O PRESIDENTE DO CONSELHO…`
  - `O COMITÊ GESTOR…`
- **Failed (2):**
  - `in_rfb_1131`: type 17, the source defect from §I.4.
  - `in_srf_84_19791220`: type 12 "Ementa ausente". It's a 1979 numbered-item IN with no ementa.
- **XML without articles (3):** `in_srf_23`, `in_srf_67` (type 9 "Articulação não identificada") and
  `in_srf_107` (only `Secao`). These are pre-1990 INs structured as numbered items (`1.`, `1.1`,
  `Seção I`) with no `Art.`.
- **Quality problems in otherwise good output:**
  - **Editorial notes leak.** Receita-site notes such as `[Redação dada pelo(a) …]`,
    `[Incluído …]`, `[Revogado …]`, `[Vide …]` and `[Suprimido …]` stay in the articulação: about
    **1,350 leaks in 24 docs** (e.g. in_rfb_1500: 220, resol_cgsn_140: 426). They either become the
    `<TituloDispositivo>` of the *next* dispositivo, which misattributes them
    (`<Inciso><TituloDispositivo><i>[Redação dada … IN RFB nº 2265 …]</i></TituloDispositivo><Rotulo>I –</Rotulo>…`),
    or they raise type 16 "Elemento da articulação não reconhecido".
  - **`circ_bc_3432` has 130 type-16 errors.** 95 of its paragraphs are incisos/alíneas whose labels
    come from **Word auto-numbering** (`w:numPr`: `upperRoman "%1"`, `lowerLetter "%1)"`). The
    DOCX reader never renders it (no `numPr` handling in `docx/`), leaving `- bens imóveis;`.
    The rest are hard-wrapped lines. It is the only corpus doc with lost auto-numbering.
  - **`port_conj_tse_srf_74`:** two TSE-site notes (`Lei nº 11.457/2007, art. 1º: altera a
    denominação…`, `V. Port. Conjunta-TSE/RFB n. 1/2016: …`) landed inside `<Ementa>`.
  - **Epígrafe loses the issuer.** The rendered epígrafe drops the issuer sigla
    (`ATO DECLARATÓRIO EXECUTIVO Nº 23…` vs. the source `… Codac nº 23…`), because it is rebuilt
    from `epigrafeHead` + number + date.
  - **`in_rfb_1558`:** type 14 `toContext: bloco nao esperado: Table`, i.e. a table inside an
    amended text. Non-fatal; parser limitation.
  - `in_srf_256` carries PDF-print artefacts: 139 lines like `16/06/2015 Sistema Sijut - Receita
    Federal` and `http://normas.receita.fazenda.gov.br/…`.
  - Type-10 schema errors: baseline noise, as in Part I.

### II.5 Fixes

**G1: `scripts/batch_parse.py` + `scripts/agency_authority.json` (required; unblocks 59/61).**

1. **Prefixes.** Extend `AGENCY_PREFIX_TIPONORMA` with `in`, `ade`, `adi`, `circ` and `resol` (next to
   `res`/`portaria`), plus the two-token prefix `port_conj` → `portaria.conjunta`. `resol` shares
   `res`'s legislative-token exclusion (senado/camara/congresso).
2. **Joint acts.** Agency tokens become *all* non-digit tokens between the prefix and the first digit
   token (1 for most, 2 for `port_conj_tse_srf`). Map each one; if any is unmapped, skip as today.
   Join them sorted alphabetically with `,`.
3. **Agency map.** Add `srf`, `rfb`, `cgsn`, `tse`, `cgpc` and `bc` as plain strings (§II.3). Let
   the loader accept a **date-ranged list** for `cosit`/`codac`, keeping plain strings backward
   compatible:

   ```json
   "codac": [{"before": "2007-05-02", "urn": "ministerio.fazenda;secretaria.receita.federal"},
             {"urn": "ministerio.fazenda;secretaria.receita.federal.brasil"}]
   ```

   It is resolved with `det.data`, which requires resolving authority *after* the date is known.
4. **`_EPIGRAFE_RE`.** Add `instrucao\s+normativa|ato\s+declaratorio\s+(?:executivo|interpretativo)|circular|portaria\s+conjunta`,
   with `portaria conjunta` placed before `portaria`. Shapes it won't match fall back to the
   filename values, which are correct for all 61: `RFB Nº 1131 DE 21/02/2011`, `CIRCULAR Nº 3.432`
   with no date, `Conjunta-TSE/SRF n. 74`. Optionally add a `de dd/mm/aaaa` alternative.
5. **`PROFILE_OVERRIDES`.** These are exactly the flags validated by the trial. Overrides *replace*
   the base lists, so each list is self-contained.

   Shared lists:
   - `POS_ORGAO = ^\[%^publicado no%^norma federal`
   - `PRE_ORGAO = ^(o |a )?secretari[oa]%^[ao] coordenador%^a diretoria colegiada%^o ministro presidente%^o presidente d%^o comite gestor`

   Per tipo (`epigrafe` / `continuacao` = `epigrafe` + `%^n[oº°˚]` / `head`):
   - `instrucao.normativa`: `^instrucao normativa`, head `INSTRUÇÃO NORMATIVA`
   - `ato.declaratorio.executivo`: `^ato declaratorio executivo`, head `ATO DECLARATÓRIO EXECUTIVO`
   - `ato.declaratorio.interpretativo`: `^ato declaratorio interpretativo`, head `ATO DECLARATÓRIO INTERPRETATIVO`
   - `circular`: `^circular`, head `CIRCULAR`
   - `portaria.conjunta`: `^portaria conjunta`, head `PORTARIA CONJUNTA`. Its pos-epígrafe also
     gets `^v\. %^lei n[oº°]\s*[\d.]+/\d{4}, art`, to keep the TSE notes out of the ementa.
   - `resolucao` (existing, shared with res_anatel/anpd): append `^o presidente d%^o comite gestor`
     to its preâmbulo and `^\[%^publicado no` to its pos-epígrafe. This is covered by the Anatel/ANPD
     regression diff.
6. Update the module comments (agency prefixes, joint acts, date-ranged map) and `CLAUDE.md`: add an
   Instrução Normativa example invocation with a quoted `-a`, and list the new prefixes.

**G2: editorial-note rule in the Part I normalization pass (required for output quality).**

- Rule **E**, in `juntaFragmentos`: a paragraph whose normalized text matches
  `^\[(redacao dada|incluid|revogad|vide|suprimid|renumerad|regulamentad|republicacao|texto)[^\]]*\]$`
  is appended to the **preceding** `Paragraph` as trailing text (`… texto. [Redação dada …]`).
  This mirrors the inline `(Redação dada pela …)` style of Planalto documents and keeps the
  information for RAG. Receita exports place the note right *after* the dispositivo it annotates.
  If the preceding block isn't a `Paragraph`, drop the note.
- E must run **before** `reconheceAlteracoes`/`identificaTitulos`. Otherwise the note keeps becoming
  the next dispositivo's `TituloDispositivo`.
- The `\[…\]` alternative added to `reFimAlteracao` in Fix B keeps `…” (NR) [Redação dada …]`
  closing correctly.
- Keyword-restricted, so Planalto docs are unaffected. A profile-gated `regexNotaEditorial` with a
  new `--prof-regex-nota-editorial` flag is the heavier alternative, if the rule should be opt-in.

**G3: optional rules in the same pass.**

- **A3 (hard-wrap continuation).** Merge `cur` into `prev` when `prev` doesn't end in `[.:;!?)\]]`
  and `cur.unormalizedText` starts with a lowercase letter that isn't a rótulo (`^[a-z]\)`,
  `^[a-z]\s*-`). The main beneficiary is `circ_bc_3432`. It may touch other docs, so size it first
  with the §I.4 simulator.
- **Print-artefact filter** for `in_srf_256`: drop `^\d{2}/\d{2}/\d{4} sistema sijut` and
  `^https?://normas\.receita\.fazenda\.gov\.br/`. This is doc-specific, so decide whether it
  belongs in the parser or in a source clean-up.

**G4: optional, epígrafe keeps the issuer sigla.** `batch_parse.py` passes
`--prof-epigrafe-head "<HEAD> <SIGLA>"`, e.g. `ATO DECLARATÓRIO EXECUTIVO CODAC` or
`INSTRUÇÃO NORMATIVA RFB`, with the sigla taken from the filename agency token (or captured from the
source epígrafe). Low effort; it preserves which numbering series the act belongs to.

**G5: optional, DOCX auto-numbering.** Render `w:numPr` labels in `docx/DOCXReader.scala`: read
`word/numbering.xml`, keep counters per `(numId, ilvl)`, and prefix `lvlText` with
`upperRoman`/`lowerLetter`/`decimal` formatting. Only `circ_bc_3432` needs it in this corpus (95
paragraphs), so defer unless more BCB documents are expected.

**G6: optional, native Scala profiles.** Add `InstrucaoNormativaProfile` / `AtoDeclaratorio…Profile`
etc., mirroring `PortariaProfile` and registered under the RFB and SRF authorities, so that plain
`-a … -t instrucao.normativa` works without `--prof-*` flags. The batch doesn't need it:
`PROFILE_OVERRIDES` stays the transport, and it must be kept in sync if G6 is done.

**Known unsupported (no work planned; list them in the batch report):**

- `in_srf_23`, `in_srf_67`, `in_srf_107` and `in_srf_84_19791220`: numbered-item INs without
  articles.
- `in_rfb_1131`: source defect; needs a manual DOCX fix.
- `in_rfb_1558`: table inside an amendment.

---

# III. Execution order and verification

**Phase 0: baseline A.** Copy `../br-taxqa-r_v2.0/lexml/articulados` (and the earlier corpora under
`../novas_normas_20260420/…`) to baseline folders.

**Phase 1: Part II G1 (+ G4 if chosen). Python only.**

1. Check `detect()` on all 226 filenames:
   - The 61 map to the `-a`/`-t` in §II.2–II.3; both `cosit`/`codac` date branches are exercised.
   - `port_conj_tse_srf` yields the alphabetical joint authority.
   - An unmapped agency is still skipped.
2. `--dry-run` over the corpus. Every already-converted document's CLI must be **identical** to the
   `# CMD:` header recorded in its current `.err.log`.
3. Real run → **baseline B**. Expect 59 new XMLs. Failures should be the 5 Part I docs plus
   `in_rfb_1131` and `in_srf_84_19791220`. Check URNs, epígrafe, ementa and preâmbulo against §II.4.

**Phase 2: parser changes (Part I A1/A2/B/C + Part II E, and A3 if chosen).**

1. Add unit tests for `reconheceAlteracoes` / `juntaFragmentos`, then run `mvn test`:
   - The Part I cases: `"…".`, `"…";`, `"…". (NR)`, `"…" (NR).`, non-closing `…o termo "x".`,
     `"Land" Berlim`, `"§ 1º …".`, `"II - …;" (NR) (Revogado …)`, the fragment pair, omissis and
     alínea non-merges, `“Art. 2o …” (NR)`, a lone `” (NR)`.
   - E: a note after a caput; a note after `…” (NR)`, where the amendment must still close; a note
     after a Table (dropped).
2. `mvn -Ponejar package`, re-run the batch, and diff against **baseline B**. Expected diffs:
   - the 5 Part I docs, now producing XML
   - `lei_11472`, `lei_9532`, `mp_2228-1`, `in_srf_23`, and the A2 docs (`decreto_59566`,
     `decreto_67542`, `decreto_71733`)
   - the 24 docs with `[…]` notes (notes move inline, and type-16 counts drop)
   - with A3: `circ_bc_3432`, plus whatever the pre-simulation predicted

   Investigate anything else.
3. Repeat the Phase 2 diff on the earlier corpora (manual_20260421, resoluções, portarias),
   especially res_anatel/anpd for the extended `resolucao` override.
4. Optionally re-run `scripts/run_segmentation_csv.py` and confirm the new documents yield
   dispositivos.

**Phase 3: optional items.** G5 (auto-numbering), G6 (native profiles) and Part I Fix D (report
problem lines), each with its own diff against the previous phase's output.

**Critical files:**

- `src/main/scala/br/gov/lexml/parser/pl/block/Block.scala`: `reFimAlteracao`, `procuraFim`,
  `reconheceInicio`, new `juntaFragmentos`
- `src/main/scala/br/gov/lexml/parser/pl/ProjetoLei.scala`: the `parseArticulacao` hook
- `scripts/batch_parse.py`: `AGENCY_PREFIX_TIPONORMA`, `detect()`, `_EPIGRAFE_RE`,
  `PROFILE_OVERRIDES`, map loader
- `scripts/agency_authority.json`
- `CLAUDE.md`
- Optional: `docx/DOCXReader.scala` (G5), `profile/DocumentProfile.scala` (G6)

**References (Part II research, 2026-09-15):**

- LexML URN spec, Parte 2 (§8.2 multiple authorities, alphabetical; §8.3 hierarchy with `;`):
  https://projeto.lexml.gov.br/documentacao/Parte-2-LexML-URN.pdf
- Catalogued examples:
  - IN SRF 459/2004: https://www.lexml.gov.br/urn/urn:lex:br:ministerio.fazenda;secretaria.receita.federal:instrucao.normativa:2004-10-17;459
  - IN RFB 1037/2010: https://www.lexml.gov.br/urn/urn:lex:br:ministerio.fazenda;secretaria.receita.federal.brasil:instrucao.normativa:2010-06-04;1037
  - ADE RFB 70/2009: https://www.lexml.gov.br/urn/urn:lex:br:ministerio.fazenda;secretaria.receita.federal.brasil:ato.declaratorio.executivo:2009-06-25;70
  - ADI SRF 2/2007: https://www.lexml.gov.br/urn/urn:lex:br:ministerio.fazenda;secretaria.receita.federal:ato.declaratorio.interpretativo:2007-03-27;2
  - Resolução CGSN 157/2021: https://www.lexml.gov.br/urn/urn:lex:br:ministerio.fazenda;comite.gestor.simples.nacional:resolucao:2021-01-28;157
  - Portaria Conjunta TSE/SRF 74/2006: https://www.lexml.gov.br/urn/urn:lex:br:ministerio.fazenda;secretaria.receita.federal,tribunal.superior.eleitoral:portaria.conjunta:2006-01-10;74
  - Portaria Conjunta RFB/PGFN 1751/2014 (alphabetical joint authority): https://www.lexml.gov.br/urn/urn:lex:br:advocacia.geral.uniao;procuradoria.geral.fazenda.nacional,ministerio.fazenda;secretaria.receita.federal.brasil:portaria.conjunta:2014-10-02;1751
- Acronyms:
  - COSIT: https://www2.camara.leg.br/atividade-legislativa/comissoes/comissoes-mistas/cpcms/siglas/siglario2/c/COSIT.html
  - CODAC: https://www.normaslegais.com.br/legislacao/adecodac16_2010.htm
  - CGPC: https://www.gov.br/previc/pt-br/normas/resolucoes/resolucoes-cgpc
  - BCB Circular 3.432: https://www.bcb.gov.br/pre/normativos/circ/2009/pdf/circ_3432_v3_l.pdf
  - TSE Portaria Conjunta 74/2006: https://www.tse.jus.br/legislacao/codigo-eleitoral/portarias-e-instrucoes/portaria-conjunta-nb0-74-de-10-de-janeiro-de-2006
