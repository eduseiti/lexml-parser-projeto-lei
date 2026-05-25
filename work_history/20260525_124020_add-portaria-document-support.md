# Add "Portaria" document support to batch_parse.py + native Scala profile

**Date:** 2026-05-25
**Status:** Implemented & verified

## Verification results (2026-05-25)

- `mvn -Ponejar package` built cleanly.
- `dumpProfiles` shows `localidade: br, autoridade: federal, tipoNorma: portaria`
  with the ministerial preambulo regexes appended.
- Parsing `portaria_mjsp_502_2021.docx` (per-ministry command with overrides):
  - Epigrafe = `PORTARIA Nº 502, DE 23 DE NOVEMBRO DE 2021` (no DOU header).
  - Ementa = `Regulamenta o processo de classificação indicativa...` (correct).
  - Preambulo = `O MINISTRO DE ESTADO DA JUSTIÇA E SEGURANÇA PÚBLICA, ...`.
  - Articulacao starts at `<Capitulo id="cap1">` (CAPÍTULO I).
  - URN = `urn:lex:br:ministerio.justica.seguranca.publica:portaria:2021-11-23;502`.
  - err.log clean.
- Native path `-a federal -t portaria` (no `--prof-*` flags) parses identically;
  URN = `urn:lex:br:federal:portaria:2021-11-23;502` — confirms the registered
  profile carries the rules and the authority is driven by `-a`, not the profile.
- `batch_parse.py --dry-run` emits the expected portaria invocation
  (`-a ministerio.justica.seguranca.publica -t portaria -n 502 --data 2021-11-23`
  + the five overrides) and leaves decreto / res_anatel / res_anpd lines unchanged.
- `detect()` unit checks: `portaria_xyz_*` (unmapped) → skipped, not `federal`;
  `res_*`, `lei_*` unaffected.

## Original plan

## Context / problem

`scripts/batch_parse.py` skips
`../novas_normas_20260420/manual_20260421/portaria_mjsp_502_2021.docx` because
"portaria" (a Brazilian ministerial order) is not a recognized document type.
`detect()` has no rule for it, and the Scala parser has no registered `Portaria`
`DocumentProfile`. Goal: parse the document with **ementa** and **preambulo**
correctly identified and the correct ministry authority URN emitted.

User decisions:
- **Native Scala profile** (not runtime-overrides-only).
- **Filename convention `portaria_<agency>_*`**, `<agency>` = ministry acronym
  resolved via `scripts/agency_authority.json` (mirrors `res_<agency>_*`).
  `mjsp -> ministerio.justica.seguranca.publica` is already in that map.

## Analysis

### Sample document structure

`portaria_mjsp_502_2021.docx` first paragraphs:

| # | Text (abridged) | Role |
|---|---|---|
| 1 | `Diário Oficial da União` | DOU boilerplate (pos-epigrafe) |
| 2 | `Publicado em: 24/11/2021 \| Edição: 220 \| Seção: 1 \| Página: 77` | DOU boilerplate |
| 3 | `Órgão: Ministério da Justiça e Segurança Pública/Gabinete do Ministro` | DOU boilerplate |
| 4 | `PORTARIA MJSP Nº 502, DE 23 DE NOVEMBRO DE 2021` | **Epigrafe** (agency segment "MJSP" between keyword and Nº) |
| 5 | `Regulamenta o processo de classificação indicativa ...` | **Ementa** |
| 6 | `O MINISTRO DE ESTADO DA JUSTIÇA E SEGURANÇA PÚBLICA, ... resolve:` | **Preambulo** |
| 7 | `CAPÍTULO I` / `Art. 1º ...` | Articulacao |

Structurally identical to the existing agency-resolution (`res_<agency>_*`) case:
pos-epigrafe boilerplate before the epigraph, an agency segment between the type
keyword and "Nº", and a non-legislative preambulo opener.

### How the parser recognizes the parts

- `spanEpigrafe` (`ProjetoLei.scala:230-245`): `pre` = blocks before the first
  `regexEpigrafe1` match (the DOU header lines 1-3); `epi` = run matching
  `regexEpigrafe ++ regexEpigrafe1`; leading `regexPosEpigrafe`/empty blocks
  after the epigraph are dropped. The `^`-anchored epigraph regexes match the
  whole `portaria mjsp no 502...` line — the agency segment is absorbed.
- `reconhecePreambulo` (`ProjetoLei.scala:269-288`): preamble found via
  `regexPreambulo`, spanning to the first articulacao start (CAPÍTULO I / Art. 1º
  via `isArticulacaoStart`); pos-epigrafe lines filtered out of the region.
