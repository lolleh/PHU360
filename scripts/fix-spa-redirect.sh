#!/usr/bin/env bash
# Patch OpenMRS web.xml so the root path redirects to the O3 SPA login page.
#
# Without this, http://localhost:8090/openmrs/ returns the legacy "no UI module"
# page or redirects to the old login.htm instead of the modern SPA at /spa/.
#
# Usage: scripts/fix-spa-redirect.sh <path-to-extracted-webapp>
set -euo pipefail

WEBAPP_DIR="${1:?Usage: fix-spa-redirect.sh <path-to-extracted-webapp>}"
WEB_XML="$WEBAPP_DIR/WEB-INF/web.xml"

if [[ ! -f "$WEB_XML" ]]; then
  echo "ERROR: $WEB_XML not found" >&2
  exit 1
fi

# Change welcome-file from index.htm (legacy "no UI module" page) to spa/index.html (O3 SPA)
if grep -q '<welcome-file>index.htm</welcome-file>' "$WEB_XML"; then
  echo "Patching welcome-file: index.htm -> spa/index.html"
  sed -i 's|<welcome-file>index\.htm</welcome-file>|<welcome-file>spa/index.html</welcome-file>|' "$WEB_XML"
else
  echo "welcome-file already patched or not found as expected"
fi

echo "Done. web.xml welcome-file now serves the O3 SPA."
