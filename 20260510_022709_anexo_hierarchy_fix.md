# Plan: Parse hierarchical LexML elements inside Annex (Anexo) outputs

## Context

The parser today emits annex (`Anexo`) sibling documents with all body content
flattened into `<p>` elements, even when the annex itself contains a fully
articulated legal hierarchy (Capítulo → Seção → Subseção → Artigo → Caput / §
/ Inciso / Alínea / Item).

Concrete evidence — parsing
`../novas_normas_20260420/manual_20260421/res_anpd_4_2022.docx`:

- The primary output `res_anpd_4_2022.xml` correctly produces an `<Articulacao>`
  with `<Artigo>`, `<Caput>`, `<Alteracao>` ... for the body.
- The annex sibling `res_anpd_4_2022.anexo1.xml` instead produces:
  ```xml
  <Anexo>
    <DocumentoGenerico>
      <PartePrincipal id="anexo1_pp">
        <p><b>CAPÍTULO I</b></p>
        <p><b>DISPOSIÇÕES GERAIS</b></p>
        <p><b>Art. 1º Este Regulamento ...</b></p>
        ...
  ```
  Every Capítulo, Seção, Subseção, Artigo, Parágrafo, Inciso and Alínea is a flat
  `<p>` instead of a structured LexML element.

The same flattening is visible in `decreto_2338_1997.anexo1.xml`,
`decreto_2338_1997.anexo2.xml`, `decreto_7724_2012.anexo*.xml`, etc., so the
defect affects the whole annex pipeline, not just resolutions.

### Schema check (`lexml/lexml-base.xsd`)

There is **no** schema restriction forbidding hierarchy inside an annex. The
schema (lexml-base.xsd:542-549) defines:

```xsd
<xsd:element name="Anexo">
  <xsd:complexType>
    <xsd:choice>
      <xsd:element name="DocumentoArticulado" type="HierarchicalStructure"/>
      <xsd:element ref="DocumentoGenerico"/>
    </xsd:choice>
  </xsd:complexType>
</xsd:element>
```

`HierarchicalStructure` (lexml-base.xsd:499-507) is exactly the same model used
by `Norma` and contains `ParteInicial?, Articulacao, ParteFinal?, Anexos?`. So
the annex of a norma may itself be a fully articulated document with its own
`<Articulacao>` containing `<Capitulo>`, `<Secao>`, `<Artigo>`…

### Why the parser flattens

`buildAnexos` (`ProjetoLei.scala:639-655`) takes the raw block list collected
under each `Anexo` marker and stores it as-is (only trimming empty paragraphs
and running the linker over Paragraphs). It never calls `parseArticulacao`, so
no `Dispositivo` is ever produced.

`renderAnexoBlocks` (`LexmlRenderer.scala:387-403`) is *already* prepared to
render Dispositivos when present (`partition { case _: Dispositivo => true }`)
— but the partition always yields `dispositivos == Nil` because of the gap
above. The renderer also unconditionally wraps the annex in
`<Anexo><DocumentoGenerico><PartePrincipal>` (`LexmlRenderer.scala:407-424`),
which is the right container for purely textual annexes but is *not* the right
schema container for an articulated annex (PartePrincipal accepts
`AgrupamentoHierarquico`, but not `<Capitulo>`/`<Secao>`/`<Artigo>` directly —
those live under `Articulacao`).

## Recommended approach

Two coordinated changes:

1. **Articulate the annex body** in `buildAnexos` by routing the body blocks
   through the same `parseArticulacao` pipeline used for the primary norma.
2. **Switch the annex container dynamically** in `renderAnexoDoc`: emit
   `<Anexo><DocumentoArticulado>…<Articulacao>…</Articulacao></DocumentoArticulado></Anexo>`
   when the parsed body contains any `Dispositivo`; otherwise keep the current
   `<DocumentoGenerico><PartePrincipal>` shape for plain-text annexes.

This preserves backwards-compatible output for non-articulated annexes (e.g.
purely tabular ones such as `decreto_2338_1997.anexo2.xml`) while fixing the
flattening for articulated ones (e.g. `res_anpd_4_2022.anexo1.xml`,
`decreto_2338_1997.anexo1.xml`).

## Files to modify

### 1. `src/main/scala/br/gov/lexml/parser/pl/ProjetoLei.scala`

