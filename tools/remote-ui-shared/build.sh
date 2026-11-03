#!/bin/sh
# Rebuilds app/src/main/assets/remote-ui/shared-react.js from the React of the website checkout.
# Needs bun and a millida-web checkout with node_modules (WEB defaults to ~/dev/millida-web).
# Run again whenever millida-web moves to another React version, then commit the result.
set -eu
WEB="${WEB:-$HOME/dev/millida-web}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../../app/src/main/assets/remote-ui/shared-react.js"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/node_modules"
ln -s "$WEB/node_modules/react" "$TMP/node_modules/react"
ln -s "$WEB/node_modules/react-dom" "$TMP/node_modules/react-dom"
ln -s "$WEB/node_modules/scheduler" "$TMP/node_modules/scheduler" 2>/dev/null || true
cp "$HERE/entry.js" "$TMP/entry.js"
(cd "$TMP" && bun build entry.js --target=browser --format=esm --minify --define 'process.env.NODE_ENV="production"' --outfile="$OUT")
grep '"version"' "$WEB/node_modules/react/package.json"
ls -l "$OUT"
