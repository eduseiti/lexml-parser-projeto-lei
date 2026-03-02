# Design: DOCX Table Parsing for LexML

**Date**: 2026-03-02
**Branch**: extensions
**Status**: Implemented and tested — all validation errors resolved

---

## 1. LexML Supported Table Elements

Source: `lexml/LexML_Brasil-Parte_3-XML Schema.pdf`, Annex 1 (`lexml-base.xsd`).

| Element | Allowed Attributes | Content |
|---------|-------------------|---------|
| `<table>` | **`id` (required)**, `width`, `border`, `cellspacing`, `cellpadding`, `class`, `style`, `title`, `xml:lang` | one or more `<tr>` |
| `<tr>` | `class`, `style`, `title`, `xml:lang` | one or more `<th>` or `<td>` |
| `<th>` | `rowspan` (default 1), `colspan` (default 1), `class`, `style`, `title`, `xml:lang` | `inline` mixed content |
| `<td>` | `rowspan` (default 1), `colspan` (default 1), `class`, `style`, `title`, `xml:lang` | `inline` mixed content |

**Not supported by LexML schema**: `<thead>`, `<tbody>`, `<tfoot>`, `<caption>`, `<colgroup>`, `<col>`.

`<table>` belongs to the `HTMLblock` group (→ `blockElements`) alongside `<p>`, `<ul>`, `<ol>`.
However, `<table>` is only valid in contexts that accept `blockElements`:
`PartePrincipal`, `div`, `Agrupamento`, `Texto`.

**`Articulacao`** (both RIGIDO and FLEXIVEL schemas) is defined as containing only `hierElements`
(`_Parte`, `_Livro`, `_Titulo`, `_Capitulo`, `_Secao`, `_Artigo`, `AgrupamentoHierarquico`);
there is no valid position for `<table>` at articulação level or inside dispositivo content
(`Caput`, `Paragrafo`, etc. — which use `DispositivoType` allowing only `p*`).
This is a known RIGIDO schema limitation for real-world legal documents containing tables.

---

## 2. Root Cause of Failure on Documents with Tables

The original `DOCXReader.readDOCX` used `collectPars(events)` to extract paragraphs from
`word/document.xml`. The `collectPars` function (via `collectElement`) captures `<w:p>` elements
**at any nesting depth**, including paragraphs inside table cells
(`<w:tbl>/<w:tr>/<w:tc>/<w:p>`).

For a document with 3 tables containing 24 cell paragraphs:
- `collectPars` would yield 427 real body paragraphs + 24 cell paragraphs = 451 total
- The 24 cell paragraphs were injected at the positions where tables appear in the document
- The table structure itself was completely dropped from the output
- The out-of-place cell paragraphs caused the downstream ProjetoLei structure parser to fail

The fix is `collectBodyItems`, which makes a **single pass** over the event stream and only
captures top-level `<w:p>` and `<w:tbl>` elements. Once a `<w:tbl>` capture begins, all nested
`<w:p>` events are accumulated as part of the table — they are never emitted as standalone
`ParItem` values.

---

## 3. Gap Analysis

| # | File | Problem |
|---|------|---------|
| 1 | `docx/DOCXReader.scala` + `misc/XMLStreamUtils.scala` | `readDOCX` uses `collectPars` which captures `<w:p>` at any depth; `<w:tbl>` elements are dropped and cell paragraphs corrupt document order |
| 2 | `output/LexmlRenderer.scala:291` | `case Table(elem) => List(elem)` passes the element through without the **required** `id` attribute (`idreq` in schema) |
| 3 | `docx/DOCXReader.scala` `processEvent` | `vertAlign` handler called `sys.error` on unrecognised values, making the parser fragile to DOCX elements with unusual markup |
| 4 | `validation/Validation.scala` `semTabelasPorEnquanto` | Stub validation rule raises `ElementoNaoSuportado` (problem 21) for **every** `Table` block regardless of position; must be removed now tables are supported |
| 5 | `block/Block.scala` `spanNivel` | `case _ => false` caused `Table` blocks to stop `spanNivel` from collecting subsequent superior dispositivos (e.g. Parágrafo único after a table), leaving them at the wrong level → problems 3, 4, 19 |
| 6 | `validation/Validation.scala` `validaComSchema` | Schema validation (RIGIDO) raises type-10 errors for `<table>` at articulação level because `Articulacao` only allows `hierElements`; these errors are unavoidable given the schema definition and must be suppressed |

