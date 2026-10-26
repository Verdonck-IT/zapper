#!/usr/bin/env python3
"""
Zet een release-APK in de dist-map en schrijft app/app.json voor de auto-updater.

  python tools/publish/publish_app.py --apk app/build/outputs/apk/release/app-release.apk \
      --version-code 42 --version-name 0.1.42 --provider-api-version 5 --dist dist

providerApiVersion in app.json laat een oudere app weten of deze APK de plugin-API heeft die de
plugins vragen (verplichte app-update, zie PluginCompat in de app).
"""
import argparse
import hashlib
import json
import pathlib
import shutil

KEEP = 3

ap = argparse.ArgumentParser()
ap.add_argument("--apk", required=True, type=pathlib.Path)
ap.add_argument("--version-code", required=True, type=int)
ap.add_argument("--version-name", required=True)
ap.add_argument("--provider-api-version", required=True, type=int)
ap.add_argument("--dist", required=True, type=pathlib.Path)
args = ap.parse_args()

app_dir = args.dist / "app"
app_dir.mkdir(parents=True, exist_ok=True)
rel = f"app/zapper-{args.version_code}.apk"
shutil.copyfile(args.apk, args.dist / rel)

(app_dir / "app.json").write_text(json.dumps({
    "versionCode": args.version_code,
    "versionName": args.version_name,
    "apk": rel,
    "sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
    "providerApiVersion": args.provider_api_version,
}, indent=2))

apks = sorted(app_dir.glob("zapper-*.apk"), key=lambda p: int(p.stem.split("-")[1]))
for old in apks[:-KEEP]:
    old.unlink()
print(f"APK {args.version_name} ({args.version_code}) gepubliceerd")
