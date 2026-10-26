#!/usr/bin/env python3
"""
Publiceert één gedexte plugin naar de dist-map (GitHub Pages) en signeert de index.

  python tools/publish/publish_plugin.py \
      --properties plugins/vrtmax/src/main/resources/zapper-plugin.properties \
      --dex build/dex/vrtmax.jar --build 42 --api-version 1 --dist dist

Privésleutel (PEM) via env PLUGIN_SIGNING_KEY.

Resultaat:
  dist/plugins/<id>/<id>-<build>.jar
  dist/plugins/index.json        (alle plugins, deze entry bijgewerkt)
  dist/plugins/index.json.sig    (base64 ECDSA-P256/SHA-256 over index.json)
Oude jars blijven staan zodat de app kan terugrollen; enkel de laatste 5 per plugin.

Per plugin bevat de index de laatste build per plugin-API (de laatste API_VERSIONS versies).
Een app laadt enkel builds met exact zijn eigen plugin-API: zo houdt een oudere app zijn laatste
passende build, en ziet hij aan de nieuwere entries dat een app-update verplicht is.
Bovenaan staat "apiVersion" = de hoogste plugin-API in de index.
"""
import argparse
import base64
import datetime as dt
import hashlib
import json
import os
import pathlib
import shutil
import sys

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

KEEP = 5
API_VERSIONS = 3


def read_properties(path: pathlib.Path) -> dict:
    props = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    for required in ("id", "entryClass"):
        if required not in props:
            sys.exit(f"{path}: '{required}' ontbreekt")
    return props


def sign(data: bytes) -> str:
    pem = os.environ.get("PLUGIN_SIGNING_KEY")
    if not pem:
        sys.exit("PLUGIN_SIGNING_KEY is niet gezet")
    key = serialization.load_pem_private_key(pem.encode(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey):
        sys.exit("PLUGIN_SIGNING_KEY is geen EC-sleutel")
    return base64.b64encode(key.sign(data, ec.ECDSA(hashes.SHA256()))).decode()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--properties", required=True, type=pathlib.Path)
    ap.add_argument("--dex", required=True, type=pathlib.Path)
    ap.add_argument("--build", required=True, type=int)
    ap.add_argument("--api-version", required=True, type=int)
    ap.add_argument("--dist", required=True, type=pathlib.Path)
    args = ap.parse_args()

    props = read_properties(args.properties)
    pid = props["id"]
    data = args.dex.read_bytes()
    sha = hashlib.sha256(data).hexdigest()

    plugins_dir = args.dist / "plugins"
    index_path = plugins_dir / "index.json"
    index = json.loads(index_path.read_text()) if index_path.exists() else {"plugins": []}
    entries = {(p["id"], p["apiVersion"]): p for p in index["plugins"]}

    existing = entries.get((pid, args.api_version))
    if existing and existing["sha256"] == sha:
        print(f"{pid}: ongewijzigd (sha {sha[:12]}), niets te publiceren")
        return

    rel = f"plugins/{pid}/{pid}-{args.build}.jar"
    target = args.dist / rel
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(args.dex, target)

    today = dt.datetime.now(dt.timezone.utc).strftime("%Y.%m.%d")
    entries[(pid, args.api_version)] = {
        "id": pid,
        "name": props.get("name", pid),
        "build": args.build,
        "version": f"{today}.{args.build}",
        "apiVersion": args.api_version,
        "entryClass": props["entryClass"],
        "file": rel,
        "sha256": sha,
    }

    # Enkel de laatste API_VERSIONS plugin-API's per plugin in de index houden.
    apis = sorted((api for (i, api) in entries if i == pid), reverse=True)
    for api in apis[API_VERSIONS:]:
        del entries[(pid, api)]

    # Oude builds opruimen (laatste KEEP bewaren voor rollback); jars uit de index blijven altijd staan.
    referenced = {e["file"] for e in entries.values()}
    jars = sorted(target.parent.glob(f"{pid}-*.jar"), key=lambda p: int(p.stem.rsplit("-", 1)[1]))
    for old in jars[:-KEEP]:
        if f"plugins/{pid}/{old.name}" not in referenced:
            old.unlink()

    index = {
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "apiVersion": max(e["apiVersion"] for e in entries.values()),
        "plugins": sorted(entries.values(), key=lambda p: (p["id"], p["apiVersion"])),
    }
    raw = json.dumps(index, indent=2, ensure_ascii=False).encode()
    index_path.write_bytes(raw)
    (plugins_dir / "index.json.sig").write_text(sign(raw))
    print(f"{pid}: build {args.build} gepubliceerd ({len(data)} bytes, sha {sha[:12]})")


if __name__ == "__main__":
    main()
