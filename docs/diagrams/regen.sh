#!/usr/bin/env bash
# Regenerate PNG diagrams from .mmd source files using the official
# Mermaid CLI (@mermaid-js/mermaid-cli, "mmdc").
#
# Requires node + npm on PATH. First run downloads Puppeteer's Chromium
# (~150MB, cached under ~/.cache/puppeteer).
#
# mmgo (https://github.com/superpowers-dev/mmgo) is a faster Go-based
# renderer but its edge-label / subgraph layout is still in development
# and produces overlapping labels for the diagrams we use here. Stick
# with mmdc for committed output. To preview locally with mmgo:
#   MMGO=/Users/maoyuankao/src/mmgo/bin/mmgo ./regen.sh
set -euo pipefail

cd "$(dirname "$0")"

SCALE="${SCALE:-2}"
BG="${BG:-white}"

if [[ -n "${MMGO:-}" ]]; then
    if [[ ! -x "$MMGO" ]]; then
        echo "MMGO=$MMGO is not executable" >&2; exit 1
    fi
    SVG2PNG="${SVG2PNG:-$HOME/.claude/bin/svg2png.swift}"
    shopt -s nullglob
    for src in *.mmd; do
        base="${src%.mmd}"
        echo "[mmgo] $src → $base.png (preview only; quality is poor)"
        "$MMGO" -i "$src" -o "$base.svg"
        swift "$SVG2PNG" "$base.svg" "$base.png" "$SCALE"
        rm -f "$base.svg"
    done
    exit 0
fi

if ! command -v node >/dev/null; then
    echo "node not found on PATH" >&2; exit 1
fi

shopt -s nullglob
for src in *.mmd; do
    base="${src%.mmd}"
    echo "[mmdc] $src → $base.png"
    npx -y -p @mermaid-js/mermaid-cli mmdc \
        -i "$src" \
        -o "$base.png" \
        -s "$SCALE" \
        -b "$BG" \
        --quiet
done
echo "Done."
