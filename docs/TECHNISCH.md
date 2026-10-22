# Zapper – technische documentatie

Voor wie aan Zapper ontwikkelt of de build- en publicatiestraat beheert.
De gebruikershandleiding staat in de [README](../README.md).

## Architectuur

```
┌──────────────────── Android TV ─────────────────────┐
│  UI (Compose for TV)  ◄── Room-index ◄── Sync-worker │
│        │                                   │          │
│        ▼                                   ▼          │
│  Player (Media3 + Widevine)        PluginManager      │
│        ▲                          (DEX, signatuur,    │
│        └── manifest + licentie ──  self-test, rollback)
└───────────────────────────────────────▲───────────────┘
                                        │ index.json (+ .sig), plugin-jars
┌────────────── GitHub ─────────────────┴───────────────┐
│ ci.yml             tests + debug-APK bij elke push     │
│ plugins.yml        bouwt, dext, signeert, publiceert   │
│ app-release.yml    release-APK + app.json (tag v*)     │
│ nightly-health.yml contracttest op runner thuis        │
│                    ↳ faalt? HAR-opname + fix-agent → PR│
│ upstream-watch.yml Kodi-add-on gewijzigd? agent → PR   │
└────────────────────────────────────────────────────────┘
```

De UI leest uitsluitend uit de lokale Room-index, zodat het startscherm meteen opent, ook als
een platform even plat ligt. Een periodieke worker synchroniseert de catalogus per zender.

## Modules

| Module | Rol |
|---|---|
| `provider-api/` | Het contract tussen app en plugins (`StreamingProvider`, `HostServices`, modellen) plus gedeelde helpers (cookie-jar, redirects, `ProviderSelfTest`). Geen dependencies. |
| `plugins/<id>/` | Eén platform per module: `vrtmax`, `play`, `focuswtv`. Pure JVM-Kotlin, `compileOnly` op provider-api. CI zet de jar om naar DEX. |
| `plugin-testkit/` | JVM-host en `ContractTest` om plugins zonder Android live te testen. |
| `app/` | Host: plugin-loader (DexClassLoader + handtekening + rollback), Room-index, categorie-mapping, UI, player, self-update. |

## Het plugin-contract (provider-api)

Plugins leveren enkel logica: inloggen, catalogus, details, stream resolven. Netwerk (altijd via
`host.http`, met een cookie-jar per provider), opslag, DRM-afspelen en UI zitten in de host.
Fouten gaan via `ProviderException` met een `ErrorKind`; `API_CHANGED` betekent dat het platform
gewijzigd is en is het signaal voor de health-check.

Elke breaking change verhoogt `PROVIDER_API_VERSION` (nu **4**) en vraagt een app-release; de app
laadt enkel plugins met exact dezelfde versie. Optionele mogelijkheden staan als vlag in `ProviderInfo`:

| Vlag | Methodes | VRT MAX | Play | Focus & WTV |
|---|---|---|---|---|
| `syncsProgress` | `continueWatching()`, `reportProgress()`, `Episode.progress` | ✓ | ✓ | – |
| `supportsMyList` | `myList()`, `setInMyList()` | ✓ | ✓ | – |
| `supportsContinueRemoval` | `removeFromContinueWatching()` | ✓ | ✓ | – |
| `requiresLogin` | `login()`, `isLoggedIn()` | ✓ | ✓ | – (geen login) |

DRM wordt nooit omzeild: plugins leveren een manifest en een Widevine-licentie-URL met headers,
de app speelt af via de Widevine-CDM van het toestel.

### Per platform

