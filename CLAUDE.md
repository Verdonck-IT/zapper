# Zapper – context voor Claude

Android TV-app (Kotlin, Compose for TV, Media3) met een Netflix-achtige interface die
Vlaamse streamingplatformen bundelt. Persoonlijk gebruik, gesideload.

Documentatie: `README.md` is voor gebruikers (met screenshots in `docs/screenshots/`); technische zaken
(architectuur, CI/CD, setup, nieuwe zender) staan in `docs/TECHNISCH.md`. Hou beide bij bij nieuwe functies.

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
- Kijkvoortgang van het platform (provider-api v2: `ProviderInfo.syncsProgress`, `continueWatching()`, `reportProgress()`,
  `Episode.progress`, `StreamInfo.resumePositionSeconds`). Rij "Verder kijken" = lijst van het platform; lokale voortgang
  (< 6 u) gaat voor. Detail: volgende aflevering klaar, bekeken afleveringen/seizoenen gemarkeerd. Zie `data/ProgressSync.kt`.
- Player: ◀/▶ spoelen (10 s, ingedrukt 30/60 s), OK = pauze, ▼ = CC-knop, CC-toets = ondertitels aan/uit (onthouden).
  Live: knop links van CC in de bedieningsbalk, "● LIVE" of (> 10 s achter) "⏭ Naar live (−m:ss)".
- Zoekscherm: zoekbalk + categoriefilters + raster A–Z, accentongevoelig, lokaal op de Room-index.
- Startscherm: sidebar (Home, Zoeken, Live tv, Instellingen) via ◀ op de linkerrand of terug bovenaan; terug sluit de app nooit.
- Live tv: eigen pagina met officiële zenderlogo's (VRT `brandLogos`, dag gecachet, vaste URL als terugval).
- Mijn lijst (provider-api v3: `supportsMyList`, `myList()`, `setInMyList()`): VRT MAX via favorieten (GraphQL `setFavorite`),
  Play via `/tv/v1/programs/myList`. Rij op het startscherm, eigen pagina in de sidebar, knop op de detailpagina.
  Lezen live getest (VRT: 20 favorieten); toevoegen/verwijderen bij het platform niet automatisch getest.
- Verwijderen uit "Verder kijken" (provider-api v4: `supportsContinueRemoval`, `removeFromContinueWatching()`): OK ingedrukt
  houden op de kaart. VRT MAX via `listDelete` (ListDeleteAction van de tegel), Play via DELETE continue-watching.
  Menu getest; het verwijderen zelf niet automatisch (schrijft in het account).
- "Verder kijken" opent altijd eerst het programma; ontbreekt het programma-id, dan zoekt `ProgressSync` het op titel.
- Inactiviteit (`core/IdleController.kt`): 5 min (gepauzeerd: 3 min) → OLED-veilige screensaver; 15 min later scherm loslaten
  → systeemslaap. Tijdens afspelen nooit.
