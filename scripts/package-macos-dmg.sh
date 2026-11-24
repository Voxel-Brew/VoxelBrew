#!/usr/bin/env bash
set -euo pipefail

app="$1"
output="$2"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
test -x "$app/Contents/MacOS/VoxelBrewCLI"
test -x "$app/Contents/MacOS/VoxelBrew" || {
    echo 'Missing executable VoxelBrew.app/Contents/MacOS/VoxelBrew' >&2
    exit 1
}
test -d "$app/Contents/runtime" || {
    echo 'Missing bundled runtime in VoxelBrew.app' >&2
    exit 1
}
chmod 755 "$stage"
/usr/bin/ditto "$app" "$stage/VoxelBrew.app"
ln -s /Applications "$stage/Applications"
/usr/bin/hdiutil create -volname VoxelBrew -srcfolder "$stage" -format UDZO -ov "$output"