- Ementa = the first non-pos-epigrafe paragraph after the epigraph (line 5).
- Regexes are matched against NFD-folded, diacritics-stripped, lowercased text
  (so they are written lowercase and unaccented, e.g. `^orgao:`).

### Profile system

`src/main/scala/br/gov/lexml/parser/pl/profile/DocumentProfile.scala`:
- `RegexProfile` (7-34): `regexEpigrafe1`, `regexEpigrafe`, `regexPosEpigrafe`,
  `regexPreambulo`, ..., `epigrafeObrigatoria` (default true), `ementaAusente`
  (default false).
- `DefaultRegexProfile` (49-95): legislative defaults (preambulo matches
  `^o (congresso nacional|senado federal)...`, `^[ao] president[ae]...`).
- Authority traits (393-411): `DoSenadoProfile`, `DaCamaraProfile`,
  `DoCongressoProfile`, `FederalProfile` — no ministry trait exists.
- `NormaProfile = DocumentProfile with DefaultRegexProfile` (434).
  `ResolucaoProfile` (436-441) is the pattern to mirror.
- Registry `DocumentProfileRegister` (325-390): `profiles:
  Map[(Localidade,Autoridade,TipoNorma),DocumentProfile]`. `getProfile` (331) is
  a strict 3-tuple `Map.get`. `builtins` (351-389) registered at line 390.
- CLI (`FECmdLine.scala:446`): `getProfile(...).getOrElse(Lei)`, then
  `profile0 + overrides`. `--prof-regex-*` flags split on `%` into a regex list
  (`stringToRegexList`, 142-146); flag→field map at 318-369.

### Design tension (key finding)

`getProfile` has no tipoNorma-only fallback. A single registered `object Portaria`
can be keyed under only ONE authority. Each ministry has a distinct authority URN,
so `getProfile("ministerio.justica.seguranca.publica", "portaria")` MISSES and
falls back to `Lei`, losing the portaria regexes. This is the same situation
`res_<agency>` already side-steps via `Lei` fallback + `PROFILE_OVERRIDES`.

**Resolution — Scala = source of truth, Python = transport:**
- Register `object Portaria` under generic `federal` authority → `-a federal -t
  portaria` works natively with zero flags; canonical spec, visible in
  `dumpProfiles`.
- For per-ministry filenames, `batch_parse.py` still passes `--prof-*` overrides
  (the `Lei` fallback needs them). The two are **complementary**, not redundant.
- Rejected: changing `getProfile` to ignore authority on fallback — would alter
  shared lookup semantics for every norm type and contradict the deliberate
  `res_<agency>` design.

**Override semantics gotcha:** `DocumentProfileOverride` *replaces* (not appends
to) the base list — `regexEpigrafe = overrideRegexEpigrafe.getOrElse(base...)`.
Override values must be self-contained (epigrafe-continuacao must include both
`^portaria` and `^n[oº°˚]`), exactly as the `resolucao` override does.

## Plan

### 1. Scala — `DocumentProfile.scala`

New trait near `ResolucaoProfile` (~441):

```scala
trait PortariaProfile extends NormaProfile {
  override def urnFragTipoNorma: String = "portaria"
  override def epigrafeHead: String = "PORTARIA"
  override def regexEpigrafe1: List[Regex] = super.regexEpigrafe1 ++ List("^portaria"r)
  override def regexEpigrafe: List[Regex]  = super.regexEpigrafe  ++ List("^portaria"r)
  override def regexPosEpigrafe: List[Regex] = super.regexPosEpigrafe ++ List(
    "^diario oficial"r, "^publicado em"r, "^orgao:"r, "^edicao"r, "^secao"r
  )
  override def regexPreambulo: List[Regex] = super.regexPreambulo ++ List(
    "^o ministro de estado"r, "^a ministra de estado"r
  )
  // ementaAusente=false, epigrafeObrigatoria=true — both default
}
```

New object near the federal objects (after `MedidaProvisoriaFederal`):

```scala
object Portaria extends PortariaProfile with FederalProfile {
  override def epigrafeTemplateCode : String =
    """PORTARIA Nº <numeroComComplemento>, DE <dataExtenso>"""
}
```

Add `Portaria` to `builtins` (after `Decreto`, line 374). No change to
`register`/`getProfile`.

### 2. Python — `scripts/batch_parse.py`

