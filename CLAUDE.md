# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

LexML Parser Projeto Lei — a Scala 2.13 library that parses Brazilian legal documents (laws, bills, regulations) into structured LexML XML. Built with Maven, targets Java 11.

## Build Commands

```bash
mvn compile -nsu          # Compile
mvn test                  # Run all tests
mvn test -Dtest=TestClassName  # Run a single test class
mvn -Ponejar package      # Build executable JAR
```

Run the CLI: `java -jar target/lexml-parser-projeto-lei-1.15.0-onejar.jar [parse|parseArticulacao|dumpProfiles] [args]`

## Test invocations

Run from the repo root (`lexml-parser-projeto-lei/`). Always set
`LC_ALL=C.UTF-8 LANG=C.UTF-8` so the parser handles Portuguese characters
correctly. Sample DOCX inputs live at
`../novas_normas_20260420/manual_20260421/`.

### Decreto

```bash
LC_ALL=C.UTF-8 LANG=C.UTF-8 java -jar target/lexml-parser-projeto-lei-1.15.0-onejar.jar parse \
   -m application/vnd.openxmlformats-officedocument.wordprocessingml.document \
   -i ../novas_normas_20260420/manual_20260421/decreto_2338_1997.docx \
   -o ../novas_normas_20260420/teste_decreto/decreto_2338_1997.xml \
   --write-errors-to-file ../novas_normas_20260420/teste_decreto/decreto_2338_1997.err.log \
   -t decreto -a federal -n 2338 --data 1997-10-07 \
   --linker /usr/local/bin/linkertool
```

### Resolução

Resoluções need extra `--prof-regex-*` overrides because the default
profile expects a different epigraph/header pattern:

```bash
LC_ALL=C.UTF-8 LANG=C.UTF-8 java -jar target/lexml-parser-projeto-lei-1.15.0-onejar.jar parse \
   -m application/vnd.openxmlformats-officedocument.wordprocessingml.document \
   -i ../novas_normas_20260420/manual_20260421/res_anatel_612_2015.docx \
   -o ../novas_normas_20260420/teste_resolucoes/res_anatel_612_2015.docx.xml \
   --write-errors-to-file ../novas_normas_20260420/teste_resolucoes/res_anatel_612_2015.docx.err.log \
   -t resolucao \
   --prof-regex-epigrafe '^resolucao' \
   --prof-regex-epigrafe-continuacao '^resolucao%^n[oº°˚]' \
   --prof-regex-pos-epigrafe '^publicado:%^left\d%^acessos:' \
   --prof-epigrafe-head 'RESOLUÇÃO' \
   --linker /usr/local/bin/linkertool
```

## Architecture

Source lives under `src/main/scala/br/gov/lexml/parser/pl/`. The parsing pipeline flows through:

1. **Input conversion** — `xhtml/` converts RTF/DOCX/text to XHTML; `docx/` handles DOCX specifically
2. **Block extraction** — `xhtml/` produces `Block` objects from XHTML nodes
3. **Block processing** — `ProjetoLei.scala` orchestrates a multi-stage pipeline: text normalization, amendment (alteracao) recognition, dispositivo recognition, hierarchy organization, link recognition
4. **Output** — `output/LexmlRenderer` produces LexML XML; `output/HtmlRenderer` produces HTML

### Key types (`block/Block.scala`)

`Block` trait hierarchy: `Paragraph`, `Dispositivo` (legal provisions with rotulo/label, content, sub-provisions, and path), `Alteracao` (amendments — content between quote marks), `Omissis`, `Table`, `OL`, `Image`.

### Rotulo system (`rotulo/`)

Rótulos are labels identifying provision types in the Brazilian legal hierarchy: Artigo > Paragrafo > Inciso > Alinea > Item. Aggregators: Parte > Livro > Titulo > Capitulo > Secao > SubSecao.

### Profiles (`profile/`)

`DocumentProfile` defines structural rules per legislation type (Lei, ProjetoDeLei, Emenda, etc.) — regex patterns for section recognition, epigraph format, authority URN fragments.

### Linker (`linker/`)

Optional component using Apache Pekko actors to identify and hyperlink legislative references.

## Domain Glossary

- **Articulacao**: main articulated body of a legal document
- **Dispositivo**: a legal provision (article, paragraph, item)
- **Alteracao**: amendment to existing legislation (detected by quote marks)
- **Rotulo**: label prefix (Art., §, Inc., etc.)
- **Ementa**: document summary; **Epigrafe**: document title; **Preambulo**: preamble
- **Omissis**: ellipsis indicating omitted text

## Notes

- Some input formats require AbiWord installed on the system
- The project uses Apache Pekko 1.0.0 (Akka successor)
- Text is normalized to Unicode NFC before processing
- See `ARCHITECTURE.md` for detailed component documentation and code patterns
