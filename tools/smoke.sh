#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

work="$(mktemp -d)"; mkdir -p "$work/bin"
cat >"$work/bin/python3" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$SMOKE_ARGV"
if [ "$2" = "build" ]; then
  out="dist"; shift 3 2>/dev/null
  mkdir -p dist
  printf 'wheel' > dist/pkg-1.0-py3-none-any.whl
  echo "Successfully built pkg-1.0-py3-none-any.whl"
  exit 0
fi
if [ "$2" = "twine" ]; then
  echo "Uploading using environment variables"
  echo "user=${TWINE_USERNAME:-unset}" >> "$SMOKE_ENV"
  exit 0
fi
exit 0
SH
chmod +x "$work/bin/python3"
export SMOKE_ARGV="$work/argv.txt" SMOKE_ENV="$work/env.txt"

dir="$work/build"; mkdir -p "$dir"; printf 'py' >"$dir/setup.py"
printf '{"python": "%s/bin/python3"}' "$work" >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "build failed: $(cat "$dir/output.json")"
[ "$(output "$dir" artifacts)" = "1" ] || fail "artifacts: $(cat "$dir/output.json")"
[ -f "$dir/dist/pkg-1.0-py3-none-any.whl" ] || fail "wheel missing"

dir="$work/upload"; mkdir -p "$dir"; printf 'py' >"$dir/setup.py"
printf '{"python": "%s/bin/python3", "upload": "true", "repository": "https://example.invalid/legacy/"}' "$work" >"$dir/input.json"
printf '{"pypiUser": "robot", "pypiPassword": "hunter2"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 || fail "upload failed: $(cat "$dir/output.json")"
grep -q "user=robot" "$work/env.txt" || fail "twine credentials must travel in the environment"
grep -q "hunter2" "$work/argv.txt" && fail "the password must not be in argv"

dir="$work/nouploadcreds"; mkdir -p "$dir"; printf 'py' >"$dir/setup.py"
printf '{"python": "%s/bin/python3", "upload": "true"}' "$work" >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "upload without credentials must fail"

echo "smoke ok: wheel build, twine via environment, missing credentials"