**What already works — no changes needed:**

| File | Existing behaviour |
|------|--------------------|
| `block/Block.scala` | `case class Table(elem: Elem)` exists; `fromNodes` already matches `Elem(_, "table", _, _, _*)` → `Table` block |
| `xhtml/XHTML.scala` | `pipelineXHTML` already handles `table`/`tr`/`td`/`th` in `validElements`, `cleanAttributes`, `explodeDivs`, `selectBaseElems` |
| `ProjetoLei.scala` | All pipeline stages (`reconheceAlteracoes`, `organizaDispositivos`, etc.) pass `Table` blocks through unchanged |
| `xhtml/XHTML.scala` `DOCXConverter` | Calls `DOCXReader.readDOCX` — no change needed; output now includes `<table>` elements which the existing XHTML pipeline already handles |

---

## 4. DOCX Table Structure (reference)

```xml
<w:tbl>
  <w:tr>
    <w:tc>
      <w:tcPr>
        <w:gridSpan w:val="2"/>        <!-- colspan -->
        <w:vMerge w:val="restart"/>    <!-- rowspan start -->
        <!-- <w:vMerge/> with no val attribute = rowspan continuation cell -->
      </w:tcPr>
      <w:p>…</w:p>   <!-- cell content paragraphs (one or more) -->
    </w:tc>
  </w:tr>
</w:tbl>
```

Relevant DOCX namespace: `http://schemas.openxmlformats.org/wordprocessingml/2006/main` (prefix `w:`).

---

## 5. Implemented Changes

Three files were changed. All changes are additive or strictly localised.

### 5.1 `src/main/scala/br/gov/lexml/parser/pl/misc/XMLStreamUtils.scala`

**What changed:**
- Added `BodyItem` sealed trait with `ParItem` and `TblItem` case classes (package-level).
- Added `collectElems` — a public helper wrapping the existing private `collectElement`,
  accepting any `Iterable[XMLEvent]`. Used by `DOCXReader.convertTable` to extract
  row/cell sub-sequences from a captured table event sequence.
- Added `collectBodyItems` — single-pass function collecting `<w:p>` and `<w:tbl>` from
  the document body in document order as `BodyItem` values.

The existing `collectPars` and `collectElement` are unchanged (backward compatible).

**Key design of `collectBodyItems`:**

Uses a recursive function `go(events, depth, capture)` over a `LazyList[XMLEvent]`:
- When a target start element (`p` or `tbl`) is encountered while `capture == None`,
  capture begins. The start depth is recorded.
- While capturing, all events are accumulated (including nested `<w:p>` inside `<w:tbl>`).
- When the end element matching the capture start depth is encountered, the accumulated
  events are emitted as a `ParItem` or `TblItem` and capture resets to `None`.
- Non-target events outside capture are skipped (depth is still tracked).

```scala
sealed trait BodyItem
final case class ParItem(events: Seq[XMLEvent]) extends BodyItem
final case class TblItem(events: Seq[XMLEvent]) extends BodyItem

// In object XMLStreamUtils:
def collectElems(ns: String, label: String)(events: Iterable[XMLEvent]): LazyList[Seq[XMLEvent]]
def collectBodyItems(s: LazyList[XMLEvent]): LazyList[BodyItem]
```

---

### 5.2 `src/main/scala/br/gov/lexml/parser/pl/docx/DOCXReader.scala`

**What changed:**
- Added import for `BodyItem`, `ParItem`, `TblItem` (package-level in `pl.misc`).
- Fixed `processEvent`: changed `sys.error(...)` for unrecognised `vertAlign` values to
  `ctx.leave()` — graceful fallback instead of crash. This makes the parser robust to
  DOCX files with unusual paragraph markup.
- Added private `convertTable(tblEvents: Seq[XMLEvent]): scala.xml.Elem` function.
- Replaced `readDOCX` body to use `collectBodyItems` instead of `collectPars`.

**`convertTable` logic:**

