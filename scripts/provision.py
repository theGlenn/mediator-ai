#!/usr/bin/env python3
"""Fetch SHA-pinned models on the host, install the APK, and stage weights over USB."""
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
APP = "ai.xybrid.mediator"


def verified(path, spec):
    if not path.is_file() or path.stat().st_size != spec["size"]:
        return False
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(4 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest() == spec["sha256"]


def fetch(path, spec):
    if verified(path, spec):
        print(f"Verified {path.name}", flush=True)
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".part")
    print(f"Downloading {path.name} ({spec['size'] / 1e9:.2f} GB)", flush=True)
    with urllib.request.urlopen(spec["url"], timeout=60) as source, temporary.open("wb") as target:
        shutil.copyfileobj(source, target, 4 * 1024 * 1024)
    if not verified(temporary, spec):
        raise RuntimeError(f"Checksum or size mismatch: {path.name}; refusing to provision")
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--download-only", action="store_true")
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--serial", help="adb device serial, required when multiple devices are attached")
    parser.add_argument("--apk", type=pathlib.Path, default=ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    args = parser.parse_args()
    specs = json.loads((ASSETS / "models.lock.json").read_text())
    for spec in specs.values():
        path = ROOT / "models" / spec["file"]
        if args.verify_only:
            if not verified(path, spec):
                raise RuntimeError(f"Missing or invalid model: {path}")
        else:
            fetch(path, spec)
    if args.verify_only or args.download_only:
        return
    if not args.apk.is_file():
        raise RuntimeError("APK missing. Run ./gradlew :app:assembleDebug first.")
    adb = ["adb"] + (["-s", args.serial] if args.serial else [])
    subprocess.run(adb + ["get-state"], check=True)
    subprocess.run(adb + ["install", "-r", str(args.apk)], check=True)
    subprocess.run(adb + ["shell", "am", "start", "-W", "-n", APP + "/.MainActivity"], check=True)
    destination = f"/sdcard/Android/data/{APP}/files/models"
    subprocess.run(adb + ["shell", "mkdir", "-p", destination + "/whisper"], check=True)
    # adb-created directories can default to shell-owned 2770 on real devices.
    # The app UID needs access; the parent remains Android's app-specific storage.
    subprocess.run(adb + ["shell", "chmod", "2777", destination, destination + "/whisper"], check=True)
    for spec in specs.values():
        subprocess.run(adb + ["push", str(ROOT / "models" / spec["file"]), destination + "/" + spec["file"]], check=True)
    print("Provisioned. Tap ‘Load and check models’ in Mediator. Keep this application ID; install with -r.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
