# Treaty-style articles with a bare label, and annex rendering that dropped or reordered text: fix

**Created:** 2026-10-08 20:01:35
**Found in:** `../br-taxqa-r_v2.0/docs/20261008_133726_corpus_rebuild_review_answers.md` (N3: 435 label-only
article chunks) and the investigation in
`../br-taxqa-r_v2.0/docs/20261008_190226_reference_fixes_and_label_only_articles_investigation.md` §2
(defects D1–D3 there, fixes F1–F3 here).

Status: **done** (2026-10-08). Tests pass (41, of which 9 are new). A full re-parse of the
`../br-taxqa-r_v2.0` statutory corpus was made in a scratch folder and compared with the HEAD
parser (§4). `../br-taxqa-r_v2.0/lexml/` has **not** been replaced: that is the corpus re-parse
step, still to be run.

Changed files:

- `src/main/scala/br/gov/lexml/parser/pl/block/Block.scala`: new pass `anexaTextoArtigoSemCaput` (F1);
- `src/main/scala/br/gov/lexml/parser/pl/ProjetoLei.scala`: calls it in `parseArticulacao`, between
  `identificaTextosAgregadores` and `identificaTitulos`;
- `src/main/scala/br/gov/lexml/parser/pl/output/LexmlRenderer.scala`: `renderAnexoArticulado` (F2) and
  `renderAnexoBlocks` (F3);
- `src/test/scala/br/gov/lexml/parser/pl/block/ArtigoSemCaputTest.scala` (new).

---

## 1. Problems

Treaties, conventions and agreements put the article label alone on a line, and often a title on
the next one:

```text
Artigo 34                                   Artigo 102
O agente diplomático gozará de isenção…:    Composição, funcionamento e reuniões do Conselho… (Const. 17)
a) os impostos indiretos…                   1. O Conselho de Administração compõe-se…
```

The corpus had **440 `Artigo` elements with no text** (outside `Alteracao`) in 15 files: 9 treaty
decrees, 5 treaty annexes and `lei_12431`.

- **D1:** the text after a bare label was never attached to the article. It also caused secondary
  damage.
  - The text became a sibling `p` of the article. That `p` stopped `organizaDispositivos` from
    absorbing the alíneas and items that followed, so they became loose too (`ALI`/`ITE` chunks with
    no article).
  - When the text had no `.`, `;` or `:`, `identificaTitulos` made it the `TituloDispositivo` of the
    *next* article. Example: the body of art. 43 of Decreto 50.656 became art. 44's title.
  - A treaty title after the label ("Composição, funcionamento… (Const. 17)") stayed a loose `p`,
    which blocked the numbered items after it.
- **D2:** `renderAnexoArticulado` kept only the `Dispositivo`s after the first one
  (`rest.collect { case d: Dispositivo => d }`). Every loose paragraph after the first article of an
  articulated annex was **dropped without a warning**. The source text lost was 79 paragraphs in the
  Decreto 8.624 annex (most of its article bodies and titles), 43 in Decreto 9.358, 16 in the IN SRF
  81 forms and 5 in IN RFB 1.548.
- **D3:** `renderAnexoBlocks` (the `DocumentoGenerico` annex path) partitioned the blocks: all
  paragraphs and tables first, all dispositivos after. The Decreto 5.128 annex (OEI seat agreement) came
  out as 53 article bodies stacked under the preamble, followed by 85 label-only articles and loose alíneas.

## 2. Fixes

### 2.1 F1: `Block.anexaTextoArtigoSemCaput`

`Paragraph.dispositivoIfPossible` turns an article line into two blocks: the article
(`RotuloArtigo`, no content) and its caput (`RotuloParagrafo(None)`) holding the inline text. With a
bare label, the caput's content is `None`. The new pass runs on the flat block list (step 6a, after
`identificaTextosAgregadores`). It must run before `identificaTitulos`, which would otherwise take
the orphan text as the next article's title.

