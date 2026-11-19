"""Stage verified, platform-specific slicer releases as slicer-impl resources."""

import hashlib
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.parse
import zipfile
from pathlib import Path

PRUSA = "https://github.com/prusa3d/PrusaSlicer/releases/download/version_2.7.2/"
ORCA = "https://github.com/OrcaSlicer/OrcaSlicer/releases/download/v2.5.0/"
ASSETS = {
    "windows": (
        ("PrusaSlicer-2.7.2+win64-202402291307.zip", "a155f1179a987dfc8c5fa34aeed2384d1470f5390848c1fb489448e1e336b5d6"),
        ("OrcaSlicer_Windows_V2.5.0_x64_portable.zip", "e355d9f8ac0be4efa4b4f99ecae448d4e99def8dee97683847ede5aa0ccb9166"),
    ),
    "linux": (
        ("PrusaSlicer-2.7.2+linux-x64-GTK3-202402291307.AppImage", "08af39db787abcdc46cc5da76a51be06cf91c67c2b6871cb3327db0c1d0c8832"),
        ("OrcaSlicer_Linux_AppImage_Ubuntu2404_V2.5.0.AppImage", "d13eb0a9cd618ed080ab6b82c64c0a60c4501aac91e88d5ac2c3b526fb141a7c"),
    ),
    "macos": (
        ("PrusaSlicer-2.7.2+MacOS-universal-202402291330.dmg", "70c6225c42e7ec0ba104375a986e8167fcdf2ee074dec648b8daf4fc467ff279"),
        ("OrcaSlicer_Mac_universal_V2.5.0.dmg", "061e0c6ff104c37655c83190725ac2420960f23c39b56aae9b546e6c542995d5"),
    ),
}
ORCA_SOURCE = "https://codeload.github.com/OrcaSlicer/OrcaSlicer/tar.gz/refs/tags/v2.5.0"
ORCA_SOURCE_SHA256 = "f2130592babc4f48194ebe0031065572dc0556bbed46245d660dd259645f0713"
MACOS_EXECUTABLE_NAMES = {"prusaslicer", "orcaslicer", "orca-slicer"}


def sha256(file):
    with file.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def fetch(cache, name, expected, url):
    destination = cache / name
    if destination.is_file() and sha256(destination) == expected:
        return destination
    if os.environ.get("VOXELBREW_BUNDLE_OFFLINE") == "true":
        raise ValueError(f"Offline build requires a checksum-matching {destination}")
    print(f"Downloading {name}", flush=True)
    with tempfile.TemporaryDirectory(dir=cache) as temp:
        partial = Path(temp) / name
        subprocess.run(["curl", "-fLsS", "--retry", "3", "--connect-timeout", "30", "-o", partial, url], check=True)
        if sha256(partial) != expected:
            raise ValueError(f"Checksum mismatch for {name}")
        partial.replace(destination)
    return destination


def copy_orca_presets(source, target):
    prefix = "OrcaSlicer-2.5.0/resources/profiles/BBL/"
    with tarfile.open(source, "r:gz") as archive:
        for entry in archive:
            category, _, name = entry.name.removeprefix(prefix).partition("/")
            if (entry.isfile() and entry.name.startswith(prefix)
                    and category in {"machine", "process", "filament"}
                    and "/" not in name and name.endswith(".json")):
                destination = target / category / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                with archive.extractfile(entry) as data, destination.open("wb") as output:
                    shutil.copyfileobj(data, output)


def macos_applications(directory):
    return [
        app
        for app in directory.rglob("*.app")
        if any(
            executable.is_file() and executable.name.lower() in MACOS_EXECUTABLE_NAMES
            for executable in (app / "Contents/MacOS").glob("*")
        )
    ]


def extract_macos(image, target, work):
    mount = work / "mount"
    mount.mkdir()
    subprocess.run(["hdiutil", "attach", "-nobrowse", "-readonly", "-mountpoint", mount, image], check=True)
    try:
        # Vendor images may put the application or installer inside a subdirectory.
        applications = macos_applications(mount)
        if not applications:
            packages = list(mount.rglob("*.pkg"))
            if len(packages) != 1:
                raise ValueError(f"Expected one application or installer package in {image.name}")
            expanded = work / "expanded"
            subprocess.run(["pkgutil", "--expand-full", packages[0], expanded], check=True)
            applications = macos_applications(expanded)
        if len(applications) != 1:
            raise ValueError(f"Expected one application in {image.name}")
        app = applications[0]
        subprocess.run(["ditto", app, target / app.name], check=True)
    finally:
        subprocess.run(["hdiutil", "detach", mount], check=True)


def prepare(platform, provider, vendor, orca_source, output):
    with tempfile.TemporaryDirectory(dir=output.parent) as temp:
        work = Path(temp)
        payload = work / "payload"
        payload.mkdir()
        if platform == "windows":
            with zipfile.ZipFile(vendor) as archive:
                archive.extractall(payload)
            names = {"prusa-slicer-console.exe"} if provider == "prusa-slicer" else {"orca-slicer.exe"}
            candidates = [p for p in payload.rglob("*.exe") if p.name.lower() in names]
        elif platform == "macos":
            extract_macos(vendor, payload, work)
            candidates = [p for p in payload.glob("*.app/Contents/MacOS/*")
                          if p.name.lower() in MACOS_EXECUTABLE_NAMES]
        else:
            image = work / vendor.name
            shutil.copyfile(vendor, image)
            image.chmod(0o700)
            subprocess.run([image, "--appimage-extract"], cwd=payload, stdout=subprocess.DEVNULL, check=True)
            candidates = [payload / "squashfs-root/AppRun"]
        if len(candidates) != 1 or not candidates[0].is_file():
            raise ValueError(f"Cannot find the {provider} executable in {vendor.name}")
        if provider == "orca":
            copy_orca_presets(orca_source, payload / "voxelbrew-bbl")
            if not (payload / "voxelbrew-bbl/machine/Bambu Lab A1 0.4 nozzle.json").is_file():
                raise ValueError("Orca source archive is missing Bambu presets")
        executable = candidates[0].relative_to(payload).as_posix()
        if platform == "windows":
            name = provider + ".zip"
            with zipfile.ZipFile(output / name, "w", zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
                for path in sorted(payload.rglob("*")):
                    if path.is_file():
                        archive.write(path, path.relative_to(payload).as_posix())
        else:
            name = provider + ".tar.gz"
            subprocess.run(["tar", "-czf", output / name, "-C", payload, "."], check=True)
        (output / (provider + ".properties")).write_text(f"archive={name}\nexecutable={executable}\n", encoding="ascii")


def main(platform, cache, destination):
    cache.mkdir(parents=True, exist_ok=True)
    destination.parent.mkdir(parents=True, exist_ok=True)
    source = fetch(cache, "orca-v2.5.0-source.tar.gz", ORCA_SOURCE_SHA256, ORCA_SOURCE)
    with tempfile.TemporaryDirectory(dir=destination.parent) as temporary:
        output = Path(temporary) / "bundled"
        output.mkdir()
        for provider, base, (name, digest) in zip(("prusa-slicer", "orca"), (PRUSA, ORCA), ASSETS[platform]):
            vendor = fetch(cache, name, digest, base + urllib.parse.quote(name))
            prepare(platform, provider, vendor, source, output)
        if destination.exists():
            shutil.rmtree(destination)
        output.replace(destination)


if __name__ == "__main__":
    main(sys.argv[1], Path(sys.argv[2]).resolve(), Path(sys.argv[3]).resolve())
