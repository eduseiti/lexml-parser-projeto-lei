# Drop strikethrough text from LexML parsing

## Status

Implemented and verified on `decreto_2338_1997.docx`. Two issues were
found and fixed during implementation:

1. **v1 bug** — strikethrough text leaked through whenever a struck
   `<w:r>` contained a soft line break (`<w:br/>`). Fixed by filtering
   struck *runs* (whole `<w:r>…</w:r>` slices) upstream of paragraph
   splitting. See "Pitfall encountered (v1)" below.

2. **Linker recognition gap** (follow-on, see "Linker preprocessing"
   section below) — after the v2 strikethrough fix, the in-force §1/§2
   of art. 14, the §1 of art. 21, and several other dispositivos still
   had unlinked references to other legal documents. Root cause: the
   external `linkertool` (Haskell binary) does not recognise certain
   Portuguese-language notations common in Brazilian legal text. Fixed
   by normalising text on its way *to* the linker.

## Context

Brazilian legal documents commonly mark amended/repealed passages with the
strikethrough font effect: the text is kept in the source for historical
traceability, but the *current* text of the law no longer contains it. The
LexML parser must therefore ignore struck-through runs when producing the
LexML XML output, otherwise the parsed articulação will contain stale text
intermixed with the in-force text, breaking rotulo recognition, hierarchy,
and downstream renderings.

Reference document:
`../novas_normas_20260420/manual_20260421/decreto_2338_1997.docx`. Verified
by unzipping `word/document.xml`:

- 18 `<w:strike/>` runs, 0 `<w:dstrike>`, all using the toggle form
  (`<w:strike/>` with no `w:val`).
- All 18 fall **between the two `ANEXO` headers**, i.e. inside Anexo I (the
  regulamento attached to the decreto). None appear before the first ANEXO.
- Mix of paragraph-level patterns: entire runs struck through, mixed
  strike + normal runs in the same `<w:p>`, struck text inside
  `<w:hyperlink>`, and one occurrence of `<w:strike/>` inside a paragraph
  mark's `<w:rPr>` (i.e. inside `<w:pPr>/<w:rPr>`, describing the ¶ glyph
  rather than any run).
- A typical struck run carries both the superseded text and its amendment
  citation, e.g. *"Parágrafo único. A fiscalização …"* followed by a struck
  *"(Redação dada pelo Decreto nº 3.986, de 29.10.2001)"* — both must be
  dropped.

The parser currently has no notion of strikethrough. `TextStyle` in
`DOCXReader.scala` tracks only bold/italics/sub/superscript, and the XHTML
post-processing whitelist in `XHTML.scala` silently strips
`text-decoration:line-through` from CSS but keeps the surrounding text.
So today struck text flows into the output verbatim.

User decisions (captured up front):

- **Drop by default, with an opt-out CLI flag** (`--keep-strikethrough`)
  for safety / regression recovery.
- **Both conversion paths** (the direct DOCXReader and the AbiWord/XHTML
  fallback) must drop strikethrough, for consistency across input formats.
- **Empty paragraphs** left over after struck-run removal should be
  dropped (the existing `if (segs.isEmpty) None` filter in `readDOCX`
  already handles this; no extra logic needed).

## Approach

### 1. DOCXReader.scala — drop struck runs before paragraph splitting

This is the load-bearing change for the example document (DOCX → XHTML via
the direct reader).

**File**: `src/main/scala/br/gov/lexml/parser/pl/docx/DOCXReader.scala`

The filter operates at the *run level* (`<w:r>…</w:r>`), not the
text-segment level, and runs **before `splitParAtSoftBreaks`**. See the
pitfall section for why segment-level filtering does not work.

