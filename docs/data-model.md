# Datenmodell

Implementiert sind technische Baseline, Authentifizierung, Album-/Originaldateien, der 90-Tage-Papierkorb und wiederaufnehmbare Transfers. Die aktuelle PostgreSQL-Schema-Version ist **13**. Migration 13 verwirft bewusst alle früheren PhotoSync-Anwendungsdaten und führt Passwortkonten sowie kontoweit stabile Albumquellen ein. PostgreSQL enthält ausschließlich Metadaten; Originalbytes liegen unter dem konfigurierten Medienpfad.

UUIDs sind stabile Server-IDs. Zeitpunkte werden als `timestamptz(3)` in UTC gespeichert. Dateigrößen und Videodauer sind `bigint` und erscheinen in JSON als Dezimalstrings, damit Android und JavaScript keine Genauigkeit verlieren.

## Implementierte Tabellen

| Tabelle / Prisma-Modell | Wesentliche Felder und Regeln |
| --- | --- |
| `service_metadata` / ServiceMetadata | Technische Schema-Version; aktuell 13 |
| pairs / Pair | Eine Instanzzeile und gemeinsame Sperre für lokale Account-Mutationen |
| users / User | UUID, eindeutiger normalisierter Benutzername, Anzeigename, Argon2id-Hash und Accountslot |
| `devices` / Device | Eigenes Gerät und optionaler eindeutiger Credential-Hash; widerrufene Geräte haben keinen Hash |
| albums / Album | UUID, ownerId, Quellgerät, clientAlbumId, sourceVolume, sourceRelativePath, Titel, sharedAt und backedUpAt |
| `assets` / Asset | UUID, Besitzer und genau ein Album, Originalmetadaten, Quellgeräte-/Client-ID, erwartete und tatsächliche SHA-256, relativer Speicherpfad, Uploadstatus, Integritätsdiagnose und Zeitpunkte |
| `asset_derivatives` / AssetDerivative | Pro Asset je eine Art `thumbnail` und `optimized`; persistenter Jobstatus, Ausgabemetadaten, Hash, Versuche, Fehler und nächste Ausführungszeit |

Prisma verwaltet zusätzlich `_prisma_migrations`.

## Alben und Freigabe

Ein Album gehört genau einem Nutzer. `ownerId` ist ein Pflicht-Fremdschlüssel auf `users`. Das Quellgerät wird zusammen mit demselben Besitzer über den zusammengesetzten Fremdschlüssel `(sourceDeviceId, ownerId) → devices(id, userId)` abgesichert. Damit kann selbst eine direkte Datenbankoperation kein fremdes Gerät als Quelle eintragen.

Die stabile Albumidentität ist UNIQUE(ownerId, sourceVolume, sourceRelativePath). Volume und Pfad werden serverseitig NFKC-normalisiert, getrimmt, slash-normalisiert und kleingeschrieben. clientAlbumId und sourceDeviceId dürfen sich nach einer Neuinstallation ändern; die Server-UUID, Freigabe und Backup-Zuordnung bleiben bestehen.

`sharedAt` ist gesetzt, solange das Album für den anderen Account desselben `Pair` freigegeben ist. Ausschalten setzt es auf `NULL`, ohne Eigentümerdaten oder Originale zu löschen. Der Eigentümer darf das Album weiterhin lesen und erneut freigeben. Der Partner darf nur Alben mit `sharedAt`, darin ausschließlich fertige Assets und deren Originale lesen. Quellgerät und lokale Album-ID werden dem Partner nicht ausgegeben.

`backedUpAt` ist davon unabhängig: Es markiert, dass das Album serverseitig als privates Auto-Backup erhalten bleiben soll. Ein privates Backup wird nie über Partnerabfragen oder den Partner-Change-Feed sichtbar. Wird ein solches Album später geteilt, setzt dieselbe Serverressource `sharedAt`; Assets und Originale werden nicht ein zweites Mal hochgeladen. Das Ausschalten von Auto-Backup entfernt weder `backedUpAt` noch vorhandene Originale.

## Assets und Originale

