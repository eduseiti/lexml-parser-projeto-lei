# RIR/2018 fake LIVROs and Lei 6.766 inciso "III-A.": fix

**Created:** 2026-10-08 18:32:26
**Found in:** `../br-taxqa-r_v2.0/docs/20261008_133726_corpus_rebuild_review_answers.md`, defects
**N4** (§3.2 sample A9, RIR/2018) and **N7** (§3.2 sample A8, Lei 6.766 art. 4).

Status: **done** (2026-10-08). The `../br-taxqa-r_v2.0` corpus still needs re-parsing, re-segmenting
and re-evaluating (report §4 step 3). That step is not done here.

All changes are in `src/main/scala/br/gov/lexml/parser/pl/rotulo/rotuloParser.scala`. The tests are in
`src/test/scala/br/gov/lexml/parser/pl/rotulo/RotuloParserTest.scala` (new).

---

## 1. Problems

### 1.1 N4: RIR/2018 (`decreto_9580_20181122.anexo1`) groupings

The report listed three problems:

- Inside Livro II, Título VIII, Cap. II, the sub-headings "Livro diário" (before art. 273) and "Livro de
  Apuração do Lucro Real" (before art. 277) were parsed as LIVROs "LIVRO DIARIO" and "LIVRO DE". They
  got the ids `liv3`/`liv4`, which the real Livros III/IV then repeated, and they took 404 Livro II
  articles.
- LIVRO III held 1 article.
- About 210 Livro III articles sat outside any LIVRO.

Per-LIVRO article counts (computed from the XML):

| | liv1 | liv2 | liv3 | liv4 | no LIVRO |
|---|---|---|---|---|---|
| before | 157 | 116 | 5 (4 fake + 1) | 561 (400 fake + 161) | 211 |
| after | 157 | 520 | 212 | 161 | 0 |

### 1.2 N7: Lei 6.766 art. 4

The source reads `III-A. - ao longo da faixa de domínio das ferrovias…`. This line was not recognised
as an inciso and was merged into `art4_cpt_inc3`. "III-B –" was parsed correctly as `inc3-2`.

## 2. Root causes and fixes

### 2.1 `livro` accepted any word

`livro` was `"livro " ~> "\\w+"`. Commit `cc9245c` added textual LIVRO names ("LIVRO PRIMEIRO",
"LIVRO COMPLEMENTAR"), so a word that is not a roman numeral became `RotuloLivro(Left(WORD))`, and
`Block.corrigeRotuloLivro` then numbered it. So any paragraph starting with "Livro <word>" opened a
LIVRO.

**Fix:** the word must be one of:

- a roman numeral;
- a word in `livroTextual` (ordinals *primeiro…décimo*, *único*, *complementar*, *preliminar*,
  *geral*, *especial*, *final*);
- a roman numeral glued to the name, `livroRomanoColado` = `[ivxl]+[a-z]{4,}`. This covers RIR/1999
  "LIVRO IIITRIBUTAÇÃO", which keeps its old behaviour.

### 2.2 `agregador` caught the rejected "livro …"

Once `livro` failed, `agregador` tried `"livro" ~ ' ' ~ numeroRomano`. `parseRotulo` does not require
end of input, so `numeroRomano` read "**di**ario" as DI = 501 and "**d**e" as D = 500. The test caught
this.

**Fix:** removed the `livro` branch from `agregador`. `algumRotulo` tries `livro` first, so that
branch only ran for words `livro` had already rejected.

After 2.1 and 2.2, these headings become the `<TituloDispositivo>` of the next article. That is the
LexML form for an article heading. Examples:
`<TituloDispositivo>Livro de Apuração do Lucro Real</TituloDispositivo>` on art. 277, and
"Livro-razão", "Livro diário".

### 2.3 LIVRO III with 1 article: "V I" in art. 677

