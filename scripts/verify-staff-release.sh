#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 1 || ! -f "$1" || "$1" != *.jar ]]; then
    echo "Usage: $0 /absolute/path/to/exact-staff-candidate.jar" >&2
    exit 2
fi
candidate="$(realpath -- "$1")"
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
exec "$repo_dir/gradlew" -p "$repo_dir" --no-daemon --max-workers=2 check "-PstaffCandidateJar=$candidate"
