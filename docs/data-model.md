# Datenmodell

Implementiert sind technische Baseline, Authentifizierung sowie Album- und Originaldateimodell. Migration `20260914000500_media_derivatives` ergänzt persistente Medienvarianten und setzt `service_metadata.schema_version` auf **5**. PostgreSQL enthält ausschließlich Metadaten; Originalbytes liegen unter dem konfigurierten Medienpfad.

UUIDs sind stabile Server-IDs. Zeitpunkte werden als `timestamptz(3)` in UTC gespeichert. Dateigrößen und Videodauer sind `bigint` und erscheinen in JSON als Dezimalstrings, damit Android und JavaScript keine Genauigkeit verlieren.

## Implementierte Tabellen

| Tabelle / Prisma-Modell | Wesentliche Felder und Regeln |
| --- | --- |
| `service_metadata` / ServiceMetadata | Technische Schema-Version; aktuell `5` |
| `pairs` / Pair | Eine Instanzzeile, Setupstatus und gemeinsame Sperre für Auth-Mutationen |
| `users` / User | Account mit Mitgliedsplatz 1 oder 2; höchstens zwei Accounts |
| `devices` / Device | Eigenes Gerät und optionaler eindeutiger Credential-Hash; widerrufene Geräte haben keinen Hash |
| `pairing_codes` / PairingCode | Gehashter, befristeter Einmalcode für Partner oder weiteres eigenes Gerät |
| `albums` / Album | UUID, `ownerId`, `sourceDeviceId`, `clientAlbumId`, Titel, `sharedAt`, Erstellungs-/Änderungszeit |
| `assets` / Asset | UUID, Besitzer und genau ein Album, Originalmetadaten, Quellgeräte-/Client-ID, erwartete und tatsächliche SHA-256, relativer Speicherpfad, Uploadstatus und Zeitpunkte |
| `asset_derivatives` / AssetDerivative | Pro Asset je eine Art `thumbnail` und `optimized`; persistenter Jobstatus, Ausgabemetadaten, Hash, Versuche, Fehler und nächste Ausführungszeit |

Prisma verwaltet zusätzlich `_prisma_migrations`.

## Alben und Freigabe

Ein Album gehört genau einem Nutzer. `ownerId` ist ein Pflicht-Fremdschlüssel auf `users`. Das Quellgerät wird zusammen mit demselben Besitzer über den zusammengesetzten Fremdschlüssel `(sourceDeviceId, ownerId) → devices(id, userId)` abgesichert. Damit kann selbst eine direkte Datenbankoperation kein fremdes Gerät als Quelle eintragen.

`clientAlbumId` ist die stabile Android-/MediaStore-Zuordnung innerhalb eines Geräts. `UNIQUE(sourceDeviceId, clientAlbumId)` verhindert Dubletten; Albumtitel sind bewusst nicht eindeutig. Die Server-UUID bleibt die API-Identität.

`sharedAt` ist gesetzt, solange das Album für den anderen Account desselben `Pair` freigegeben ist. Ausschalten setzt es auf `NULL`, ohne Eigentümerdaten oder Originale zu löschen. Der Eigentümer darf das Album weiterhin lesen und erneut freigeben. Der Partner darf nur Alben mit `sharedAt`, darin ausschließlich fertige Assets und deren Originale lesen. Quellgerät und lokale Album-ID werden dem Partner nicht ausgegeben.

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
- `sha256`: serverseitig über die tatsächlich empfangenen Originalbytes berechnet; nur bei `ready` gesetzt.
- `storagePath`: eindeutiger, servergenerierter relativer Pfad.
- `status`: `pending`, `uploading`, `ready` oder `failed`.
- `createdAt`, `updatedAt`: Erstellungs- und Änderungszeit.

SQL-CHECKs sichern positive Größen und Dimensionen, MIME-/Dauer-Konsistenz, gekoppelte Clientidentität, Hashformate, erlaubte Statuswerte sowie die Regel: Nur `ready` besitzt einen serverseitig berechneten SHA-256-Hash. Eindeutige Constraints auf `(sourceDeviceId, clientAssetId)` und `(albumId, expectedSha256)` verhindern doppelte Anlage nach verlorenen Antworten oder erneutem Scan. Der interne `storagePath` ist eindeutig und wird in API-Antworten nicht veröffentlicht.

## Uploadzustände und Dateisystem

Der Originalupload ist innerhalb eines Requests nicht fortsetzbar, kann über denselben idempotenten Metadatensatz aber erneut gestartet werden:

```text
Metadaten anlegen → pending
Stream beanspruchen → uploading
vollständig + fsync + SHA-256 + atomare Umbenennung → ready
Fehler/Abbruch → failed
```

Der Server schreibt den Request-Stream zunächst als zufällig benannte Datei unter:

