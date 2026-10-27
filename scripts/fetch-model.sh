#!/usr/bin/env bash
# Downloads all-MiniLM-L6-v2 (ONNX export, Apache-2.0) from Hugging Face into ./models.
# Without it the gateway falls back to the hashing embedder and says so in its startup log.
set -euo pipefail
cd "$(dirname "$0")/.."
dest=models/all-MiniLM-L6-v2
base=https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main
mkdir -p "$dest"
for f in onnx/model.onnx vocab.txt config.json tokenizer_config.json; do
  out="$dest/$(basename "$f")"
  [ -s "$out" ] && continue
  echo "fetching $f"
  curl -fsSL "$base/$f" -o "$out"
done
ls -l "$dest"
