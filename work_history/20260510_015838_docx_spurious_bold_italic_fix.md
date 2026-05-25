# Fix: spurious `<b>`/`<i>` wrapping every paragraph in DOCX-sourced output

## Symptom

When parsing `decreto` and `resolução` DOCX inputs, virtually every text node in the
generated LexML XML was wrapped in `<b><i>...</i></b>`, regardless of the actual
formatting in the source document. Sample (from
`teste_resolucoes_batch/decreto_2338_1997.xml`, generated before the fix):

```xml
<Preambulo id="preambulo">
  <p><i>O PRESIDENTE DA REPÚBLICA, no uso das atribuições...</i></p>
  <p><i>DECRETA</i></p>
</Preambulo>
...
<p><b><i>Ficam aprovados, na forma dos Anexos I e II,
   o Regulamento da Agência Nacional de Telecomunicações...</i></b></p>
```

Per-file bold/italic counts before the fix (a few examples from
`teste_resolucoes_batch/`):

| File | `<b>` | `<i>` |
| --- | --- | --- |
| `decreto_7724_2012.xml` | 441 | 442 |
| `decreto_12002_2024.xml` | 563 | 626 |
| `res_anatel_396_2005.anexo2.xml` | 2475 | 2474 |
| `res_anatel_612_2015.anexo1.xml` | 2061 | 2061 |

These counts are wildly out of proportion with the actual visual formatting
in the source `.docx` files, where bold/italic appear only in a handful of
headings or emphasised words.

## Root cause

The OOXML (`.docx`) run-formatting state machine in
`src/main/scala/br/gov/lexml/parser/pl/docx/DOCXReader.scala` (function
`processEvent`, originally around line 150) had **two distinct bugs that
combined to wrap nearly every text node in `<b><i>...</i></b>`**.

### Bug 1 — `w:val` attribute on `<w:b>` / `<w:i>` was ignored

In the OOXML schema (ECMA-376 §17.3.2.1 for `<w:b>`, §17.3.2.16 for `<w:i>`),
these are **toggle properties** whose `w:val` attribute can be:

- absent, `"true"`, `"1"`, `"on"` → enable bold/italic
- `"false"`, `"0"`, `"off"` → **disable** bold/italic (typically used to
  override an inherited "on" state from a paragraph or character style)

The previous code unconditionally turned the property ON whenever it saw the
end-element, regardless of `w:val`:

```scala
case "i" => ctx.leave(Some(s => s.copy(italics = true)))
case "b" => ctx.leave(Some(s => s.copy(bold = true)))
```

Sample DOCXs are saturated with `w:val="false"` overrides — they're written
by Word/LibreOffice as a defensive way of pinning a run to "no bold / no
italic" against the inherited paragraph style. Counts in the actual sources:

| Source DOCX | `<w:b/>` (real bold) | `<w:b w:val="false"/>` (off override) |
| --- | --- | --- |
| `decreto_52795_1963.docx` | 0 | many |
| `decreto_7724_2012.docx` | 116 | 1421 |
| `res_anatel_612_2015.docx` | 15 | 4835 |

So the parser was reading 4835 explicit "**not** bold" markers in
`res_anatel_612_2015` and converting all of them to `bold = true`. The same
pattern held for `<w:i>` / italic.

### Bug 2 — `<w:rPr>` inside `<w:pPr>` (paragraph-mark formatting) leaked into runs

A `<w:p>` paragraph in OOXML has the structure:

```
<w:p>
  <w:pPr>
    <w:rPr> ... </w:rPr>   <-- paragraph-mark rPr (formats the ¶ glyph only)
  </w:pPr>
  <w:r>
    <w:rPr> ... </w:rPr>   <-- run rPr (formats the run's text)
    <w:t>actual text</w:t>
  </w:r>
  ...
</w:p>
```

The `<w:rPr>` *directly inside* `<w:pPr>` describes the format of the
paragraph mark (the invisible ¶ character), **not** of the runs. It must
therefore have no effect on the rendered text.

The old state machine treated `pPr` and `rPr` identically:

```scala
case "pPr" | "rPr" => ctx.leave(Some(x => x))
```

`leave(Some(x => x))` keeps the current style (including any mutations done
inside the element), instead of restoring the saved one. As a result, any
`<w:b/>` mutation inside the paragraph-mark `<w:rPr>` survived past
`</w:rPr>` and `</w:pPr>` and persisted across every `<w:r>` in the rest of
the paragraph.

### Combined effect

Almost every paragraph in modern DOCX output contains a paragraph-mark
`<w:rPr>` that pins formatting via `w:val="false"` overrides. The first bug
flipped those overrides into "bold = true / italics = true". The second bug
let those mutations leak out of the `<w:pPr>` and apply to every run in the
paragraph. Net result: every paragraph rendered with `<b><i>...</i></b>`
around its text.

## Fix

`src/main/scala/br/gov/lexml/parser/pl/docx/DOCXReader.scala`, in
`processEvent` and two new helpers above it.

