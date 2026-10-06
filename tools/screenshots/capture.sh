#!/usr/bin/env bash
# Runs each demo scene in the emulator and screenshots it.
# Usage: capture.sh <pbw> <out_dir> <platform...>
set -u
PBW="$1"; OUT="$2"; shift 2
SCENE_FILE="$(mktemp)"
python3 tools/screenshots/control.py "$SCENE_FILE" &
CONTROL_PID=$!
trap 'kill $CONTROL_PID 2>/dev/null; pebble kill 2>/dev/null' EXIT

# scene name, seconds to wait before the shot, extra shots "label:seconds" after it
SCENES=(
  "home 8"
  "photo 22"
  "email 14"
  "flight 14"
  "weather 14"
  "timer 14"
)

for PLATFORM in "$@"; do
  mkdir -p "$OUT/$PLATFORM"
  n=0
  for entry in "${SCENES[@]}"; do
    set -- $entry
    name="$1"; wait_s="$2"
    n=$((n + 1))
    echo "$name" > "$SCENE_FILE"
    echo "=== $PLATFORM: $name"
    for attempt in 1 2 3; do
      if timeout 240 pebble install --emulator "$PLATFORM" "$PBW"; then break; fi
      echo "install attempt $attempt failed"; sleep 5
    done
    if [ "$n" = 1 ]; then
      pebble emu-battery --emulator "$PLATFORM" --percent 80 || true
      pebble emu-bt-connection --emulator "$PLATFORM" --connected yes || true
    fi
    sleep "$wait_s"
    pebble screenshot --emulator "$PLATFORM" --no-open "$OUT/$PLATFORM/$(printf %02d $n)-$name.png" || echo "screenshot failed"
    # A later frame too, in case the answer was still arriving.
    sleep 6
    pebble screenshot --emulator "$PLATFORM" --no-open "$OUT/$PLATFORM/$(printf %02d $n)-$name-later.png" || true
  done
  pebble kill || true
done
