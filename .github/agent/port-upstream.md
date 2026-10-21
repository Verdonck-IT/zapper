# Werkwijze: upstream-wijzigingen beoordelen

De Kodi-add-on waarop een plugin gebaseerd is, kreeg nieuwe commits.

1. Lees `.agent/upstream/<id>.log` en `.agent/upstream/<id>.diff`.
2. Beoordeel per wijziging of ze voor onze plugin relevant is. Relevant zijn:
   endpoints, client-ids/versies, headers, loginflow, tokens, GraphQL-queries en -velden,
   stream- of licentielogica. Niet relevant: Kodi-UI, vertalingen, menustructuur, tests.
3. Niets relevant? Stop zonder wijzigingen. Schrijf één zin waarom.
4. Wel relevant? Port het naar `plugins/<id>/` (enkel daar), voeg een unit test toe,
   draai `./gradlew :plugins:<id>:test` en open een PR:
   - branch `auto-port/<id>-<yyyymmdd>`, titel `port(<id>): <omschrijving>`, label `auto-fix`
   - beschrijving met de upstream-commit(s) en wat je overnam.
5. Nooit mergen, nooit `provider-api/` wijzigen, niets onder `.agent/` committen.
