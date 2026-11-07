#!/usr/bin/env bash
# Run on pull requests with full history and tags available.
set -euo pipefail
base=${1:?Usage: check-chart-versions.sh BASE_COMMIT}
for chart in charts/*/Chart.yaml; do
  dir=${chart%/Chart.yaml}
  if ! git diff --name-only "$base"...HEAD -- "$dir" | grep -q .; then
    continue
  fi
  name=$(awk '$1 == "name:" {print $2; exit}' "$chart")
  version=$(awk '$1 == "version:" {print $2; exit}' "$chart" | tr -d '"'"'"'')
  if [[ -z "$name" || -z "$version" ]]; then
    echo "::error file=$chart::Chart name and version are required"
    exit 1
  fi
  if git cat-file -e "$base:$chart" 2>/dev/null; then
    previous=$(git show "$base:$chart" | awk '$1 == "version:" {print $2; exit}' | tr -d '"'"'"'')
    if [[ "$version" == "$previous" ]]; then
      echo "::error file=$chart::Changes to $dir require a new chart version (currently $version)"
      exit 1
    fi
  fi
  if git show-ref --verify --quiet "refs/tags/$name-$version"; then
    echo "::error file=$chart::Tag $name-$version already exists; choose an unpublished version"
    exit 1
  fi
  echo "$dir: $version is new and unpublished"
done