Ein Asset gehört über `albumId` genau einem Album und besitzt zusätzlich `ownerId`. Der zusammengesetzte Fremdschlüssel `(albumId, ownerId) → albums(id, ownerId)` erzwingt, dass Asset und Album denselben Eigentümer haben.

Gespeicherte Felder:

- `id`: stabile Server-UUID.
- `ownerId`, `albumId`: Besitzer und genau ein Album.
- `originalFileName`: reine Metadaten, niemals Bestandteil eines Dateisystempfads.
- `mimeType`: derzeit nur `image/*` oder `video/*`.
- `capturedAt`: optional, weil Android-Medien nicht immer einen verlässlichen Aufnahmezeitpunkt liefern.
- `fileSize`: erwartete und nach Upload geprüfte Originalgröße, größer null.
- `width`, `height`: positive Pixelmaße.
- `durationMillis`: bei Videos erforderlich, bei Bildern nicht erlaubt.
- `sourceDeviceId`, `clientAssetId`: optionale gekoppelte Quellidentität; Android setzt beide stabil pro MediaStore-Datensatz.
- `expectedSha256`: vor dem Upload clientseitig gestreamt berechneter Hash zur Inhaltsprüfung und Deduplizierung im Album.
- `sha256`: serverseitig über die tatsächlich empfangenen Originalbytes berechnet; bei `ready`, `deleted` und `purging` gesetzt, nach erfolgreichem Purge geleert.
- `integrityStatus`, `integrityError`, `integrityCheckedAt`: persistenter `healthy`- oder `error`-Diagnosezustand für fehlende, falsch große oder SHA-abweichende aktive Originale.
- `storagePath`: eindeutiger, servergenerierter relativer Pfad.
- `status`: `pending`, `uploading`, `ready`, `failed`, `deleted`, `purging` oder `purged`. `deleted` und `purging` behalten den Hash bis zur erfolgreichen Dateilöschung; `purged` ist der dauerhafte Tombstone.
- `uploadSessionId`, `uploadOffset`, `uploadPartPath`, `uploadExpiresAt`: persistente, eindeutige Upload-Sitzung mit ausschließlich serverbestätigtem Offset. Diese Felder sind genau im Zustand `uploading` gesetzt.
- `uploadLeaseId`, `uploadLeaseExpiresAt`: kurzlebiger exklusiver Claim für genau einen Chunk beziehungsweise die Finalisierung; die Sitzungs-TTL ist davon unabhängig und länger.
- `cleanupLeaseId`, `cleanupLeaseExpiresAt`: exklusiver, zeitlich begrenzter Claim für den physischen Purge. Ein abgebrochener `purging`-Lauf bleibt irreversibel und wird erst nach Lease-Ablauf übernommen.
- `createdAt`, `updatedAt`: Erstellungs- und Änderungszeit.

SQL-CHECKs sichern positive Größen und Dimensionen, MIME-/Dauer-Konsistenz, gekoppelte Clientidentität, Hashformate, erlaubte Statuswerte sowie die Regel: `ready`, `deleted` und `purging` besitzen einen serverseitig berechneten SHA-256-Hash. Eindeutige Constraints auf `(sourceDeviceId, clientAssetId)` und `(albumId, expectedSha256)` verhindern doppelte Anlage nach verlorenen Antworten oder erneutem Scan. Der interne `storagePath` ist eindeutig und wird in API-Antworten nicht veröffentlicht.

## Uploadzustände und Dateisystem

Der Originalupload ist chunkweise und über App-/Serverneustarts fortsetzbar:

```text
Metadaten anlegen → pending
Sitzung anlegen → uploading(offset = 0)
Chunk mit passendem Offset + fsync → uploading(offset += Bytes)
vollständig + SHA-256 + atomare Umbenennung + DB-Commit → ready
Hashfehler → failed; Transport-/Speicherfehler → uploading mit letztem bestätigten Offset
```

Der Server schreibt alle Chunks einer Sitzung in:

```text
uploads/<assetId>-<uploadSessionId>.part
```

