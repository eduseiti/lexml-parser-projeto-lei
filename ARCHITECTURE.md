# LexML Parser Projeto Lei - Project Architecture

## Project Overview

**lexml-parser-projeto-lei** is a Brazilian legal document parsing library developed for the Brazilian Senate (Senado Federal). It parses normative documents (bills, laws, regulations) and converts them into structured LexML format.

### Key Information
- **Language**: Scala 2.13.12
- **Build Tool**: Maven
- **Current Version**: 1.14.19-SNAPSHOT
- **License**: GPLv2
- **Java Version**: 11
- **Main Branch**: master
- **Repository**: https://github.com/lexml/lexml-parser-projeto-lei

## Project Purpose

The parser converts various document formats (RTF, DOCX, plain text) containing Brazilian legal documents into structured XML following the LexML (Legal XML) specification. It:

1. Parses document structure (articles, paragraphs, items, etc.)
2. Recognizes alterations/amendments to existing legislation
3. Identifies document metadata (authority, document type, dates, etc.)
4. Generates valid LexML XML output
5. Validates the structure against XML schemas
6. Creates links between legislative references

## Build & Run

### Compilation
```bash
mvn compile -nsu
```

### Testing
```bash
mvn test
```

### Building Executable JAR
```bash
mvn -Ponejar package
java -jar target/lexml-parser-projeto-lei-VERSION-onejar.jar [args...]
```

### Running from IDE
Main class: `br.gov.lexml.parser.pl.fe.FECmdLine`

## Core Architecture

### Package Structure

```
src/main/scala/br/gov/lexml/parser/pl/
├── block/              # Block model - document structure elements
├── docx/              # DOCX file reader
├── errors/            # Exception and error handling
├── fe/                # Frontend - command-line interface
├── linker/            # Legislative reference linking
├── metadado/          # Document metadata handling
├── misc/              # Utility classes
├── output/            # Output renderers (LexML XML, HTML)
├── profile/           # Document profiles (different types of legislation)
├── rotulo/            # Label parsing (Art., Par., Inc., etc.)
├── text/              # Text normalization
├── util/              # General utilities
├── validation/        # Structure and schema validation
└── xhtml/             # XHTML processing and conversion
```

### Core Components

#### 1. Document Model (`block/`)

The heart of the system is the `Block` trait hierarchy representing document structure:

- **`Block`**: Base trait for all document elements
  - **`Paragraph`**: Text paragraphs with formatting
  - **`Dispositivo`**: Legal provisions (articles, paragraphs, items, etc.)
    - Contains: rotulo (label), conteudo (content), subDispositivos (sub-provisions), path
  - **`Alteracao`**: Amendments/modifications to existing legislation
  - **`Omissis`**: Omitted text markers (...)
  - **`Table`**: Tables in documents
  - **`OL`**: Ordered lists
  - **`Image`**: Image placeholders

**Key file**: `Block.scala` (929 lines) - Contains all block recognition, organization, and transformation logic

#### 2. Parsing Pipeline (`ProjetoLei.scala`, `ParserFrontEnd.scala`)

The parsing process follows these stages:

**Main Pipeline** (ParserFrontEnd.scala:34-46):
```scala
parseProjetoLei(params) ->
  1. Convert input to XHTML
  2. Extract blocks from XHTML nodes
  3. Parse blocks into ProjetoLei structure
  4. Validate and return result
```

**Block Processing** (ProjetoLei.scala:208-265):
```scala
parseArticulacao(blocks) ->
  1. Normalize text (Unicode NFC)
  2. Trim paragraphs
  3. Recognize alteracoes (amendments)
  4. Recognize dispositivos (provisions)
  5. Recognize omissis
  6. Identify aggregator texts
  7. Identify titles
  8. Organize dispositivos hierarchy
  9. Number generic dispositivos
  10. Clean empty paragraphs
  11. Push last omissis
  12. Number alteracoes
  13. Identify paths
  14. Recognize links (if linker enabled)
```

#### 3. ProjetoLei Structure

The parsed document is represented as a `ProjetoLei` case class:

```scala
case class ProjetoLei(
  metadado: Metadado,           // Document metadata
  preEpigrafe: List[Block],     // Content before epigraph
  epigrafe: Block,              // Document title/epigraph
  ementa: Option[Block],        // Summary/abstract
  preambulo: List[Paragraph],   // Preamble
  articulacao: List[Block],     // Main articulated content
  otherCaracteristicas: Map[String, Boolean]  // Features
)
```