For an article followed by an **empty** caput (no sub-dispositivos, no fecha-aspas or nota), it
skips empty paragraphs and collects the following run of non-empty paragraphs. The run stops at the
next non-paragraph block, at a paragraph with abre/fecha-aspas or a nota, at an **all-uppercase
heading** ("DISPOSIÇÕES TRANSITÓRIAS"), and at a **treaty closing formula** (`fechoTratadoRe`: em fé
do que, em testemunho, feito/feita em, assinado em, pelo/pela governo, pela república).

- **Title:** the first paragraph becomes the article's `titulo` (`TituloDispositivo`) when it is
  ≤ 150 characters (`tituloArtigoMaxLen`), does not end in `.`, `;`, `:`, `,`, `?` or `!`, and is
  followed by more text or by a sub-article dispositivo (§, inciso, alínea, item).
- **Caput:** the rest of the run becomes the caput, joined with spaces into one paragraph. Step 6
  does the same for agrupadores, and the `Dispositivo` model holds one content block.

Then `organizaDispositivos` absorbs the alíneas and items after the caput, as it already did for
articles whose text starts with "1.".

Not touched: articles with inline text, articles inside `Alteracao` (the pass does not descend into
it), and Brazilian-style titles *above* "Art. N" (still set by `identificaTitulos`).

### 2.2 F2: `renderAnexoArticulado` keeps the paragraphs

The `Articulacao` of an articulated annex now renders every block after the first `Dispositivo`, in
order, through `renderBlocks`. Empty paragraphs are left out. This is what `renderArticulacao` already
does for the main document, which keeps loose `p` inside `Articulacao`.

### 2.3 F3: `renderAnexoBlocks` keeps the source order

Blocks are emitted in order. Each run of consecutive `Dispositivo`s goes through `renderBlocks` at
its own position. Table ids keep the old scheme (`tab` + running index among the non-`Dispositivo`
blocks), so no existing table id changes.

## 3. Tests (`ArtigoSemCaputTest`, 9)

| Test | Case |
|---|---|
| `corpoEAlineasDepoisDoRotulo` | Vienna art. 34: body → caput, `a)`/`b)` → `art34_cpt_ali1/2`; no loose `p` |
| `tituloEItensNumerados` | UPU art. 102: title line → `TituloDispositivo`, `1.`/`2.` → `art102_cpt_ite1/2` |
| `tituloECorpo` | NDB art. 15: title + body; a short sentence ending in "." stays caput text |
| `variosParagrafosNoFimDoCapitulo` | ALALC art. 1: two body paragraphs joined; the article stays inside `cap1` |
| `tituloAcimaDoArtigoNaoMuda` | "Do imposto" above "Art. 1º …" still becomes the title (existing behaviour) |
| `fechoDoTratadoFicaFora` | the pass does not take "EM FÉ DO QUE…" / "Feito em…" |
| `cabecalhoEmMaiusculasFicaFora` | an uppercase heading is not caput text |
| `anexoArticuladoNaoPerdeParagrafos` | F2: loose paragraphs between and after annex articles are rendered, in order |
| `anexoGenericoMantemAOrdem` | F3: `p`, `Artigo`, `p`, `table` in source order; table id `anexo1_tab3` unchanged |

Run with `LC_ALL=C.UTF-8 LANG=C.UTF-8 mvn test`. Without the UTF-8 locale the test sources compile
with the wrong encoding and the accented assertions fail.

## 4. Corpus check

Both jars parsed all 209 `.docx` of `../br-taxqa-r_v2.0/original/articulados` (386 XML files) with
`scripts/batch_parse.py --no-justificativa --linker /usr/local/bin/linkertool`, into scratch folders:

- baseline: HEAD `c587f6b`, jar sha256 `583a35e6…`;
- patched: this change, jar sha256 `7ea59676…`.

`lei_12431_20110624.docx` was edited by the user during the runs, so it was parsed again with the
baseline jar. On the same input, both jars give byte-identical output.

| Measure | Baseline | Patched |
|---|---:|---:|
| files changed | n/a | 38 of 386 |
| `Artigo` ids gained / lost (any file) | n/a | 0 / 0 |
| label-only `Artigo` (outside `Alteracao`) | 440 | **1** |
| source paragraphs missing from the XML: Decreto 8.624 / 9.358 / IN SRF 81 / IN RFB 1.548 | 79 / 43 / 16 / 5 | 0 / 1 / 0 / 1 |
| new or changed `TituloDispositivo` on articles | n/a | 263 in 9 files |