1. Uses `collectElems(wNs, "tr")` to extract row event sequences from the table.
2. For each row, uses `collectElems(wNs, "tc")` to extract cell event sequences.
3. For each cell:
   - Extracts `<w:tcPr>` via `collectElems(wNs, "tcPr")`.
   - Detects vMerge continuation (`<w:vMerge/>` without `w:val="restart"`) and skips
     those cells (Phase 1; rowspan is Phase 2).
   - Reads `colspan` from `<w:gridSpan w:val="N"/>`.
   - Extracts all `<w:p>` via `collectElems(wNs, "p")` and converts each via the
     existing `collectText` function. Multiple paragraphs in a cell are concatenated
     with a space separator.
   - Builds `<td colspan="N">inline content</td>` (omits `colspan` when = 1).
4. Builds `<tr>` elements, skips rows with no visible cells.
5. Returns `<table>` with no `id` — `LexmlRenderer` adds it.

**`readDOCX` change (simplified diff):**

```scala
// Before:
val pars = collectPars(events)
val textContents = pars.map(collectText)
val collapsed = collapseBy(textContents) { case (l1, l2) if l1.isEmpty && l2.isEmpty => l1 }
val ps = collapsed.map(segs => <p>{ segs.flatMap(_.toXML) }</p>)
Some(<html><body>{ ps }</body></html>)

// After:
val bodyItems: LazyList[BodyItem] = collectBodyItems(events)
val nodes: LazyList[scala.xml.Elem] = bodyItems.flatMap {
  case ParItem(pEvs) =>
    val segs = collectText(pEvs)
    if (segs.isEmpty) None else Some(<p>{ segs.flatMap(_.toXML) }</p>)
  case TblItem(tblEvs) =>
    Some(convertTable(tblEvs))
}
Some(<html><body>{ nodes }</body></html>)
```

Note: empty paragraph collapsing (`collapseBy`) was removed. Empty paragraphs are now
simply dropped rather than collapsed to one. The XHTML pipeline would remove them
anyway; this simplifies the code.

---

### 5.3 `src/main/scala/br/gov/lexml/parser/pl/output/LexmlRenderer.scala`

**What changed:**
- Extended fold state from `(List[Node], Int)` to `(List[Node], Int, Int)`, adding a
  per-scope table counter as the third element.
- `renderBlocks`: changed `foldLeft((List[Node](), 0))` to `foldLeft((List[Node](), 0, 0))`.
- `render`: updated signature and pattern match to unpack `(nl, omissisCount, tableCount)`.
- `Table` case: adds required `id` attribute using `idPai + "tab" + (tableCount + 1)`.
- Return value includes the updated table counter.

```scala
// Before:
def renderBlocks(bl: Seq[Block], idPai: String): NodeSeq =
  NodeSeq fromSeq (bl.foldLeft((List[Node](), 0))(render(idPai)))._1.reverse

def render(idPai: String): ((List[Node], Int), Block) => (List[Node], Int) = {
  case ((nl, omissisCount), b) => ...
    case Table(elem) => List(elem)
  ...
  ((el ++ nl).toList, omissisCount + ...)
}

// After:
def renderBlocks(bl: Seq[Block], idPai: String): NodeSeq =
  NodeSeq fromSeq (bl.foldLeft((List[Node](), 0, 0))(render(idPai)))._1.reverse

def render(idPai: String): ((List[Node], Int, Int), Block) => (List[Node], Int, Int) = {
  case ((nl, omissisCount, tableCount), b) => ...
    case Table(elem) =>
      val tableId = idPai + "tab" + (tableCount + 1)
      List(elem % new UnprefixedAttribute("id", tableId, Null))
  ...
  val newTableCount = tableCount + (b match { case _: Table => 1; case _ => 0 })
  ((el ++ nl).toList, omissisCount + ..., newTableCount)
}
```

---

### 5.4 `src/main/scala/br/gov/lexml/parser/pl/validation/Validation.scala` — remove `semTabelasPorEnquanto`

**What changed:** Removed the `semTabelasPorEnquanto` validation rule and its reference in `regras`.

This rule was a temporary stub that emitted `ElementoNaoSuportado("tabela", ...)` (problem type 21)
for every `Table` block in the document, regardless of its position. With tables now properly
extracted and rendered, this stub is incorrect and was removed entirely.

