# Sync-Protokoll – Hintergrundabgleich v1

Stand 20.09.2026: MediaStore-Inventarisierung, persistente Upload-Queue, resumierbare Uploads und Offline-Downloads, transaktionaler Change-Feed, Partnerabgleich, privates Auto-Backup, optionaler FCM-Wakeup sowie 90-Tage-Papierkorb sind implementiert. HTTPS, JSON unter `/v1`, Medien als gestreamte Binärdaten. Serverrevisionen, Offsets und Dateigrößen werden als Dezimalstrings übertragen.

## Implementierter vollständiger Datenfluss

`MediaStore → Kontrollscan → Room upload_queue → Metadaten-POST → Upload-Sitzung/Chunks → Backend ready → Derivat-Worker → Change-Feed → Room remote_work / remote_metadata → Partnergalerie / Offline-Auftrag`

Die Galerie liest chronologisch paginierte Room-Metadaten. Feed-Verarbeitung lädt nur Metadaten, keine Thumbnail-, Optimized- oder Originalbytes. Binärdaten bleiben Lazy-Cache bzw. ausdrücklich gewählte dauerhafte Offline-Dateien.

### Hintergrundauslöser und Grenzen

- Application-Initialisierung installiert eindeutige periodische Arbeiten unabhängig vom Öffnen eines Tabs. Anmeldung löst ebenfalls Arbeit aus.
- MediaStore-ContentObserver liefert Hinweise, solange der Prozess lebt. Ein persistierter WorkManager-Content-URI-Auftrag beobachtet Bild-/Video-Änderungen ohne laufende Activity und plant sich nach Auslösung erneut.
- Ein separater lokaler Kontrollscan läuft ohne Netzwerk-Constraint; verpasste Hinweise werden dadurch nachgeholt. Der Steuerdaten-Worker benötigt CONNECTED und verwendet exponentiellen WorkManager-Backoff. Ist WLAN-only aktiv, läuft der Steuerdatenpfad weiterhin mit CONNECTED; Uploads und Offline-Downloads werden als Folgearbeit mit UNMETERED geplant. WorkManager stoppt solche Transfers bei Verlust der zulässigen Verbindung, die persistente Queue setzt sie später fort.
- Periodische lokale und Netzwerk-Arbeiten haben ein 15-Minuten-Intervall; Android darf sie durch Doze, Kontingente und Energiesparregeln später ausführen. Das ist keine zugesicherte 15-Minuten-Latenz.
- Eine OS-Dateisperre serialisiert lokale Inventarisierung und Upload-/Feed-Worker auch bei konkurrierender periodischer und unmittelbarer Arbeit. Ein Prozess-Kill gibt die Sperre frei. Recovery findet erst unter dieser Sperre statt.
- Lange Uploads und explizite Offline-Downloads verwenden WorkManager-Foreground-Ausführung mit dataSync-Benachrichtigung. Android-Kontingente gelten weiterhin; Abbrüche bleiben wiederholbar.
- WorkManager persistiert Aufträge über normale Prozessbeendigung und Geräteneustart. Androids explizites „Stopp erzwingen“, entzogene Medienberechtigungen und herstellerspezifische Hintergrundsperren können nicht umgangen werden. Nach „Stopp erzwingen“ ist erneutes Starten durch den Nutzer erforderlich.

### Serverjournal und Cursor

Schema 7 ergänzt `sync_head`, `sync_changes` und `sync_push_devices`. Album-, Asset- und Derivat-Trigger schreiben das Journal in derselben Transaktion wie die Mutation. BEFORE-STATEMENT-Trigger sperren die einzelne Zählerzeile vor Medienzeilen; das verhindert die Reihenfolgefehler einer PostgreSQL-Sequenz. Der Zähler ist für diese Zwei-Personen-Serverinstanz global. Rollback veröffentlicht weder Datenänderung noch Revision.

