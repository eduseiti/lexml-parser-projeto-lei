# Plan — Unwrap leading layout tables so the ementa is recognized

## Context

**The initial hypothesis was that this is a regex-configuration problem; it isn't.** Parsing `decreto_2338_1997.docx` (Casa Civil layout) fails with problem code 12 (`EmentaAusente` — "Ementa ausente ou fora do padrão"), even though the document does contain an ementa. We confirmed:

1. The CLI exposes regex overrides for `preambulo`, `epigrafe`, `epigrafe-continuacao`, `pos-epigrafe`, `assinatura`, `anexos`, `legislacao-citada`, `local-data`, `justificativa` (see `FECmdLine.scala`). **None of them control ementa detection.**
2. There is no `regexEmenta` field on `RegexProfile` and no `--prof-ementa-ausente` flag. Ementa is detected *implicitly* as whatever sits between the epigrafe and the preambulo.
3. The DOCX places the ementa text ("Aprova o Regulamento da Agência Nacional de Telecomunicações e dá outras providências") inside a `<w:tbl>` — a layout artifact from the original Casa Civil HTML scrape, used to position the text to the right of an image. The DOCX reader faithfully turns it into a `Table` block.
4. The validator at `ProjetoLei.scala:363-372` rejects the ementa slot if any block in it is not a `Paragraph` (`ementa2.exists(!isParagraph(_))`). The `Table` block trips this check → `EmentaAusente` is thrown.

The fix needs to **flatten layout tables that appear before the articulacao** into paragraphs, while leaving content tables inside the articulacao untouched (recent commits — `7c9a9a4`, `d8f0108` — were specifically about preserving those). The "before articulacao" boundary is already computed by `reconhecePreambulo` via the `isArticulacao` predicate, so position-based scoping is precise — no content-based heuristic is needed and no false positive on real data tables is possible.

Beyond this one document, the same Casa Civil layout (image + ementa table) is used across many older federal decretos and laws on planalto.gov.br, so the fix is expected to unblock multiple inputs in `../novas_normas_20260420/manual_20260421/`.

## Approach

Add a small preprocessing pass inside `ProjetoLei.fromBlocks` (between block intake and the existing `spanEpigrafe` / `reconhecePreambulo` calls) that walks the head of the block list, replacing `Table` blocks with the `Paragraph` blocks extracted from their cells, and stops at the first block that looks like the start of the articulacao (i.e. its rotulo nivel is `<= niveis.nivel_maximo_aceito_na_raiz`).

Why inside `fromBlocks` and not in the XHTML/DOCX reader: the `isArticulacao` predicate (which defines the stop boundary) lives in `ProjetoLei.scala` and depends on `rotuloParser` + `niveis`. Doing it earlier would either duplicate that logic or scope incorrectly.

## Critical files

- `src/main/scala/br/gov/lexml/parser/pl/ProjetoLei.scala` — add the unwrap step at the top of `fromBlocks` (currently line 348) and reuse the `isArticulacao` predicate currently defined inside `reconhecePreambulo` (lines 260-266). Lift that predicate to a private method on the enclosing class so both call sites can use it.
- `src/main/scala/br/gov/lexml/parser/pl/block/Block.scala` — `Table.elem` (line 267) is a raw `scala.xml.Elem` of the XHTML `<table>` produced by `DOCXReader.convertTable`. Cells contain `<p>` elements; reuse `Block.fromNodes` (line ~340) on each cell's children to recover proper `Paragraph` blocks rather than re-implementing text extraction.

## Implementation sketch

```scala
// In ProjetoLei (the class that owns fromBlocks)

private val isArticulacaoStart: Block => Boolean = {
  case p: Paragraph => rotuloParser.parseRotulo(p.text) match {
    case Some((rotulo, _)) => rotulo.nivel <= niveis.nivel_maximo_aceito_na_raiz
    case None => false
  }
  case _ => false
}

/** Flattens layout tables (image-positioning, ementa-wrapping) that appear
 *  before the articulacao starts. Tables inside the articulacao are
 *  preserved unchanged — see commits 7c9a9a4 / d8f0108. */
private def unwrapLeadingLayoutTables(blocks: List[Block]): List[Block] = {
  val (head, tail) = blocks.span(b => !isArticulacaoStart(b))
  val flattenedHead = head.flatMap {
    case Table(elem) => Block.fromNodes(elem \ "tr" \ "td" flatMap (_.child))
    case b => b
  }
  flattenedHead ++ tail
}
```

