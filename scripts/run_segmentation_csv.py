#!/usr/bin/env python3
"""Batch-apply GeraCSVporArtigoPorAgrupador.xsl to a folder of LexML documents.

Runs the XSLT 3.0 stylesheet against every LexML ``.xml`` file in an input
folder and writes the resulting CSV(s) into an output folder. A "document" can
span a main file plus ``.anexoN`` siblings sharing a stem; such families are
grouped into a per-stem subfolder, while standalone documents get a flat CSV.

The stylesheet is XSLT 3.0 and emits plain-text CSV. Python's lxml only speaks
XSLT 1.0, so this script drives Saxon via the ``saxonche`` package
(``pip install saxonche``).

Example:
    python3 scripts/run_segmentation_csv.py \\
        --input-dir  ../novas_normas_20260420/lexml_manual_20260524 \\
        --output-dir ../novas_normas_20260420/lexml_manual_20260524/segmentation
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from datetime import datetime
from pathlib import Path

try:
    from saxonche import PySaxonProcessor
except ImportError:
    sys.stderr.write(
        "ERROR: the 'saxonche' package is required to run the XSLT 3.0 stylesheet.\n"
        "       Install it with:  pip install saxonche\n"
    )
    sys.exit(2)

# Filenames that live in the input folder but are not LexML documents.
EXCLUDE_SUFFIXES = (".err.log", ".log", ".txt")
ANEXO_RE = re.compile(r"\.anexo\d+$", re.IGNORECASE)
LEXML_NS = "http://www.lexml.gov.br/1.0"

DEFAULT_XSL = Path(__file__).resolve().parent / "GeraCSVporArtigoPorAgrupador.xsl"


def parse_args(argv=None) -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="Apply GeraCSVporArtigoPorAgrupador.xsl to every LexML XML in a folder.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    p.add_argument("--input-dir", required=True, type=Path,
                   help="Folder containing LexML .xml documents.")
    p.add_argument("--output-dir", required=True, type=Path,
                   help="Destination folder for the CSVs (created if missing).")
    p.add_argument("--xsl", type=Path, default=DEFAULT_XSL,
                   help="Path to the XSLT 3.0 stylesheet.")
    p.add_argument("--report", default="segmentation_report",
                   help="Report basename (.json and .md) written into --output-dir.")
    p.add_argument("--recursive", action="store_true",
                   help="Recurse into subfolders of --input-dir (default: top level only).")
    p.add_argument("--overwrite", action="store_true",
                   help="Overwrite existing CSVs (default: skip and mark as 'skipped').")
    return p.parse_args(argv)


def discover_inputs(input_dir: Path, recursive: bool) -> list[Path]:
    """Return LexML .xml candidates, excluding logs and the skipped report."""
    globber = input_dir.rglob if recursive else input_dir.glob
    out = []
    for path in sorted(globber("*.xml")):
        if not path.is_file():
            continue
        name = path.name.lower()
        if any(name.endswith(sfx) for sfx in EXCLUDE_SUFFIXES):
            continue
        out.append(path)
    return out


def stem_of(path: Path) -> str:
    """Strip '.xml' and a trailing '.anexoN' segment to get the family stem."""
    base = path.name[:-4] if path.name.lower().endswith(".xml") else path.name
    return ANEXO_RE.sub("", base)


def group_by_stem(paths: list[Path]) -> dict[str, list[Path]]:
    groups: dict[str, list[Path]] = {}
    for p in paths:
        groups.setdefault(stem_of(p), []).append(p)
    for members in groups.values():
        members.sort()
    return dict(sorted(groups.items()))


def is_lexml(path: Path) -> bool:
    """Cheap well-formedness + root check without a full parse of huge files."""
    from xml.etree import ElementTree as ET
    try:
        for _event, elem in ET.iterparse(path, events=("start",)):
            tag = elem.tag
            local = tag.split("}", 1)[-1] if "}" in tag else tag
            ns = tag[1:].split("}", 1)[0] if tag.startswith("{") else ""
            return local == "LexML" and (ns == LEXML_NS or ns == "")
    except ET.ParseError:
        return False
    return False


def count_rows(csv_text: str) -> int:
    """Data rows = non-empty lines minus the header line."""
    lines = [ln for ln in csv_text.splitlines() if ln.strip()]
    return max(len(lines) - 1, 0)


def output_path(out_dir: Path, stem: str, member: Path, multi: bool) -> Path:
    """Flat CSV for standalone docs; per-stem subfolder for families."""
    base = member.name[:-4] if member.name.lower().endswith(".xml") else member.name
    if multi:
        return out_dir / stem / f"{base}.csv"
    return out_dir / f"{base}.csv"


def main(argv=None) -> int:
    args = parse_args(argv)
    started = time.time()

    if not args.input_dir.is_dir():
        sys.stderr.write(f"ERROR: input dir not found: {args.input_dir}\n")
        return 2
    if not args.xsl.is_file():
        sys.stderr.write(f"ERROR: stylesheet not found: {args.xsl}\n")
        return 2

    args.output_dir.mkdir(parents=True, exist_ok=True)

    inputs = discover_inputs(args.input_dir, args.recursive)
    groups = group_by_stem(inputs)
    records: list[dict] = []

    with PySaxonProcessor(license=False) as proc:
        xslt = proc.new_xslt30_processor()
        executable = xslt.compile_stylesheet(stylesheet_file=str(args.xsl))
        if executable is None or xslt.exception_occurred:
            sys.stderr.write(f"ERROR: failed to compile stylesheet: {xslt.error_message}\n")
            return 2

        for stem, members in groups.items():
            multi = len(members) > 1
            for src in members:
                rec = {"source": str(src), "stem": stem, "status": None,
                       "output": None, "rows": None, "duration_ms": None, "error": None}
                t0 = time.time()

                if not is_lexml(src):
                    rec["status"] = "failure"
                    rec["error"] = "not a well-formed LexML document"
                    rec["duration_ms"] = round((time.time() - t0) * 1000, 1)
                    records.append(rec)
                    continue

                out = output_path(args.output_dir, stem, src, multi)
                rec["output"] = str(out)

                if out.exists() and not args.overwrite:
                    rec["status"] = "skipped"
                    rec["error"] = "output exists (use --overwrite)"
                    rec["duration_ms"] = round((time.time() - t0) * 1000, 1)
                    records.append(rec)
                    continue

                try:
                    result = executable.transform_to_string(source_file=str(src))
                    if executable.exception_occurred or result is None:
                        raise RuntimeError(executable.error_message or "unknown Saxon error")
                    out.parent.mkdir(parents=True, exist_ok=True)
                    out.write_text(result, encoding="utf-8")
                    rec["status"] = "success"
                    rec["rows"] = count_rows(result)
                except Exception as exc:  # noqa: BLE001 - record any transform failure
                    rec["status"] = "failure"
                    rec["error"] = str(exc).strip()[:1000]

                rec["duration_ms"] = round((time.time() - t0) * 1000, 1)
                records.append(rec)

    elapsed = time.time() - started
    summary = build_summary(records, args, elapsed)
    write_reports(args.output_dir, args.report, summary, records)
    print_stdout(summary, records)

    return 0 if summary["failure"] == 0 else 1


def build_summary(records: list[dict], args: argparse.Namespace, elapsed: float) -> dict:
    success = sum(1 for r in records if r["status"] == "success")
    failure = sum(1 for r in records if r["status"] == "failure")
    skipped = sum(1 for r in records if r["status"] == "skipped")
    total = len(records)
    rows = sum(r["rows"] or 0 for r in records if r["status"] == "success")
    rate = (success / total * 100) if total else 0.0
    return {
        "timestamp": datetime.now().isoformat(timespec="seconds"),
        "input_dir": str(args.input_dir),
        "output_dir": str(args.output_dir),
        "stylesheet": str(args.xsl),
        "engine": "saxonche / SaxonC-HE",
        "saxon_version": _saxon_version(),
        "total_documents": total,
        "success": success,
        "failure": failure,
        "skipped": skipped,
        "success_rate_pct": round(rate, 1),
        "total_rows": rows,
        "wall_clock_s": round(elapsed, 2),
    }


def _saxon_version() -> str:
    try:
        with PySaxonProcessor(license=False) as proc:
            return proc.version
    except Exception:  # noqa: BLE001
        return "unknown"


def write_reports(out_dir: Path, basename: str, summary: dict, records: list[dict]) -> None:
    (out_dir / f"{basename}.json").write_text(
        json.dumps({"summary": summary, "documents": records}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    lines = ["# Segmentation CSV processing report", ""]
    lines.append(f"- Generated: {summary['timestamp']}")
    lines.append(f"- Input: `{summary['input_dir']}`")
    lines.append(f"- Output: `{summary['output_dir']}`")
    lines.append(f"- Stylesheet: `{summary['stylesheet']}`")
    lines.append(f"- Engine: {summary['engine']} (Saxon {summary['saxon_version']})")
    lines.append("")
    lines.append("## Summary")
    lines.append("")
    lines.append(f"- Documents: **{summary['total_documents']}**")
    lines.append(f"- Success: **{summary['success']}**  |  "
                 f"Failure: **{summary['failure']}**  |  Skipped: **{summary['skipped']}**")
    lines.append(f"- Success rate: **{summary['success_rate_pct']}%**")
    lines.append(f"- Total CSV data rows: {summary['total_rows']}")
    lines.append(f"- Wall-clock: {summary['wall_clock_s']} s")
    lines.append("")
    lines.append("## Per-document detail")
    lines.append("")
    lines.append("| Source | Status | Rows | Time (ms) | Output / Error |")
    lines.append("|---|---|---|---|---|")
    for r in records:
        src = Path(r["source"]).name
        rows = "" if r["rows"] is None else str(r["rows"])
        detail = r["output"] if r["status"] == "success" else (r["error"] or "")
        if r["status"] == "success" and r["output"]:
            detail = Path(r["output"]).relative_to(out_dir) if _under(out_dir, r["output"]) else r["output"]
        lines.append(f"| {src} | {r['status']} | {rows} | {r['duration_ms']} | {detail} |")
    (out_dir / f"{basename}.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def _under(out_dir: Path, p) -> bool:
    try:
        Path(p).relative_to(out_dir)
        return True
    except ValueError:
        return False


def print_stdout(summary: dict, records: list[dict]) -> None:
    for r in records:
        src = Path(r["source"]).name
        mark = {"success": "OK ", "failure": "FAIL", "skipped": "skip"}.get(r["status"], "?")
        extra = "" if r["status"] != "failure" else f"  <- {r['error']}"
        rows = "" if r["rows"] is None else f"{r['rows']} rows"
        print(f"  [{mark}] {src:40s} {rows}{extra}")
    print()
    print(f"Documents: {summary['total_documents']}  "
          f"success: {summary['success']}  failure: {summary['failure']}  "
          f"skipped: {summary['skipped']}  "
          f"({summary['success_rate_pct']}% success, {summary['total_rows']} rows, "
          f"{summary['wall_clock_s']}s)")


if __name__ == "__main__":
    sys.exit(main())