**Document Sections** (ProjetoLei.scala:149-164):
The parser recognizes these sections using regex patterns (via `Marcadores`):
- Local e Data (place and date)
- Justificação (justification)
- Anexos (attachments)
- Legislação Citada (cited legislation)
- Assinatura (signature)
- Articulação (main articles - core content)

#### 4. Rotulo (Label) System (`rotulo/`)

Rótulos identify legal provision types:

- `RotuloArtigo`: Articles (Art. 1º, Art. 2º)
- `RotuloParagrafo`: Paragraphs (§ 1º, § 2º, or caput)
- `RotuloInciso`: Items (I, II, III)
- `RotuloAlinea`: Sub-items (a, b, c)
- `RotuloItem`: Sub-sub-items (1, 2, 3)
- `RotuloPena`: Penalties
- Aggregators:
  - `RotuloParte`: Parts
  - `RotuloLivro`: Books
  - `RotuloTitulo`: Titles
  - `RotuloCapitulo`: Chapters
  - `RotuloSecao`: Sections
  - `RotuloSubSecao`: Subsections

**Key concept**: Each dispositivo has a `path` (List[Rotulo]) representing its position in the document hierarchy.

#### 5. Document Profiles (`profile/`)

Different types of legislation have different structural rules. Profiles define:

- Regex patterns for recognizing document sections
- Whether epigraph is mandatory
- Epigraph format templates
- Authority and document type URN fragments

**Key file**: `DocumentProfile.scala` - Defines the profile system

Common profiles include:
- Lei (Law)
- ProjetoDeLeiDoSenadoNoSenado (Senate Bill in Senate)
- Emenda (Amendment)
- etc.

#### 6. Frontend (`fe/FECmdLine.scala`)

Command-line interface with three main commands:

**a) `parse`** - Parse complete document
- Input: file or stdin (various MIME types)
- Output: LexML XML
- Options: metadata parameters, profile overrides, linker configuration

**b) `parseArticulacao`** - Parse only articulation (for testing)
- Input: XHTML paragraphs
- Output: LexML XML articulation

**c) `dumpProfiles`** - Show available document profiles

#### 7. Output Rendering (`output/`)

**LexmlRenderer**: Converts ProjetoLei to LexML XML format
- `render(pl: ProjetoLei): NodeSeq` - Full document
- `renderArticulacao(blocks: List[Block]): NodeSeq` - Articles only

**HtmlRenderer**: Converts to HTML for display

#### 8. Linker (`linker/`)

The linker component identifies and creates hyperlinks to legislative references:
- Uses external linker tool (optional)
- Recognizes references like "Lei nº 123/2000"
- Creates URN links following LexML specification
- Operates via Akka/Pekko actors for parallelization

**Note**: Uses Apache Pekko (successor to Akka) version 1.0.0

#### 9. Validation (`validation/`)

Two-level validation:
1. **Structure validation**: Checks document structure rules
2. **Schema validation**: Validates against LexML XML Schema

#### 10. XHTML Processing (`xhtml/`)

Converts various input formats to XHTML:
- Uses AbiWord converter for some formats
- Processes XHTML into Block structures
- **Note**: Some formats may require AbiWord installed

## Key Technologies & Dependencies

### Core Libraries
- **Scala 2.13.12**: Main programming language
- **Apache Pekko 1.0.0**: Actor system (replaces Akka)
- **Scala XML 1.3.0**: XML processing
- **Scala Parser Combinators 1.1.2**: Parsing utilities

### Document Processing
- **Apache Commons IO 2.18.0**: File I/O
- **TagSoup 1.2.1**: HTML/XML parsing
- **JSON4s 3.7.0-M6**: JSON handling

### LexML Integration
- **lexml-xml-schemas 4.0.2**: LexML XML schemas

### Logging
- **Log4j2 2.17.1**: Logging framework
- **Grizzled-SLF4J 1.3.4**: Scala logging facade

### Command-line
- **scopt 3.7.1**: Command-line option parser

### Templates
- **StringTemplate (ST4) 4.3.1**: Template engine

## Development Workflow

### Typical Development Session

1. **Read existing code** in relevant packages
2. **Test changes** with unit tests or command-line
3. **Build** with Maven
4. **Test parsing** with sample documents

### Testing Approaches

1. **Unit tests**: In `src/test/scala`
2. **Command-line testing**:
   ```bash
   java -jar target/lexml-parser-projeto-lei-*-onejar.jar parse \
     --input sample.rtf \
     --output output.xml \
     --tipo-norma lei \
     --autoridade federal
   ```
