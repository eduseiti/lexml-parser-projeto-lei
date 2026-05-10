# Plan: split DOCX paragraphs at `<w:br/>` soft line breaks

## Context

In `res_anatel_777_2025.xml` the signature line renders as `CARLOS MANUEL BAIGORRIPresidente` (name and role fused), and in `res_anatel_396_2005.anexo2.xml` the annex heading renders as `ANEXO IPRINCÍPIOS E CRITÉRIOS PARA A ELABORAÇÃO DO DOCUMENTO DE SEPARAÇÃO E ALOCAÇÃO DE CONTAS(Redação dada pela Resolução nº 619, de 8 de agosto de 2013)` (three logical lines fused into one).

Inspecting the source `word/document.xml` confirms that in both cases the visual line breaks are **soft breaks** (`<w:br w:type="line"/>`, produced by Shift+Enter in Word) sitting **inside a single `<w:p>` paragraph**:

```
<w:r>
  <w:t>CARLOS MANUEL BAIGORRI</w:t>
  <w:br type="line"/>
  <w:t>Presidente</w:t>
</w:r>
```

The DOCX→XHTML converter at `src/main/scala/br/gov/lexml/parser/pl/docx/DOCXReader.scala` has explicit handling for `w:tab` (line 158) but **no handler for `w:br`**, so soft breaks become a no-op and the surrounding `<w:t>` text gets concatenated with no separator. The user has chosen the "split paragraph at each break" remediation: each `<w:br/>` should end the current XHTML `<p>` and start a new one, producing three paragraphs from the annex example and two from the signature example.

This will also help downstream parsing — `ANEXO I` as a standalone paragraph is a candidate match for the annex-heading recognizer (`anexoHeadRe = "(?i)^anexo\\b"` at `ProjetoLei.scala:640`), and a standalone `Presidente` (after the name) sits cleanly inside the assinatura block.

## Files to change

- `src/main/scala/br/gov/lexml/parser/pl/docx/DOCXReader.scala` — only this file.
- The plan capture file (this one); no new docs.

## Approach

Keep the change local to `DOCXReader.scala` and as targeted as possible: split a single paragraph's event stream into multiple sub-paragraphs around `<w:br/>` (specifically `w:type="line"` or no `w:type` attribute, plus `w:type="page"`). `w:type="column"` is rare in legal documents and column breaks are visually inline; treat them as a Space to be safe.

The cleanest implementation point is **right after** `collectBodyItems` returns a `ParItem` and **before** `collectText` is invoked. We split the `ParItem`'s event sequence at each `w:br` boundary, then run `collectText` per sub-sequence and emit one `<p>` per non-empty result.

### Concrete change

In `DOCXReader.scala` `readDOCX` (lines 317–325), the current loop is:

```scala
val nodes: LazyList[scala.xml.Elem] = bodyItems.flatMap {
  case ParItem(pEvs) =>
    val segs = collectText(pEvs)
    if (segs.isEmpty) None
    else Some(<p>{ segs.flatMap(_.toXML) }</p>)
  case TblItem(tblEvs) =>
    Some(convertTable(tblEvs))
}
```

Replace the `ParItem` branch so it splits on `w:br`:

```scala
val nodes: LazyList[scala.xml.Elem] = bodyItems.flatMap {
  case ParItem(pEvs) =>
    splitParAtSoftBreaks(pEvs).flatMap { subEvs =>
      val segs = collectText(subEvs)
      if (segs.isEmpty) None
      else Some(<p>{ segs.flatMap(_.toXML) }</p>)
    }
  case TblItem(tblEvs) =>
    Some(convertTable(tblEvs))
}
```

Add a small private helper next to `collectText`:

```scala
/**
 * Split a single <w:p> event stream into one or more sub-streams, breaking at
 * each <w:br/> with type "line" (default), "page", or no type. <w:br
 * w:type="column"/> stays inline (treated as part of the surrounding text by
 * collectText). Each returned slice carries no <w:br/> StartElement /
 * EndElement events, so collectText doesn't need to know about <w:br/>.
 *
 * Word-soft-break semantics: ANEXO I<w:br/>PRINCÍPIOS<w:br/>(Redação...) is
 * three logical lines that should render as three <p> elements.
 */
private def splitParAtSoftBreaks(evs: Seq[XMLEvent]): Seq[Seq[XMLEvent]] = {
  def isBreakStart(ev: XMLEvent): Boolean = ev match {
    case se: StartElement
      if se.getName.getNamespaceURI == XElem.wNs && se.getName.getLocalPart == "br" =>
      val typeAttr = se.getAttributes.asScala
        .collect { case a: Attribute => a }
        .find(_.getName.getLocalPart == "type")
        .map(_.getValue)
      typeAttr match {
        case None | Some("line") | Some("page") => true
        case Some("column") | _                 => false
      }
    case _ => false
  }
  def isBreakEnd(ev: XMLEvent, depth: Int): Boolean = ev match {
    case ee: EndElement
      if ee.getName.getNamespaceURI == XElem.wNs && ee.getName.getLocalPart == "br"
         && depth == 0 => true
    case _ => false
  }
  // Walk events; whenever we hit a splitting <w:br/>, end the current slice
  // (skipping both its StartElement and matching EndElement) and start a new
  // one. Events inside other elements are kept in the current slice.
  val out = scala.collection.mutable.ListBuffer[Seq[XMLEvent]]()
  val cur = scala.collection.mutable.ListBuffer[XMLEvent]()
  var skipUntilBrEnd = false
  for (ev <- evs) {
    if (skipUntilBrEnd) {
      ev match {
        case ee: EndElement
          if ee.getName.getNamespaceURI == XElem.wNs && ee.getName.getLocalPart == "br" =>
          skipUntilBrEnd = false
        case _ => () // <w:br/> is empty; this branch should not normally run
      }
    } else if (isBreakStart(ev)) {
      out += cur.toList
      cur.clear()
      skipUntilBrEnd = true
    } else {
      cur += ev
    }
  }
  out += cur.toList
  out.toSeq
}
```

Notes:

- `<w:br/>` is normally a self-closing empty element, but the StAX event reader still emits both StartElement and EndElement events for it; the `skipUntilBrEnd` flag handles both forms.
- We don't need to map `w:type="column"` to a Space explicitly — if we don't split on it, it just falls through `processEvent` as an unknown EndElement and is ignored, same as today.
- The `scala.jdk.CollectionConverters._` import is already in scope at the top of `convertTable` (line 219) but not at the file level; the helper can reuse the same `import` pattern (or we can hoist the import to the file top — the simpler change is to put `import scala.jdk.CollectionConverters._` inside the helper).

### Why this works for both reported cases

- **Signature**: `<w:t>CARLOS MANUEL BAIGORRI</w:t><w:br/><w:t>Presidente</w:t>` → two slices → two `<p>` elements: `<p>CARLOS MANUEL BAIGORRI</p>` and `<p>Presidente</p>`. The signing-region detector (`signingRegionEnd`, `ProjetoLei.scala:559`) already accepts subsequent person-name paragraphs after a LocalData/Assinatura match, so this won't break signature handling.
- **Annex header**: `<w:t>ANEXO I</w:t><w:br/><w:t>PRINCÍPIOS...</w:t><w:br/><w:t>(Redação...)</w:t>` → three slices → three `<p>` elements. The first matches `anexoHeadRe = "(?i)^anexo\\b"` at `ProjetoLei.scala:640`, so it'll be picked as the annex titulo.

### What about edge cases