1. Add a new `stripStruckRuns(evs : Seq[XMLEvent], dropStrikethrough :
   Boolean) : Seq[XMLEvent]` that:
   - Iterates the event stream with a hand-rolled buffered iterator.
   - When it sees a `<w:r>` start element, it consumes events up to the
     matching `</w:r>` end element into a buffer.
   - It inspects the buffered slice for an enabled `<w:strike/>` or
     `<w:dstrike/>` **directly inside the run's `<w:rPr>`** (not inside
     nested elements, and not in any `<w:pPr>/<w:rPr>` because the slice
     is bounded by run start/end).
   - Toggle-aware: `<w:strike/>` alone, `w:val="true"`, `"1"`, `"on"` —
     enabled. `w:val="false"`, `"0"`, `"off"` — disabled.
   - If the run is struck, emit nothing; otherwise emit the buffered
     events as-is.
   - Non-run events pass through unchanged.

2. Call `stripStruckRuns` on `pEvs` *before* `splitParAtSoftBreaks` in
   the body-paragraph branch of `readDOCX` (around line 385):

   ```scala
   splitParAtSoftBreaks(stripStruckRuns(pEvs, dropStrikethrough)).flatMap { ... }
   ```

3. Call `stripStruckRuns` on each cell's `<w:p>` events inside
   `convertTable` (around line 412) so struck text in table cells is
   filtered too:

   ```scala
   .map(pEvs => collectText(stripStruckRuns(pEvs, dropStrikethrough))...)
   ```

4. Plumb `dropStrikethrough : Boolean = true` through:
   - `readDOCX(s, dropStrikethrough)`
   - `convertTable(tblEvents, dropStrikethrough)`
   - `stripStruckRuns(evs, dropStrikethrough)` — short-circuits when
     `dropStrikethrough == false` and returns the input untouched.

   Defaults of `true` keep existing internal callers behaving with the new
   default ("drop strikethrough").

5. **Do not** add a `strikethrough` field to `TextStyle`, and do not
   filter segments inside `collectText`. That approach (the v1
   implementation) was incomplete — see pitfall section.

6. The existing `isParagraphMarkRPr` guard remains relevant for the
   other style toggles (b, i, sub/superscript). `stripStruckRuns` does
   not interact with it because it only ever looks at the run's *own*
   `<w:rPr>`.

### Pitfall encountered (v1) — segment-level filtering misses struck text

The first implementation extended `TextStyle` with `strikethrough` and
filtered `TextSegment`s inside `collectText`. It worked for runs
containing a single `<w:t>` but **silently leaked** struck text when a
single struck `<w:r>` contained multiple `<w:t>` elements separated by
`<w:br/>`. In `decreto_2338_1997.docx` this produced duplicate Paragrafo
nodes (`art14_par1`, `art14_par2`) because the in-force §1/§2 text and
the struck old text both reached the output.

Root cause: `splitParAtSoftBreaks` slices the `<w:p>` event stream at
each `<w:br/>`. The first slice contains the run's `<w:r>` open and
`<w:rPr>` (including `<w:strike/>`); subsequent slices contain only the
bare `<w:t>` events that came after the break, with no `<w:rPr>` in
scope. `processEvent` walking the second slice never sees the
`<w:strike/>`, so the segment carries an empty `TextStyle`, evades the
strike filter, and reaches the output.

Concrete shape of the bug in the source:

```xml
<w:r>
  <w:rPr><w:strike/>…</w:rPr>
  <w:t>Parágrafo único. A fiscalização … atividades materiais de apoio.</w:t>
  <w:br/>
  <w:t>§ 1o  A fiscalização … atividades de apoio.</w:t>
</w:r>
```

After `splitParAtSoftBreaks`:

- Slice 1: `<w:r><w:rPr><w:strike/>…</w:rPr><w:t>Parágrafo único …</w:t>`
- Slice 2: `<w:t>§ 1o  A fiscalização …</w:t></w:r>`

Slice 2 is processed with no active `<w:strike/>`. Segment-level
filtering can therefore never catch it.

Fix: filter whole runs upstream of splitting (`stripStruckRuns` before
`splitParAtSoftBreaks`). Now the second `<w:t>` never reaches the
splitter at all, because the whole struck `<w:r>` was removed wholesale.

### 2. XHTML.scala — drop strikethrough on the AbiWord/LibreOffice path

AbiWord can render struck text as any of: a `<span style="text-decoration:line-through">`
wrapper, an `<s>` / `<strike>` / `<del>` element, or both. Today:

- `cleanStyle` (lines 380–382) drops the `line-through` declaration from
  the style attribute but leaves the span (and its children text) in place.
- `cleanSeqNodes` (lines 506–507) replaces any unknown element with its
  **children** — so `<s>X</s>` becomes `X`. The text is kept.

Fix:

1. **Tag-based drop** — add a transform that *deletes* `<s>`, `<strike>`,
   `<del>` elements (and their entire subtree), inserted into
   `pipelineXHTML` (line 715) **before** `cleanSeqNodes`. Pattern after the
   existing `bottomUp` helpers; one new function `dropStrikethroughElems`
   that returns `Seq.empty` when the element label matches the set.

2. **CSS-based drop** — in `makeSpanOrIandB` (lines 452–493), recognize
   `text-decoration:line-through`:

   ```scala
   val hasLineThrough = styles.get("text-decoration").contains("line-through")
   ```

   If `hasLineThrough && dropStrikethrough`, return `Seq.empty` instead of
   building the span. (Mirrors the existing `hasUnderline` plumbing on
   line 476/488–489, which is currently a no-op warning.)

3. Thread `dropStrikethrough` from `pipelineXHTML` down to
   `cleanAttributes`/`fixSpans`/`makeSpanOrIandB`. They're currently
   `private def`s on the `XHTML` object — adding a parameter is local.
   Note `allowedStyles` (line 376) does **not** contain `line-through`,
   so no allowlist change is needed there.

### 3. Wire the CLI flag

**File**: `src/main/scala/br/gov/lexml/parser/pl/fe/FECmdLine.scala`

1. Add `keepStrikethrough : Boolean = false` to `case class CmdParse`
   (line 91). Default `false` ⇒ default behavior is "drop strikethrough".

2. Register a new scopt option in the `parse` command's `.children(...)`
   block (after the `--write-errors-to-file` option around line 197):

   ```scala
   opt[Unit]("keep-strikethrough"). action { cmdParse { case (_, cmd) =>
     cmd.copy(keepStrikethrough = true)
   }}.text("preserva texto com formatação tachada (strikethrough). Padrão: removido."),
   ```

3. Plumb `cmd.keepStrikethrough` into the `process(...)` call (around
   line 494). The receiving function lives in `ParserFrontEnd.scala` —
   add the parameter there and pass it through to the converter pipeline.
   The converter wiring is in `XHTML.scala`'s `convertSrcToXHTML` flow
   (call site around line 755 `pipelineXHTML`); both `DOCXConverter`
   (line 48) and the eventual `pipelineXHTML` invocation need the flag.

   Implementation note: rather than adding a parameter to every Converter
   method, lift the flag into a single
   `case class ConverterOptions(dropStrikethrough: Boolean = true)` and
   pass it as an implicit or constructor arg to `DOCXConverter` / the
   function that runs `pipelineXHTML`. The smaller alternative is a
   threaded explicit parameter; pick whichever produces a smaller diff.

### 4. Test plan

End-to-end verification with the example document (per `CLAUDE.md`):

```bash
LC_ALL=C.UTF-8 LANG=C.UTF-8 java -jar target/lexml-parser-projeto-lei-1.15.0-onejar.jar parse \
   -m application/vnd.openxmlformats-officedocument.wordprocessingml.document \
   -i ../novas_normas_20260420/manual_20260421/decreto_2338_1997.docx \
   -o /tmp/decreto_2338_1997.xml \
   --write-errors-to-file /tmp/decreto_2338_1997.err.log \
   -t decreto -a federal -n 2338 --data 1997-10-07 \
   --linker /usr/local/bin/linkertool
```

Then verify, checking the **annex** outputs (`decreto_2338_1997.anexo1.xml`)
because all the struck text in this sample lives inside Anexo I, not in
the decreto's short body:

1. **Default run (drop)**: anexo1 must *not* contain any of the struck
   phrases from the source. Sample negative-grep targets:
   - `ressalvadas as atividades materiais de apoio`
   - `Redação dada pelo Decreto nº 3.986, de 29.10.2001`

   Expected: `grep -F -c` returns 0 for each in
   `decreto_2338_1997.anexo1.xml`.

   The in-force successor sentences (e.g. `§ 1º A fiscalização …
   ressalvadas as atividades de apoio.(Redação dada pelo Decreto nº
   4.037, de 29.11.2001)`) must still be present.

