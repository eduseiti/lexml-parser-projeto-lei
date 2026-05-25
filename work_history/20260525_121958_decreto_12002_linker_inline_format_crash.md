# Analysis + Fix — Parser crash on `decreto_12002_2024.docx` (linker chokes on inline-format runs)

> **Status: FIXED (2026-05-25).** Both changes below implemented and verified.
> `decreto_12002_2024.docx` now parses to valid XML (exit 0, no "Erro de
> sistema"/timeout); the formerly-crashing citations (`art. 84, caput, inciso
> VIII`; `art. 150, caput, inciso I`) are correctly linked. Regression sample
> `decreto_2338_1997.docx` unchanged. Per user decision, no unit test added
> (repo has no test infrastructure). See "Implementation & outcome" at the end.


## Context

Parsing `../novas_normas_20260420/manual_20260421/decreto_12002_2024.docx` with
the linker enabled aborts the whole document: **no XML is produced** and the
error log shows

```
Problem type = [14: Erro de sistema, categoria: [5: Erro geral do parser]],
pos = List(), msg = Some(Erro de sistema: Future timed out after [30 seconds])
```

Invocation:

```bash
LC_ALL=C.UTF-8 LANG=C.UTF-8 java -jar target/lexml-parser-projeto-lei-1.15.0-onejar.jar parse \
  -m application/vnd.openxmlformats-officedocument.wordprocessingml.document \
  -i ../novas_normas_20260420/manual_20260421/decreto_12002_2024.docx \
  -o ../novas_normas_20260420/teste_decreto/decreto_12002_2024.xml \
  --write-errors-to-file ../novas_normas_20260420/teste_decreto/decreto_12002_2024.err.log \
  -a federal -t decreto -n 12002 --data 2024-04-22 --linker /usr/local/bin/linkertool
```

The user's hypothesis (semantic "identification texts" / bold sidenote headings
sprinkled between articles) pointed to the right area of the document, but the
actual trigger is narrower: **inline `<i>`/`<b>` formatting runs that overlap a
citation the linker recognizes**.

## Root cause

The crash is a `scala.xml.parsing.FatalError: expected closing tag of i`, raised
at **`LinkerActor.scala:83`**:

```scala
val r = XhtmlParser(Source.fromString("<result>" + res + "</result>")).head.asInstanceOf[Elem]
```

Here `res` is the **stdout of the external `linkertool`** (the Haskell binary),
re-parsed back into Scala XML. The linker is emitting **unbalanced markup**.

### Why the linker emits unbalanced markup

The DOCX contains citations whose `<i>`/`<b>` runs do not align with word
boundaries — manual-formatting artifacts faithfully carried through DOCX→XHTML
into the `Paragraph` blocks. Two confirmed cases from this document:

| Source fragment (well-formed Scala XML) | Where |
|---|---|
| `art. 3º, <i>capu</i>t, inciso III, da Lei nº 14.129, de 29 de março de 2021` | item 17.7 (italic covers only "capu", not the final "t") |
| `art. 84, <i>caput</i><b>,</b> inciso VIII, da Constituição` | item, art. 150 etc. (the comma after `caput` is bold) |

These are *balanced* as input. But the linker recognizes the surrounding text as
a single reference span and re-tokenizes it, attaching/dropping the inline tags.
Reproduced directly against `linkertool --hxml --xml --contexto=INLINE`:

Input:
```
<p>por meio eletrônico (art. 3º, <i>capu</i>t, inciso III, da Lei nº 14.129, de 29 de março de 2021)?</p>
```
Output (note the **opening `<i>` with no matching `</i>`**):
```
<p>por meio eletrônico (art. 3º, <i><span xlink:href="...lei...14129!cpt">t</span>, i<span xlink:href="...!cpt_inc3">nciso III, da Lei nº 14.129, de 29 de março de 2021)</span>?</p>
```
The output contains exactly **one `<i>` and zero `</i>`** → `XhtmlParser` rejects
it with `expected closing tag of i`.

**Control:** removing the `<i>` from the input makes the linker produce
perfectly balanced, correctly-linked output — confirming the inline tag is the
sole trigger:
```
<p>por meio eletrônico (art. 3º, <span xlink:href="...!cpt">caput</span>, <span xlink:href="...!cpt_inc3">inciso III, da Lei nº 14.129, ...</span>)?</p>
```

### Why one bad fragment kills the entire document

`LinkerActor.receive` does no error handling. When `XhtmlParser` throws inside
the actor:

1. The actor's `receive` aborts **without replying** to `sender()`.
2. The pool supervisor (`SmallestMailboxPool` + `OneForOneStrategy`, `Linker.scala:23`)
   restarts/escalates the actor, but the pending `ask` (`?`) future is never
   completed.
3. `findLinks` (`Linker.scala:96`) is blocked in `Await.result(f, 30.seconds)`
   and throws `TimeoutException` after the configured
   `linker.timeoutSeconds` (default 30).
4. That propagates up through `reconheceLinks` (`ProjetoLei.scala:680-695`) →
   `ProjetoLei.fromBlocks` → mapped to "Erro de sistema". **The whole parse
   fails**; nothing is written.

Stack trace (abridged) from the run:
```
scala.xml.parsing.FatalError: expected closing tag of i
  at scala.xml.parsing.XhtmlParser$.apply(XhtmlParser.scala:32)
  at br.gov.lexml.parser.pl.linker.LinkerActor$$anonfun$receive$1.applyOrElse(LinkerActor.scala:83)
  ...pekko ActorCell/Mailbox...
[INFO] CoordinatedShutdown ... (exactly 30s after the FatalError)
```

## Components involved

- `src/main/scala/br/gov/lexml/parser/pl/linker/LinkerActor.scala` — sends the
  Paragraph nodes to `linkertool`, re-parses its stdout at line 83 (the crash
  site). No try/catch around the parse.
- `src/main/scala/br/gov/lexml/parser/pl/linker/Linker.scala` — `findLinks`
  (blocking `Await`), the actor pool, supervision strategy, and the existing
  `rewriteTextNodes`/`preprocess` text-normalisation helpers (lines 60-100). The
  preprocessing only rewrites `Text` nodes; it does not touch element structure.
- `src/main/scala/br/gov/lexml/parser/pl/ProjetoLei.scala:680` — `reconheceLinks`,
  the caller that has no fallback if `findLinks` throws.
- External `linkertool` (Haskell, separate repo) — the actual source of the
  unbalanced markup. Out of scope to rebuild from this Scala repo.

## Fix strategy

The external linker can't be fixed from here, so the fix lives in this repo. Two
complementary layers; recommend implementing **both** (defensive is mandatory,
preventive removes the trigger and is cheap):

### A. Defensive (mandatory) — make `LinkerActor` failure-tolerant

Wrap the linker round-trip (write + read + `XhtmlParser`) in a `try`/`catch`.
On any failure (`FatalError`, `LinkerActorException`, IO, etc.), **reply to the
sender with the original input nodes and an empty link set** instead of letting
the actor die:

```scala
sender() ! ((msg, Set.empty[String]))   // fallback: unlinked, but document survives
```

This guarantees the `ask` future always completes, so `findLinks` never times
out and a single un-parseable fragment degrades to "no links for this
fragment" instead of "whole document fails". This mirrors the existing
`case None => sender() ! ((msg, Set()))` branch already present for the
"no linker process" case.

### B. Preventive (recommended) — neutralise the trigger before sending

Since the linker only misbehaves when inline `<i>`/`<b>` runs overlap a
recognized citation, strip inline presentational tags from the nodes sent to
the linker. Extend the existing normalisation in `Linker.findLinks` (alongside
`rewriteTextNodes`/`preprocess`) to **flatten `<i>`/`<b>` (and similar
presentational) elements to their text content** before building the linker
message. Confirmed above that this yields correct, balanced, correctly-linked
output. Trade-off: italic/bold styling inside linked text is dropped in the
LexML output — acceptable for these citation contexts and consistent with how
LexML treats reference text. (If styling must be preserved elsewhere, scope the
flattening to nodes being sent to the linker only, not the rendered output.)

### Notes / decisions to confirm with the user
- Whether to keep inline styling anywhere (B drops `<i>`/`<b>` within linked
  paragraphs). If styling matters, do A only and accept that those specific
  fragments come back unlinked.
- This decreto is a **decreto-profile regression candidate** going forward
  (federal, has these inline-format citations). Per
  [[feedback_decreto_regression_targets]], keep using genuine decreto inputs.

## Verification

1. `mvn compile -nsu` then `mvn -Ponejar package`.
2. Re-run the exact command above; expect **exit 0**, an `.xml` written to
   `../novas_normas_20260420/teste_decreto/decreto_12002_2024.xml`, and **no**
   `Erro de sistema` / `Future timed out` in the `.err.log`.
3. Grep the produced XML for the affected citations (art. 3º / Lei 14.129,
   art. 84 / Constituição, art. 150) to confirm they are present and (with fix B)
   correctly linked.
4. Regression: re-run the Decreto sample from `CLAUDE.md`
   (`decreto_2338_1997.docx`) and confirm output is unchanged.
5. Add a focused unit test feeding a node sequence with `<i>capu</i>t`-style
   overlap through `LinkerActor`/`findLinks`, asserting it returns (not throws).

## Implementation & outcome (2026-05-25)

Both changes from the fix strategy were implemented:

- **`LinkerActor.scala`** — the `Some(p)` branch of `receive` now wraps the
  whole linker round-trip (write/read/`XhtmlParser`/span extraction) in a
  `try`/`catch (ex: Throwable)`. On failure it logs a warning and replies
  `sender() ! ((msg, Set()))` (original nodes, no links), mirroring the existing
  `case None` fallback. The actor never dies → the `ask` future always completes
  → no 30s timeout.
- **`Linker.scala`** — added `inlineFormatTags = Set("i","b","em","strong")` and
  `flattenInlineFormat(ns)` (recursive, modelled on `rewriteTextNodes`), wired
  into `findLinks` as
  `val nsRewritten = flattenInlineFormat(rewriteTextNodes(ns, preprocess))`.
  Inline format tags are flattened to their text before the nodes reach the
  linker, so the unbalanced-output trigger never fires and the citations are
  still recognised.

Verification results:
- `mvn compile -nsu` and `mvn -Ponejar package -DskipTests` → success.
- Re-ran the failing command → **exit 0**, 165 KB XML written, `xmllint`
  reports valid XML, error log contains **0** "Erro de sistema"/"timed
  out"/"FatalError"; only non-fatal técnica-legislativa warnings remain.
  78 articles + `<Anexos>` produced. Links present for the formerly-crashing
  citations (e.g. `...constituicao...!cpt_inc8`, `!cpt_inc4`, `art150_par1`).
- Regression `decreto_2338_1997.docx` → exit 0, valid XML, no fatal errors,
  unchanged (3 articles; 0 links as expected — no external citations).

Notes:
- The remaining single balanced `<i>caput</i>` in the **preâmbulo** of the
  output is harmless and expected — the preâmbulo isn't routed through
  `findLinks`, and the tag is well-formed.
- No unit test added: the repo has no `src/test` tree or ScalaTest dependency
  (`mvn test` runs nothing). User opted to skip rather than introduce a test
  scaffold as part of this fix.
