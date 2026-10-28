#!/usr/bin/env bash
# Gate: refuse to ship an APK without a verifiable signer certificate.
# Honor MagicOS shows「该安装包未包含任何证书」when META-INF lacks *.RSA/*.EC (v2-only APKs).
set -euo pipefail
APK="${1:?usage: verify-apk-signed.sh <apk>}"
APKSIGNER="${APKSIGNER:-}"
if [[ -z "$APKSIGNER" ]]; then
  for c in "${ANDROID_HOME:-}/build-tools"/*/apksigner "$HOME/android/sdk/build-tools"/*/apksigner; do
    [[ -x "$c" ]] && APKSIGNER="$c"
  done
fi
[[ -x "${APKSIGNER:-}" ]] || { echo "ERROR: apksigner not found"; exit 2; }
[[ -f "$APK" ]] || { echo "ERROR: APK missing: $APK"; exit 2; }

OUT="$("$APKSIGNER" verify -v --print-certs "$APK" 2>&1)" || {
  echo "ERROR: apksigner verify FAILED — do NOT upload this APK"
  echo "$OUT"
  exit 1
}
echo "$OUT"
echo "$OUT" | grep -q "^Verifies$" || { echo "ERROR: missing Verifies line"; exit 1; }
echo "$OUT" | grep -q "Number of signers: [1-9]" || { echo "ERROR: no signers"; exit 1; }
echo "$OUT" | grep -q "Signer #1 certificate DN:" || { echo "ERROR: no certificate DN printed"; exit 1; }

# Hard requirement for Honor / OEM file-manager installers: JAR cert file in the zip.
CERT_ENTRY="$(unzip -Z1 "$APK" | grep -E '^META-INF/[^/]+\.(RSA|DSA|EC)$' | head -1 || true)"
if [[ -z "$CERT_ENTRY" ]]; then
  echo "ERROR: no META-INF/*.(RSA|DSA|EC) — Honor installer reports「未包含任何证书」. Refusing upload."
  exit 1
fi
echo "OK: certificate entry present: $CERT_ENTRY"

# Prefer v2 as well (modern Android).
if ! echo "$OUT" | grep -q "Verified using v2 scheme (APK Signature Scheme v2): true"; then
  echo "ERROR: v2 signing missing"; exit 1
fi
echo "OK: $APK signed and safe to upload."