`GET /v1/sync/changes?cursor=…&limit=100` liefert höchstens 100 für diesen Partner sichtbare Invalidationen, nextCursor und hasMore. Jede Seite verwendet einen Repeatable-Read-High-Watermark. Der Cursor bindet Datenbank-Epoche, Paar, Nutzer und zuletzt gescannte Revision. Private Eigentümerereignisse und technische Zustände verbrauchen keine Partnerseite. Ein anderer Nutzer, eine falsche Epoche, eine zukünftige oder bereits kompaktierte Revision und ein beschädigter Cursor ergeben 410 CURSOR_RESET_REQUIRED.

Ein Ereignis enthält Revision, ALBUM/ASSET, Album-ID, optionale Asset-ID und UPSERT/DELETE/RESTORE. Partner-DELETE entfernt vor dem Cursor-Fortschritt Metadaten, Cache und dauerhafte Offline-Dateien; der kleine persistente Variantenwunsch einschließlich eines Asset-Overrides bleibt dabei als `REMOTE_DELETED` erhalten. Restore nimmt das Asset wieder auf und plant genau diese gewünschte Variante erneut. Der spätere physische Purge ist rein technisch und erzeugt kein zweites fachliches DELETE. Beim Freigabeentzug bleibt die Album-Invalidation für den bisherigen Partner sichtbar. Private nachfolgende Änderungen bleiben unsichtbar. Metadaten werden immer über aktuell autorisierte APIs gelesen, nicht aus historischen Payloads. Erneute Freigabe löst einen vollständigen, paginierten Metadatenabgleich des Albums aus.

Das Journal hat eine konfigurierbare Retention (`SYNC_CHANGE_RETENTION_DAYS`, Standard 90). Die tägliche Compaction sperrt den Head, entfernt den abgelaufenen Präfix und erneuert atomar die Epoche. Dadurch wird kein alter Cursor über eine Lücke fortgesetzt: 410 löst den bestehenden vollständigen paginierten Partneralbumabgleich aus. Ein neuer Client inventarisiert ebenfalls zuerst alle aktuell freigegebenen Alben. Ein Datenbank-Restore auf einen älteren Stand muss vor Freigabe dieselbe Epoch-Erneuerung ausführen.

### Room-Commit und Wiederanlauf

Room-Schema 8 trennt pro Server-URL und Nutzer den vollständig verarbeiteten cursor vom empfangenen pendingCursor und speichert zusätzlich Upload-Sitzungs-ID sowie bestätigten Byteoffset. Empfang einer Feed-Seite speichert Invalidationen in remote_work und pendingCursor atomar. Eine Albumaufgabe besitzt einen persistenten Metadaten-Seitencursor. Maximal 20 Aufgaben/Metadatenseiten werden pro Lauf verarbeitet.

Jede erfolgreiche Metadatenseite wird zusammen mit ihrem Aufgabenfortschritt committed. Erst wenn alle Aufgaben abgeschlossen sind, wird pendingCursor zum vollständig verarbeiteten cursor. Ein Kill davor wiederholt höchstens bereits idempotent ausgeführte Arbeit. Eine unbekannte Ereignisart wird nicht stillschweigend bestätigt. 410 verwirft den betroffenen Cursor/Arbeitsstand und baut die Remote-Metadaten neu auf; lokale Uploadwünsche bleiben erhalten.

Metadatenänderungen markieren bestehende Offline-Albumwünsche persistent als PENDING. Nachfolgendes Enqueue ist eine Optimierung: Stirbt der Prozess zwischen Room-Commit und Enqueue, plant der Kontrollabgleich die erhaltenen Wünsche erneut. Der Cursor wartet auf erfolgreich persistierte lokale Verarbeitung, nicht auf den Download aller optionalen Offline-Bytes.

### Offline-Recovery

Pro Album serialisiert eine eigene OS-Sperre die Dateiarbeit. Recovery setzt ausschließlich DOWNLOADING-Zeilen dieses Albums zurück. Albumwünsche besitzen eine monoton erhöhte lokale Generation; ein Worker darf einen neueren Modus oder Override nicht mit seinem alten Abschluss überschreiben.

