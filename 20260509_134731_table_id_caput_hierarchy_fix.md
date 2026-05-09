# Fix table IDs to respect LexML article hierarchy

## Context

When the parser produces LexML XML for a `.docx` containing a table inside an article's caput, the table is rendered as a sibling of `<Caput>` (after `</Caput>`) with id `art3_tab1`. Per the LexML hierarchy, that table belongs INSIDE the caput and should have id `art3_cpt_tab1`.

Sample (current, buggy) output for `lei-9250-26-dezembro-1995-362566-normaatualizada-pl.docx`:
```xml
<Artigo id="art3">
  <Rotulo>Art. 3º</Rotulo>
  <Caput id="art3_cpt">
    <p>… caput text …</p>
  </Caput><table id="art3_tab1">…</table><Paragrafo id="art3_par1u">…</Paragrafo>
</Artigo>
```

Desired output:
```xml
<Artigo id="art3">
  <Rotulo>Art. 3º</Rotulo>
  <Caput id="art3_cpt">
    <p>… caput text …</p>
    <table id="art3_cpt_tab1">…</table>
  </Caput>
  <Paragrafo id="art3_par1u">…</Paragrafo>
</Artigo>
```

The same bug affects two more tables in the same document (`art3-1_tab1`, `art11-1_tab1` — all sit between `</Caput>` and `<Paragrafo>` of their article).

The user's constraint: the fix must respect LexML hierarchy WITHIN articles (caput, paragrafo, inciso, alinea, item) and **must not change behavior for anything outside the article — especially Anexo**, which already has a working independent fix.

## Root cause

`Block.spanNivel` (Block.scala:505-535) decides which following blocks become a Dispositivo's `subDispositivos`. The current Table branch only absorbs trailing tables at `nivel == niveis.artigo`. At sub-article levels (paragrafo/Caput, inciso, alinea, item) it returns `(Nil, l)` — leaving the table for an outer level to absorb. Result: tables that semantically belong to a Caput end up in the parent Article's `subDispositivos` instead of the Caput's, and the renderer prefixes them with the Article's id (`artN_`) instead of the Caput's id (`artN_cpt_`).

Trace for `[Art3, Caput, Table, Par_unico]` (`organizaDispositivos` foldRight + `spanNivel`):
1. Caput's `spanNivel(120, [Table, Par_unico])` reaches the Table case, recurses on `[Par_unico]`. Inner returns `(Nil, [Par_unico])` (Par_unico is nivel 120, not `> 120`). Outer falls to `else (Nil, l)` because `120 != niveis.artigo`. Caput does NOT absorb the Table.
2. Article's `spanNivel(110, [Caput, Table, Par_unico])` — Par_unico is nivel 120 > 110, so the inner Table case sees `l1=[Par_unico]` and returns `(omissis ++ List(t) ++ l1, l2)` (line 524). Article absorbs Caput, Table, AND Par_unico into `subDispositivos`.
3. Renderer produces `art3_tab1` because `idPai = "art3_"` when iterating Article's `subDispositivos` (LexmlRenderer.scala:276).

## Fix

Single-line change in `Block.spanNivel` (Block.scala:521-526): extend trailing-table absorption from "only at artigo level" to "at any sub-article level (nivel > artigo)". This makes a Caput / Paragrafo / Inciso / Alinea / Item that is followed by a trailing Table (with no further same-or-higher dispositivo following at that level) absorb the Table into its `subDispositivos`. The renderer then produces the Table inside the owning Dispositivo's element with the correctly-prefixed id.

Before (Block.scala:521-526):
```scala
afterSuperior match {
  case (t: Table) :: restAfterTable =>
    val (l1, l2) = proxSpan(restAfterTable)
    if (l1.nonEmpty) (omissis ++ List(t) ++ l1, l2)
    else if (nivel == niveis.artigo) (omissis ++ List(t), restAfterTable)
    else (Nil, l)
  case _ => …
```

After:
```scala
afterSuperior match {
  case (t: Table) :: restAfterTable =>
    val (l1, l2) = proxSpan(restAfterTable)
    if (l1.nonEmpty) (omissis ++ List(t) ++ l1, l2)
    else if (nivel >= niveis.artigo) (omissis ++ List(t), restAfterTable)
    else (Nil, l)
  case _ => …
```