Jeder Request muss am persistierten Offset beginnen, eine exakte `Content-Length` besitzen und unter `MAX_UPLOAD_CHUNK_BYTES` liegen. Erst nach dem letzten Chunk prüft der Server Gesamtgröße und SHA-256 der vollständigen Datei. Danach wird sie auf demselben Dateisystem atomar nach folgendem servergenerierten Ziel umbenannt:

```text
originals/<ownerId>/<assetId>/original
```

Erst danach setzt eine durch Sitzung und Lease bedingte Datenbanktransaktion Status und Hash auf `ready` und legt die Derivatjobs an. Ein abgebrochener Chunk wird auf den letzten DB-bestätigten Offset zurückgekürzt. Stale-Lease-Recovery gleicht DB-Offset und `.part`-Länge ab; ein bereits umbenanntes vollständiges Original wird nach Größen- und Hashprüfung fertig committed. Abgelaufene Sitzungen und nicht referenzierte Upload-Parts werden idempotent entfernt. Ein periodischer Integritätsjob und jeder Originaldownload prüfen aktive Originale auf Dateiart, Größe und SHA-256. Fehlerhafte Originale werden nicht mit falschem ETag ausgeliefert, aus Partnerlisten ausgeblendet und in `/health` als `degraded` gemeldet; ohne sichere Quelle erfolgt keine destruktive Reparatur. Ein Hashfehler verwirft die Sitzung als `failed`; bereits bestätigte Bytes werden bei Netzwerk- oder Speicherfehlern nicht neu übertragen.

Der relative Pfad wird gegen den aktiven `MEDIA_DEV_ROOT` oder `MEDIA_PROD_ROOT` aufgelöst. Absolute Pfade und `..`-Ausbrüche werden abgelehnt. DB und Originaldateien müssen zusammen gesichert werden; ein Datenbankbackup enthält keine Mediendaten. Ein optionaler, außerhalb des Medienroots liegender `CATALOG_ROOT` wird aus diesen Metadaten als lokale Symlink-Projektion aufgebaut. Er enthält keine zusätzlichen Medienbytes und ist niemals API- oder Partnerdatenquelle; fehlende, unvollständige, bereits purgte oder als fehlerhaft markierte Originale werden darin nicht verlinkt.

## Derivate und Jobzustände

`UNIQUE(assetId, kind)` garantiert genau höchstens eine Zeile pro Variante; nach einem fertigen Upload werden beide Zeilen idempotent angelegt. `kind` ist `thumbnail` oder `optimized`, `status` ist `pending`, `processing`, `ready` oder `failed`. `processingLeaseId` und `processingLeaseExpiresAt` sind im Zustand `processing` verpflichtend; nur abgelaufene Claims werden übernommen. `attempts`, `lastError` und `nextAttemptAt` erlauben automatische sowie explizite Wiederholung. Nur `ready` darf MIME-Type, relativen Speicherpfad, Dateigröße, Dimensionen, optionale Videodauer und SHA-256 besitzen; SQL-CHECKs erzwingen vollständige Ausgabemetadaten. Nicht fertige Zeilen müssen diese Felder leer lassen.

Dateien liegen unter `derivatives/<ownerId>/<assetId>/<kind>-<claimId>.<ext>`. Jeder Claim besitzt temporären und finalen Pfad exklusiv. Die Lease wird während langer Sharp-/FFmpeg-Arbeit erneuert; nur der weiterhin aktuelle Claim darf seine Ausgabe als `ready` referenzieren. Ein Verlierer entfernt ausschließlich seinen eigenen Pfad. Die Übernahme nutzt Datei-`fsync`, atomaren Rename und Verzeichnis-`fsync`. Eine begrenzte Reconciliation prüft fertige Varianten auf Existenz, Größe und SHA-256; eindeutig ungültige Dateien werden auf `pending` zurückgesetzt; gealterte, von keinem aktuellen Claim referenzierte Final- und Part-Dateien werden entfernt. Temporäre Infrastrukturfehler behalten auch nach dem normalen Versuchslimit automatischen, auf 24 Stunden begrenzten Backoff. Beim endgültigen Purge werden Dateien und nicht mehr gültige Derivatzeilen entfernt, während die Asset-Zeile als Tombstone bestehen bleibt.

