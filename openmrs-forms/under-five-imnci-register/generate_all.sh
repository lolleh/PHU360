#!/usr/bin/env bash
# Regenerate the Under Five IMNCI register from its specification, then check it.
#
# The register is published and filling encounters, so the order matters: the form
# and the concepts CSV both name concepts by uuid, and those uuids are pinned in
# concept-uuids.json rather than derived at generation time. Concepts are settled
# first, then the CSV that loads them, then the form that records against them.
#
#   ./generate_all.sh          regenerate and verify
#   ./generate_all.sh --check  verify only, regenerate nothing
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

if [[ "${1:-}" != "--check" ]]; then
  echo "==> Concepts and their pinned uuids"
  python3 register_concepts.py

  echo "==> Concepts CSV"
  python3 generate_concepts_csv.py

  echo "==> HTML form"
  python3 generate_form.py
fi

echo "==> Verifying"
python3 verify_source_coverage.py
python3 verify_report_mapping.py
python3 verify_form.py

echo
echo "Under Five IMNCI register is consistent."
echo "New concepts in this generation:"
python3 - <<'PY'
import json
uuids = json.load(open("concept-uuids.json"))
spec = {c["name"] for c in json.load(open("concepts-to-create.json"))}
missing = [n for n in sorted(uuids) if n not in spec]
print("    (none - concept-uuids.json is complete)" if not missing
      else "".join(f"    {n}\n" for n in missing))
PY