- **VRT MAX** – OIDC-login, GraphQL-catalogus per genre, "Programma's verder kijken",
  resumepunten (`ddt.profiel.vrt.be`), favorieten (`setFavorite`), live-zenders met logo's uit
  `brandLogos`. Afgeleid van [add-ons/plugin.video.vrt.nu](https://github.com/add-ons/plugin.video.vrt.nu).
- **Play** (vroeger GoPlay) – AWS Cognito-login (USER_SRP_AUTH, `CognitoSrp.kt`, bit voor bit getest
  tegen de Python-code van de add-on), A–Z-catalogus zonder Play Kinepolis (huren/kopen), "Kijk verder",
  Mijn lijst, Widevine via KeyOS. Afgeleid van [add-ons/plugin.video.play](https://github.com/add-ons/plugin.video.play).
- **Focus & WTV** – enkel de twee livestreams, zonder login: de HLS-URL komt uit de embed-speler
  van focus-wtv.be/focus-live en /wtv-live, met een vaste URL als terugval.

## Categorieën en volgorde

`app/src/main/assets/categories.json` bepaalt de rijen op het startscherm:

1. `titles` – vaste titels (bv. Thuis → Fictie), wint altijd;
2. `providers.<id>` – native categorie van de zender → eigen categorieën;
3. `keywords` – trefwoorden in titel en omschrijving (aanvullend);
4. `rules` – opkuis (true crime niet ook in crime-fictie, kids niet in volwassen rijen, …);
5. niets gevonden → "Meer om te ontdekken".

`liveOrder` bepaalt de volgorde op de pagina Live tv (op zendernaam, populairste eerst; onbekende
zenders achteraan). Dezelfde titel bij twee zenders wordt samengevoegd; op het detailscherm kies je
waar je kijkt.

## Kijkvoortgang

Voor platformen met `syncsProgress` is het platform de bron van waarheid ("Verder kijken", bekeken
afleveringen). Lokale voortgang die recenter is dan 6 uur gaat voor, zodat wat je net in Zapper keek
meteen klopt, ook als het platform nog achterloopt. Zie `app/.../data/ProgressSync.kt`.

## Veiligheid

- `plugins/index.json` is ECDSA-P256-gesigneerd; elke plugin-jar heeft een sha256 in die index.
- De app controleert de handtekening met de publieke sleutel uit de build (`PLUGIN_PUBLIC_KEY`).
- Een plugin-update die de self-test niet haalt, wordt automatisch teruggedraaid.
- Tokens en credentials worden nooit gelogd; credentials staan versleuteld op het toestel.

## Distributie

- Dist-site: branch `gh-pages` van dit repo via GitHub Pages, <https://verdonck-it.github.io/zapper/>
  (standaardwaarde van `zapperUpdateBaseUrl`).
  - `plugins/index.json`, `plugins/index.json.sig`, `plugins/<id>/<id>-<build>.jar`
  - `app/app.json`, `app/zapper-<versionCode>.apk`
- Paden in `index.json` en `app.json` zijn relatief aan de root van de site.
- Een release-APK staat ook als bijlage bij de GitHub-release (voor de eerste installatie).

## CI/CD

| Workflow | Wanneer | Wat |
|---|---|---|
| `ci.yml` | push naar `main`, pull requests | unit tests + debug-APK |
| `plugins.yml` | wijziging in `plugins/`, `provider-api/`, `tools/publish/` of handmatig | bouwen, testen, DEX (d8), signeren, publiceren naar `gh-pages` |
| `app-release.yml` | tag `v*` of handmatig | release-APK signeren, publiceren voor auto-update, GitHub-release |
| `nightly-health.yml` | elke nacht, enkel met `HEALTH_RUNNER=true` | contracttests op een runner thuis; faalt er één, dan HAR-opname + fix-agent |
| `upstream-watch.yml` | dagelijks | nieuwe commits in de Kodi-add-ons → agent beoordeelt en port (met `ANTHROPIC_API_KEY`) |

Zonder de nodige secrets falen de workflows niet: ze bouwen en testen, en melden wat ontbreekt.

### Eenmalige setup

1. **Signeersleutel voor plugins**
   ```bash
   pip install cryptography
   python tools/signing/keygen.py
   ```
   - inhoud van `plugin-signing-key.pem` → secret `PLUGIN_SIGNING_KEY` (daarna veilig bewaren, niet in de repo laten staan);
   - publieke sleutel → variable of secret `ZAPPER_PLUGIN_PUBLIC_KEY`, en lokaal in `~/.gradle/gradle.properties` als `zapperPluginPublicKey=…`.

2. **App-signeersleutel** (altijd dezelfde, anders weigert Android updates)
   ```bash
   keytool -genkeypair -v -keystore zapper.jks -alias zapper -keyalg RSA -keysize 4096 -validity 10000
   ```
   Secrets zetten vanuit een bestaande Android `key.properties`:
   ```bash
   bash tools/signing/set-release-secrets.sh /pad/naar/key.properties
   ```
   Dat zet `ZAPPER_KEYSTORE_B64`, `ZAPPER_KEYSTORE_PASSWORD`, `ZAPPER_KEY_ALIAS` en `ZAPPER_KEY_PASSWORD`
   (via stdin, niets in de commandoregel of op schijf). Een release start met een tag:
   `git tag -a v0.2.0 -m "…" && git push origin v0.2.0`.

3. **Andere dist-host** (optioneel): variables `DIST_REPO`, `ZAPPER_UPDATE_BASE_URL` en secret
   `DIST_TOKEN` (fine-grained PAT met *Contents: write*).

4. **Self-hosted runner thuis** (optioneel, voor de nachtelijke check; Belgisch IP, geen datacenter-IP)
   - Repo → Settings → Actions → Runners → *New self-hosted runner*, label `zapper-be`.
   - Variable `HEALTH_RUNNER=true`.
   - Java 17 en Node 22; optioneel Google Chrome + variable `CHROME_CHANNEL=chrome`.
   - Secrets: `VRTMAX_USERNAME`, `VRTMAX_PASSWORD`, `PLAY_USERNAME`, `PLAY_PASSWORD`, `ANTHROPIC_API_KEY`.
   - De fix-agent kan op die runner de zender-credentials zien (nodig om de test te draaien).

## Ontwikkelen

- Open de map in Android Studio en draai de `debug`-variant op een TV of TV-emulator. In debug
  worden alle plugins rechtstreeks van het classpath geladen (`DEV_PLUGINS` in `app/build.gradle.kts`),
  zonder DEX of download.
- `local.properties` met `sdk.dir` is nodig (niet in git). Gradle haalt zelf een JDK 17 binnen
  (foojay-resolver) als er lokaal geen is.

### Testen

```bash
./gradlew test                       # unit tests; contracttests zonder credentials worden overgeslagen
./gradlew :app:assembleDebug         # debug-APK

# Live tegen een platform (rapport in plugins/<id>/build/health/<id>.json):
VRTMAX_USERNAME=… VRTMAX_PASSWORD=… ./gradlew :plugins:vrtmax:test
PLAY_USERNAME=… PLAY_PASSWORD=… ./gradlew :plugins:play:test
./gradlew :plugins:focuswtv:test     # geen login nodig; in CI enkel met LIVE_TESTS=1
```

## Een zender toevoegen (bv. VTM GO)

1. `plugins/<id>/` kopiëren van een bestaande plugin, `zapper-plugin.properties` aanpassen.
2. `include(":plugins:<id>")` in `settings.gradle.kts`, `debugImplementation` en de entry class in `DEV_PLUGINS` (`app/build.gradle.kts`).
3. Native categorieën mappen in `categories.json` onder `providers.<id>`; live-zenders in `liveOrder`.
4. Regel in de matrix van `nightly-health.yml`, secrets `<ID>_USERNAME/_PASSWORD`, een flow in
   `tools/har-capture/capture.mjs` en de upstream-repo in `.github/agent/upstream.json`.
5. Eigen licentieformaat? `DrmInfo(handledByProvider = true)` en `acquireLicense()` implementeren.
6. Kijkvoortgang, Mijn lijst of verwijderen uit "Verder kijken"? De vlaggen in `ProviderInfo` zetten
   en de bijbehorende methodes implementeren.

## Bekende beperkingen

- Het Widevine-niveau van de TV (L1/L3) bepaalt de maximale kwaliteit.
- Nog niet gebouwd: VTM GO, programma's op aanvraag van Focus & WTV, EPG voor live tv,
  automatisch de volgende aflevering na het einde.
- De Play-plugin is enkel voor de publieke delen live getest; login, streams en voortgang
  vragen een account.