- **Empty leading/trailing slices**: a paragraph starting or ending with `<w:br/>` produces an empty slice; the existing `if (segs.isEmpty) None` branch already drops those — no extra logic needed.
- **Tables**: `convertTable` already calls `collectText` per `<w:p>` inside cells (line 267). If a cell paragraph contains a soft break, it currently collapses; the same fix could be applied there, but **out of scope** for this change unless table cells with breaks are observed in real outputs. Plan a follow-up only if a regression appears.
- **Run-properties-only paragraphs**: `pPr`/`rPr` only carry styling and contain no text; if a `<w:br/>` appears inside `rPr` (it shouldn't per OOXML), the helper still splits — this is acceptable because the surrounding events will produce empty slices that get dropped.

## Verification

From the repo root:

1. **Rebuild the onejar**:
   ```bash
   mvn -Ponejar package -nsu -DskipTests
   ```

2. **Re-run the resolução conversions** that demonstrated the bug, checking the affected lines:
   ```bash
   rm -rf ../novas_normas_20260420/teste_resolucoes_batch
   python3 scripts/batch_parse.py \
     ../novas_normas_20260420/manual_20260421 \
     ../novas_normas_20260420/teste_resolucoes_batch \
     --linker /usr/local/bin/linkertool

   echo "=== res_777 signature line ==="
   grep -o '<[^<]*BAIGORRI[^<]*</[^>]*>' \
     ../novas_normas_20260420/teste_resolucoes_batch/res_anatel_777_2025.xml

   echo "=== res_396 anexo2 title ==="
   head -15 ../novas_normas_20260420/teste_resolucoes_batch/res_anatel_396_2005.anexo2.xml
   ```
   Expected:
   - signature: `BAIGORRI` and `Presidente` are no longer textually adjacent — separated either by a paragraph boundary or visible whitespace in the rendered XML.
   - anexo2: the title appears as `ANEXO I` cleanly, with `PRINCÍPIOS E CRITÉRIOS...` as a separate paragraph beneath it (or as part of the annex titulo string with a space between, depending on how the renderer joins them).

3. **Regression check** — re-run on the full `manual_20260421/` set and inspect the `OK`/`FAIL` counts:
   ```bash
   python3 scripts/batch_parse.py \
     ../novas_normas_20260420/manual_20260421 \
     /tmp/lexml_regr --linker /usr/local/bin/linkertool 2>&1 | tail -3
   ```
   Expected: `converted=23 skipped=1 failed=0` (same as before the fix). If any decreto/lei file regresses, the most likely culprit is a soft break that was previously concealing an issue (e.g. a stray `<w:br/>` inside a heading) — investigate per case.

4. **Decreto regression target** — per existing memory, avoid using `res_anatel_*` as the regression baseline for decreto-profile fixes. Instead spot-check `decreto_2338_1997.xml` and `decreto_5602_2005.xml` outputs to confirm no spurious paragraph splits in their bodies.

5. **Optional unit test** — none exists in this repo today (`src/test` is empty). If we want a guard rail, the natural place would be a small `DOCXReaderSpec` that synthesizes a `<w:p>` event stream containing a `<w:br/>` and asserts the number of `<p>` elements emitted. Skip unless the user asks.

## Out of scope

- Soft-break handling **inside table cells** (`convertTable`'s per-paragraph `collectText` call at line 267). No reported case yet.
- Soft-break handling in the **non-DOCX** XHTML pipeline (`xhtml/XHTML.scala` doesn't recognize `<br/>` as a splitter either). DOCX is the only known input format for this work.
- Rendering distinction between `w:type="line"` and `w:type="page"` (we treat both as paragraph breaks). For LexML XML output the distinction is meaningless.

## Follow-up

Splitting `<w:p>` at `<w:br/>` exposed a separate, pre-existing bug: `<w:b>`/`<w:i>` toggle handling in `processEvent` ignored the `w:val` attribute and flipped the style flag on EndElement, so explicit `w:val="false"` markers (which the Anatel resolutions carry on every paragraph) were being read as bold/italic ON. The mid-run `<w:br/>` slice boundary made this asymmetric (slice 1 styled, slice 2 not), which is what the user observed on `BAIGORRI` / `Presidente`. Captured as a separate plan: `20260510_001455_docx_bold_italic_toggle_plan.md`.
