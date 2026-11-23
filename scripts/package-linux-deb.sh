#!/usr/bin/env bash
set -euo pipefail

image="$1"
output="$2"
version="$3"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
chmod 755 "$stage"
mkdir -p "$stage"/{opt,DEBIAN,usr/bin,usr/share/applications}
cp -a "$image" "$stage/opt/VoxelBrew"
for launcher in VoxelBrew VoxelBrewCLI; do
    test -x "$stage/opt/VoxelBrew/bin/$launcher"
    ln -s "/opt/VoxelBrew/bin/$launcher" "$stage/usr/bin/$launcher"
done
cat > "$stage/usr/share/applications/dev.voxelbrew.gui.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=VoxelBrew
Exec=/usr/bin/VoxelBrew
Icon=/opt/VoxelBrew/lib/VoxelBrew.png
Categories=Graphics;Engineering;
Terminal=false
EOF
cat > "$stage/DEBIAN/control" <<EOF
Package: voxelbrew
Version: ${version/-SNAPSHOT/~snapshot}
Architecture: $(dpkg --print-architecture)
Maintainer: VoxelBrew
Depends: libgtk-3-0 | libgtk-3-0t64, libgl1, libstdc++6
Description: VoxelBrew parametric CAD desktop application and command-line tools
EOF
dpkg-deb -Zgzip --root-owner-group --build "$stage" "$output"
