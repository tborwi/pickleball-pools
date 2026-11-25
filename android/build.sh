#!/usr/bin/env bash
# Builds the Pickleball Pools app in Docker (no Android Studio needed).
#   ./build.sh        -> android/PickleballPools.apk (to install directly)
#   ./build.sh play   -> android/PickleballPools.aab (to upload to Google Play; leaves out the private practice roster)
# Output: android/PickleballPools.apk. Signed with your key if android/keystore.properties exists,
# otherwise with a throwaway debug key (fine for trying it out; updates then need an uninstall).
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p .cache
GV=8.14.3
TASK=assembleRelease; PROPS=""
if [ "${1:-}" = play ]; then TASK=bundleRelease; PROPS="-Pplay"; fi
EXTRA=()
if [ -f keystore.properties ]; then
  KS=$(sed -n 's/^storeFile=//p' keystore.properties)
  EXTRA=(-v "$KS":"$KS":ro)
fi
docker run --rm -u "$(id -u):$(id -g)" \
  -e GRADLE_USER_HOME=/cache/gradle -e HOME=/cache \
  -v "$(dirname "$PWD")":/repo -v "$PWD/.cache":/cache "${EXTRA[@]}" -w /repo/android \
  ghcr.io/cirruslabs/android-sdk:36 bash -c "
    set -e
    if [ ! -x /cache/gradle-$GV/bin/gradle ]; then
      curl -fsSL -o /cache/g.zip https://services.gradle.org/distributions/gradle-$GV-bin.zip
      cd /cache && unzip -q g.zip && rm g.zip && cd /repo/android
    fi
    echo sdk.dir=\$ANDROID_HOME > local.properties
    /cache/gradle-$GV/bin/gradle --no-daemon -q $TASK $PROPS
  "
if [ "$TASK" = bundleRelease ]; then cp app/build/outputs/bundle/release/app-release.aab PickleballPools.aab
else cp app/build/outputs/apk/release/app-release.apk PickleballPools.apk; fi
echo Built
