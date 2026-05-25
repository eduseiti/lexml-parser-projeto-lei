#!/usr/bin/env python3
"""Batch-convert a folder of DOCX legal documents to LexML XML.

Inspects each filename to infer the Brazilian-law profile, invokes the
lexml-parser-projeto-lei onejar with the matching `-a`/`-t` flags, and
writes a report of any documents skipped because their type is not
natively supported.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import unicodedata
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from xml.etree import ElementTree as ET

DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

# Ordered rules: longest token prefix first so `decreto_lei` beats `decreto`,
# `lei_complementar` beats `lei`, etc. Each entry is
# (tuple-of-leading-tokens, autoridade, tipoNorma).
RULES: list[tuple[tuple[str, ...], str, str]] = [
    (("emenda", "constitucional"), "federal", "emenda.constitucional"),
    (("lei", "complementar"),      "federal", "lei.complementar"),
    (("lei", "delegada"),          "federal", "lei.delegada"),
    (("decreto", "lei"),           "federal", "decreto.lei"),
    (("decreto", "legislativo"),   "congresso.nacional", "decreto.legislativo"),
    (("medida", "provisoria"),     "federal", "medida.provisoria"),
    (("resolucao", "senado"),      "senado.federal", "resolucao"),
    (("resolucao", "camara"),      "camara.deputados", "resolucao"),
    (("resolucao", "congresso"),   "congresso.nacional", "resolucao"),

    (("constituicao",), "federal", "constituicao"),
    (("cf",),           "federal", "constituicao"),
    (("adct",),         "federal", "ato.disposicoes.constitucionais.transitorias"),
    (("ec",),           "federal", "emenda.constitucional"),
    (("lc",),           "federal", "lei.complementar"),
    (("lcp",),          "federal", "lei.complementar"),
    (("ldl",),          "federal", "lei.delegada"),
    (("dl",),           "federal", "decreto.lei"),
    (("dlg",),          "congresso.nacional", "decreto.legislativo"),
    (("mp",),           "federal", "medida.provisoria"),
    (("mpv",),          "federal", "medida.provisoria"),
    (("rss",),          "senado.federal", "resolucao"),
    (("rsc",),          "camara.deputados", "resolucao"),
    (("rcn",),          "congresso.nacional", "resolucao"),
    (("pls",),          "senado.federal", "projeto.lei;pls"),
    (("plc",),          "senado.federal", "projeto.lei;plc"),
    (("pl",),           "camara.deputados", "projeto.lei;pl"),
    (("pec",),          "senado.federal", "proposta.emenda.constitucional;pec"),

    # Plain single-token rules last so they don't shadow multi-token matches.
    (("lei",),     "federal", "lei"),
    (("decreto",), "federal", "decreto"),
]

# Filenames whose second token is a regulatory/ministerial AGENCY acronym
# (e.g. `res_anatel_*`, `portaria_mjsp_*`) rather than a fixed authority. The
# parser has no registered profile keyed on (autoridade=<agency-urn>, tipoNorma),
# so we route them through the fallback profile (Lei) plus the runtime overrides
# in PROFILE_OVERRIDES. The leading token selects the tipoNorma:
#   res_<agency>      -> resolucao   (regulatory-agency resolution)
#   portaria_<agency> -> portaria    (ministerial order)
# The `<agency>` token (e.g. "anpd", "mjsp") is mapped to its LexML authority URN
# fragment via the agency-authority map loaded from AGENCY_AUTHORITY_MAP_FILE;
# unmapped agencies are skipped so we never emit a wrong "federal" authority.
AGENCY_PREFIX_TIPONORMA = {"res": "resolucao", "portaria": "portaria"}
# For `res_*`, these second tokens are legislative bodies with registered
# profiles (not agencies), so they fall through to the general RULES engine.
AGENCY_LEGISLATIVE_TOKENS = frozenset({"senado", "camara", "congresso"})

# Default location of the JSON file mapping a lowercased/accent-folded agency
# acronym to its LexML authority URN fragment, e.g.
# {"anpd": "agencia.nacional.protecao.dados"}. Overridable with
# --agency-authority-map.
AGENCY_AUTHORITY_MAP_FILE = Path(__file__).resolve().parent / "agency_authority.json"

# Extra CLI flags appended for agency documents (det.agency set), whose
# (authority, tipoNorma) pair has no registered DocumentProfile: they fall back
# to the Lei profile and need these runtime overrides to recognize the actual
# epigraph format. Keyed on tipoNorma because agency documents of a given kind
# share the same epigraph shape regardless of which agency issued them. NOT
# applied to legislative resolutions (res_senado/camara/congresso), which have
# registered profiles and reach the parser via the RULES engine (det.agency is
# None there).
#
# Each entry mirrors the corresponding native Scala profile in
# DocumentProfile.scala (ResolucaoProfile / PortariaProfile); keep them in sync.
# The native profile is registered under a single fixed authority (e.g.
# "federal" for Portaria), so `-a federal -t portaria` works with no flags, but
# the per-agency authority URN misses the registry and falls back to Lei — hence
# these overrides carry the rules onto that authority at runtime.
#
# Regexes here are matched against the parser's normalized text (NFD,
# diacritics stripped, lowercased), so they are written lowercase and without
# accents — e.g. `^observacao` matches "Observação:".
#
# - pos-epigrafe skips website-export boilerplate that sits between the
#   epigraph and the ementa, AND editorial annotations interleaved with the
#   ementa itself ("Prazos ... prorrogados conforme o Acórdão ...", "Observação:
#   Este texto não substitui ..."). ProjetoLei.fromBlocks filters every
#   pos-epigrafe match out of the ementa region, not just the leading ones, so
#   an annotation following the real ementa sentence is also dropped. Harmless
#   on docs without those lines (no match → no skip).
# - preambulo recognizes the issuer's opener — for resolucao the regulatory-
#   agency "O CONSELHO DIRETOR DA <agency>, ...", for portaria the ministerial
#   "O MINISTRO DE ESTADO ..." / "A MINISTRA DE ESTADO ...". The default profile
#   only knows the legislative openers ("O Congresso Nacional ...", "O Presidente
#   da República ..."). Kept broad so they match every issuer of that kind
#   (Anatel/ANPD/ANEEL... for resolucao; any ministry for portaria).
# - portaria pos-epigrafe skips the Diário Oficial da União header lines
#   ("Diário Oficial da União", "Publicado em: ...", "Órgão: ...", "Edição ...",
#   "Seção ...") that precede the epigraph and the ementa.
#
# Override semantics gotcha: DocumentProfileOverride REPLACES (does not append
# to) the base list, so each value must be self-contained — e.g. the
# epigrafe-continuacao must list both `^<tipo>` and `^n[oº°˚]` since the Lei
# base's `^(n[oº°˚]|complementar)` continuation is lost when overridden.
PROFILE_OVERRIDES: dict[str, list[str]] = {
    "resolucao": [
        "--prof-regex-epigrafe", "^resolucao",
        "--prof-regex-epigrafe-continuacao", r"^resolucao%^n[oº°˚]",
        "--prof-regex-pos-epigrafe", r"^publicado:%^left\d%^acessos:%^prazos%^observacao",
        "--prof-regex-preambulo", "^o conselho diretor",
        "--prof-epigrafe-head", "RESOLUÇÃO",
    ],
    "portaria": [
        "--prof-regex-epigrafe", "^portaria",
        "--prof-regex-epigrafe-continuacao", r"^portaria%^n[oº°˚]",
        "--prof-regex-pos-epigrafe", r"^diario oficial%^publicado em%^orgao:%^edicao%^secao",
        "--prof-regex-preambulo", r"^o ministro de estado%^a ministra de estado",
        "--prof-epigrafe-head", "PORTARIA",
    ],
}


def load_agency_authority_map(path: Path, required: bool) -> dict[str, str]:
    """Load the agency-acronym → authority-URN-fragment map from JSON.

    Keys are accent-folded and lowercased to match `tokenize()` output. When
    `required` is False (the default file) a missing file yields an empty map so
    the script still runs; when True (an explicit --agency-authority-map) a
    missing file is a hard error handled by the caller.
    """
    if not path.exists():
        if required:
            raise FileNotFoundError(path)
        return {}
    raw = json.loads(path.read_text(encoding="utf-8"))
    return {strip_accents(k).lower(): str(v) for k, v in raw.items()}


@dataclass
class Detection:
    autoridade: str | None = None
    tipo_norma: str | None = None
    numero: str | None = None
    data: str | None = None  # YYYY-MM-DD
    ano: str | None = None   # YYYY
    agency: str | None = None  # set for agency filenames (`res_<agency>_*`, `portaria_<agency>_*`)
    skip_reason: str | None = None


@dataclass
class Outcome:
    path: Path
    status: str          # "converted" | "skipped" | "failed"
    detection: Detection
    detail: str = ""
    stderr_tail: str = ""


PT_MONTHS = {
    "janeiro": 1, "fevereiro": 2, "marco": 3, "abril": 4,
    "maio": 5, "junho": 6, "julho": 7, "agosto": 8,
    "setembro": 9, "outubro": 10, "novembro": 11, "dezembro": 12,
}

# Epígrafe line, e.g. "DECRETO Nº 2.338, DE 7 DE OUTUBRO DE 1997." or
# "LEI Nº 4.117, DE 27 DE AGOSTO DE 1962". Number may have thousands dots.
# The day may carry an ordinal marker ("1º" → "1o" after accent-folding).
# Anchored at start of string (with optional leading whitespace) so that
# ementa lines mentioning other laws don't produce false matches.
# Accent-folded, lowercased, NBSP-normalized input is expected.
#
# Regulatory-agency resolutions carry an agency segment between the type
# keyword and "nº", e.g. "RESOLUÇÃO CD/ANPD Nº 4, DE 24 DE FEVEREIRO DE 2023".
# The optional `(?:\s+[a-z][a-z./-]*)?` group absorbs that single short token
# (letters plus `. / -`); it is non-capturing and bounded so it cannot swallow
# ementa text.
_EPIGRAFE_RE = re.compile(
    r"^\s*"
    r"(?:lei\s+complementar|lei\s+delegada|"
    r"decreto[-\s]lei|decreto[-\s]legislativo|"
    r"medida\s+provisoria|emenda\s+constitucional|"
    r"constituicao|resolucao|portaria|lei|decreto)"
    r"(?:\s+[a-z][a-z./-]*)?"
    r"\s*n[o°º]?\s*"
    r"([\d\.]+)"
    r"[^0-9a-z]+de\s+(\d{1,2})[oa°º]?\s+de\s+([a-z]+)\s+de\s+(\d{4})"
)


def _docx_first_paragraphs(docx: Path, limit: int = 8) -> list[str]:
    """Return the first `limit` non-empty paragraph texts from a DOCX."""
    ns = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main"}
    out: list[str] = []
    try:
        with zipfile.ZipFile(docx) as z:
            with z.open("word/document.xml") as f:
                root = ET.parse(f).getroot()
    except (zipfile.BadZipFile, KeyError, ET.ParseError, OSError):
        return out
    for p in root.iterfind(".//w:p", ns):
        text = "".join(t.text or "" for t in p.iterfind(".//w:t", ns)).strip()
        if text:
            out.append(text)
            if len(out) >= limit:
                break
    return out


def parse_docx_metadata(docx: Path) -> tuple[str | None, str | None]:
    """Extract (numero, data_YYYY-MM-DD) from the epígrafe in the first
    few paragraphs of a DOCX. Returns (None, None) if no match."""
    paragraphs = _docx_first_paragraphs(docx)
    for raw in paragraphs:
        folded = strip_accents(raw.replace("\xa0", " ")).lower()
        m = _EPIGRAFE_RE.search(folded)
        if not m:
            continue
        numero = m.group(1).replace(".", "").lstrip("0") or "0"
        day = int(m.group(2))
        month = PT_MONTHS.get(m.group(3))
        year = int(m.group(4))
        if month is None:
            return numero, None
        if not (1 <= day <= 31 and 1000 <= year <= 2999):
            return numero, None
        return numero, f"{year:04d}-{month:02d}-{day:02d}"
    return None, None


def strip_accents(s: str) -> str:
    return "".join(
        c for c in unicodedata.normalize("NFKD", s) if not unicodedata.combining(c)
    )


def tokenize(stem: str) -> list[str]:
    folded = strip_accents(stem).lower()
    return [t for t in re.split(r"[\s_\-]+", folded) if t]


def detect(stem: str, agency_map: dict[str, str] | None = None) -> Detection:
    agency_map = agency_map or {}
    tokens = tokenize(stem)
    if not tokens:
        return Detection(skip_reason="Empty filename")

    # Agency special case: `<prefix>_<agency>_...` where the leading token
    # selects the tipoNorma (AGENCY_PREFIX_TIPONORMA: res -> resolucao,
    # portaria -> portaria) and `<agency>` is mapped to its LexML authority URN
    # fragment via `agency_map`. The doc is then routed through the (Lei)
    # fallback profile + PROFILE_OVERRIDES[tipoNorma]. An agency missing from the
    # map is skipped rather than mislabelled "federal". For `res`, the
    # legislative variants (res_senado / res_camara / res_congresso) are NOT
    # agencies and fall through to the general RULES engine below.
    prefix_tipo = AGENCY_PREFIX_TIPONORMA.get(tokens[0])
    if (prefix_tipo is not None
            and len(tokens) >= 2
            and not (tokens[0] == "res" and tokens[1] in AGENCY_LEGISLATIVE_TOKENS)):
        agency = tokens[1]
        autoridade = agency_map.get(agency)
        if autoridade is None:
            return Detection(
                agency=agency,
                skip_reason=f"Unmapped agency acronym: {agency} "
                            f"(add it to the agency-authority map)",
            )
        det = Detection(
            autoridade=autoridade,
            tipo_norma=prefix_tipo,
            agency=agency,
        )
        # Skip the `<prefix>` + `<agency>` tokens when scanning for numero/ano/data.
        matched_len = 2
        for t in tokens[matched_len:]:
            if not t.isdigit():
                continue
            if len(t) == 8 and det.data is None:
                y, m, d = t[:4], t[4:6], t[6:8]
                if "1000" <= y <= "2999" and "01" <= m <= "12" and "01" <= d <= "31":
                    det.data = f"{y}-{m}-{d}"
                    continue
            if len(t) == 4 and det.ano is None and "1000" <= t <= "2999":
                det.ano = t
                continue
            if det.numero is None:
                det.numero = t
        return det

    matched_len = 0
    autoridade: str | None = None
    tipo_norma: str | None = None
    for prefix, aut, tn in RULES:
        n = len(prefix)
        if n > len(tokens):
            continue
        if tuple(tokens[:n]) == prefix and n > matched_len:
            matched_len = n
            autoridade, tipo_norma = aut, tn

    if matched_len == 0:
        return Detection(skip_reason=f"Unrecognized document type prefix: {tokens[0]}")

    det = Detection(autoridade=autoridade, tipo_norma=tipo_norma)

    # Parse numero and date/year from the tokens *after* the matched prefix.
    # Walk them in order and assign the first 8-digit → data, first 4-digit → ano,
    # first shorter run-of-digits → numero (the legal-norm number).
    for t in tokens[matched_len:]:
        if not t.isdigit():
            continue
        if len(t) == 8 and det.data is None:
            # YYYYMMDD → YYYY-MM-DD. Validate loosely.
            y, m, d = t[:4], t[4:6], t[6:8]
            if "1000" <= y <= "2999" and "01" <= m <= "12" and "01" <= d <= "31":
                det.data = f"{y}-{m}-{d}"
                continue
        if len(t) == 4 and det.ano is None and "1000" <= t <= "2999":
            det.ano = t
            continue
        if det.numero is None:
            det.numero = t
    return det


def find_default_jar(repo_root: Path) -> Path | None:
    candidates = sorted(
        (repo_root / "target").glob("lexml-parser-projeto-lei-*-onejar.jar"),
        key=lambda p: p.name,
    )
    return candidates[-1] if candidates else None


def build_cli_args(jar: Path, docx: Path, out_xml: Path, err_log: Path,
                   det: Detection, linker: Path | None) -> list[str]:
    args = [
        "java", "-jar", str(jar), "parse",
        "-m", DOCX_MIME,
        "-i", str(docx),
        "-o", str(out_xml),
        "--write-errors-to-file", str(err_log),
        "-a", det.autoridade or "",
        "-t", det.tipo_norma or "",
    ]
    if det.numero:
        args += ["-n", det.numero]
    if det.data:
        args += ["--data", det.data]
    elif det.ano:
        args += ["--ano", det.ano]
    # Overrides apply only to agency resolutions (det.agency set), never to
    # legislative resolutions, which have registered profiles.
    if det.agency:
        args += PROFILE_OVERRIDES.get(det.tipo_norma or "", [])
    if linker is not None:
        args += ["--linker", str(linker)]
    return args


def process_file(docx: Path, out_dir: Path, jar: Path, dry_run: bool,
                 linker: Path | None, agency_map: dict[str, str]) -> Outcome:
    det = detect(docx.stem, agency_map)
    if det.skip_reason is not None:
        return Outcome(path=docx, status="skipped", detection=det,
                       detail=det.skip_reason)

    # Filename-derived numero/date are unreliable (the positional heuristic
    # swaps them when, e.g., a pre-1000 decree numero like 2338 sits next
    # to a 4-digit year like 1997). Prefer values read from the DOCX
    # epígrafe line, and fall back to filename only if extraction fails.
    content_numero, content_data = parse_docx_metadata(docx)
    if content_numero:
        det.numero = content_numero
    if content_data:
        det.data = content_data
        det.ano = None  # data supersedes ano

    out_xml = out_dir / f"{docx.stem}.xml"
    err_log = out_dir / f"{docx.stem}.err.log"
    cli = build_cli_args(jar, docx, out_xml, err_log, det, linker)

    if dry_run:
        print("DRY-RUN " + " ".join(cli))
        return Outcome(path=docx, status="converted", detection=det,
                       detail="dry-run")

    # Force a UTF-8 locale so the JVM can open filenames with non-ASCII
    # characters (e.g. "constituição_*"). Without this, Java raises
    # InvalidPathException: Malformed input or input contains unmappable
    # characters, because sun.jnu.encoding inherits the parent process
    # locale and falls back to ASCII under C/POSIX.
    env = {**os.environ, "LC_ALL": "C.UTF-8", "LANG": "C.UTF-8"}
    proc = subprocess.run(cli, capture_output=True, text=True, env=env)

    # Prepend the exact CLI used so users can inspect every parameter
    # passed to the parser. The parser's --write-errors-to-file flag
    # overwrites the file, so we rewrite it after the run.
    existing = err_log.read_text(encoding="utf-8", errors="replace") if err_log.exists() else ""
    header = f"# CMD: {shlex.join(cli)}\n# ---\n"
    body = existing
    if (proc.stderr or "").strip() and not existing:
        # Parser crashed before opening the err-file (e.g. path-decoding
        # failure); keep the captured stderr so nothing is lost.
        body = proc.stderr
    err_log.write_text(header + body, encoding="utf-8")

    if proc.returncode != 0 or not out_xml.exists() or out_xml.stat().st_size == 0:
        tail = (proc.stderr or "").strip().splitlines()[-10:]
        return Outcome(
            path=docx, status="failed", detection=det,
            detail=f"exit={proc.returncode}",
            stderr_tail="\n".join(tail),
        )
    return Outcome(path=docx, status="converted", detection=det)


def write_report(out_dir: Path, outcomes: list[Outcome], input_dir: Path) -> Path:
    report = out_dir / "skipped_report.txt"
    lines: list[str] = []
    for o in outcomes:
        if o.status == "converted":
            continue
        rel = o.path.relative_to(input_dir) if o.path.is_relative_to(input_dir) else o.path
        lines.append(f"FILE: {rel}")
        lines.append(f"REASON: {'skipped' if o.status == 'skipped' else 'parse-failed'}")
        lines.append(f"DETAIL: {o.detail}")
        if o.detection.autoridade and o.detection.tipo_norma:
            lines.append(
                f"INFERRED: autoridade={o.detection.autoridade}, "
                f"tipoNorma={o.detection.tipo_norma}"
            )
        if o.stderr_tail:
            lines.append("STDERR_TAIL:")
            for ln in o.stderr_tail.splitlines():
                lines.append(f"  {ln}")
        lines.append("")
    report.write_text("\n".join(lines), encoding="utf-8")
    return report


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("input_dir", type=Path)
    ap.add_argument("output_dir", type=Path)
    ap.add_argument("--jar", type=Path, default=None,
                    help="Path to the lexml-parser onejar. Defaults to the "
                         "highest-versioned jar under <repo>/target/.")
    ap.add_argument("--linker", type=Path, default=None,
                    help="Path to the linker executable (forwarded to the "
                         "parser's --linker flag). If omitted, the linker "
                         "is skipped.")
    ap.add_argument("--agency-authority-map", type=Path, default=None,
                    help="Path to a JSON file mapping regulatory-agency "
                         "acronyms (e.g. \"anpd\") to their LexML authority URN "
                         f"fragment. Defaults to {AGENCY_AUTHORITY_MAP_FILE.name} "
                         "next to this script. Agencies absent from the map are "
                         "skipped.")
    ap.add_argument("--recursive", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    if args.linker is not None and not args.linker.exists():
        print(f"error: --linker path not found: {args.linker}", file=sys.stderr)
        return 2

    # Explicit --agency-authority-map must exist; the default file may be absent
    # (then the map is empty and all agency resolutions are skipped).
    map_path = args.agency_authority_map or AGENCY_AUTHORITY_MAP_FILE
    map_required = args.agency_authority_map is not None
    try:
        agency_map = load_agency_authority_map(map_path, required=map_required)
    except FileNotFoundError:
        print(f"error: --agency-authority-map path not found: {map_path}",
              file=sys.stderr)
        return 2
    except (json.JSONDecodeError, OSError) as e:
        print(f"error: could not read agency-authority map {map_path}: {e}",
              file=sys.stderr)
        return 2

    if not args.input_dir.is_dir():
        print(f"error: input dir not found: {args.input_dir}", file=sys.stderr)
        return 2

    repo_root = Path(__file__).resolve().parent.parent
    jar = args.jar or find_default_jar(repo_root)
    if jar is None or not jar.exists():
        print("error: could not locate onejar. Build it with "
              "`mvn -Ponejar package` or pass --jar.", file=sys.stderr)
        return 2

    if not args.dry_run and shutil.which("java") is None:
        print("error: `java` not on PATH.", file=sys.stderr)
        return 2

    args.output_dir.mkdir(parents=True, exist_ok=True)

    pattern = "**/*.docx" if args.recursive else "*.docx"
    docx_files = sorted(p for p in args.input_dir.glob(pattern) if p.is_file())
    if not docx_files:
        print(f"no .docx files found in {args.input_dir}")
        return 0

    outcomes: list[Outcome] = []
    for docx in docx_files:
        o = process_file(docx, args.output_dir, jar, args.dry_run, args.linker,
                         agency_map)
        outcomes.append(o)
        tag = {"converted": "OK  ", "skipped": "SKIP", "failed": "FAIL"}[o.status]
        extra = ""
        if o.detection.autoridade and o.detection.tipo_norma:
            extra = f"  [{o.detection.autoridade} / {o.detection.tipo_norma}]"
        elif o.detail:
            extra = f"  ({o.detail})"
        print(f"{tag} {docx.name}{extra}")

    report_path = write_report(args.output_dir, outcomes, args.input_dir)
    converted = sum(1 for o in outcomes if o.status == "converted")
    skipped   = sum(1 for o in outcomes if o.status == "skipped")
    failed    = sum(1 for o in outcomes if o.status == "failed")
    print(f"\nconverted={converted} skipped={skipped} failed={failed}")
    print(f"report: {report_path}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
