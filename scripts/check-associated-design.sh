#!/usr/bin/env bash
set -euo pipefail

# A unique filename ensures the log entry came from this desktop open request.
work="$(mktemp -d)"
work="$(cd "$work" && pwd -P)"
trap 'rm -rf "$work"' EXIT
document="$work/associated design.vxb"
cp fixtures/reference-8mm.vxb "$document"
log="${VOXELBREW_ASSOCIATION_LOG:-$HOME/.voxelbrew/log/app.log}"
open_design() {
    if [[ "$(uname -s)" == Darwin ]]; then
        /usr/bin/open -a /Applications/VoxelBrew.app "$1"
    else
        export XDG_CURRENT_DESKTOP="${XDG_CURRENT_DESKTOP:-GNOME}"
        test "$(xdg-mime query filetype "$1")" = application/x-voxelbrew-design
        test "$(xdg-mime query default application/x-voxelbrew-design)" = dev.voxelbrew.gui.desktop
        gio open "$1"
    fi
    for ((i=0; i<60; i++)); do
        if grep -Fq " from $1" "$log" 2>/dev/null; then return; fi
        sleep 1
    done
    echo "The installed GUI did not open $1" >&2
    return 1
}
open_design "$document"
if [[ "$(uname -s)" == Darwin ]]; then
    cp "$document" "$work/second design.vxb"
    open_design "$work/second design.vxb"
fi
