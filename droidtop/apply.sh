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
sed -i '/about_content/s/Mozilla/Droidtop/' "${strings[@]}"
sed -i '/<string name="trackers_blocked_panel_categorical_num_trackers_blocked">/s/%s/%1$s/g; /<string name="trackers_blocked_panel_categorical_num_trackers_blocked">/s/%d/%2$d/g' "${strings[@]}"

echo "apply.sh: dev.droidtop.browser ready"