`niveis.artigo = 110`, `niveis.paragrafo = 120`, `niveis.inciso = 130`, `niveis.alinea = 140`, `niveis.item = 150`, `niveis.pena = 160`. Aggregators (parte=10, livro=20, titulo=30, capitulo=50, secao=70, subsecao=80) are all `< niveis.artigo`. The `>= niveis.artigo` test thus covers exactly the LexML hierarchy WITHIN an article and keeps the supra-article aggregator behavior unchanged.

Also update the comment block at Block.scala:516-520 to reflect the new behavior:
```scala
// When no immediately-superior dispositivos are found, check if a Table
// follows. If something at the parent's level still follows the table,
// the table is included in the span (line 524) so it lands inside the
// owning Dispositivo. If the table is trailing (nothing follows), absorb
// it at any nivel within the article hierarchy (artigo and below) so it
// becomes a child of the current Dispositivo and the renderer produces
// the correctly-prefixed id (e.g. art3_cpt_tab1 inside <Caput>).
// Aggregator levels (capitulo, secao, …) are unchanged: they still
// return (Nil, l) so tables at those levels stay where they were.
```

## Why Anexo is unaffected

Anexo blocks go through a separate pipeline:
- `ProjetoLei.parse` (ProjetoLei.scala:402) calls `buildAnexos(elementos.anexos, urnContexto)`. Anexo blocks never enter `parseArticulacao` / `organizaDispositivos` / `spanNivel`.
- The output renderer routes Anexos through `LexmlRenderer.renderAnexoBlocks` (separate path), which has its own table-id generation independent of `spanNivel`.

The `trailingTables` salvage (`ProjetoLei.scala:450-458`) explicitly excludes Anexo (only collects tables from `LocalData`/`Justificacao`/`Legislacao`/`Assinatura` buckets — Anexo tables stay in their bucket per the existing comment).

## Side effect to confirm: salvaged trailing tables

Tables salvaged from tail markers (LocalData/Justificacao/Legislacao/Assinatura) are appended to `articulacao1` at ProjetoLei.scala:389. Currently they end up inside the last article with id `artN_tabM`. With the fix, they end up inside the last sub-dispositivo of the last article (typically the caput) with id `artN_cpt_tabM`. This is consistent with the new "tables respect article hierarchy" behavior. The user said "do not modify behavior for anything outside the article, especially Anexo" — Anexo is unchanged; salvaged-into-article tables now respect article hierarchy too. If the user prefers salvaged tables to keep article-level ids (`artN_tabM`), an additional guard is needed in `trailingTables`/`parseArticulacao` (a separate, smaller follow-up; not required for the user-reported bug).

## Critical files

- `src/main/scala/br/gov/lexml/parser/pl/block/Block.scala` — single-line change at 525, comment update at 516-520. Only file edited.

## Verification

1. Build: `mvn -Ponejar package` (no automated tests in repo).
2. Re-run the user's command:
   ```
   java -jar target/lexml-parser-projeto-lei-1.15.0-onejar.jar parse -v -t "lei" \
     -m application/vnd.openxmlformats-officedocument.wordprocessingml.document \
     -i lei-9250-26-dezembro-1995-362566-normaatualizada-pl.docx \
     -o lei-9250-26-dezembro-1995-362566-normaatualizada-pl.xml \
     --linker /usr/local/bin/linkertool
   ```
3. Confirm in the new XML:
   - `art3_tab1` → `art3_cpt_tab1`, with `<table>` now BEFORE `</Caput>` (inside `<Caput id="art3_cpt">`).
   - `art3-1_tab1` → `art3-1_cpt_tab1`, similarly inside its Caput.
   - `art11-1_tab1` → `art11-1_cpt_tab1`, similarly inside its Caput.
   - All three `<Paragrafo>` siblings still present and well-formed.
4. Regression check: build and run on `LEI_9250_3.docx` (no tables) and `lei_10406_20020110_1.docx` to confirm no diff in non-table output.
5. Schema-validation regression: the existing `'table'` filter (Validation.scala:553-555) suppresses RIGIDO type-10 errors regardless of the table's position, so no new validation problems should appear.