- **`buildAnexos`** (lines 639-655) — after the existing
  `(titulo, body0)` split, run the body through the articulation pipeline
  before storing it in the `Anexo` instance. Reuse the existing
  `parseArticulacao` (line 293) which already does:
  NFC normalization → trimParagraphs → reconheceAlteracoes →
  reconheceDispositivos → reconheceOmissis → identificaTextosAgregadores →
  identificaTitulos → organizaDispositivos → numeraDispositivosGenericos →
  corrigeRotuloParte/Livro → pushLastOmissis → numeraAlteracoes →
  identificaPaths → numeraDispsGenericos → reconheceLinks →
  Linker.paraCadaAlteracao.

  Sketch (annotated, only the changed return path):

  ```scala
  def buildAnexos(buckets: List[List[Block]], urnContexto: String): List[Anexo] = {
    val anexoHeadRe = "(?i)^anexo\\b".r
    buckets.zipWithIndex.flatMap { case (raw, idx) =>
      val trimmed = trimEmptyPars(raw)
      if (trimmed.isEmpty) None
      else {
        val (titulo, body0) = trimmed match {
          case (p: Paragraph) :: tail
              if anexoHeadRe.findFirstIn(normalizer.normalize(p.text.trim.toLowerCase)).isDefined =>
            (Some(p), tail)
          case _ => (None, trimmed)
        }
        val bodyTrimmed = trimEmptyPars(body0)
        // Run the same articulation pipeline used for the primary norma so
        // hierarchical legal structures (Capitulo, Secao, Artigo, ...) are
        // recognized inside the annex too. Falls back gracefully: if no
        // dispositivo rotulos match, parseArticulacao returns the input as
        // Paragraphs/Tables.
        val body = parseArticulacao(bodyTrimmed, urnContexto = urnContexto)
        Some(Anexo(num = idx + 1, titulo = titulo, blocks = body, implicito = titulo.isEmpty))
      }
    }
  }
  ```

  Notes:
  - `parseArticulacao` already calls `reconheceLinks` internally (line 344);
    drop the explicit `reconheceLinks` map that was applied on the raw body
    (line 651) to avoid double-linking.
  - `parseArticulacao` is defined on `ProjetoLeiParser` (instance method, has
    access to `profile`). `buildAnexos` is currently in
    `object ProjetoLeiParser` — confirm during implementation that it's
    callable from the same enclosing class; if it isn't, move `buildAnexos`
    onto the class or thread `profile` through it.

### 2. `src/main/scala/br/gov/lexml/parser/pl/output/LexmlRenderer.scala`

- **`renderAnexoBlocks`** (lines 387-403) — keep the partitioning logic but
  return a structured pair `(simplesNodes, dispNodes)` so the caller can place
  them into the right container. Concretely, refactor it (or add a sibling
  helper) to expose the two pieces separately rather than concatenating them.

- **`renderAnexoDoc`** (lines 407-424) — branch on whether the annex contains
  any `Dispositivo`:

  ```scala
  def renderAnexoDoc(pl: ProjetoLei, a: Anexo): Elem = {
    val tituloP: NodeSeq = a.titulo match {
      case Some(p) => <p>{ NodeSeq fromSeq p.nodes }</p>
      case None => NodeSeq.Empty
    }
    val hasDispositivos = a.blocks.exists(_.isInstanceOf[Dispositivo])
    val body =
      if (hasDispositivos) {
        // Articulated annex — emit DocumentoArticulado/Articulacao so
        // Capitulo/Secao/Artigo render natively. Non-dispositivo blocks
        // (intro paragraphs, tables before the first article) are placed
        // in a ParteInicial-like preface; if there are none, we just emit
        // <Articulacao>.
        val dispositivos = a.blocks.collect { case d: Dispositivo => d }
        <Anexo>
          <DocumentoArticulado>
            { /* optional ParteInicial with titulo paragraph if present */ }
            <Articulacao>{ renderBlocks(dispositivos, a.urnFragment + "_") }</Articulacao>
          </DocumentoArticulado>
        </Anexo>
      } else {
        <Anexo>
          <DocumentoGenerico>
            <PartePrincipal id={ a.urnFragment + "_pp" }>
              { tituloP }
              { renderAnexoBlocks(a) }
            </PartePrincipal>
          </DocumentoGenerico>
        </Anexo>
      }
    <LexML xmlns="http://www.lexml.gov.br/1.0" xmlns:xlink="http://www.w3.org/1999/xlink"
           xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
           xsi:schemaLocation="http://www.lexml.gov.br/1.0 ../xsd/lexml-br-rigido.xsd">
      { renderMetadadoAnexo(pl.metadado, a) }
      { body }
    </LexML>
  }
  ```

  Notes on the articulated branch:
  - `HierarchicalStructure` allows an optional `ParteInicial` (Epigrafe /
    Ementa / Preambulo). The `titulo` paragraph (e.g. "ANEXO I") plus any
    leading non-Dispositivo blocks (chapter sub-headings already become
    aggregator Dispositivos via `identificaTextosAgregadores`, so this is
    rare) can either be wrapped as an `Ementa`/`Epigrafe` or skipped — start
    by skipping (titulo is already preserved on the `Anexo` instance and
    surfaced by callers); add `ParteInicial` later if a regression test
    reveals the titulo is needed in the XML.
  - Tables/OL that sit before the first dispositivo will currently be
    *dropped* from the articulated branch since they are not Dispositivos.
    The existing `parseArticulacao` flow attaches trailing tables to the
    last article via `spanNivel`; verify this still happens for annexes
    (the same `pushLastOmissis` / Articulação logic should kick in). If
    leading tables are observed in the wild, fall back to
    `DocumentoGenerico` for that annex.

## Edge cases / things to watch

