#!/usr/bin/env bash
# Re-signs Billy Companion so it installs over copies signed with the retired
# debug key (v0.5 and early 0.6 test builds) as well as the current key.
#
# Android 9+ accepts the new key because the retired key signs a "lineage"
# saying the new key replaces it (APK Signature Scheme v3 key rotation).
# The retired key was public in this repo's history, so it's no secret; it is
# used here only to vouch for the new key. Updates signed by the old key alone
# are refused once a phone has this build (rollback is off).
#
# Usage: tools/sign-with-rotation.sh <apk> <new-key.p12>   (needs BILLY_KEYSTORE_PASSWORD)
set -euo pipefail
APK="$1"
NEW_KS="$2"
WORK="${RUNNER_TEMP:-/tmp}/billy-rotation"
mkdir -p "$WORK"

# The last commit that still had the retired key.
OLD_COMMIT=2c55b8453e98876e173c31900694120e507348c6
git fetch -q --depth=1 origin "$OLD_COMMIT"
git show "$OLD_COMMIT:companion-android/debug.keystore" > "$WORK/old.keystore"

BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
SIGNER="$BT/apksigner"
OLD=(--ks "$WORK/old.keystore" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android)
NEW=(--ks "$NEW_KS" --ks-type PKCS12 --ks-pass env:BILLY_KEYSTORE_PASSWORD --ks-key-alias billy --key-pass env:BILLY_KEYSTORE_PASSWORD)

"$SIGNER" rotate --out "$WORK/lineage.bin" --old-signer "${OLD[@]}" --set-rollback false --new-signer "${NEW[@]}"
"$SIGNER" sign "${OLD[@]}" --next-signer "${NEW[@]}" \
  --lineage "$WORK/lineage.bin" --rotation-min-sdk-version 28 \
  --out "$WORK/signed.apk" "$APK"
"$SIGNER" verify -v --print-certs --min-sdk-version 26 "$WORK/signed.apk" | grep -E "Verified|Signer|SHA-1|rotation|lineage" || true
mv "$WORK/signed.apk" "$APK"
rm -f "$WORK/old.keystore"
