#!/usr/bin/env bash
# -e：任一子命令失败立即退出，避免"测试挂了但产物仍被判定合格"
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${OUT:-$ROOT/performance/results/build-qualification.json}"
mkdir -p "$(dirname "$OUT")"
qualified=true
reasons=()
if ! command -v mvn >/dev/null 2>&1; then qualified=false; reasons+=("maven unavailable")
else
  (cd "$ROOT/backend" && mvn -B -DskipTests=false test) || { qualified=false; reasons+=("maven tests failed"); }
fi
python3 - "$OUT" "$qualified" "${reasons[*]:-}" <<'PY'
import json,sys,datetime
out,qualified,reasons=sys.argv[1:]
obj={'qualified':qualified=='true','reasons':[reasons] if reasons else [],'timestamp':datetime.datetime.now(datetime.timezone.utc).isoformat()}
open(out,'w').write(json.dumps(obj,indent=2)); print(json.dumps(obj,indent=2))
PY
$qualified
