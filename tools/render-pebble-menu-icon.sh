#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
src="$repo_root/app/resources/icons/billy_general.svg"
out="$repo_root/app/resources/icons/billy_general_menu.png"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

rsvg-convert -w 250 -h 250 "$src" -o "$tmp_dir/billy_general_250.png"
convert "$tmp_dir/billy_general_250.png" \
  -resize 25x25 \
  -alpha on \
  -channel A \
  -threshold 35% \
  +channel \
  "$out"

identify "$out"
