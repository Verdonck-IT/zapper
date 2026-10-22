# Zapper

Eén Android TV-app met een Netflix-achtige interface voor VRT MAX, VTM GO, Play en Focus & WTV.
Inhoud van alle zenders staat door elkaar in gedeelde rijen (Crime, Humor, Docu, …).
Elke zender is een plugin die zichzelf bijwerkt zonder nieuwe APK.

> Status: skelet + VRT MAX. Nog niet gecompileerd in een omgeving met Android SDK;
> verwacht wat kleine compileerfixes bij de eerste build.

## Hoe het in elkaar zit

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
│ plugins.yml        bouwt, dext, signeert, publiceert   │
│ nightly-health.yml contracttest op runner thuis        │
│                    ↳ faalt? HAR-opname + fix-agent → PR│
│ upstream-watch.yml Kodi-add-on gewijzigd? agent → PR   │
│ app-release.yml    APK + app.json voor self-update     │
└────────────────────────────────────────────────────────┘
```

- **provider-api**: het contract. Plugins leveren alleen logica (login, catalogus, stream
  resolven); netwerk, opslag, DRM en UI zitten in de host.
- **Categorieën**: `app/src/main/assets/categories.json` mapt native genres per zender naar
  één taxonomie, met trefwoorden als aanvulling. Dezelfde titel bij twee zenders wordt
  samengevoegd; op het detailscherm kies je waar je kijkt.
- **Veiligheid**: de index is ECDSA-gesigneerd, elke jar heeft een sha256 in die index.
  Een update die de self-test niet haalt, wordt automatisch teruggedraaid.

## Eenmalige setup

1. **Signeersleutel voor plugins**
   ```bash
   pip install cryptography
   python tools/signing/keygen.py
   ```
   - inhoud van `plugin-signing-key.pem` → secret `PLUGIN_SIGNING_KEY` (en daarna lokaal wissen of veilig bewaren)
   - publieke sleutel → variable of secret `ZAPPER_PLUGIN_PUBLIC_KEY` en in `~/.gradle/gradle.properties` als `zapperPluginPublicKey=…`

2. **Dist-site** (waar de TV updates ophaalt)
   - Standaard: branch `gh-pages` van dit (publieke) repo via GitHub Pages,
     https://verdonck-it.github.io/zapper/. De workflows maken de branch zelf aan.
   - Andere host? Variables `DIST_REPO`, `ZAPPER_UPDATE_BASE_URL` en secret `DIST_TOKEN`
     (fine-grained PAT met *Contents: write* op dat repo).
   - Er staan geen geheimen op die site: enkel gesigneerde plugins en de APK.

3. **App-signeersleutel** (altijd dezelfde, anders weigert Android updates). Daarna een release
   starten met een tag (`git tag v0.1.0 && git push --tags`) of via *Run workflow* bij "App release".
   ```bash
   keytool -genkeypair -v -keystore zapper.jks -alias zapper -keyalg RSA -keysize 4096 -validity 10000
   base64 -w0 zapper.jks   # → secret ZAPPER_KEYSTORE_B64
   ```
   Secrets: `ZAPPER_KEYSTORE_PASSWORD`, `ZAPPER_KEY_ALIAS`, `ZAPPER_KEY_PASSWORD`.

4. **Self-hosted runner thuis** (Raspberry Pi 4/5, NAS of mini-pc)
   - Repo → Settings → Actions → Runners → *New self-hosted runner*, label `zapper-be`.
   - Variable `HEALTH_RUNNER=true` (zonder die variabele slaat de nachtelijke check zichzelf over).
   - Installeer Java 17 en Node 22. Optioneel Google Chrome + variable `CHROME_CHANNEL=chrome`
     (dan staat ook de licentie-request in de HAR-opname).
   - Secrets: `VRTMAX_USERNAME`, `VRTMAX_PASSWORD`, `PLAY_USERNAME`, `PLAY_PASSWORD`, `ANTHROPIC_API_KEY`
     (de laatste ook voor "Upstream volgen"; zonder sleutel enkel een waarschuwing).

5. **Eerste installatie op de TV**
   ```bash
   adb connect <ip-van-tv>:5555
   adb install app-release.apk     # uit de GitHub release
   ```
   Sta daarna in de TV-instellingen *onbekende bronnen* toe voor Zapper; zo kan de app
   zichzelf bijwerken. Log in via **Instellingen** in de app.

## Ontwikkelen

- Open de map in Android Studio en draai de `debug`-variant op de TV of een TV-emulator.
  In debug wordt de VRT MAX-plugin rechtstreeks van het classpath geladen
  (`DEV_PLUGINS` in `app/build.gradle.kts`), geen DEX of download nodig.
- Plugin live testen zonder TV:
  ```bash
  VRTMAX_USERNAME=… VRTMAX_PASSWORD=… ./gradlew :plugins:vrtmax:test
  cat plugins/vrtmax/build/health/vrtmax.json
  ```

## Een zender toevoegen (VTM GO, Play, Focus & WTV)

1. `plugins/<id>/` kopiëren van `vrtmax`, `zapper-plugin.properties` aanpassen.
2. `include(":plugins:<id>")` in `settings.gradle.kts` en de entry class toevoegen aan
   `DEV_PLUGINS`.
3. Native categorieën mappen in `categories.json` onder `providers.<id>`.
4. Regel in de matrix van `nightly-health.yml`, secrets `<ID>_USERNAME/_PASSWORD`,
   een flow in `tools/har-capture/capture.mjs` en de upstream-repo in `.github/agent/upstream.json`.
5. Heeft het platform een eigen licentieformaat? Zet `DrmInfo(handledByProvider = true)`
   en implementeer `acquireLicense()`.

## Bekende beperkingen

- Widevine-niveau van je TV (L1/L3) bepaalt de maximale kwaliteit.
- De VRT-flow komt uit de Kodi-add-on [add-ons/plugin.video.vrt.nu](https://github.com/add-ons/plugin.video.vrt.nu) (GPLv3);
  de GraphQL-queries zijn ingekort en nog niet live geverifieerd.
- Nog niet gebouwd: zoekscherm (de query staat al in `CatalogDao.search`), EPG voor live tv,
  "volgende aflevering" na het einde.
- De fix-agent kan op de runner de zender-credentials zien (nodig om de test te draaien).