## Authentifizierung

Ein User besitzt einen eindeutigen normalisierten Benutzernamen und einen Argon2id-PHC-Hash. Accounts entstehen nur über die lokale Operator-CLI; es existiert kein HTTP-Admin-, Setup-, Pairing- oder Recovery-Endpunkt. Login prüft Argon2id unter einem IP-basierten Rate-Limit und erzeugt pro Anmeldung ein neues Gerät mit einem zufälligen Token. PostgreSQL speichert nur den zweckgebundenen SHA-256-Tokenhash.

Mehrere Geräte referenzieren denselben User. Widerruf entfernt ausschließlich den Tokenhash des Zielgeräts und setzt revokedAt. Alle Album-/Asset-Routen prüfen bei jedem Request ein aktives Gerät und begrenzen Daten auf Eigentümer beziehungsweise freigegebenen Partnerzustand.

## Android-Room-Modell

Room-Schema 9 ergänzt sourceRelativePath in shared_albums; Schema 8 ergänzte die persistente `uploadSessionId` in `upload_queue`; der serverbestätigte Offset liegt in `uploadedBytes`. `shared_albums` unterscheidet intern shareRequested und backupRequested, ohne einen dritten sichtbaren Albumzustand. Queue-, Cursor-, Offline- und Cleanup-Zustände überleben App- und Prozessneustarts. `REMOTE_DELETED` bewahrt ohne Mediendatei den kleinen Variantenwunsch eines gelöschten Partnerassets, insbesondere einzelne ORIGINAL-Overrides. Ein unveränderter belastbarer MediaStore-Befund bewahrt den fertigen Zustand; nur ein erfolgreicher vollständiger Scan mit voller Berechtigung darf lokale Abwesenheit als Löschung werten. Geänderte Bytes bewahren in `REPLACEMENT_PENDING` und `REPLACEMENT_DELETE_PENDING` die alte Server-ID bis zum bestätigten Papierkorbauftrag; erst danach beginnt der neue Upload. Offene, lokal verschwundene Uploads wechseln nach vollständigem Scan über `CANCEL_PENDING` zu `ABANDONED`; spätere Wiederentdeckung erzeugt wieder eine normale Queuezeile. Auch Legacy-Zeilen ohne reale MediaStore-ID verwenden dieses Replacement-Protokoll. Einstellungen, Remote-Metadaten und alle Partner-Offline-Zeilen sind nach Server-URL und Nutzer getrennt.

## Noch nicht implementiert

- Ein Asset in mehreren Alben; aktuell gehört es absichtlich genau einem Album.
- Einzelne Empfänger; im Zwei-Personen-Betrieb ist `sharedAt` die Freigabe für den Partner.


## Android Room: Partner-Offline-Zustand

`offline_albums` und `offline_assets` verwenden seit Schema 6 zusammengesetzte Schlüssel aus Server-/Account-Scope und Album-/Asset-ID. Das Album speichert Wunschmodus, Größenprognosen, monotone Generation, Arbeits-/Fehlerstatus und persistenten Metadaten-Seitencursor. Das Asset speichert Albumstandard oder Override, lokale Variante, Version, Hash, Pfad, Fortschritt, Fehler und zuletzt gesehene Generation. `offline_cleanup` hält fehlgeschlagene Datenschutz- und Legacy-Bereinigungen retryfähig. Altzeilen erhalten einen unbenutzbaren Legacy-Scope statt einer Zuordnung zur nächsten Session.

## Server-Sync-Journal

Schema 7 ergänzt eine transaktional gesperrte `sync_head`-Zeile, unveränderliche `sync_changes` und dauerhafte `sync_push_devices`. Nur fachlich partner-sichtbare Projektionen werden aufgezeichnet; Lease, Heartbeat, Recovery und interne nicht sichtbare Derivatstatus erzeugen keine Revision. `createdAt` und `minRevision` tragen die Epoch-basierte Retention. Fehlgeschlagene DB-Transaktionen hinterlassen keine Revision. Push-Zustände sind nur Auslieferungshinweise und werden niemals als Client-Cursor ausgewertet.