```scala
// OOXML toggle properties (e.g. <w:b/>, <w:i/>) accept w:val ∈ {true,false,1,0,on,off}.
// Absent attribute means ON. See ECMA-376 §17.3.2.1 / §17.3.2.16.
private def toggleVal(e : XElem) : Boolean =
  e.attributes.get((XElem.wNs,"val")) match {
    case Some("false") | Some("0") | Some("off") => false
    case _ => true
  }

// True when this rPr is the paragraph-mark rPr (direct child of pPr): its
// styles describe the ¶ glyph, not any run, and must not leak into runs.
private def isParagraphMarkRPr(stack : List[(XElem,TextStyle)]) : Boolean =
  stack match {
    case _ :: (parent, _) :: _ => parent.ns == Some(XElem.wNs) && parent.label == "pPr"
    case _ => false
  }
```

In the `processEvent` end-element switch:

```scala
case "i" => ctx.leave(Some(s => s.copy(italics = toggleVal(e))))
case "b" => ctx.leave(Some(s => s.copy(bold = toggleVal(e))))
case "rPr" =>
  if (isParagraphMarkRPr(ctx.stack)) ctx.leave()    // discard ¶-mark mutations
  else ctx.leave(Some(x => x))                       // run rPr: keep mutations
case "pPr" => ctx.leave()                            // paragraph properties: discard
```

### Why it works

The existing `Context.leave` semantics:

- `leave()` (no arg) **restores** the style saved when the element was
  entered, discarding everything done inside.
- `leave(Some(f))` **applies `f`** to the current style and keeps that as the
  new style, so mutations propagate outward.

Tracing the two cases:

**Run-level bold (legitimate emphasis)** —
`<w:r><w:rPr><w:b/></w:rPr>...</w:r>`:

1. `<w:b/>` end → mutates `bold = true`.
2. `</w:rPr>` — `isParagraphMarkRPr` sees parent is `<w:r>`, takes the
   `else` branch (`leave(Some(x => x))`) → keeps `bold = true`.
3. The run's `<w:t>` text picks up `bold = true`.
4. `</w:r>` end → `leave()` restores the pre-run style (resetting to
   non-bold). ✓

**Paragraph-mark bold (must be ignored)** —
`<w:p><w:pPr><w:rPr><w:b/></w:rPr></w:pPr>...<w:r>...</w:r>...</w:p>`:

1. `<w:b/>` end → mutates `bold = true`.
2. Inner `</w:rPr>` — `isParagraphMarkRPr` sees parent is `<w:pPr>`, calls
   `leave()` → restores style to pre-rPr (clears `bold`).
3. `</w:pPr>` → `leave()` → restores pre-pPr style.
4. Following `<w:r>` runs are unaffected by the paragraph-mark mutation. ✓

**`<w:b w:val="false"/>` inside a run rPr (off-override)** —
`<w:r><w:rPr><w:b w:val="false"/></w:rPr>...</w:r>`:

1. `<w:b>` end → `toggleVal(e)` returns `false`; mutates `bold = false`.
2. `</w:rPr>` keeps `bold = false`.
3. Run text correctly stays non-bold even if a parent style had bold on. ✓

## Validation

Re-ran the same parser invocations the user uses (decreto profile via
`-t decreto -a federal …`; resolução via the full
`--prof-regex-epigrafe` / `--prof-epigrafe-head` invocation in
`CLAUDE.md`).

Per-file `<b>` / `<i>` counts, before vs. after:

| File | Old `b` / `i` | New `b` / `i` |
| --- | --- | --- |
| `decreto_2338_1997.xml` | 5 / 7 | 0 / 0 |
| `decreto_2338_1997.anexo1.xml` | 343 / 343 | 0 / 0 |
| `decreto_52795_1963.xml` | 2 / 4 | 0 / 0 |
| `decreto_7724_2012.xml` | 441 / 442 | 72 / 0 |
| `res_anatel_612_2015.xml` | (wrapped everywhere) | 2 / 0 |

The non-zero post-fix counts correspond to genuinely bold text in the
source. Spot-checks:

- `res_anatel_612_2015.xml` keeps `<b>Observação</b>` and `<b>O CONSELHO
  DIRETOR DA AGÊNCIA NACIONAL DE TELECOMUNICAÇÕES</b>` — both confirmed
  bold in the source `<w:r>` blocks (`<w:rStyle w:val="Strong"/><w:b/>`
  and bare `<w:b/>`).
- `decreto_7724_2012.xml` keeps things like `<b>caput</b>`, `<b>banner</b>`,
  `<b>jetons</b>`, `<b>Do Serviço de Informação ao Cidadão</b>`,
  `<b>Do Pedido de Acesso à Informação</b>` — all consistent with the 116
  real `<w:b/>` instances counted in the source DOCX.

No new `*.err.log` entries beyond pre-existing legislative-technique
warnings (rótulo duplicado, descontinuidade) that already existed in the
pre-fix outputs and are unrelated to formatting.

## Scope and risk

- Change is confined to `DOCXReader.scala` (+23 / -3).
- The XHTML/CSS path (`xhtml/XHTML.scala::makeSpanOrIandB`, used for
  RTF-derived input) is unaffected — it already inspects CSS
  `font-weight` / `font-style` correctly.
- The downstream `LexmlRenderer` already handles `<b>` and `<i>` (e.g.
  `cleanBs`, `cleanTopBIs`), so reducing their occurrences cannot break
  consumers.
