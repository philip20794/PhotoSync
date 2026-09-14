# Sync-Protokoll – Entwurf v1

Noch nicht implementiert; Pfade sind Vorschläge. HTTPS, JSON unter `/v1`, Medien als gestreamte Binärdaten. Server-UUIDs identifizieren Entitäten, UTC-Zeitstempel dienen Anzeige und Fristen, niemals der Konfliktordnung. Große Zähler/Dateigrößen im JSON als Dezimalstrings übertragen.

## Ablauf

1. Gerät authentifiziert sich; Server leitet Nutzer und Paar aus dem Token ab.
2. Android inventarisiert freigegebene MediaStore-Alben und speichert Befunde sowie Operationen atomar in Room. Fehlende Leseberechtigung pausiert die Inventarisierung.
3. Outbox sendet Operationen mit stabiler `operationId` und erwarteter Entitätsrevision. Server prüft Eigentum und verarbeitet Mutation, Revision, Change-Log und Idempotenzresultat in einer DB-Transaktion.
4. Dateien werden separat wiederaufnehmbar hochgeladen. Erst abgeschlossene, geprüfte Dateien erscheinen beim Partner.
5. Client liest Seiten von `GET /v1/sync/changes?cursor=...`. Änderungen und Folgecursor werden gemeinsam in einer Room-Transaktion gespeichert. Wiederholte Seiten sind unschädlich.
6. UI liest Room. Binärdaten werden bei Bedarf autorisiert geladen; persistente Offline-Pins kommen später.

## Uploads

`POST /v1/uploads` reserviert eine Sitzung für Eigentümer, Client-Medium-ID, Größe, MIME-Typ und SHA-256. Gleiche Operation liefert dieselbe Sitzung. Server begrenzt Größen und Quoten. `GET /v1/uploads/{id}` meldet bestätigten Offset; `PATCH` mit erwartetem Offset schreibt einen begrenzten Chunk. Falscher Offset ergibt 409 mit aktuellem Stand; nach Timeout fragt der Client zuerst den Stand ab. Uploads werden sitzungsweise gesperrt.

`POST /v1/uploads/{id}/complete` prüft Länge und Hash der tatsächlich gespeicherten Bytes, benennt die temporäre Datei atomar um und schaltet das Medium anschließend in der DB auf `ready`. Abschluss ist idempotent. Abstürze zwischen Dateioperation und DB-Commit werden anhand Sitzung, deterministischem Objektschlüssel und Prüfsumme repariert. Unfertige Dateien sind unsichtbar. Abgelaufene Sitzungen werden bereinigt; genaue TTL und Chunkgröße folgen vor Implementierung. Keine nutzerübergreifende Hash-Deduplizierung oder Existenz-Auskunft.

## Ordnung, Wiederholung und Konflikte

Serverrevisionen steigen pro Entität. Ein falsches `expectedRevision` ergibt 409 samt aktuellem Zustand; kein Last-write-wins nach Geräteuhr. Originalbytes werden nie überschrieben. Geänderte lokale Bytes werden als neues Medium behandelt. Nur Eigentümer dürfen Metadaten, Mitgliedschaften oder Papierkorb ändern.

Idempotenzschlüssel sind je Gerät eindeutig; Request-Hash verhindert Wiederverwendung mit anderem Inhalt (409). Ergebnis und Mutation werden gemeinsam committed. Client bestätigt erledigte Operationen dauerhaft. Nach Ablauf der noch festzulegenden Idempotenz-Retention muss ein altes Gerät neu abgleichen, statt ungeprüft Operationen erneut abzusetzen. Stabile Client-Entitäts-IDs mit Unique-Constraints verhindern insbesondere doppelte Medienanlage.

Change-Sequenzen müssen in Commit-Reihenfolge sichtbar werden: pro Paar eine gesperrte Zählerzeile innerhalb der Mutationstransaktion erhöhen. Eine ungesicherte PostgreSQL-Sequenz allein genügt nicht, weil spätere Nummern früher committen könnten. Seiten laufen bis zu einem festen High-Watermark; Cursor bindet Paar, Nutzer, Zugriffsrevision und Sequenz. ACL-Änderungen liefern explizite Entfernungsereignisse für den bisherigen Empfänger.

Bei ungültigem oder abgelaufenem Cursor: `410 sync_reset_required`. Initialer/erneuter Snapshot ist über Seiten konsistent und enthält einen zugehörigen Anschlusscursor. Umsetzung etwa durch materialisierte Snapshot-Sitzung; keine unabhängigen Live-Abfragen mit übersprungenen Zwischenänderungen. Client ersetzt Servermetadaten atomar, behält getrennte lokale Outbox und prüft deren Operationen gegen den neuen Zustand.

## Freigaben, Löschungen und Offline-Geräte

Abwählen beendet Partnerzugriff, behält Eigentümerdaten. Entfernen aus einem Quellalbum entfernt die entsprechende Mitgliedschaft erst nach bestätigtem vollständigem Scan, löscht aber nicht das Servermedium. Lokales Löschen wird nicht automatisch zum Papierkorbauftrag. Berechtigungsverlust löst niemals Massenlöschungen aus.

Expliziter Papierkorbauftrag setzt `trashedAt` und `purgeAfter = trashedAt + 30 Tage` nach Serverzeit und erzeugt ein Entfernungsereignis. Partner können nicht wiederherstellen oder löschen. Restore ist vor `purgeAfter` möglich; Restore und Purge sperren dieselbe Medienzeile. Ab Frist ist Restore ausgeschlossen, auch wenn der Job verspätet läuft. Purge ist wiederholbar, erst nach erfolgreicher Dateientfernung abgeschlossen. Tombstones/Entfernungsereignisse leben unabhängig von der 30-Tage-Dateifrist.

Offline-Clients sehen bereits gespeicherte Inhalte bis zum nächsten Kontakt; ein sofortiger Fernentzug ist offline technisch nicht möglich. Nach Abgleich werden entzogene Inhalte samt Offline-Pins entfernt. Tombstone-/Log-Retention wird vor Umsetzung festgelegt; ältere Cursor erzwingen vollständigen Snapshot, damit gelöschte Medien nicht wieder auftauchen.

## Fehler und Übertragung

401: Anmeldung erneuern; 403/404: keine Berechtigung; 409: Konflikt/Offset; 410: Zustand abgelaufen; 413: zu groß; 429: `Retry-After`; 5xx/Netzausfall: exponentiell mit Jitter wiederholen. Speicherfehler pausieren Uploads ohne Erfolgsmeldung. WorkManager bündelt Arbeit; manuelles Aktualisieren nutzt denselben Sync-Pfad. Keine Push-Cloud erforderlich, zunächst Pull beim Öffnen und periodisch nach Android-Möglichkeiten.

Downloads unterstützen später HTTP Range und einen stabilen ETag pro Variante. Freigabeprüfung gilt auch für Teilanfragen. Original und optimierte Variante besitzen getrennte Prüfsummen und Cache-Schlüssel. Größen, Timeouts und Retry-Budgets müssen vor Featurebau konkretisiert werden.
