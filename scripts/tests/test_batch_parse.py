"""Numbering detection in batch_parse.py (no jar needed).

See work_history/20260925_130918_numero_complemento_and_constituicao_epigrafe_fix.md.
Run from the repo root: `python3 -m pytest scripts/tests`.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import batch_parse as bp  # noqa: E402


def _epigrafe(text: str):
    return bp._EPIGRAFE_RE.search(bp.strip_accents(text).lower())


def test_detect_mp_with_complemento():
    det = bp.detect("mp_2228-1_20010906")
    assert (det.tipo_norma, det.numero, det.complemento, det.data, det.ano) == \
        ("medida.provisoria", "2228", "1", "2001-09-06", None)


def test_detect_mp_with_two_digit_complemento():
    det = bp.detect("mp_2158-35_20010824")
    assert (det.numero, det.complemento, det.data) == ("2158", "35", "2001-08-24")


def test_detect_plain_numbers_unchanged():
    det = bp.detect("lei_9250_19951226")
    assert (det.numero, det.complemento, det.data) == ("9250", None, "1995-12-26")


def test_detect_constituicao_has_no_number_from_filename():
    det = bp.detect("constituição_1988_19881005")
    assert (det.tipo_norma, det.numero, det.data) == ("constituicao", None, "1988-10-05")


def test_cli_passes_complemento():
    det = bp.detect("mp_2228-1_20010906")
    cli = bp.build_cli_args(Path("x.jar"), Path("in.docx"), Path("o.xml"), Path("e.log"), det, None)
    assert cli[cli.index("-n") + 1] == "2228"
    assert cli[cli.index("--complemento") + 1] == "1"


def test_epigrafe_regex_with_complemento():
    m = _epigrafe("MEDIDA PROVISÓRIA Nº 2.228-1, DE 6 DE SETEMBRO DE 2001")
    assert m and (m.group(1), m.group(2), m.group(3), m.group(4), m.group(5)) == \
        ("2.228", "1", "6", "setembro", "2001")


def test_epigrafe_regex_without_complemento():
    m = _epigrafe("DECRETO Nº 2.338, DE 7 DE OUTUBRO DE 1997.")
    assert m and (m.group(1), m.group(2), m.group(3)) == ("2.338", None, "7")


def test_epigrafe_regex_numeric_date():
    m = _epigrafe("INSTRUÇÃO NORMATIVA RFB Nº 1131, DE 21/02/2011")
    assert m and (m.group(1), m.group(6), m.group(7), m.group(8)) == ("1131", "21", "02", "2011")
