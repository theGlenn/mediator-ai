#!/usr/bin/env python3
"""Check the packaged manifest, including permissions contributed by dependencies."""
import os
import pathlib
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parents[1]
sdk = pathlib.Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
tools = sorted(sdk.glob("build-tools/*/aapt2"))
if not tools:
    sys.exit("Set ANDROID_HOME to an SDK containing build-tools/aapt2")
apk = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else root / "app/build/outputs/apk/debug/app-debug.apk"
permissions = subprocess.check_output([str(tools[-1]), "dump", "permissions", str(apk)], text=True)
forbidden = [p for p in ["android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"] if p in permissions]
if forbidden:
    sys.exit("Forbidden packaged permission(s): " + ", ".join(forbidden))
if "android.permission.RECORD_AUDIO" not in permissions:
    sys.exit("Packaged APK is missing microphone permission")
print("Packaged APK: microphone permission present; INTERNET and ACCESS_NETWORK_STATE absent.")