1. **Profile reuse.** `parseArticulacao` consumes the same `DocumentProfile`
   used for the primary norma. That is the right behavior: a Decreto's annex
   uses the Decreto rótulo regexes. If a profile relaxes article rules, those
   relaxations apply equally to the annex.
2. **Non-articulated annexes** (purely tabular, image-only, or short text
   appendices) must keep emitting `DocumentoGenerico` to avoid validation
   errors against `HierarchicalStructure` (which requires `Articulacao`).
   The runtime check `a.blocks.exists(_.isInstanceOf[Dispositivo])` covers
   this.
3. **`PartePrincipal` already in callers.** No external code paths construct
   `<PartePrincipal>`-shaped DOM by hand for annexes; `renderAnexoDoc` is the
   single producer (`renderAll` is the only caller).
4. **Implicit annexes.** `implicitAnexoPromotion`
   (`ProjetoLei.scala:585-602`) builds annex buckets from post-signature
   tail content. Once `buildAnexos` runs `parseArticulacao`, those implicit
   annexes get the same hierarchy detection — no extra change needed.
5. **Linker double-pass.** `parseArticulacao` runs `reconheceLinks` and
   `Linker.paraCadaAlteracao`; remove the explicit `reconheceLinks` map at
   `ProjetoLei.scala:651` to avoid linking annex paragraphs twice.

## Verification

End-to-end check using the documented Decreto and Resolução invocations from
`CLAUDE.md`. Run from the repo root with
`LC_ALL=C.UTF-8 LANG=C.UTF-8`.

1. **Rebuild.**
   ```bash
   mvn -Ponejar package
   ```

2. **Resolução ANPD 4/2022 (the smoking-gun case).**
   ```bash
   LC_ALL=C.UTF-8 LANG=C.UTF-8 java -jar target/lexml-parser-projeto-lei-1.15.0-onejar.jar parse \
      -m application/vnd.openxmlformats-officedocument.wordprocessingml.document \
      -i ../novas_normas_20260420/manual_20260421/res_anpd_4_2022.docx \
      -o /tmp/res_anpd_4_2022.xml \
      --write-errors-to-file /tmp/res_anpd_4_2022.err.log \
      -t resolucao \
      --prof-regex-epigrafe '^resolucao' \
      --prof-regex-epigrafe-continuacao '^resolucao%^n[oº°˚]' \
      --prof-regex-pos-epigrafe '^publicado:%^left\d%^acessos:' \
      --prof-epigrafe-head 'RESOLUÇÃO' \
      --linker /usr/local/bin/linkertool
   diff <(grep -c '<Capitulo' /tmp/res_anpd_4_2022.anexo1.xml) <(echo 12)  # expect non-zero matches
   grep -c '<Artigo'   /tmp/res_anpd_4_2022.anexo1.xml   # > 0
   grep -c '<Secao'    /tmp/res_anpd_4_2022.anexo1.xml   # > 0
   grep -c '<Inciso'   /tmp/res_anpd_4_2022.anexo1.xml   # > 0
   ```

3. **Decreto 2338/1997 (regulation in annex 1, table-only annex 2).**
   ```bash
   LC_ALL=C.UTF-8 LANG=C.UTF-8 java -jar target/lexml-parser-projeto-lei-1.15.0-onejar.jar parse \
      -m application/vnd.openxmlformats-officedocument.wordprocessingml.document \
      -i ../novas_normas_20260420/manual_20260421/decreto_2338_1997.docx \
      -o /tmp/decreto_2338_1997.xml \
      --write-errors-to-file /tmp/decreto_2338_1997.err.log \
      -t decreto -a federal -n 2338 --data 1997-10-07 \
      --linker /usr/local/bin/linkertool
   ```
   Expected:
   - `decreto_2338_1997.anexo1.xml` now contains `<DocumentoArticulado>`,
     `<Articulacao>`, `<Capitulo>`, `<Secao>`, `<Artigo>`, `<Caput>`,
     `<Paragrafo>`, `<Inciso>`.
   - `decreto_2338_1997.anexo2.xml` (the cargo/positions table) keeps the
     existing `<DocumentoGenerico><PartePrincipal>` shape since it has no
     dispositivos — only a heading paragraph and a table.

4. **XSD validation.** Both annex outputs (the articulated and the textual
   one) must validate against the bundled `lexml-br-rigido.xsd`. The existing
   pipeline already runs validation via
   `FECmdLine.renderAndValidaXML` (`FECmdLine.scala:536-545`); any failure
   appears in the corresponding `.err.log`. Confirm both `.err.log` files
   are empty (no new validation errors introduced).

5. **Regression sweep.** Re-run the manual_20260421 corpus (the same one
   used to build `lexml_manual_20260509/`) and diff:
   ```bash
   for d in ../novas_normas_20260420/manual_20260421/*.docx; do
     # parse with the appropriate -t / overrides for each family
     ...
   done
   ```
   Compare the produced primary-norma XML files against
   `lexml_manual_20260509/*.xml` — they must be byte-identical (the change
   only touches the annex pipeline). Annex files are expected to differ.
