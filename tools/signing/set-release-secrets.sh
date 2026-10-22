#!/usr/bin/env bash
# Zet de GitHub-secrets voor de app-release (APK signeren) vanuit een Android key.properties.
#
#   bash tools/signing/set-release-secrets.sh /pad/naar/key.properties [owner/repo]
#
# key.properties (zoals Android Studio die gebruikt):
#   storePassword=…   keyPassword=…   keyAlias=…   storeFile=/pad/naar/keystore.jks
#
# De waarden gaan via stdin naar `gh secret set`: ze verschijnen niet in de commandoregel,
# de procestabel of een logbestand, en dit script schrijft niets naar schijf.
set -euo pipefail

props="${1:?gebruik: $0 /pad/naar/key.properties [owner/repo]}"
repo="${2:-Verdonck-IT/zapper}"
[ -f "$props" ] || { echo "Niet gevonden: $props" >&2; exit 1; }

value() { grep -E "^[[:space:]]*$1[[:space:]]*=" "$props" | tail -1 | cut -d= -f2- | tr -d '\r'; }
store_password="$(value storePassword)"
key_password="$(value keyPassword)"
key_alias="$(value keyAlias)"
store_file="$(value storeFile)"

for v in store_password key_password key_alias store_file; do
  [ -n "${!v}" ] || { echo "Ontbreekt in $props: $v" >&2; exit 1; }
done
[ -f "$store_file" ] || { echo "Keystore niet gevonden: $store_file" >&2; exit 1; }

# Optioneel: controleren dat alias en wachtwoord kloppen (als keytool beschikbaar is).
if command -v keytool >/dev/null 2>&1; then
  if STOREPASS="$store_password" keytool -list -keystore "$store_file" -alias "$key_alias" -storepass:env STOREPASS >/dev/null 2>&1; then
    echo "Keystore gecontroleerd: alias '$key_alias' gevonden."
  else
    echo "Keystore-controle mislukt (alias of wachtwoord klopt niet?). Er is niets ingesteld." >&2
    exit 1
  fi
fi

# Een eventuele GITHUB_TOKEN zonder rechten op secrets niet gebruiken; dan geldt de gh-login.
gh_() { env -u GITHUB_TOKEN gh "$@"; }

base64 -w0 "$store_file" | gh_ secret set ZAPPER_KEYSTORE_B64 -R "$repo"
printf '%s' "$store_password" | gh_ secret set ZAPPER_KEYSTORE_PASSWORD -R "$repo"
printf '%s' "$key_alias" | gh_ secret set ZAPPER_KEY_ALIAS -R "$repo"
printf '%s' "$key_password" | gh_ secret set ZAPPER_KEY_PASSWORD -R "$repo"

echo "Klaar. Secrets op $repo:"
gh_ secret list -R "$repo"