Temporärdateien sind an Server-/Account-Scope, Asset, Variante, Version und Hash gebunden. Der Dateistand ist beim Wiederanlauf maßgeblich und wird sofort in Room gespiegelt; während des Streams wird der Fortschritt begrenzt häufig persistiert. 206 wird nur mit exakter Content-Range und plausibler Bodylänge angehängt; 200 startet kontrolliert neu. 429, 5xx und Netzwerkabbrüche erhalten die Part-Datei. Bei 416 wird zuerst eine bereits vollständige Part-Datei validiert. Auch eine als READY markierte Offline-Datei wird vor Wiederverwendung auf Länge und SHA-256 geprüft. Erfolgreiche Übernahme verlangt Länge, SHA-256, fsync und atomare Umbenennung; `.part` wird nie präsentiert.

### FCM: optionaler Hinweis, keine Datenquelle

Androids FirebaseMessagingService plant ausschließlich Sync-Arbeit; Payload-Inhalte verändern weder Medien noch Cursor. onDeletedMessages löst denselben Abgleich aus. Die Tokenregistrierung erfolgt idempotent über die authentifizierte PUT-API `/v1/sync/push-token`. Der Backend-Absender verwendet nur `data: {type: "sync"}`, normale Priorität und einen Collapse-Key. Er berücksichtigt ausschließlich neue freigegebene Änderungen des Partners. Upload-Leases, Heartbeats, Recovery und nicht sichtbare Derivat-Jobstatus erhöhen keine Revision und lösen keinen Push aus. Keine Mediendaten, Asset-IDs oder Benutzertexte werden an Firebase gesendet.

Push-Token, letzter ausgelieferter Hinweisstand und Retry-Zeit sind persistent. Sendefehler verwenden begrenzten exponentiellen Backoff; permanent ungültige Tokens werden entfernt. Ein Kill nach erfolgreichem Send, aber vor Checkpoint kann doppelte Hinweise verursachen; KEEP und idempotenter API-Abgleich machen diese unschädlich. Verlorene oder gedrosselte Pushes werden durch periodischen Pull kompensiert. Ein FCM-Ausfall blockiert den maßgeblichen Sync nicht.

Für reale Zustellung: Backend `FCM_ENABLED=true` und Application Default Credentials (z. B. ausschließlich lokal gemountete Service-Account-Datei über GOOGLE_APPLICATION_CREDENTIALS); Android-Gradle-Properties firebaseAppId, firebaseSenderId, firebaseApiKey und firebaseProjectId aus demselben Firebase-Projekt. Ohne Konfiguration bleibt FCM deaktiviert, der periodische Sync funktioniert weiterhin. Keine Service-Account-Schlüssel ins Repository aufnehmen.