a. Generalize the `res_<agency>` detect branch (248-279). Replace constant
   (73-74) with `AGENCY_PREFIX_TIPONORMA = {"res": "resolucao", "portaria":
   "portaria"}`; guard = `tokens[0]` in that map, `len>=2`, and not (`res` +
   legislative token). Reuse the numero/ano/data tail-scan. `portaria` NOT added
   to `RULES` (the agency branch owns it).

b. `_EPIGRAFE_RE` (172-182): add `portaria` to the keyword alternation
   (`...|resolucao|portaria|lei|decreto)`). The `(?:\s+[a-z][a-z./-]*)?` group
   already absorbs `mjsp`.

c. `PROFILE_OVERRIDES` (107-115): add a `portaria` entry mirroring the Scala
   profile:
   ```python
   "portaria": [
       "--prof-regex-epigrafe", "^portaria",
       "--prof-regex-epigrafe-continuacao", r"^portaria%^n[oº°˚]",
       "--prof-regex-pos-epigrafe", r"^diario oficial%^publicado em%^orgao:%^edicao%^secao",
       "--prof-regex-preambulo", r"^o ministro de estado%^a ministra de estado",
       "--prof-epigrafe-head", "PORTARIA",
   ],
   ```
   `build_cli_args` (344-345) already appends `PROFILE_OVERRIDES[tipo_norma]` when
   `det.agency` is set.

d. Generalize comments (66-141) to mention `portaria_<agency>_*`; note the
   override mirrors `PortariaProfile` and must stay in sync.

### 3. Docs — `CLAUDE.md`

Add a Portaria test-invocation block mirroring the Resolução example, using
`-a ministerio.justica.seguranca.publica -t portaria` + the five `--prof-*` flags.

## Verification

1. `mvn -Ponejar package`.
2. `java -jar target/...-onejar.jar dumpProfiles 2>&1 | grep -i portaria` →
   `autoridade=federal, tipoNorma: portaria` with the regexes.
3. Parse `portaria_mjsp_502_2021.docx` into `../novas_normas_20260420/teste_portaria/`:
   ```bash
   LC_ALL=C.UTF-8 LANG=C.UTF-8 java -jar target/lexml-parser-projeto-lei-1.15.0-onejar.jar parse \
      -m application/vnd.openxmlformats-officedocument.wordprocessingml.document \
      -i ../novas_normas_20260420/manual_20260421/portaria_mjsp_502_2021.docx \
      -o ../novas_normas_20260420/teste_portaria/portaria_mjsp_502_2021.docx.xml \
      --write-errors-to-file ../novas_normas_20260420/teste_portaria/portaria_mjsp_502_2021.docx.err.log \
      -a ministerio.justica.seguranca.publica -t portaria -n 502 --data 2021-11-23 \
      --prof-regex-epigrafe '^portaria' \
      --prof-regex-epigrafe-continuacao '^portaria%^n[oº°˚]' \
      --prof-regex-pos-epigrafe '^diario oficial%^publicado em%^orgao:%^edicao%^secao' \
      --prof-regex-preambulo '^o ministro de estado%^a ministra de estado' \
      --prof-epigrafe-head 'PORTARIA' \
      --linker /usr/local/bin/linkertool
   ```
   (Optional: `-a federal -t portaria` with NO `--prof-*` flags → native profile.)
4. Output XML: `<Epigrafe>` = "PORTARIA ... Nº 502 ... 2021" (no DOU header);
   `<Ementa>` = "Regulamenta o processo..."; `<Preambulo>` = "O MINISTRO DE
   ESTADO...resolve:"; articulacao starts at CAPÍTULO I / Art. 1º; URN authority
   = `ministerio.justica.seguranca.publica:portaria:2021-11-23;502`; clean err.log.
5. Batch: `python3 scripts/batch_parse.py .../manual_20260421 <out> --dry-run`
   shows the inferred flags; real run → `OK portaria_mjsp_502_2021.docx
   [ministerio.justica.seguranca.publica / portaria]`. Unmapped agency
   (`portaria_xyz_*`) is SKIPPED, not `federal`.

## Critical files
- `src/main/scala/br/gov/lexml/parser/pl/profile/DocumentProfile.scala` — `PortariaProfile`, `object Portaria`, `builtins`.
- `scripts/batch_parse.py` — detect branch (248-279), `_EPIGRAFE_RE` (172-182), `PROFILE_OVERRIDES` (107-115), comments (66-141).
- `scripts/agency_authority.json` — already has `mjsp`.
- `CLAUDE.md` — Portaria test-invocation block.
- `FECmdLine.scala` — no edit; reference for flag→override mapping (318-369), fallback (446).