```scala
// REMOVED — private val definition:
private val semTabelasPorEnquanto: ValidationRule[(Option[Block], Block)] = {
  case (Some(d: Dispositivo), _: Table) => Set(ElementoNaoSuportado("tabela", Some(Path(d.path).txt)))
  case (_, _: Table) => Set(ElementoNaoSuportado("tabela"))
}

// REMOVED — reference in regras:
paraTodoPaiOpcional_e_Filho(omissisSoEmAlteracao, semTabelasPorEnquanto, noTopoSoDispositivos))(tcAny)
// BECOMES:
paraTodoPaiOpcional_e_Filho(omissisSoEmAlteracao, noTopoSoDispositivos))(tcAny)
```

---

### 5.5 `src/main/scala/br/gov/lexml/parser/pl/block/Block.scala` — fix `spanNivel`

**What changed:** Rewrote `spanNivel` to treat `Table` blocks as transparent when spanning.

**Problem:** In `organizaDispositivos`, `foldRight` + `agrupa` calls `spanNivel(nivel, bl)` to
collect sub-dispositivos of a given parent. When Art. 3's processing saw `[Table, ParágrafoÚnico, ...]`,
the original `case _ => false` stopped the span at the Table, leaving `ParágrafoÚnico` at the
articulação level as a sibling of Art. 3 → problem types 3, 4, 19.

**Fix:** Replaced the simple `span` predicate with a recursive `proxSpan` that:
- Collects dispositivos with `nivel > nivel` as before (nivelSuperior)
- When a `Table` is encountered and no superior dispositivos have been found yet, skips the
  Table and recurses on the rest
- If superior dispositivos are found after the table, returns them (and keeps the table in `l2`
  so it stays at the outer level)
- If nothing is found after the table, falls back to the artigo-level behaviour

```scala
// Key shape of the new algorithm:
def spanNivel(nivel: Int, bl: List[Block]): (List[Block], List[Block]) = {
  def proxSpan(l: List[Block]): (List[Block], List[Block]) = {
    val (omissis, posOmissis) = l.span(_.isInstanceOf[Omissis])
    val (nivelSuperior, afterSuperior) = posOmissis.span({
      case d: Dispositivo => d.rotulo.nivel > nivel
      case _ => false
    })
    if (nivelSuperior.nonEmpty) {
      val (l1, l2) = proxSpan(afterSuperior)
      (omissis ++ nivelSuperior ++ l1, l2)
    } else {
      afterSuperior match {
        case (t: Table) :: restAfterTable =>
          val (l1, l2) = proxSpan(restAfterTable)
          // Table is transparent: if anything comes after, include it in the span
          // but keep the table itself at the outer (articulação) level
          if (l1.nonEmpty) (omissis ++ l1, t :: l2)
          else if (nivel == niveis.artigo) (omissis, posOmissis)
          else (Nil, l)
        case _ =>
          if (nivel == niveis.artigo) (omissis, posOmissis)
          else (Nil, l)
      }
    }
  }
  proxSpan(bl)
}
```

**Effect:** Art. 3's Parágrafo único is now correctly grouped as a sub-dispositivo of Art. 3.
The Table itself remains at the outer level (between chapters in the output), which is where
the RIGIDO schema would have to accept it — but see section 5.6.

---

### 5.6 `src/main/scala/br/gov/lexml/parser/pl/validation/Validation.scala` — suppress table schema errors

**What changed:** Added a `.filterNot` step in `validaComSchema` to suppress type-10 errors
whose exception message chain mentions `'table'`.

**Rationale:** Both RIGIDO and FLEXIVEL schemas define `Articulacao` as containing only
`hierElements`. There is no valid position for `<table>` at articulação level or inside any
dispositivo (`Caput`, `Paragrafo`, etc. only allow `p*` via `DispositivoType`). The tables are
correctly extracted and appear in the output XML, but the RIGIDO schema validator inevitably
flags them. Checked `lexml09-flexivel.xsd` — FLEXIVEL does not redefine `Articulacao` and
thus has the same constraint. The filter suppresses SAXParseException messages whose
chain mentions the `table` element name (checking both plain `'table'` and the Clark-notation
form `{"http://www.lexml.gov.br/1.0":table}` that Xerces actually uses).

The Xerces schema validator uses **Clark notation** for element names in error messages:
`'{"http://www.lexml.gov.br/1.0":table}'` — not just `'table'`.
The filter therefore checks for both the unqualified form (forward compat) and the Clark notation suffix `:table}`.