Referenzen: [FCM-Empfang und WorkManager](https://firebase.google.com/docs/cloud-messaging/android/receive-messages), [Android-Nachrichtenpriorität](https://firebase.google.com/docs/cloud-messaging/android-message-priority).

## Implementierter Android-Zwischenstand

Ein aktivierter Album-Schalter wird gerätebezogen in Room gespeichert. Ein eindeutiger WorkManager-Auftrag legt oder verknüpft das Serveralbum idempotent, inventarisiert MediaStore batchweise und fügt neue Content-URIs mit stabiler Geräte-/MediaStore-ID in `upload_queue` ein. Eine periodische Arbeit wiederholt die Inventarisierung mindestens im von Android erlaubten 15-Minuten-Raster.

Lokale Abwesenheit darf nur nach einem erfolgreichen, vollständig durchlaufenen Scan bei `FULL`-Medienberechtigung zur DELETE-Queue führen. `PARTIAL`, `NONE`, Selected Photos Access, ein vor oder während der Abfrage verschwundenes Volume, eine fehlgeschlagene Query oder ein Berechtigungswechsel während des Scans sind ausdrücklich keine Löschsignale. Sichtbare Medien dürfen bei `PARTIAL` weiter inventarisiert werden; erst ein späterer bestätigter `FULL`-Scan reconciliert wirklich fehlende Medien.

Vor der Metadatenanlage liest Android das Original als Stream und berechnet SHA-256. `clientAssetId` und `expectedSha256` machen den Metadaten-POST wiederholbar. MediaStore-ID, Größe und Änderungszeit werden in Room gespeichert. Eine unveränderte Datei behält Queuezustand und Serverzuordnung. Bei möglicher Änderung wird der neue Hash bestimmt: ist er gleich, bleibt die Zuordnung bestehen; andernfalls wird die alte `serverAssetId` idempotent über die normale DELETE-/Papierkorblogik entfernt und erst danach die neue Client-Version hochgeladen. Dieser Ablauf gilt auch für migrierte Queuezeilen ohne bisher belastbare MediaStore-ID. Der Server vergleicht nach dem gestreamten PUT seinen eigenen Hash. Nach Prozessende werden `hashing` und `uploading` zu `retry`. Stellt ein erfolgreicher vollständiger Scan mit Vollzugriff die dauerhafte lokale Abwesenheit fest, wird eine offene Serversitzung über `DELETE /v1/assets/{id}/upload` bereinigt und die lokale Zeile terminal `ABANDONED`; temporäre Leseprobleme bleiben retryfähig.

Ausschalten setzt den persistenten lokalen Freigabewunsch zurück und serverseitig `sharedAt=NULL`. Damit verschwinden Album und Assets aus allen Partnerabfragen, ohne Eigentümerdaten oder Originale zu löschen.

## Weiterer Zielablauf (noch nicht vollständig implementiert)

1. Gerät authentifiziert sich; Server leitet Nutzer und Paar aus dem Token ab.
2. Android inventarisiert freigegebene MediaStore-Alben und speichert Befunde sowie Operationen atomar in Room. Fehlende Leseberechtigung pausiert die Inventarisierung.
3. Outbox sendet Operationen mit stabiler `operationId` und erwarteter Entitätsrevision. Server prüft Eigentum und verarbeitet Mutation, Revision, Change-Log und Idempotenzresultat in einer DB-Transaktion.
4. Dateien werden separat wiederaufnehmbar hochgeladen. Erst abgeschlossene, geprüfte Dateien erscheinen beim Partner.
5. Client liest Seiten von `GET /v1/sync/changes?cursor=...`. Änderungen und Folgecursor werden gemeinsam in einer Room-Transaktion gespeichert. Wiederholte Seiten sind unschädlich.
6. UI liest Room. Binärdaten werden bei Bedarf autorisiert geladen; persistente Partner-Offline-Dateien werden separat verwaltet.

## Uploads

`POST /v1/assets/{id}/upload-session` legt die persistente Sitzung idempotent an oder liefert ihren bestätigten Offset. `PATCH /v1/upload-sessions/{id}` schreibt höchstens `MAX_UPLOAD_CHUNK_BYTES` ab exakt diesem Offset. Android speichert Sitzungs-ID und Offset nach jeder Bestätigung in Room; Prozess-Kill, Netzwerkwechsel oder Neustart beginnen deshalb nicht bei Byte 0. Falsche Offsets und parallele Worker werden mit 409 abgewiesen.

Der Server fsynct jeden Chunk vor der Offsettransaktion. Ein Abbruch vor diesem Commit wird auf den alten Offset zurückgekürzt. Der letzte Chunk prüft Gesamtgröße und SHA-256, benennt die Part-Datei atomar um, fsynct das Elternverzeichnis und setzt Asset plus Derivatjobs transaktional auf `ready`. Bleibt der Prozess zwischen Rename und DB-Commit stehen, erkennt die Stale-Lease-Recovery die vollständige finale Datei und schließt den Commit ab. Sitzungs-TTL, Chunkgröße, Lease und Recoveryintervall sind konfigurierbar; Pfade bleiben relativ zum konfigurierten Medienroot.

## Künftige Entitätskonflikte und Snapshot-Retention

Serverrevisionen steigen pro Entität. Ein falsches `expectedRevision` ergibt 409 samt aktuellem Zustand; kein Last-write-wins nach Geräteuhr. Originalbytes werden nie überschrieben. Geänderte lokale Bytes werden als neues Medium behandelt. Nur Eigentümer dürfen Metadaten, Mitgliedschaften oder Papierkorb ändern.

Idempotenzschlüssel sind je Gerät eindeutig; Request-Hash verhindert Wiederverwendung mit anderem Inhalt (409). Ergebnis und Mutation werden gemeinsam committed. Client bestätigt erledigte Operationen dauerhaft. Nach Ablauf der noch festzulegenden Idempotenz-Retention muss ein altes Gerät neu abgleichen, statt ungeprüft Operationen erneut abzusetzen. Stabile Client-Entitäts-IDs mit Unique-Constraints verhindern insbesondere doppelte Medienanlage.

Change-Sequenzen müssen in Commit-Reihenfolge sichtbar werden: pro Paar eine gesperrte Zählerzeile innerhalb der Mutationstransaktion erhöhen. Eine ungesicherte PostgreSQL-Sequenz allein genügt nicht, weil spätere Nummern früher committen könnten. Seiten laufen bis zu einem festen High-Watermark; Cursor bindet Paar, Nutzer, Zugriffsrevision und Sequenz. ACL-Änderungen liefern explizite Entfernungsereignisse für den bisherigen Empfänger.

Bei ungültigem oder abgelaufenem Cursor: `410 sync_reset_required`. Initialer/erneuter Snapshot ist über Seiten konsistent und enthält einen zugehörigen Anschlusscursor. Umsetzung etwa durch materialisierte Snapshot-Sitzung; keine unabhängigen Live-Abfragen mit übersprungenen Zwischenänderungen. Client ersetzt Servermetadaten atomar, behält getrennte lokale Outbox und prüft deren Operationen gegen den neuen Zustand.

## Freigaben, Löschungen und Offline-Geräte

Abwählen beendet Partnerzugriff, behält Eigentümerdaten. Entfernen aus einem Quellalbum entfernt die entsprechende Mitgliedschaft erst nach bestätigtem vollständigem Scan, löscht aber nicht das Servermedium. Lokales Löschen wird nicht automatisch zum Papierkorbauftrag. Berechtigungsverlust löst niemals Massenlöschungen aus.

Expliziter Papierkorbauftrag setzt `deletedAt` und `purgeAfter = deletedAt + 90 Tage` nach Serverzeit und erzeugt bei geteilter Ressource genau ein DELETE-Ereignis. Partner können nicht wiederherstellen oder löschen. Restore ist vor `purgeAfter` nur aus `deleted` erlaubt, nachdem Größe und SHA-256 des Originals geprüft wurden, und erzeugt genau ein RESTORE-Ereignis. Der Cleanup führt `deleted → purging → purged`; `purging` ist nach Beginn der irreversiblen Dateilöschung nicht wiederherstellbar. Eine persistierte Lease verhindert parallele Claims, und nur abgelaufene Leases werden nach einem Prozessabbruch übernommen. Dateifehler lassen das Asset in `purging`, sodass der nächste Lauf idempotent fortsetzt. Die Asset-Zeile bleibt als Tombstone bestehen, auch wenn Original, Optimized und Thumbnail physisch entfernt sind. Private Assets erzeugen keine Partneränderung.

Offline-Clients sehen bereits gespeicherte Inhalte bis zum nächsten Kontakt; ein sofortiger Fernentzug ist offline technisch nicht möglich. Nach Abgleich werden entzogene Inhalte samt Offline-Pins entfernt. Asset-Tombstones bleiben dauerhaft in PostgreSQL; das separate Change-Journal hat weiterhin seine Epoch-/Cursor-Retention, bei der ältere Cursor einen vollständigen Snapshot erzwingen. So kann ein lange offline gewesenes Gerät ein endgültig bereinigtes Asset nicht wieder einführen.

## Fehler und Übertragung

401: Android entfernt die betroffene ungültige Gerätesession, ohne eine inzwischen neu angemeldete Session zu löschen; 403/404: keine Berechtigung; 409: Konflikt; 410: Feed-Reset; 413: zu groß; 429/5xx/Netzausfall: WorkManager-Backoff. Worker binden ihren HTTP-Client an den zu Laufbeginn gelesenen Token. Speicherfehler sind kein Erfolg. Manuelles Aktualisieren, Push und periodische Arbeit nutzen denselben maßgeblichen Sync-Pfad.

Original, Thumbnail und optimierte Variante unterstützen einzelne HTTP-Byte-Ranges und besitzen je einen stabilen, aus SHA-256 gebildeten ETag. Vor jeder Originalauslieferung werden Datei, Größe und SHA-256 gegen die DB geprüft; ein Hintergrundjob führt dieselbe Prüfung schrittweise aus und meldet Fehler in `/health`. Die Freigabeprüfung gilt auch für Teilanfragen. Derivatgrößen, Tool-Timeout und Retry-Budget sind serverseitig festgelegt; temporäre Infrastrukturfehler bleiben mit Backoff auch über dieses Budget hinaus automatisch retryfähig; das Profil steht in [media-derivatives.md](media-derivatives.md). Mehrfachranges bleiben offen; Partner-Offline-Budgets und Zustände sind clientseitig implementiert.


## Implementierte Partner-Offline-Downloads

Der Partner kann pro freigegebenem Album NONE, OPTIMIZED oder ORIGINAL wählen. Albumwunsch und Datei-Overrides liegen in Room und sind mit normalisierter Serveradresse, Nutzer-ID und Album-/Asset-ID gescopt. Dateipfade verwenden den SHA-256 desselben Scopes. Der Metadaten-Seitencursor wird nach jeder vollständig verarbeiteten Seite atomar fortgeschrieben und bei relevanter Serveränderung zusammen mit einer neuen Generation invalidiert. Ein Worker lädt nie ein komplettes Album in den RAM.

Jeder Download besitzt eine persistente DOWNLOADING-Zeile und eine versionierte .part-Datei. Bei Logout werden alle Offline-Wünsche des alten Scopes vor Entfernen der Session auf NONE gesetzt und scoped zur Löschung eingeplant. Der flüchtige Bildcache validiert vorhandene Finals per SHA-256, schreibt ausschließlich `.part` und übernimmt auch im Copy-Fallback atomar; nach einem Kill werden Parts oder ungültige Finals beim nächsten Zugriff verworfen. Der Cache wird synchron gelöscht; ein Löschfehler bleibt als persistenter Cleanup-Auftrag erhalten. Eine neue Session kann alte Dateien wegen Scope in Room, Pfad und Cache-Key nicht adressieren. Ungescopte Altbestände aus Schema 5 erhalten einen unbenutzbaren Legacy-Scope und einen retryfähigen Cleanup-Auftrag. Zu wenig Speicher führt zu sichtbarem FAILED und wird nach Freigabe von Platz automatisch erneut versucht.

## Race-/Failure-Audit dieses Schrittes

Geprüft und abgesichert: Commit-Reihenfolge und Rollback des Feeds; Cursor-Replay/Scope/Reset; Freigabeentzug; Kill zwischen Feed-, Room- und Cursorcommit; Uploadabbruch innerhalb eines Chunks; persistenter Resume-Offset; parallele Uploadworker; stale Upload-/Derivat-Leases; Crash zwischen atomarem Original-Rename und DB-Commit; Derivat-Rename ohne DB-Commit; fehlendes READY-Derivat; Offline-Range-Abbruch, beschädigte Parts, Hash-/Größenfehler und voller Speicher; verlorene/doppelte Pushes.

Automatisierte Nachweise umfassen echte PostgreSQL-Transaktionen/Dateioperationen, HTTP-Routen, simuliertes `ENOSPC`, Room/Robolectric mit DB-Neustart, Range-/Part-Entscheidungen und beschädigte Dateien. Android-Instrumentationstests kompilieren mit vollständiger Room-Schemavalidierung, liefen in diesem Schritt aber nicht auf realer Hardware. Echter Prozess-Kill, Geräteneustart, Doze, Flugmodus, WLAN/Mobilfunk-Wechsel, voller physischer Handyspeicher und OEM-Hintergrundverhalten benötigen Gerät oder Emulator; echte FCM-Zustellung benötigt zusätzlich ein Firebase-Projekt.
