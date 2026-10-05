#!/usr/bin/env sh
# Downloads the Skoltech Anomaly Benchmark (SKAB) CSV files into datasets/skab/.
# Source: https://github.com/waico/SKAB (GPL-3.0). Data is not committed to this repository.
set -e
BASE=https://raw.githubusercontent.com/waico/SKAB/master
DIR=$(dirname "$0")/skab
mkdir -p "$DIR"
for f in anomaly-free/anomaly-free.csv \
  valve1/0.csv valve1/1.csv valve1/2.csv valve1/3.csv valve1/4.csv valve1/5.csv valve1/6.csv valve1/7.csv \
  valve1/8.csv valve1/9.csv valve1/10.csv valve1/11.csv valve1/12.csv valve1/13.csv valve1/14.csv valve1/15.csv \
  valve2/0.csv valve2/1.csv valve2/2.csv valve2/3.csv \
  other/1.csv other/2.csv other/3.csv other/4.csv other/5.csv other/6.csv other/7.csv other/8.csv \
  other/9.csv other/10.csv other/11.csv other/12.csv other/13.csv other/14.csv; do
  mkdir -p "$DIR/$(dirname $f)"
  curl -sfL "$BASE/data/$f" -o "$DIR/$f"
done
curl -sfL "$BASE/LICENSE" -o "$DIR/LICENSE"
( cd "$DIR" && find . -name '*.csv' | sort | xargs sha256sum ) > "$(dirname "$0")/skab.sha256"
echo "SKAB downloaded to $DIR"
