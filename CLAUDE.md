# Zapper – context voor Claude

Android TV-app (Kotlin, Compose for TV, Media3) met een Netflix-achtige interface die
Vlaamse streamingplatformen bundelt. Persoonlijk gebruik, gesideload.

## Modules
- `provider-api/` – het contract tussen app en plugins (`StreamingProvider`, `HostServices`,
  modellen) plus gedeelde helpers (cookie-jar, redirects, `ProviderSelfTest`).
  Geen dependencies. Elke breaking change → `PROVIDER_API_VERSION` verhogen + app-release.
- `plugins/<id>/` – één platform per module. Pure JVM-Kotlin, `compileOnly` op provider-api
  en org.json. CI zet de jar om naar DEX en publiceert gesigneerd.
- `plugin-testkit/` – JVM-host + `ContractTest` om plugins zonder Android live te testen.
- `app/` – host: plugin-loader (DexClassLoader + handtekening + rollback), Room-index,
  categorie-mapping, UI, player.

## Regels
- Plugins bevatten enkel logica; netwerk gaat altijd via `host.http` (cookie-jar per provider).
- Gebruik `ProviderException` met de juiste `ErrorKind`; `API_CHANGED` = platform veranderd.
- JSON in plugins via de helpers in `Json.kt` (`str()`, `obj()`, …): Android's org.json
  geeft "null" als string terug voor JSON-null.
- Nooit tokens of credentials loggen, committen of in PR's zetten.
- Geen DRM omzeilen: plugins leveren manifest + licentie-URL/headers, de app speelt af via Widevine.

## Status (overdracht vanuit claude.ai, 21/10/2026)
Klaar:
- Volledig skelet: provider-api, plugin-testkit, host-app (loader, Room-index, UI, player, self-update), CI-workflows, HAR-opname, agent-instructies (`.github/agent/`).
- VRT MAX-plugin: login (OIDC), tokens, GraphQL-catalogus per genre, populair-lijst van de homepage, seizoenen/afleveringen, live-zenders, stream + Widevine-licentie. Flow afgeleid van add-ons/plugin.video.vrt.nu.
- Categorieën (`app/src/main/assets/categories.json`): Vlaamse taxonomie met vaste titels, native mapping, trefwoorden en opkuisregels; rij "Moet je nu zien" uit de populair-lijsten; per-categorie positie zodat zenders om beurten verschijnen.
- Beelden: fallback-keten (liggend → staand → titel-art → gegenereerde titelkaart in merkkleur).

Geverifieerd: provider-api + VRT-plugin + testkit compileren (Kotlin 2.0); cookie-jar; ECDSA-signatuur Python→Java.
NIET geverifieerd: de Android-app is nooit gebouwd (verwacht kleine compileerfixes); de VRT-plugin is nooit live getest.

Volgende stappen (in deze volgorde):
1. `./gradlew :app:assembleDebug` laten slagen.
2. Live contracttest VRT MAX (zie Testen) en de plugin bijsturen tot alle stappen groen zijn.
3. TMDB-verrijking (enkel aanvullen: ontbrekende beelden, jaar, omschrijving; koppelen op titel+jaar+type, gecachet per dedupKey; bij twijfel niet koppelen). Gebruiker moet nog een TMDB API-sleutel aanmaken. Geen AI voor categorieën (bewuste keuze).
4. Plugins VTM GO, Play, Focus & WTV (stappen in README).

## Testen
- `./gradlew test` – unit tests; contracttests worden overgeslagen zonder credentials.
- Live: `VRTMAX_USERNAME=… VRTMAX_PASSWORD=… ./gradlew :plugins:vrtmax:test`
  → rapport in `plugins/vrtmax/build/health/vrtmax.json`.
