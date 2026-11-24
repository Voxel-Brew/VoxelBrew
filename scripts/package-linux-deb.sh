#!/usr/bin/env bash
set -euo pipefail

image="$1"
output="$2"
version="$3"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
chmod 755 "$stage"
mkdir -p "$stage"/{opt,DEBIAN,usr/bin,usr/share/applications,usr/share/mime/packages}
cp -a "$image" "$stage/opt/VoxelBrew"
for launcher in VoxelBrew VoxelBrewCLI; do
    test -x "$stage/opt/VoxelBrew/bin/$launcher"
    ln -s "/opt/VoxelBrew/bin/$launcher" "$stage/usr/bin/$launcher"
done
cat > "$stage/usr/share/applications/dev.voxelbrew.gui.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=VoxelBrew
Exec=/usr/bin/VoxelBrew %f
MimeType=application/x-voxelbrew-design;
Icon=/opt/VoxelBrew/lib/VoxelBrew.png
Categories=Graphics;Engineering;
Terminal=false
EOF
cat > "$stage/usr/share/mime/packages/dev.voxelbrew.gui.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<mime-info xmlns="http://www.freedesktop.org/standards/shared-mime-info">
  <mime-type type="application/x-voxelbrew-design">
    <comment>VoxelBrew design</comment>
    <glob pattern="*.vxb"/>
  </mime-type>
</mime-info>
EOF
cat > "$stage/DEBIAN/control" <<EOF
Package: voxelbrew
Version: ${version/-SNAPSHOT/~snapshot}
Architecture: $(dpkg --print-architecture)
Maintainer: VoxelBrew
Depends: shared-mime-info, desktop-file-utils, libgtk-3-0 | libgtk-3-0t64, libgl1, libstdc++6
Description: VoxelBrew parametric CAD desktop application and command-line tools
EOF
dpkg-deb -Zgzip --root-owner-group --build "$stage" "$output"
