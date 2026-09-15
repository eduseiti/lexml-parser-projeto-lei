# Execution: br-taxqa-r_v2.0 batch — 5 `parse-failed` + 61 `skipped` documents

Executes `20260915_130338_parse_failed_alteracao_sem_fecha_aspas_plan.md`
(Part I A1/A2/B/C, Part II G1 + E). Optional items were **not** done: A3, the
print-artefact filter, G4 (issuer sigla in epígrafe), G5 (DOCX auto-numbering),
G6 (native profiles), Fix D (err.log lines in the report).

**Result:** batch goes from converted=160 / skipped=61 / parse-failed=5 to
**converted=224 / skipped=0 / failed=2**. The 2 failures are the expected
`in_rfb_1131` (type 17, source defect) and `in_srf_84_19791220` (type 12, no
ementa). No document lost its XML, and none went from ok to ERR17.

## Decisions taken (plan's open points)

- Cosit/Codac: collapse to the parent Receita authority by date, with the
  cut-over at `before: 2007-05-02` (cosit_30_2001 → SRF, codac_23_2019 → RFB).
- CGPC: parent authority `ministerio.previdencia.social`.
- BC circular: spec-derived `banco.central.brasil` (outside the LexML vocabulary).

## Changes

### Phase 1 — `scripts/batch_parse.py`, `scripts/agency_authority.json`

- `AGENCY_PREFIX_TIPONORMA` is keyed by token tuples (longest wins): adds `resol`,
  `in`, `ade`, `adi`, `circ`, `port_conj`. `res`/`resol` + senado/camara/congresso
  still fall through to RULES.
- Agency tokens: all non-digit tokens between the prefix and the first digit
  token. Any unmapped token → skipped. Joint authorities are `,`-joined,
  sorted by URN fragment.
- Map loader: values are a string or a date-ranged list `[{before, urn}, …]`.
  `resolve_agencies` runs in `detect()` and again in `process_file` once the
  epígrafe date is known.
- `_EPIGRAFE_RE` adds instrução normativa, ato declaratório
  executivo/interpretativo, portaria conjunta (before portaria) and circular,
  plus a numeric `de dd/mm/aaaa` date alternative (groups 5–7).
- **Extra fix not in the plan:** in the agency branch, with a full 8-digit date
  and no numero, a 4-digit token taken as `ano` becomes the numero. Without it,
  `in_rfb_1131_20110221` got no `-n`.
- `PROFILE_OVERRIDES`: `_orgao_overrides()` builds the IN/ADE/ADI/circular/
  portaria-conjunta entries from the shared `_POS_EPIGRAFE_ORGAO` /
  `_PREAMBULO_ORGAO`. `portaria.conjunta` adds the TSE-note pos-epígrafe
  patterns. `resolucao` gains `^\[%^publicado no` and
  `^o presidente d%^o comite gestor`.
- `CLAUDE.md`: new prefixes and an IN example with a quoted `-a`.

### Phase 2 — parser (`block/Block.scala`, `ProjetoLei.scala`)

- **B:** new `reFimAlteracao` (`[.;]?` after the quote, repeated `(…)`/`[…]`
  notes, trailing `.`). Adds the closer guard `fechaAlteracao`, used in both
  `procuraFim` sites.
- **C:** `abreAlteracao` (odd quote count, or the paragraph closes itself)
  replaces the plain starts-with-quote tests. `''` openers keep the old
  unconditional behavior.
- **A1/A2/E:** `Block.juntaFragmentos`, called in `parseArticulacao` between
  `trimEmptyPars` and `reconheceAlteracoes`. It looks back past empty
  paragraphs, and consecutive notes chain onto the same paragraph.
  - E appends the note with a space.
  - A1 and A2 join with no separator.
  - A note preceded by a Table is dropped.
- A note merged after `…” (NR)` is removed by `cutRight` together with the
  `(NR)`, the same way trailing `(…)` parentheticals already were.
- Tests: `src/test/scala/.../block/AlteracaoFragmentosTest.scala` (22 JUnit 4
  tests). `pom.xml` gains a `junit:junit:4.13.2` test dependency.

## Verification

Folders under `../br-taxqa-r_v2.0/lexml/`:

- `articulados_baselineA`: the original output.
- `phase1_new61`: the 61 new documents, old jar.
- `articulados_baselineB`: A plus phase1_new61.
- `articulados_phase2`: full corpus, new jar.

`lexml/articulados` itself was left untouched.

- **Phase 1:**
  - CLI identical to the `# CMD:` header for all 165 already-converted docs.
  - 59/61 new XMLs.
  - URNs match the catalogued forms, e.g.
    `…secretaria.receita.federal,tribunal.superior.eleitoral:portaria.conjunta:2006-01-10;74`.
  - Epígrafe, ementa and preâmbulo spot-checked.
- **Phase 2 vs baseline B: 186/226 unchanged.** Changed:
  - The 5 Part I docs now produce XML: 59566 (88 arts), 70951 (85), 95711
    (3 arts, 1 alteração), 1510 (20 arts, 4 alterações), decreto_legislativo_92
    (treaty in anexo1, 222 type-10 errors, out of scope). The split inline
    quotes merged (`alínea " c" do inciso III`, `" royalties", aluguéis`).
  - A2 docs: decreto_67542 (`onde se lê::…`, a harmless doubled colon from the
    source) and decreto_71733 (stray space before a comma removed).
  - lei_11472 and lei_9532: type 16 gone, as the plan predicted.
  - 24 Receita docs with `[…]` notes: notes are now inline, no longer a
    misattributed `<TituloDispositivo>`. Big problem drops, e.g. resol_cgsn_140
    type-10 303→0 and type-16 11→0; in_rfb_2066 type-10 41→0; in_srf_208 90→7.
  - in_rfb_1911: Parte V's Livros are now nested (`prt5_liv*`). The single new
    type 5 exposes a LIVRO II that really is repeated in the source.
  - Linker granularity only, text unchanged: lei_10833, lei_13043, lei_9504,
    mp_2158-35, decreto_9580.anexo1, lei_8242.anexo1. References are now split
    correctly, e.g. `art. 4º, inciso III` → `!art4_cpt_inc3` instead of the
    wrong `!art6_cpt_inc3`. A re-run with the same jar is byte-identical, so
    this is not noise.
- **Not changed although the plan predicted it:** `mp_2228-1`. Its closer
  `…regulamento". (NR)` follows a caput marked `(Revogado …)` and has no quoted
  opener, so nothing changes. Not a regression.
- **Not done:**
  - Phase 2.3: the earlier corpora `../novas_normas_20260420/` are not on this
    machine, so the extended `resolucao` override was not diffed on
    res_anatel/anpd.
  - Phase 2.4: `run_segmentation_csv.py` fails with the installed `saxonche`
    (`'PyXslt30Processor' object has no attribute 'exception_occurred'`). This
    is a pre-existing API mismatch, unrelated to these changes.

## Known unsupported (unchanged)

- `in_srf_23`, `in_srf_67`, `in_srf_107`, `in_srf_84_19791220`: numbered-item
  INs with no articles.
- `in_rfb_1131`: source defect.
- `in_rfb_1558`: table inside an amendment.
- `circ_bc_3432`: auto-numbered incisos/alíneas (G5).
