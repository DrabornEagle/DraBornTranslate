#!/usr/bin/env bash
set -euo pipefail
target="${1:?model output directory}"
mkdir -p "$target"
files=(encoder_q8.onnx decoder_q8.onnx tokenizer.model LICENSE)
for name in "${files[@]}"; do
    if [[ ! -s "$target/$name" ]]; then
        apk="$(mktemp)"
        trap 'rm -f "$apk"' EXIT
        curl --fail --location --retry 3 --silent --show-error --max-time 180 \
            'https://github.com/DrabornEagle/DraBornTranslate/releases/download/v0.4.11/DraBornTranslate-v0.4.11-Android-arm64.apk' \
            --output "$apk"
        printf '907d711dd612c2743cbfa795b0e658387d3d9a5869932bf9f3525614916ed921  %s\n' "$apk" | sha256sum --check
        for model in "${files[@]}"; do
            unzip -p "$apk" "assets/toz/$model" > "$target/$model.part"
            mv "$target/$model.part" "$target/$model"
        done
        break
    fi
done
# The Android Gradle task verifies the pinned fingerprint of each model before packaging.