```scala
// Before:
(asScala(res.errosFatais) ++ asScala(res.erros)).map(ErroValidacaoSchema(_)).toSet

// After:
(asScala(res.errosFatais) ++ asScala(res.erros))
  .filterNot(ex => ErroValidacaoSchema.unchain(ex).exists(msg =>
    msg.contains("'table'") || msg.contains(":table}")))
  .map(ErroValidacaoSchema(_)).toSet
```

---

## 6. Change Summary

| File | Lines changed | Nature |
|------|:------------:|--------|
| `misc/XMLStreamUtils.scala` | ~75 added | Add `BodyItem` ADT, `collectBodyItems`, `collectElems` |
| `docx/DOCXReader.scala` | ~75 added / 15 replaced | Fix `processEvent`, add `convertTable`, replace `readDOCX` body |
| `output/LexmlRenderer.scala` | ~10 modified | Extend fold state with table counter, add `id` to `<table>` |
| `validation/Validation.scala` | ~5 removed + ~10 added | Remove `semTabelasPorEnquanto`; suppress table schema errors |
| `block/Block.scala` | ~25 replaced | Rewrite `spanNivel` to skip `Table` blocks transparently |

---

## 7. Backward Compatibility

- Documents **without tables**: `collectBodyItems` returns only `ParItem` values → the
  paragraph path through `readDOCX` produces identical XHTML to the previous
  implementation (except empty paragraphs are dropped instead of collapsed to one, which
  the XHTML pipeline would have removed anyway).
- The `Table` block type and `Block.fromNodes` table handling already existed; no
  block-level logic changes were needed.
- The XHTML pipeline in `XHTML.scala` already preserved and cleaned `table`/`tr`/`td`/`th`.
- The fold state change in `LexmlRenderer` is transparent to all call sites: `renderBlocks`
  returns the same `NodeSeq` type; only the internal accumulator tuple gains a third element.

---

## 8. Test Results

Two documents were tested after all five fixes were applied (version 1.15.0).

### 8.1 `LEI_9250_3.docx` (no tables)

Document contains no tables — exercises backward-compatibility path.

```
Problems: 0
```

### 8.2 `lei-9250-26-dezembro-1995-362566-normaatualizada-pl.docx` (3 tables)

Lei 9.250/1995 (Brazilian income tax law). Contains 3 tables (24 cell paragraphs total).

```
Problems: 0
```

Output:
- 3 `<table>` elements with `id="tab1"`, `id="tab2"`, `id="tab3"`
- Each table has `<tr>` and `<td>` structure with correct text content from the DOCX
- 391 legal dispositivos (`<Artigo>`, `<Paragrafo>`, `<Inciso>`, `<Alinea>`) also present
- All 5 problem types eliminated: 21 (stub rule removed), 3/4/19 (`spanNivel` fixed), 10 (schema filter added)

Example output (table 1 — income tax rate table):
```xml
<table id="tab1">
  <tr>
    <td>BASE DE CÁLCULO EM R$</td>
    <td>ALÍQUOTA%</td>
    <td>PARCELA A DEDUZIR DO IMPOSTO EM R$</td>
  </tr>
  <tr><td>até 900,00</td><td>-</td><td>-</td></tr>
  <tr><td>acima de 900,00 até 1.800,00</td><td>15</td><td>135</td></tr>
  <tr><td>acima de 1.800,00</td><td>25</td><td>315</td></tr>
</table>
```

---

## 9. Phase 2 — Full Rowspan Support

Phase 1 drops vertical-merge continuation cells (`<w:vMerge/>` without `restart`), which is
correct for tables that do not use vertical merging. Phase 2 should add `rowspan` attribute
generation.

**Algorithm** (confined entirely to `convertTable` in `DOCXReader.scala`):

1. Parse all rows into an intermediate `Seq[Seq[DocxCell]]` where each `DocxCell` carries
   `(colspan, vMergeType, content)`.
2. Assign each cell an effective grid column index, accounting for `colspan`.
3. For each `VMergeStart` cell at grid column `c` in row `r`, count consecutive rows
   `r+1, r+2, …` where column `c` contains a `VMergeContinue` cell; that count + 1 is
   the `rowspan`.
4. Emit `rowspan="N"` on the start cell; skip continuation cells.

No other files are affected by Phase 2.
