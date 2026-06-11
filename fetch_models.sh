#!/usr/bin/env bash
# Fetch the large, standard (pretrained) ONNX model that is NOT committed to the
# repository. Small in-house / openWakeWord models are already bundled under
# app/src/main/assets/. This script downloads only the heavy ECAPA-TDNN speaker
# verification model.
#
# Usage: ./fetch_models.sh
set -euo pipefail

ASSETS_DIR="app/src/main/assets"
mkdir -p "$ASSETS_DIR"

# ECAPA-TDNN speaker embedding model (SpeechBrain, pretrained, ~80 MB).
# Set ECAPA_TDNN_URL to your mirror, or replace with your own export that
# matches the input/output contract expected by SpeakerVerifier.kt.
ECAPA_TDNN_URL="${ECAPA_TDNN_URL:-}"
TARGET="$ASSETS_DIR/ecapa_tdnn.onnx"

if [ -f "$TARGET" ]; then
  echo "ecapa_tdnn.onnx already present, skipping."
  exit 0
fi

if [ -z "$ECAPA_TDNN_URL" ]; then
  echo "ECAPA_TDNN_URL is not set."
  echo "Provide a download URL for the ECAPA-TDNN ONNX export, e.g.:"
  echo "  ECAPA_TDNN_URL=https://example.com/ecapa_tdnn.onnx ./fetch_models.sh"
  echo "The model must be an ONNX export of SpeechBrain spkrec-ecapa-voxceleb"
  echo "consumed by app/src/main/java/com/repository/listener/audio/SpeakerVerifier.kt."
  exit 1
fi

echo "Downloading ECAPA-TDNN model to $TARGET ..."
curl -fSL "$ECAPA_TDNN_URL" -o "$TARGET"
echo "Done."
