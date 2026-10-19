#!/usr/bin/env bash
set -euo pipefail
MODEL="depthanythingv2-vits-dynamic-quant.onnx"
DEST_DIR="$(dirname "$0")/../app/src/main/assets"
DEST="$DEST_DIR/$MODEL"
mkdir -p "$DEST_DIR"
# This model is a regular Git blob, not a Git LFS object.
URL="https://raw.githubusercontent.com/combatwombat/tiefling/main/site/public/models/$MODEL"
curl --fail --location --retry 3 --output "$DEST.tmp" "$URL"
test "$(wc -c < "$DEST.tmp")" -eq 27084785
# Check the Git blob hash against the upstream model.
test "$(git hash-object "$DEST.tmp")" = c23b48b3b6102815d83d5b17666bd72f9c62c20c
mv "$DEST.tmp" "$DEST"
echo "Verified depth model: $(wc -c < "$DEST") bytes"