Then in `fromBlocks` (line 348), the first line becomes:
```scala
def fromBlocks(metadado: Metadado, blocks: List[Block]): (Option[ProjetoLei], List[ParseProblem]) = {
  val unwrapped = unwrapLeadingLayoutTables(blocks)
  try {
    val (preEpigrafe, epigrafe, posEpigrafe) = {
      if (profile.regexEpigrafe.isEmpty) {
        (List(), Paragraph(List()), unwrapped)
      } else {
        spanEpigrafe(unwrapped) match {
          ...
```

And replace the duplicated predicate inside `reconhecePreambulo` (lines 260-266) with a call to `isArticulacaoStart`.

### Notes on cell traversal

`Table.elem` is the XHTML produced by `DOCXReader.convertTable`. Cells appear as `<td>` (or `<th>`) under `<tr>`. Rather than invent a new traversal, pass each cell's children through the existing `Block.fromNodes` (in `Block.scala`, around line 340), which already knows how to turn `<p>`/`<blockquote>` into `Paragraph` and how to handle nested elements. This keeps the unwrap consistent with how the rest of the pipeline interprets XHTML.

For the Decreto 2338 input the unwrap will produce, in order: the header table's text ("Presidência da República / Casa Civil / Subchefia para Assuntos Jurídicos") as paragraphs (kept harmless because Decreto has `preEpigrafePermitida = true`), then the existing epigrafe paragraph, then the ementa text as a `Paragraph` (which now satisfies the `isParagraph` check at line 366), then the existing preambulo paragraphs.

## What this plan deliberately does *not* do

- Add `regexEmenta` or `--prof-ementa-ausente`. The original instinct was a regex tweak, but ementa is implicit, and setting `ementaAusente=true` would make the ementa text vanish from the output rather than be recognized.
- Touch `LexmlRenderer` / `HtmlRenderer`. Once the leading tables are unwrapped, the existing renderers receive a normal `Paragraph` ementa and need no changes.
- Modify behavior for tables inside the articulacao. The position scoping (`span(!isArticulacaoStart)`) guarantees this.

## Verification

1. **Reproduce the failure** before any code change:
   ```
   java -jar target/lexml-parser-projeto-lei-1.15.0-onejar.jar parse \
     -m application/vnd.openxmlformats-officedocument.wordprocessingml.document \
     -i ../novas_normas_20260420/manual_20260421/decreto_2338_1997.docx \
     -o /tmp/decreto_2338_1997.xml \
     --write-errors-to-file /tmp/decreto_2338_1997.err.log \
     -a federal -t decreto -n 2338 --data 1997-10-07 --linker /usr/local/bin/linkertool
   ```
   Confirm the err log contains `Problem type = [12: Ementa ausente`.

2. **Build** with `mvn -Ponejar package` and rerun the same command. Expected: no error log entry; the output XML contains an `<Ementa>` element with text "Aprova o Regulamento da Agência Nacional de Telecomunicações e dá outras providências".

3. **Regression check on documents that exercise articulacao tables**: pick at least one input known to contain real tables inside dispositivos (the recent table-handling commits suggest `lei9250_test.xml` / `lei_13105_20150316_1.docx` from the repo root or analogous DOCX samples in `../novas_normas_20260420/test_docs/` are good candidates) and confirm the resulting XML still contains `<table>` markup inside `<Articulacao>`.

4. **Unit test (recommended addition)**: in the existing test tree under `src/test/scala`, add a test that constructs a `List[Block]` of the form `List(Table(...layout...), Paragraph(epigrafe), Table(...ementa...), Paragraph(preambulo), Paragraph(art1))` and asserts that `unwrapLeadingLayoutTables` produces a list whose ementa-slot block is a `Paragraph` and whose articulacao-slot block (`Paragraph(art1)`) is unchanged. This locks in the position-scoping contract.

5. **Broader sweep (optional)**: run the parser over the rest of `../novas_normas_20260420/manual_20260421/*.docx` with appropriate `-t`/`-n`/`--data` flags and check whether any other documents that previously failed with code 12 now succeed.