```text
uploads/<assetId>-<random>.part
```

Währenddessen zählt er Bytes und berechnet SHA-256. Die empfangene Länge muss sowohl `Content-Length` als auch der vorher deklarierten `fileSize` entsprechen und unter `MAX_UPLOAD_BYTES` liegen. Nach vollständigem Schreiben wird die Temporärdatei synchronisiert und auf demselben Dateisystem atomar nach folgendem servergenerierten Ziel umbenannt:

```text
originals/<ownerId>/<assetId>/original
```

Erst danach setzt eine bedingte Datenbankänderung Status und Hash auf `ready`. Downloads suchen nur `ready`-Zeilen und prüfen zusätzlich, dass die Datei existiert und ihre Größe stimmt. Der berechnete Serverhash muss zusätzlich `expectedSha256` entsprechen. Bei Fehlern werden temporäre beziehungsweise bereits umbenannte Dateien bestmöglich entfernt und das Asset auf `failed` gesetzt. Derselbe Metadaten-POST setzt ein passendes fehlgeschlagenes Asset wieder auf `pending`; ein bereits fertiges Asset bleibt `ready`. Ein Prozessabsturz kann eine `uploading`-Zeile oder verwaiste Datei hinterlassen, aber keine teilweise Datei als `ready` markieren. Ein Reparaturjob für solche Crash-Reste folgt später.

Der relative Pfad wird gegen den aktiven `MEDIA_DEV_ROOT` oder `MEDIA_PROD_ROOT` aufgelöst. Absolute Pfade und `..`-Ausbrüche werden abgelehnt. DB und Originaldateien müssen zusammen gesichert werden; ein Datenbankbackup enthält keine Mediendaten.

## Derivate und Jobzustände

`UNIQUE(assetId, kind)` garantiert genau höchstens eine Zeile pro Variante; nach einem fertigen Upload werden beide Zeilen idempotent angelegt. `kind` ist `thumbnail` oder `optimized`, `status` ist `pending`, `processing`, `ready` oder `failed`. `attempts`, `lastError` und `nextAttemptAt` erlauben automatische sowie explizite Wiederholung. Nur `ready` darf MIME-Type, relativen Speicherpfad, Dateigröße, Dimensionen, optionale Videodauer und SHA-256 besitzen; SQL-CHECKs erzwingen vollständige Ausgabemetadaten. Nicht fertige Zeilen müssen diese Felder leer lassen. Der Fremdschlüssel auf `assets` löscht Jobmetadaten später zusammen mit dem Asset.

Dateien liegen unter `derivatives/<ownerId>/<assetId>/<kind>.<ext>`. Auch hier entsteht die endgültige Datei nur durch `fsync` und atomare Umbenennung einer benachbarten `.part`-Datei. Der Originalpfad ist niemals ein Ausgabeziel. Beim Start werden unterbrochene `processing`-Jobs wieder `pending`; fertige Alt-Assets ohne Jobzeilen werden nachgetragen. Das Profil beschreibt [media-derivatives.md](media-derivatives.md).

## Authentifizierung

Accounts sind passwortlos; jedes Gerät besitzt ein eigenes zufälliges und widerrufbares Token. In PostgreSQL liegen nur zweckgebundene SHA-256-Hashes. Setup, Pairing und Widerruf sind in [API-Dokumentation](api.md) beschrieben.

Alle Album-/Asset-Routen benötigen ein aktives Gerätetoken. Abfragen werden auf das Pair des authentifizierten Geräts eingeschränkt. Fremde oder noch nicht fertige Partner-Assets liefern 404, damit keine privaten Metadaten oder Uploadzustände offengelegt werden.

## Android-Room-Modell

Room-Schema 2 ergänzt `shared_albums` und `upload_queue`. Freigabewunsch, Server-Album-ID, letzter Scan, Content-URI, stabile Client-Asset-ID, SHA-256, Server-Asset-ID, Status, Versuche und übertragene Bytes überleben App- und Prozessneustarts. `hashing` und `uploading` werden beim nächsten Worker-Lauf auf `retry` zurückgesetzt. Fertige Queuezeilen bleiben als Deduplizierungsnachweis erhalten. Zeilen sind an die Geräte-ID gebunden, damit ein später angemeldeter anderer Account keine alte Queue übernimmt.

## Noch nicht implementiert

- Serverseitiges Change-Log, Tombstones und Partnergalerie.
- Ein Asset in mehreren Alben; aktuell gehört es absichtlich genau einem Album.
- Einzelne Empfänger; im Zwei-Personen-Betrieb ist `sharedAt` die Freigabe für den Partner.
- Byteweise wiederaufnehmbare/chunkbasierte Uploads; Wiederholung beginnt derzeit das einzelne Original erneut.
- 30-Tage-Papierkorb, Purge- und Reparaturjobs.
