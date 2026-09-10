#!/usr/bin/env bash
# Summarise test counts across both modules.
cd "$(dirname "$0")/.."
python3 - <<'PY'
import glob, re
total = fails = 0
for pattern in ('core/build/test-results/test/*.xml',
                'app/build/test-results/testDebugUnitTest/*.xml'):
    for f in glob.glob(pattern):
        m = re.search(r'tests="(\d+)".*?failures="(\d+)".*?errors="(\d+)"', open(f).read())
        if m:
            total += int(m.group(1))
            fails += int(m.group(2)) + int(m.group(3))
print(f"TOTAL tests={total} failures={fails}")
PY