3. **ArticulacaoParser**: Test only articulation parsing

### Adding New Features

Common extension points:

1. **New Block Types**: Extend `Block` trait in `block/Block.scala`
2. **New Rotulo Types**: Add to `rotulo/RotuloDispositivo.scala`
3. **New Document Profiles**: Create in `profile/` package
4. **New Output Formats**: Extend renderers in `output/`
5. **Custom Recognition Logic**: Modify pipeline in `Block.scala` and `ProjetoLei.scala`

## Important Implementation Details

### HasId Trait
Dispositivos and Alteracoes implement `HasId[T]`:
- Auto-generates IDs from path (e.g., "art1_par2_inc3")
- Can override with custom ID

### Alteracao Recognition
Alterations are recognized by quotes:
- Opening quote: `"` `"` `"` `''`
- Closing quote: `"` `"` `"` `''` with optional `(ac)` or `(nr)` notes
- Content between quotes becomes `Alteracao` block

### Omissis Recognition
Omissis (omitted text) patterns:
- `...` or `…` or `(...)` etc.
- Empty dispositivo content (except Artigo)

### Text Normalization
All text is normalized to Unicode NFC form before processing (ProjetoLei.scala:210-223)

### Paragraph Trimming
Leading/trailing whitespace carefully handled to preserve structure

## Common Patterns

### Pattern 1: Block Transformation
```scala
def transformBlocks(blocks: List[Block]): List[Block] =
  blocks.map(_.mapBlock {
    case d: Dispositivo => // transform dispositivo
    case p: Paragraph => // transform paragraph
    case x => x
  })
```

### Pattern 2: Fold with State
```scala
blocks.foldLeft((initialList, initialState)) {
  case ((accum, state), block) =>
    // process block with state
    (newAccum, newState)
}._1 // extract result list
```

### Pattern 3: Recursive Child Processing
```scala
def process(block: Block): Block = block match {
  case d: Dispositivo =>
    d.copy(subDispositivos = d.subDispositivos.map(process))
  case a: Alteracao =>
    a.copy(blocks = a.blocks.map(process))
  case x => x
}
```

## Maven Profiles

### onejar Profile
Creates self-contained JAR:
```bash
mvn -Ponejar package
```
Output: `target/lexml-parser-projeto-lei-VERSION-onejar.jar`

### release Profile
For publishing to Maven Central:
- Generates sources JAR
- Generates javadoc JAR
- Signs with GPG
- Deploys to Sonatype/Maven Central

## Debugging Tips

1. **Enable verbose logging**: Use `--verbose` flag or configure log4j2
2. **Use printArticulacao**: Uncomment calls in ProjetoLei.scala (lines 227-260)
3. **Test individual stages**: Use parseArticulacao command
4. **Inspect Block structure**: Use `.toNodeSeq` for XML representation
5. **Check rotulo parsing**: Test with `rotuloParser.parseRotulo(text)`

## Git Branch Strategy

- **master**: Main branch for releases
- **parser_extensions**: Current development branch (where we are)
- Feature branches: As needed

## Related Projects

- **lexml-xml-schemas**: LexML XML schema definitions
- **Linker tool**: External tool for creating legislative references (optional)

## Glossary

- **Articulação**: The main articulated content of the legal document
- **Dispositivo**: A legal provision (article, paragraph, item, etc.)
- **Alteração**: An amendment to existing legislation
- **Ementa**: Document summary/abstract
- **Epígrafe**: Document title
- **Preâmbulo**: Preamble text
- **Rótulo**: Label identifying provision type (Art., §, Inc., etc.)
- **Agregador**: Structural grouping (Part, Book, Title, Chapter, Section)
- **Omissis**: Omitted text indication
- **URN**: Uniform Resource Name for legislation (e.g., urn:lex:br:federal:lei:2000-01-01;1)

## Known Limitations

1. Some input formats require AbiWord installation
2. Linker is optional external tool
3. Schema validation may produce warnings even for valid documents
4. Complex table structures may not parse perfectly

## Next Steps for New Developers

1. Read this document
2. Explore `Block.scala` - understand the block model
3. Read `ProjetoLei.scala` - understand the parsing pipeline
4. Try parsing sample documents with FECmdLine
5. Run existing tests to understand expected behavior
6. Experiment with parseArticulacao for quick iterations

---

**Last Updated**: 2025-12-08
**Document Version**: 1.0
**Maintainer**: LexML Team