2. **Duplicate-paragrafo check (the v1 regression case)**: the source has
   a struck `<w:r>` containing a `<w:br/>`-separated old §1, and an
   unstruck in-force §1, both inside Art. 14. Verify exactly one
   `art14_par1` and exactly one `art14_par2` appear in
   `decreto_2338_1997.anexo1.xml`:

   ```bash
   grep -c 'art14_par1\b' …/decreto_2338_1997.anexo1.xml  # expect 1
   grep -c 'art14_par2\b' …/decreto_2338_1997.anexo1.xml  # expect 1
   ```

   Also confirm there are **zero** duplicate IDs anywhere:

   ```bash
   grep -oE 'id="[^"]+"' …/decreto_2338_1997.anexo1.xml | sort | uniq -d
   # expect empty output
   ```

3. **Opt-out run (`--keep-strikethrough`)**: re-run with the flag.
   Expect `art14_par1` to appear twice (one is the struck old §1 from
   the source's `<w:br/>`-containing run), and `ressalvadas as
   atividades materiais de apoio` to appear once. Schema-validation
   error count rises relative to the drop run (because the
   reintroduced struck text generates additional invalid IDs).

4. **Regression on a no-strike DOCX**: parse
   `decreto_11713_2023.docx` with and without `--keep-strikethrough`.
   The two outputs must be byte-identical (`diff | wc -l` returns 0).

5. **Unit-level smoke** (no formal test suite per `CLAUDE.md`): manually
   craft a `<w:p>` event sequence with:
   - one struck single-`<w:t>` run,
   - one struck multi-`<w:t>`-with-`<w:br/>` run (the bug case),
   - one unstruck run,
   - and a paragraph-mark `<w:pPr>/<w:rPr>/<w:strike/>`,

   drive it through `stripStruckRuns(evs, true)`, and assert all struck
   `<w:t>` text disappears while unstruck text remains and the
   paragraph mark's strike does not bleed into the unstruck run.

## Linker preprocessing — recognise Portuguese citation notations

Even after the strikethrough fix correctly removed amended text, the
in-force amendment-citation tails ("(Redação dada pelo Decreto nº 4.037,
de 29.11.2001)" and similar) on art14_par1, art14_par2, art21_par1 were
not turned into `<span xlink:href="...">` references. The same was true
of "Lei no. 8.977, de 1995" inside art17_cpt_inc42 (an inciso entirely
unrelated to strikethrough). The missing links were therefore not a
strikethrough regression — they were a pre-existing limitation of the
external `linkertool` (Haskell binary) used by the parser.

### Behaviour probed against the linker

Sending each candidate string through `linkertool --hxml --xml
--contexto=INLINE`:

| Source notation | Linker recognises? |
|---|---|
| `Lei nº 9.472, de 1997` (year only) | ✓ |
| `Decreto nº 4.037, de 29 de novembro de 2001` (long date) | ✓ |
| `Decreto nº 4.037, de 29/11/2001` (slash date) | ✓ |
| `Decreto nº 4.037, de 29.11.2001` (**dot date**) | ✗ |
| `Lei n. 8.977, de 1995` / `Lei nº 8.977, de 1995` | ✓ |
| `Lei no. 8.977, de 1995` (typewriter ordinal `no.`) | ✗ |
| `Decreto nº 2.853, de 2/12/1998` | ✓ |
| `Dec. 2.853, de 2/12/1998` (abbreviated `Dec.`) | ✗ |

The linker is tolerant in some ways (year-only, hyphen-numbered MPs,
mixed case months) but rejects: dot-separated dates, the `no.`
typewriter ordinal, and the `Dec.` abbreviation.

### Fix — normalise text before the linker call

In `src/main/scala/br/gov/lexml/parser/pl/linker/Linker.scala`,
`findLinks` now applies three small text rewrites to the `Seq[Node]` it
receives *before* sending it to the actor, and reverses the
date-rewrite on the linker's returned nodes:

1. **`DD.MM.YYYY` → `DD/MM/YYYY`** before linking, then back
   `DD/MM/YYYY` → `DD.MM.YYYY` after, so the visible LexML output
   preserves the source's dot-date notation. Pure linker-recognition
   aid; no visible-text change in the output.

2. **`no. <digit>` → `nº <digit>`**. Not reversed — the typographically
   correct ordinal sign appears in the output. Pattern is bounded by a
   following whitespace + digit so it only matches the ordinal form,
   not Portuguese preposition `no` followed by a sentence-ending
   period.

3. **`Dec. <digit>` → `Decreto <digit>`** (capital `Dec.` only).
   Not reversed — the canonical/expanded form appears in the output.
   Same digit-lookahead bound to avoid touching unrelated `dec.`
   abbreviations.

The rewrites are written as Scala regexes operating on `Text` nodes
within the `scala.xml` tree (`rewriteTextNodes` walks the tree and
replaces text content, leaving element structure alone).

### Why this lives in the parser, not the linker

The Haskell linker is a separate repository. Patching it requires a
release cycle on that side. Normalising text on the parser side is
contained, reversible (for dates), low-risk (regexes are tightly
bounded), and ships immediately. If the linker later adds native
support for these notations, the parser-side rewrites can be removed
without changing the linker's contract.

### Test plan for the linker fix

Re-run the verbatim CLAUDE.md invocation on `decreto_2338_1997.docx`
and verify each of the four user-flagged elements now contains an
`xlink:href`:

```bash
for elem in 'art14_par1' 'art14_par2' 'art21_par1' 'art17_cpt_inc42'; do
  echo "=== $elem ==="
  awk "/id=\"$elem\"/,/<\\/(Paragrafo|Inciso)>/" \
      ../novas_normas_20260420/teste_decreto/decreto_2338_1997.anexo1.xml | grep -c xlink:href
done
```

Expected: each command prints at least 1.

Also compare total link counts vs. the pre-fix output to confirm the
fix doesn't only patch the example dispositivos but recovers links
throughout the document:

```bash
grep -c xlink:href .../decreto_2338_1997.anexo1.xml          # NEW
grep -c xlink:href .../lexml_manual_20260510/...anexo1.xml   # OLD
```

In the example document the count rose from 15 → 35.

## Critical files to modify

- `src/main/scala/br/gov/lexml/parser/pl/docx/DOCXReader.scala`
  - New `stripStruckRuns(evs, dropStrikethrough)` near
    `splitParAtSoftBreaks`.
  - `convertTable(tblEvents, dropStrikethrough = true)` accepts the flag
    and wraps each cell's `<w:p>` events with `stripStruckRuns(...)`
    before passing them to `collectText`.
  - `readDOCX(s, dropStrikethrough = true)` accepts the flag and wraps
    each body paragraph's events with `stripStruckRuns(...)` before
    `splitParAtSoftBreaks`.
  - `TextStyle` is **not** modified; `processEvent` is **not** modified.
- `src/main/scala/br/gov/lexml/parser/pl/xhtml/XHTML.scala`
  - `DOCXConverter(otherConverter, dropStrikethrough = true)` — accepts
    the flag and forwards it to `readDOCX`.
  - `dropStrikethroughElems` transform (drops `<s>`, `<strike>`, `<del>`
    elements and their subtrees) inserted into `pipelineXHTML` before
    `cleanSeqNodes`.
  - `makeSpanOrIandB(prefix, scope, attrs, child, dropStrikethrough = true)`
    returns `Seq.empty` when the span carries
    `text-decoration:line-through`.
  - `pipelineXHTML(xhtml, dropStrikethrough = true)` accepts and
    propagates the flag.
  - `pipelineWithDefaultConverter(source, mimeType, dropStrikethrough = true)`
    accepts the flag.
  - `defaultConverter(dropStrikethrough = true)` is now a `def` (was a
    cached `val`) so the AbiWord wrapper can be constructed with the
    requested flag value per call.
  - The previous `pipeline(InputStream, Converter)` and
    `pipeline(rtfSource: InputStream)` callers remain compilable via
    overloads (no source change in `ParserFrontEnd.scala`).
- `src/main/scala/br/gov/lexml/parser/pl/linker/Linker.scala`
  - `findLinks(urnContexto, ns)` now normalises text in `ns` before
    sending it to the linker actor (`preprocess`: dot-date → slash-date,
    `no.` → `nº`, `Dec.` → `Decreto`) and restores dot-dates on the
    returned nodes (`restoreDates`).
  - New helpers: `rewriteTextNodes`, `dotDateRe`, `slashDateRe`,
    `numAbbrRe`, `decAbbrRe`, `preprocess`, `restoreDates`.
- `src/main/scala/br/gov/lexml/parser/pl/fe/FECmdLine.scala`
  - `CmdParse` gains `keepStrikethrough : Boolean = false`.
  - `--keep-strikethrough` registered in scopt (under the `parse`
    command's `.children(...)` block, next to `--write-errors-to-file`).
  - `process(..., dropStrikethrough : Boolean = true)` accepts the flag
    and forwards to `XHTMLProcessor.pipelineWithDefaultConverter(...)`.
  - The `main` dispatch passes `dropStrikethrough = !cmd.keepStrikethrough`.
  - `--info` dump prints `--keep-strikethrough` when set.

## Notes / Risks

- **Filter at the run level, not the segment level.** Segment-level
  filtering misses any struck `<w:r>` that contains a soft line break
  (`<w:br/>`). See "Pitfall encountered (v1)" above. The user-visible
  symptom is duplicate Paragrafo IDs (`art14_par1` twice, `art14_par2`
  twice in `decreto_2338_1997.docx` Anexo I).
- **Filter upstream of `splitParAtSoftBreaks`.** Once that splitter has
  run, the `<w:rPr>` is no longer in scope of the post-break slices and
  the strikethrough information is lost. The pre-filter approach also
  has the nice side effect that an entirely-struck `<w:p>` becomes
  empty, which the existing `if (segs.isEmpty) None` filter drops
  cleanly.
- **Toggle semantics.** OOXML toggle properties (`<w:strike/>`,
  `<w:dstrike/>`) treat absent `w:val` as ON and accept `true/false/0/1/on/off`.
  `stripStruckRuns` implements the same semantics as
  `DOCXReader.toggleVal` (used by bold/italics). Don't open-code
  `attributes.contains("strike")`.
- **Paragraph-mark rPr is safe by construction.** `stripStruckRuns`
  only inspects events between a `<w:r>` start and its matching `</w:r>`
  end. A `<w:strike/>` inside `<w:pPr>/<w:rPr>` (paragraph mark, applies
  to the ¶ glyph only) is not inside any `<w:r>`, so it is neither
  examined nor used to drop following runs.
- **Annexes inherit the fix.** `ProjetoLei.scala` chunks the document
  into sections (Anexo, Articulação, etc.) *after* DOCX → XHTML
  conversion. Since the filter runs during conversion, annexes receive
  the same treatment as the main body automatically.
- **AbiWord/LibreOffice path is independent.** That path handles
  strikethrough via `dropStrikethroughElems` (removes `<s>/<strike>/<del>`
  subtrees) and `makeSpanOrIandB` (drops spans with
  `text-decoration:line-through`). Both paths must change together so
  behavior is consistent across input formats.
- **No formal test suite.** Verification is end-to-end via the CLI
  against the example document plus a no-strike regression doc. The
  duplicate-ID check (Test plan step 2) catches the v1 bug specifically.
- **Linker preprocessing is a workaround, not a linker fix.** If the
  external `linkertool` later learns to recognise dot-dates, `no.`,
  and `Dec.` natively, the regexes in `Linker.scala` can be removed
  without changing behaviour (the rewritten forms are still
  recognisable). The reverse direction is also safe: more abbreviations
  can be added to `preprocess` as new documents surface them. The two
  patterns currently bound by `(?=\d)` lookahead (`no.` and `Dec.`)
  must keep that bound to avoid corrupting unrelated text.
