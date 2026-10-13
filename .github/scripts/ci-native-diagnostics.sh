#!/usr/bin/env bash
# Native crash diagnostics for the Linux/JDK 8 CI jobs. Run via bash.
set -euo pipefail

diagnostics_dir=${CI_NATIVE_DIAGNOSTICS_DIR:?CI_NATIVE_DIAGNOSTICS_DIR is required}
# Only this host sysctl is bind-mounted into the disposable GitHub-hosted job.
pattern_file=${CI_CORE_PATTERN_FILE:-/ci-core-pattern}

restore_pattern() {
  if [[ -f "$diagnostics_dir/core-pattern.before" ]]; then
    cat "$diagnostics_dir/core-pattern.before" > "$pattern_file"
  fi
}

prepare() {
  umask 077
  mkdir -p "$diagnostics_dir"/{cores,jni,reports}
  local previous_pattern
  previous_pattern=$(cat "$pattern_file")
  printf '%s\n' "$previous_pattern" > "$diagnostics_dir/core-pattern.before"
  local pattern="$diagnostics_dir/cores/core.%e.%p.%t"
  if (( ${#pattern} > 127 )); then
    echo '::error::The core dump path exceeds the kernel core_pattern limit.'
    return 1
  fi
  printf '%s\n' "$pattern" > "$pattern_file"
  {
    uname -a
    cat /etc/os-release
    java -version 2>&1
    readlink -f "$(command -v java)"
    gdb --version
    ulimit -a
    cat /proc/sys/kernel/core_pattern
  } > "$diagnostics_dir/reports/environment.txt"

  # Verify actual abort -> core -> native frames, not just a writable setting.
  cat > "$diagnostics_dir/core-probe.c" <<'EOF'
#include <stdlib.h>
void native_crash_probe(void) { abort(); }
int main(void) { native_crash_probe(); }
EOF
  cc -g -O0 -o "$diagnostics_dir/core-probe" "$diagnostics_dir/core-probe.c"
  local status=0
  "$diagnostics_dir/core-probe" > "$diagnostics_dir/reports/probe.txt" 2>&1 || status=$?
  if [[ $status != 134 ]]; then
    echo "::error::Native diagnostic probe exited with $status instead of 134."
    return 1
  fi
  local cores=()
  shopt -s nullglob
  cores=("$diagnostics_dir"/cores/core.*)
  if (( ${#cores[@]} != 1 )); then
    echo '::error::Native diagnostic probe did not produce exactly one core dump.'
    return 1
  fi
  gdb -nx -batch -iex 'set auto-load off' \
    -ex 'set pagination off' -ex 'bt' \
    "$diagnostics_dir/core-probe" "${cores[0]}" \
    >> "$diagnostics_dir/reports/probe.txt" 2>&1
  if ! grep -q '#[0-9].*native_crash_probe' "$diagnostics_dir/reports/probe.txt"; then
    echo '::error::GDB could not recover the native diagnostic probe stack.'
    return 1
  fi
  rm -- "${cores[0]}"
  touch "$diagnostics_dir/ready"
  echo 'Verified native abort, core dump creation, and GDB backtrace.'
}

run_with_deadline() {
  # A shared deadline covers build, smoke tests, RocksDB tests and coverage.
  # It starts before dependency installation, leaving time in the 60-minute job
  # for the bounded collector and artifact upload.
  local remaining=$(( ${CI_BUILD_DEADLINE:?CI_BUILD_DEADLINE is required} - $(date +%s) ))
  if (( remaining <= 0 )); then
    echo '::error::CI build budget exhausted; reserving time for diagnostics.'
    return 124
  fi
  timeout --signal=TERM --kill-after=15s "${remaining}s" "$@"
}

collect() {
  mkdir -p "$diagnostics_dir/reports"
  # Restore the host setting even when setup failed, before parsing any cores.
  restore_pattern
  if [[ ! -f "$diagnostics_dir/ready" ]]; then
    echo 'Native diagnostics setup did not complete.'
    return 0
  fi
  local cores=()
  shopt -s nullglob
  cores=("$diagnostics_dir"/cores/core.*)
  if (( ${#cores[@]} == 0 )); then
    echo 'No native core dumps found.'
    return 0
  fi
  # Upload evidence even if Gradle retries have masked a worker crash.
  echo 'has-cores=true' >> "${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}"
  printf '%s\n' "${cores[@]}" > "$diagnostics_dir/reports/cores.txt"
  # One timeout covers ALL parsing and library copying, not 120s per core.
  local status=0
  timeout --signal=TERM --kill-after=5s 120s bash "$0" parse || status=$?
  echo "Native diagnostics collector exit status: $status" \
    >> "$diagnostics_dir/reports/collector.txt"
  if (( status != 0 )); then
    echo "::warning::Native diagnostics collection ended with status $status; preserving partial reports."
  fi
}

parse_cores() {
  # Save the libraries first so a slow GDB cannot consume their time budget.
  preserve_libraries
  local java_binary core name count=0 status
  java_binary=$(readlink -f "$(command -v java)")
  shopt -s nullglob
  for core in "$diagnostics_dir"/cores/core.*; do
    (( count += 1 ))
    if (( count > 2 )); then
      echo 'Only the first two cores are parsed.' >> "$diagnostics_dir/reports/collector.txt"
      break
    fi
    name=$(basename "$core")
    status=0
    # Bound each report to 16 MiB; do not run GDB while the tests are executing.
    (
      ulimit -f 16384
      gdb -nx -batch -iex 'set auto-load off' \
        -ex 'set pagination off' -ex 'set print thread-events off' \
        -ex 'info files' -ex 'info sharedlibrary' -ex 'info proc mappings' \
        -ex 'thread apply all bt 40' "$java_binary" "$core"
    ) > "$diagnostics_dir/reports/$name.gdb.txt" 2>&1 || status=$?
    echo "$name: gdb exit status $status" >> "$diagnostics_dir/reports/collector.txt"
  done
}

preserve_libraries() {
  # A crashed JVM skips deleteOnExit. Preserve its extracted JNI libraries with
  # checksums and build IDs; deduplicate across workers and cap payload at 128 MiB.
  local library size digest total=0
  mkdir -p "$diagnostics_dir/reports/libraries"
  while IFS= read -r -d '' library; do
    size=$(stat -c %s "$library")
    if (( size > 134217728 - total )); then
      printf 'Omitted (128 MiB budget): %s\n' "$library" >> "$diagnostics_dir/reports/libraries.txt"
      continue
    fi
    digest=$(sha256sum "$library")
    digest=${digest%% *}
    printf '%s  %s\n' "$digest" "$library" >> "$diagnostics_dir/reports/libraries.txt"
    if [[ ! -f "$diagnostics_dir/reports/libraries/$digest.so" ]]; then
      cp -- "$library" "$diagnostics_dir/reports/libraries/$digest.so"
      total=$(( total + size ))
      readelf -n "$library" >> "$diagnostics_dir/reports/libraries.txt" 2>&1 || true
    fi
  done < <(find "$diagnostics_dir/jni" -type f -name '*.so' -print0)
}

case "${1:-}" in
  prepare) prepare ;;
  run) shift; run_with_deadline "$@" ;;
  collect) collect ;;
  parse) parse_cores ;;
  *) echo 'Usage: ci-native-diagnostics.sh prepare|run COMMAND...|collect|parse' >&2; exit 2 ;;
esac