Label-only articles per file: 67542 149→0, 50656 65→0, 61078 39→0, 5128 annex 36→0, 56435 28→0,
75102 27→0, 57784 26→0, 9358 annex 1 24→0, 8624 annex 21→0, 85306 9→0, DL 92 annex 9→0,
9358 annex 3 6→0.

**The five cited articles that were empty** (dataset references 142-03, 151-01, 151-02, 153-02,
157-01) now have their text. `art34_cpt_ali4` (Decreto 56.435) and `art21_cpt_ali4` (Decreto 5.128
annex) now exist, so those two cited fragments become matchable.

**Titles.** All 263 new or changed titles were reviewed. They are the treaties' article headings: the Vienna
Consular Convention (70), NDB agreement (50), UPU acts (33 + 38 + 23), Germany double-taxation
convention (31), Decreto 85.306 (9), Decreto 52.288 (8 + 1). The one false positive found,
`decreto_67542` art. 51 "A Organização … realiza os seus fins por intermédio:?", led to adding `?`
and `!` to the final-punctuation set. It is caput text now. Decreto 50.656 art. 44 lost its
false title (the body of art. 43).

**F3 files** (generic annexes of IN RFB 1.717, 2.055, 2.172, IN SRF 208 and 256, Lei 9.504, MP
2.228-1, IN RFB 1.548 annex 9). Each file has the same elements, only in source order. Example: IN SRF 256
annex 2 alternates the PDF-print page header ("16/06/2015 Sistema Sijut…", parsed as an `Item`) and the
URL footer, page by page. The baseline had moved all the headers to the end.

## 5. Left as is

- **Remaining label-only article:** `decreto_52288` art. 10, the convention's last article. Its text
  is taken by the annex boundary. Not cited.
- **Paragraphs now kept but loose:** some of the text F2 saves has no label the parser reads, so it
  is a loose `p` in `Articulacao`. That is better than lost, but not attached:
  - "(i)", "(ii)" sub-items in the Decreto 8.624 annex (41 `p`);
  - "– …" dash items in Decreto 9.358 annex 1 (9);
  - the IN SRF 81 form fields (23);
  - three lone `*` paragraphs (Decreto 56.435, 8.289 annex 1, 9.580 annex 1).

  Recognizing "(i)" and "– " as item labels would be a separate change.
- **Existing merge rule in `organizaDispositivos.agrupa`** (`Block.scala`, the case that continues
  a dispositivo whose text ends in `,` `;` `-` *or* when the next paragraph "starts in lowercase").
  The test `Character.isLowerCase(p.text.head)` runs on `p.text`, which is the *normalized*,
  lowercase text, so it is true for every paragraph that starts with a letter. In effect, any loose
  paragraph after a dispositivo with text and no sub-dispositivos is appended to it. That is why a
  treaty's closing "EM FÉ DO QUE… / Feito em…" still ends up in the last article's caput. F1 stops
  before it, but this rule then appends it. Fixing the test (`p.unormalizedText`) would change
  output across the whole corpus and needs its own before/after review, so it was not done here.
- **`decreto_9358` annex 3:** "Capítulo 1 / Oferta de prestações" is an arabic-numbered chapter
  heading that the parser does not recognize. In the baseline its name was the title of art. 12; now
  it trails the previous item, as "Capítulo 1" already did. It is misplaced either way.

## 6. Next steps (in `../br-taxqa-r_v2.0`)

1. Build the jar (`mvn -Ponejar package -DskipTests`) and re-parse the statutory corpus into
   `lexml/articulados/`. Update `PARSER_VERSION` with the new commit and jar sha256.
2. Re-run `scripts/lexml/audit_article_coverage.py`, the segmentation, the dataset build (after
   pulling `../br-taxqa-document-fetcher`), the embedding of new texts, retrieval and evaluation.