- Play-plugin (`plugins/play`, vroeger GoPlay): Cognito-SRP-login (bit voor bit getest tegen de Python-code van
  add-ons/plugin.video.play), A–Z-catalogus zonder Play Kinepolis (huren/kopen), details met liggend beeld en genres,
  "Moet je zien" en "Kijk verder" van de homepage, live-zenders, streams met Widevine via KeyOS (`customdata`-header).
  Publieke delen live getest (636 programma's, live-zenders); login, afleveringen, streams en voortgang NIET (geen account).
- Focus & WTV-plugin (`plugins/focuswtv`): enkel de twee livestreams, zonder login. Stream-URL uit de embed-speler
  van focus-wtv.be/focus-live en /wtv-live (gewone HLS op Bunny CDN), dag gecachet, vaste URL als terugval.
  Live contracttest draait altijd (geen credentials nodig). Programma's op aanvraag nog niet.
- Live tv-volgorde: `liveOrder` in categories.json (op titel, populairste eerst naar CIM-marktaandeel; schatting,
  geen officiële jaartabel 2025 gevonden). Onbekende zenders achteraan.
- Beelden: fallback-keten (liggend → staand → titel-art → gegenereerde titelkaart in merkkleur).
- Login (26/10/2026): refresh token eerst, wachtwoord als stille terugval, bewaakt door `LoginGuard`
  (`plugin-shared/`, als bron per plugin gecompileerd): netwerk/throttling wist geen tokens meer, oplopende pauze
  15 min → 1 u → 4 u, geweigerde credentials niet automatisch opnieuw. `isLoggedIn()` gooit NETWORK i.p.v. false
  bij tijdelijke fouten. Testkit bewaart tokens + credentials in `~/.zapper/testkit/` (zie docs/TECHNISCH.md).
  Unit tests zonder netwerk: `VrtAuthTest`, `LoginGuardTest`, `PlayAuthTest`. Nog niet live/op toestel getest.

Geverifieerd: provider-api + VRT-plugin + testkit compileren (Kotlin 2.1); cookie-jar; ECDSA-signatuur Python→Java;
`./gradlew :app:assembleDebug` slaagt (21/10/2026, zonder compileerfouten of -waarschuwingen) en `./gradlew test` is groen.
VRT MAX live getest (21/10/2026): login, alle 20 categorieën (incl. paginering), 5 live-zenders (manifest HTTP 200),
on-demand streams met en zonder Widevine (licentie-URL vudrm/drm.technology bereikbaar). Rapport: `plugins/vrtmax/build/health/vrtmax.json`.
VRT `reportProgress` (POST ddt.profiel.vrt.be/resumePoints, zoals de Kodi-add-on) is niet expliciet live getest.
NIET geverifieerd: Widevine-licentie en afspelen enkel op toestel te testen.
Bekend: live-zenders hebben geen beeld vanuit de plugin (app valt terug op gegenereerde titelkaart).

Lokale setup: `local.properties` met `sdk.dir` (git-ignored); de foojay-resolver in `settings.gradle.kts` haalt zelf een JDK 17
binnen als Gradle er lokaal geen vindt.

Volgende stappen (in deze volgorde):
1. ~~`./gradlew :app:assembleDebug` laten slagen.~~ Klaar.
2. ~~Live contracttest VRT MAX.~~ Klaar, alle stappen groen.
3. TMDB-verrijking (enkel aanvullen: ontbrekende beelden, jaar, omschrijving; koppelen op titel+jaar+type, gecachet per dedupKey; bij twijfel niet koppelen). Gebruiker moet nog een TMDB API-sleutel aanmaken. Geen AI voor categorieën (bewuste keuze).
4. Plugin VTM GO; programma's op aanvraag voor Focus & WTV (stappen in README). Play: live contracttest met `PLAY_USERNAME/PASSWORD`.

## Distributie
- Repo is publiek: https://github.com/Verdonck-IT/zapper. Dist-site via GitHub Pages (branch `gh-pages`):
  https://verdonck-it.github.io/zapper/ (standaard `zapperUpdateBaseUrl`).
- Publiceren van plugins vraagt nog het secret `PLUGIN_SIGNING_KEY` (+ variabele `ZAPPER_PLUGIN_PUBLIC_KEY` voor de app);
  zonder sleutel bouwt de workflow enkel. Nachtelijke check: variabele `HEALTH_RUNNER=true` + runner `zapper-be`.

## Testen
- `./gradlew test` – unit tests; contracttests worden overgeslagen zonder credentials.
- Live: `VRTMAX_USERNAME=… VRTMAX_PASSWORD=… ./gradlew :plugins:vrtmax:test`
  → rapport in `plugins/vrtmax/build/health/vrtmax.json`. Zelfde voor Play met `PLAY_USERNAME/PLAY_PASSWORD` en `:plugins:play:test`.
  Credentials mogen ook in `~/.zapper/testkit/credentials.properties`; tokens blijven in `~/.zapper/testkit/<zender>.properties`
  bewaard, dus herhaald testen gebruikt het refresh token en logt niet telkens met wachtwoord in.
