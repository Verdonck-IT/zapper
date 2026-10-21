#!/usr/bin/env python3
"""
Genereert het sleutelpaar waarmee CI de plugin-index signeert.

  pip install cryptography
  python tools/signing/keygen.py

- plugin-signing-key.pem  → GitHub secret PLUGIN_SIGNING_KEY (nooit committen!)
- de publieke sleutel (base64) → GitHub variable ZAPPER_PLUGIN_PUBLIC_KEY
  en lokaal in ~/.gradle/gradle.properties als zapperPluginPublicKey=...
"""
import base64
import pathlib

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

out = pathlib.Path("plugin-signing-key.pem")
if out.exists():
    raise SystemExit(f"{out} bestaat al; verwijder hem eerst als je echt een nieuwe sleutel wil.")

key = ec.generate_private_key(ec.SECP256R1())
out.write_bytes(
    key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
)
out.chmod(0o600)

public_der = key.public_key().public_bytes(
    serialization.Encoding.DER,
    serialization.PublicFormat.SubjectPublicKeyInfo,
)
print(f"Privésleutel: {out.resolve()}")
print("Publieke sleutel (zapperPluginPublicKey / ZAPPER_PLUGIN_PUBLIC_KEY):")
print(base64.b64encode(public_der).decode())