The text of 2.1/2.2 was not the only cause. In art. 677 the source has the typo **`V I - para os meses de
abril a dezembro do ano-calendário de 2015:`** (inciso VI with a space). It was not a rótulo. The
hierarchy builder moved that paragraph and its table to the top of the annex, before LIVRO I. Then
`§ 1º`–`§ 3º` of art. 677 (ids `par1`–`par3`, which fail schema validation) and everything after them
fell out of LIVRO III. The same break made the annex fall back to `DocumentoGenerico`.

**Fix:** `numeroRomanoEspacado` accepts `[ivx]+ [ivx]+` when the joined letters form a valid numeral.
It is only a fallback: `inciso = incisoNumerado(numeroRomano) | incisoNumerado(numeroRomanoEspacado)`.
It still needs the dash after it ("V I para…" is not an inciso). Art. 677 now has `inc1`–`inc6`. The
annex is now a `DocumentoArticulado` with `ParteInicial/Epigrafe` + `Articulacao`, where it used to be
a `DocumentoGenerico/PartePrincipal`.

### 2.4 Inciso "III-A." (Lei 6.766)

`inciso` was `numeroRomano ~ opt(complemento) <~ ' '? <~ not(')') <~ hyphen`. The `.` between the
complement and the dash made it fail.

**Fix:** added `opt('.')` after the complement. Art. 4 now has `inc3`, `inc3-1` (III-A) and `inc3-2`
(III-B).

## 3. Verification

- `mvn test`: 32 tests, 0 failures. `RotuloParserTest` covers LIVRO roman, textual and glued forms;
  "Livro diário / de Apuração… / razão / de Presença / auxiliar n. 8 / de inventário" are not rótulos;
  III-A., III-B, "V I -".
- Full regression on the 211 DOCX of `../br-taxqa-r_v2.0/original/articulados` (392 XML files incl.
  annexes). I ran `batch_parse.py --no-justificativa` without linker once with the HEAD jar and once
  with the new jar. **386 outputs are byte-identical.** The 6 that differ are all intended:

| File | Change |
|---|---|
| `decreto_9580_20181122.anexo1` | §1.1 table. Schema errors in the err.log go from 19 to 6 (remaining: duplicate `art1`/`art645_par1`, already there before) |
| `decreto_3000_19990326` (RIR/1999) | Same defect: fake "LIVRO DIARIO", "LIVRO RAZAO", "LIVRO DE" removed. Livros 144/474/167/218 (was 144/112/1/3/358/167/218 over 7 LIVROs); errors 345 → 333 |
| `lei_6404_19761215` | Fake "LIVRO DE Presença" (art. 127) removed; caps 11–15 no longer inside a fake `liv1`; errors 392 → 388 |
| `decreto_lei_58_19371210.anexo1` | "LIVRO AUXILIAR N. 8" (a form model) is plain text, not a LIVRO |
| `lei_6766_19791219` | `art4_cpt_inc3-1` (III-A) added |
| `lei_4886_19651209` | Bonus from 2.4: art. 10 "I.- elaborar o seu regimento interno" was merged into the caput and is now `art10_cpt_inc1` |

Legitimate textual LIVROs are unchanged: Lei 5.172 "LIVRO PRIMEIRO/SEGUNDO", Lei 10.406 "LIVRO
COMPLEMENTAR".

## 4. Not addressed

- RIR/2018 duplicate ids `art1`/`art1_cpt` (an `Art. 1º` inside Livro II, Tít. VI, Cap. II) and
  `art645_par1`. These were already there before and are separate defects.
- RIR/2018 "Seção X … Determinação do custo" (report A9: unnumbered sub-heading glued onto the section
  name). Not in scope.
- RIR/1999 "LIVRO IIITRIBUTAÇÃO" stays glued, with the rótulo text kept.
- `scripts/run_segmentation_csv.py` crashes with saxonche 13.0.0 (`PyXslt30Processor` has no
  `exception_occurred`) before reading any input. This is unrelated to this fix and was not changed.
