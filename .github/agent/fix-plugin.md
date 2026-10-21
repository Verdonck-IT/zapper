# Werkwijze: een kapotte plugin herstellen

Je wordt gestart omdat de nachtelijke contracttest van één of meer plugins faalde.

## 1. Diagnose
1. Lees `.agent/health/<id>.json`. De eerste stap met `"ok": false` is waar het brak
   (`login` → `catalog` → `details` → `stream` → `manifest`).
2. Lees de plugincode in `plugins/<id>/src/main/kotlin/`.
3. Vergelijk met de echte webflow in `.agent/har/<id>.har` (indien aanwezig). Zoek de
   requests die overeenkomen met de falende stap en kijk wat er anders is: URL, methode,
   headers, body, GraphQL-veldnamen, JSON-structuur van het antwoord, redirects, cookies.
   Gevoelige waarden zijn gemaskeerd (`<redacted>`); de namen kloppen wel.
4. Kijk ook in de upstream Kodi-add-on (repo in `.github/agent/upstream.json`).
   Daar staat vaak al een fix. Clone indien nodig naar `/tmp`, niet in de repo.

## 2. Fix
- Wijzig enkel `plugins/<id>/**`. Raak `provider-api/` niet aan: dat breekt alle plugins
  en vereist een nieuwe app-release. Lijkt een contractwijziging echt nodig, stop dan en
  beschrijf het in de PR zonder het zelf door te voeren.
- Houd de fix minimaal en gericht. Geen refactors.
- Voeg een unit test toe in `plugins/<id>/src/test` die de nieuwe parsing/URL vastlegt
  (zonder netwerk), naast de bestaande tests.

## 3. Verifiëren
- `./gradlew :plugins:<id>:test` moet slagen, inclusief de live contracttest
  (credentials staan in de omgeving). Lees `plugins/<id>/build/health/<id>.json`.
- Faalt hij nog, ga terug naar stap 1. Maximaal 3 pogingen; daarna een PR als draft
  met je bevindingen.

## 4. Pull request
- Branch: `auto-fix/<id>-<yyyymmdd>`.
- Titel: `fix(<id>): <korte omschrijving>`; label `auto-fix` (`gh pr create --label auto-fix`).
- Beschrijving: welke stap faalde, wat er in de webflow veranderd is (met de relevante
  request uit de HAR, samengevat), wat je aanpaste, en de output van de contracttest.

## Nooit
- Credentials, tokens, cookies of HAR-bestanden committen of in de PR zetten.
- Iets onder `.agent/` committen.
- DRM omzeilen of streams ontsleutelen. De plugin levert enkel manifest + licentie-URL;
  afspelen gebeurt via Widevine in de app.
- Mergen. Ivo reviewt en merget zelf; daarna publiceert CI de plugin.
