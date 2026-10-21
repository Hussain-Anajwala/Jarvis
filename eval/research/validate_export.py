"""Validate a JARVIS research export (JSON or CSV) against the documented schema (docs/TRD.md section 15).

Standard library only. Usage:
    python eval/research/validate_export.py jarvis-research.json [jarvis-research.csv]
Checks: schema and version, allow-listed fields only, value types, null/empty for unmeasured values, percent values
consistent with numerator/denominator, CSV header and column count, and agreement between the export and
app/src/main/assets/research/evidence.json. Prints a summary; exits 1 on the first problem.
"""
import csv
import io
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TOP = {"schema", "schema_version", "generated_at", "app_version", "evidence_schema_version", "evidence_commit",
       "evidence_generated_on", "contents", "source_types", "experiments", "device_records"}
EXPERIMENT = {"id", "title", "description", "category", "source_type", "source_type_label", "sources", "run_date",
              "sample_size", "status", "notes", "metrics"}
METRIC = {"id", "label", "value", "unit", "numerator", "denominator", "denominator_label", "definition", "text"}
DEVICE = {"source_type", "since", "plan_runs_by_status", "plan_steps_by_status", "privacy_decisions", "routing_targets"}
CSV_COLUMNS = ["schema_version", "generated_at", "evidence_commit", "experiment_id", "category", "source_type", "run_date",
               "sample_size", "status", "metric_id", "metric_label", "value", "unit", "numerator", "denominator",
               "denominator_label", "definition", "text", "sources"]
SOURCE_TYPES = {"RECORDED_BENCHMARK", "REPLAY", "SIMULATED", "TEST_RUN", "DEVICE_OBSERVATION", "ESTIMATE", "HISTORICAL", "ON_DEVICE_RECORD"}


def fail(msg):
    print("INVALID:", msg)
    sys.exit(1)


def evidence():
    with open(os.path.join(ROOT, "app", "src", "main", "assets", "research", "evidence.json"), encoding="utf-8") as f:
        e = json.load(f)
    return {(x["id"], m["id"]): m.get("value") for x in e["experiments"] for m in x["metrics"]}


def check_json(path, expected):
    with open(path, encoding="utf-8") as f:
        d = json.load(f)
    if set(d) != TOP:
        fail(f"top-level fields {sorted(set(d) ^ TOP)}")
    if d["schema"] != "jarvis.research.export" or d["schema_version"] != 1:
        fail("schema or version")
    n = 0
    for e in d["experiments"]:
        if set(e) != EXPERIMENT:
            fail(f"{e.get('id')}: fields {sorted(set(e) ^ EXPERIMENT)}")
        if e["source_type"] not in SOURCE_TYPES:
            fail(f"{e['id']}: source_type {e['source_type']}")
        for m in e["metrics"]:
            n += 1
            if set(m) != METRIC:
                fail(f"{e['id']}/{m.get('id')}: fields {sorted(set(m) ^ METRIC)}")
            v = m["value"]
            if v is not None and not isinstance(v, (int, float)):
                fail(f"{e['id']}/{m['id']}: value is not a number or null")
            if m["unit"] == "percent" and v is not None and m["denominator"]:
                if abs(v - 100.0 * m["numerator"] / m["denominator"]) > 0.05:
                    fail(f"{e['id']}/{m['id']}: {v} != {m['numerator']}/{m['denominator']}")
            if expected.get((e["id"], m["id"]), "missing") != v:
                fail(f"{e['id']}/{m['id']}: export {v} differs from evidence {expected.get((e['id'], m['id']))}")
    dev = d["device_records"]
    if dev is not None:
        if set(dev) != DEVICE:
            fail(f"device_records fields {sorted(set(dev) ^ DEVICE)}")
        for k in DEVICE - {"source_type", "since"}:
            if not all(isinstance(c, int) for c in dev[k].values()):
                fail(f"device_records.{k} holds something other than counts")
    print(f"JSON ok: {len(d['experiments'])} experiments, {n} metrics, device_records={'present' if dev else 'null'}")
    return d


def check_csv(path, expected):
    with open(path, encoding="utf-8", newline="") as f:
        rows = list(csv.reader(io.StringIO(f.read())))
    if rows[0] != CSV_COLUMNS:
        fail("CSV header")
    h = {c: i for i, c in enumerate(CSV_COLUMNS)}
    metrics = 0
    for r in rows[1:]:
        if len(r) != len(CSV_COLUMNS):
            fail(f"row with {len(r)} cells: {r[:4]}")
        if r[h["experiment_id"]] == "device_records":
            int(r[h["value"]])
            continue
        metrics += 1
        key = (r[h["experiment_id"]], r[h["metric_id"]])
        want = expected.get(key, "missing")
        got = r[h["value"]]
        if want is None and got != "":
            fail(f"{key}: unmeasured value written as {got!r}")
        if want not in (None, "missing") and abs(float(got) - want) > 1e-9:
            fail(f"{key}: CSV {got} differs from evidence {want}")
        for cell in r:
            if cell[:1] in ("=", "+", "@") or (cell[:1] == "-" and not _number(cell)):
                fail(f"{key}: unguarded formula-like cell {cell[:20]!r}")
    if metrics != len(expected):
        fail(f"CSV has {metrics} metric rows, evidence has {len(expected)}")
    print(f"CSV ok: {len(rows) - 1} rows ({metrics} metric rows), UTF-8, {len(CSV_COLUMNS)} columns")


def _number(s):
    try:
        float(s)
        return True
    except ValueError:
        return False


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    exp = evidence()
    for p in sys.argv[1:]:
        (check_json if p.endswith(".json") else check_csv)(p, exp)
