# `batch_parse.py`: `--no-justificativa`, `--only`, and TSE resolutions

**Created:** 2026-10-08 00:14:50
**Found in:** `../br-taxqa-r_v2.0`, rebuild of its statutory LexML (step R3b of
`../br-taxqa-r_v2.0/docs/20261007_180833_corpus_fixes_and_full_rebuild_plan.md`, §1.2 and §3.1 cause
2b). Only `scripts/batch_parse.py` changes; the parser (Scala) is unchanged.

Status: **done** (2026-10-08).

---

## 1. Changes

| Change | Why |
|---|---|
| **`--no-justificativa`** adds `--prof-regex-justificativa '(?!)'` (a regex that never matches) to every parser call | The parser is the *projeto de lei* parser: a paragraph matching `regexJustificativa` (`^justificacao`, `^justificativa`, …) ends the articulação. Enacted acts have no justification section, but Lei 6.404/1976 printed a section heading "Justificação" before art. 225, and arts. 225–300 were lost (`ProjetoLei.scala:172,187`). The flag is a guard for the whole corpus; the user also removed that heading from the `.docx` |
| **`--only GLOB`** converts only the `.docx` whose name matches | Re-parse one document after a `.docx` repair with the same command line (and `.err.log` header) as the batch. `skipped_report.txt` then covers only the matched files |
| **`PROFILE_OVERRIDES["resolucao"]`**: `%^relator` added to `--prof-regex-pos-epigrafe`, `%^o tribunal superior eleitoral` to `--prof-regex-preambulo` | TSE resolutions (`resol_tse_*`) print "Relator: Ministro …" between the epígrafe and the ementa and open with "O TRIBUNAL SUPERIOR ELEITORAL, …". Without them the parse of `resol_tse_22250_20060629` stops at "Ementa ausente" / finds no preamble |

`scripts/tests` pass (8). The `resolucao` alternatives only match lines that start with "Relator" or
"O Tribunal Superior Eleitoral", so the other resolutions parse as before: all 214 statutory
documents of br-taxqa-r_v2.0 were re-parsed, and `resol_cgsn_140_20180522` (24 files) and
`resol_cgpc_26_20080929` are byte-identical to the previous parse.

## 2. Use in br-taxqa-r_v2.0

```bash
python3 ../lexml-parser-projeto-lei/scripts/batch_parse.py --no-justificativa \
    --linker /usr/local/bin/linkertool original/articulados lexml/articulados_new
```

Jar: `target/lexml-parser-projeto-lei-1.15.0-onejar.jar` rebuilt from `ebf6567` with
`mvn -Ponejar package -DskipTests` (SHA-256 `5196351950546324ddc27cd6752cc13ee557e7b89c1eafa7d4a6ac8bfd46a766`).
The jar that was in `target/` dated from 2026-05-25, older than `ebf6567`.

Result: 214 converted, 0 skipped, 0 failed. Of the 451 XML files shared with the previous parse, 440
are byte-identical. Of the other 11, 7 come from `.docx` repairs in br-taxqa-r_v2.0. The other 4
(`decreto_9580_20181122.anexo1`, `lei_10833_20031229`, `lei_8242_19911012.anexo1`,
`lei_9504_19970930`) differ only in `Remissao` links. The text is the same.

## 3. Known issue (not fixed here): linker output for "art. N, caput"

For "art. 19, caput, da Lei nº 9.096…" the current `/usr/local/bin/linkertool` returns
`urn:lex:br:federal:lei:1995-09-19;9096!cpt`, which loses the article (`!art19_cpt` expected):

```bash
echo 'O art. 19, caput, da Lei nº 9.096, de 19 de setembro de 1995 - Lei dos Partidos' \
    | LC_ALL=C.UTF-8 linkertool --contexto=federal
```

The previous br-taxqa-r_v2.0 parse already had 227 such `…!cpt…` links; this parse has 234. Some
older files got the right link, maybe from a linker built elsewhere (their `.err.log` points at
`/work/unicamp/…`). The binary in use dates from 2025-10-31, and the uncommitted change in
`../lexml-linker` only touches imports. This belongs in `../lexml-linker`; chunk texts are not
affected.
