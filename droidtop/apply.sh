#!/usr/bin/env bash
# Turns the composed tree (Iceraven at the line's pinned commit, with plugin-core written over it) into
# droidtop's browser: its own package, name and signing identity. Run once from the repository root before
# Gradle; it changes only what it names, and refuses when an anchor it expects is missing, so an upstream
# change that moves one fails the build instead of shipping half-renamed.
set -euo pipefail
shopt -s nullglob

gradle=app/build.gradle

need() { grep -q -- "$1" "$2" || { echo "apply.sh: '$1' not found in $2: upstream moved it" >&2; exit 1; }; }

need 'applicationId "io.github.forkmaintainers"' "$gradle"
need 'applicationIdSuffix ".iceraven"' "$gradle"
need 'applicationIdSuffix ".iceraven.debug"' "$gradle"
need 'io.github.forkmaintainers.iceraven.sharedID' "$gradle"

# Package: dev.droidtop.browser (release), dev.droidtop.browser.debug (debug). droidtop finds the browser
# by this name and trusts it by its APK signing certificate, never by the name alone.
sed -i 's#applicationId "io.github.forkmaintainers"#applicationId "dev.droidtop"#' "$gradle"
sed -i 's#applicationIdSuffix ".iceraven.debug"#applicationIdSuffix ".browser.debug"#' "$gradle"
sed -i 's#applicationIdSuffix ".iceraven"#applicationIdSuffix ".browser"#' "$gradle"
sed -i 's#io.github.forkmaintainers.iceraven.sharedID#dev.droidtop.browser.sharedID#g' "$gradle"

# Name. Iceraven's own CI does the same with its name; Mozilla's trademarks stay out of a fork's UI.
strings=(app/src/*/res/values*/*strings.xml)
sed -i 's/Firefox/Droidtop Browser/g' "${strings[@]}"
# Iceraven's own name is the fork build's app name (the console listed the app as "Iceraven", 2026-11-22).
sed -i 's/Iceraven/droidtop Browser/g' "${strings[@]}"
sed -i '/about_content/s/Mozilla/Droidtop/' "${strings[@]}"
sed -i '/<string name="trackers_blocked_panel_categorical_num_trackers_blocked">/s/%s/%1$s/g; /<string name="trackers_blocked_panel_categorical_num_trackers_blocked">/s/%d/%2$d/g' "${strings[@]}"

# The plugin side: droidtop's AIDL and the adapter's sources (build.part.gradle), its service and page activity
# (AndroidManifest.part.xml), and droidtop's package made visible, since the browser checks droidtop's signature and
# knocks on its door.
manifest=app/src/main/AndroidManifest.xml
need '</application>' "$manifest"
need '<application' "$manifest"
need 'android:name=".FenixApplication"' "$manifest"
# Fenix's ordinary components remain unchanged in its main process. The explicit secondary-process guard prevents
# incidental access to them from creating a default GeckoRuntime before the restricted profile is configured.
sed -i 's#android:name=".FenixApplication"#android:name="dev.droidtop.browser.DroidtopBrowserApplication"#' "$manifest"
[ -d droidtop/vendor/mytop-plugins/android/aidl ] || { echo "apply.sh: droidtop/vendor/mytop-plugins is missing (browser-build.yml checks it out)" >&2; exit 1; }
[ -f app/src/main/java/dev/droidtop/browser/WebViewActivity.kt ] || { echo "apply.sh: the adapter's sources are missing from app/src/main/java" >&2; exit 1; }
# droidtop's AIDL where the build compiles AIDL by default (see build.part.gradle).
mkdir -p app/src/main/aidl
cp -R droidtop/vendor/mytop-plugins/android/aidl/. app/src/main/aidl/
# Compile the canonical shared sources through the default Android source tree; keep
# transfer mechanics and challenge classification identical to Droidtop's consumers.
[ -f droidtop/vendor/mytop-net/net/src/main/kotlin/dev/droidtop/shared/net/BoundedStreamCopy.kt ] || { echo "apply.sh: pinned mytop-net source is missing" >&2; exit 1; }
mkdir -p app/src/main/java/dev/droidtop/shared/net
cp droidtop/vendor/mytop-net/net/src/main/kotlin/dev/droidtop/shared/net/BoundedStreamCopy.kt app/src/main/java/dev/droidtop/shared/net/
cp -R droidtop/vendor/mytop-net/sitecheck/src/main/kotlin/dev/droidtop/shared/sitecheck app/src/main/java/dev/droidtop/shared/
if ! grep -q 'dev.droidtop.browser.DroidtopInterfaceService' "$manifest"; then
    cat droidtop/build.part.gradle >> "$gradle"
    python3 - "$manifest" droidtop/AndroidManifest.part.xml <<'PY'
import sys
path, part = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8").read()
block = open(part, encoding="utf-8").read()
queries = '    <queries>\n        <package android:name="dev.droidtop.app" />\n    </queries>\n\n'
start = text.index("<application")
line = text.rfind("\n", 0, start) + 1
text = text[:line] + queries + text[line:]
end = text.rindex("</application>")
line = text.rfind("\n", 0, end) + 1
text = text[:line] + block + "\n" + text[line:]
open(path, "w", encoding="utf-8").write(text)
PY
fi

echo "apply.sh: dev.droidtop.browser ready"
