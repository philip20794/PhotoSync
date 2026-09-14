# Datenmodell – konzeptionell

Noch kein ausführbares Prisma-Schema und keine Migration. PostgreSQL enthält Metadaten, keine Medien-BLOBs. UUIDs als IDs; Zeitpunkte als `timestamptz` in UTC; Revisionen/Sequenzen und Bytegrößen als `bigint`.

## Serverentitäten

| Entität | Wesentliche Felder und Beziehungen |
| --- | --- |
| Pair | id, nächste Change-Sequenz; exakt zwei aktive Nutzer als Ziel, während Einrichtung höchstens zwei |
| User | id, pairId, Anzeigename, Status |
| Device | id, userId, widerrufenAm; Tokens nur gehasht, sofern serverseitig gespeichert |
| Album | id, ownerId, sourceDeviceId, clientAlbumId, Titel, revision, Freigabestatus |
| AlbumShare | albumId, recipientId, active, revision; Unique(albumId, recipientId) |
| Media | id, ownerId, sourceDeviceId, clientMediaId, MIME, Bytes, SHA-256, Aufnahmezeit optional, Breite/Höhe/Dauer optional, status, revision, trashedAt, purgeAfter |
| AlbumMedia | albumId, mediaId, revision; zusammengesetzter Primärschlüssel |
| MediaObject | id, mediaId, kind(original/preview/optimized), recipeVersion, relativer storageKey, Bytes, Hash, status |
| UploadSession | id, ownerId, deviceId, clientMediaId, erwartete Bytes/Hash, bestätigter Offset, tempKey, Status, expiresAt |
| OperationReceipt | deviceId, operationId, requestHash, Ergebnis, createdAt; eindeutiges Schlüsselpaar |
| ChangeEvent | pairId, sequence, recipientId, entityType, entityId, revision, Aktion(upsert/remove), Payload; Index(pairId, recipientId, sequence) |
| SyncDeviceState | deviceId, bestätigter Cursor, lastSeenAt; nur nach Clientbestätigung fortschreiben |
| Job | id, Typ, Ziel-ID, Status, Versuche, nächster Versuch, Lease-Ende; Varianten/Purge/Reparatur |

## Beziehungen und Invarianten

Ein Nutzer gehört zu einem Paar und besitzt Geräte, Alben und Medien. Die Obergrenze von zwei aktiven Mitgliedern muss transaktional unter Sperre der Paarzeile durchgesetzt werden. AlbumShare erlaubt nur den anderen aktiven Nutzer desselben Paares. Die Prüfung erfolgt serverseitig unabhängig von Clientdaten.

Album und Medium einer Mitgliedschaft müssen denselben Eigentümer haben, durch zusammengesetzte Fremdschlüssel oder entsprechende transaktionale Constraints absichern. Ein Medium kann mehreren Alben angehören. Ein Partner darf ein Medium sehen, wenn mindestens eine aktive Freigabe eines zugehörigen Albums existiert und das Medium `ready` und nicht im Papierkorb ist. Entzug einer einzelnen Freigabe entfernt die Partneransicht erst, wenn kein anderer Zugriffsweg besteht.

Unique(sourceDeviceId, clientAlbumId) und Unique(sourceDeviceId, clientMediaId) verhindern doppelte Anlage durch Wiederholung. Lokale MediaStore-IDs sind keine globalen IDs; Wiederverwendung nach Geräte-/Indexreset muss eine neue Client-Zuordnung erzeugen. Albumname ist nicht eindeutig. SHA-256 ist Integritätsmerkmal, kein globaler Primärschlüssel.

MediaObject hat einen eindeutigen storageKey und Unique(mediaId, kind, recipeVersion); genau ein Original je Medium zusätzlich erzwingen. Nichtnegative Bytes/Offset und Offset ≤ erwartete Größe als Checks. Papierkorbzeitpunkte müssen gemeinsam gesetzt oder leer sein; Frist beträgt 30 Tage. Purge entfernt Original, Varianten und Nutzmetadaten, erhält aber minimale Löschidentität im Change-Log/Tombstone bis zur Sync-Retention.

Indizes auf Eigentümer, Freigabeempfänger, AlbumMedia.mediaId, fällige Papierkorbeinträge, Uploadablauf und Jobstatus/nächsten Versuch. Kein kaskadierendes Löschen von Dateiverweisen ohne vorherigen Dateibereinigungsauftrag. Nicht mehr referenzierte Dateien erkennt ein Reparaturjob mit Schonfrist für laufende Uploads.

## Dateisystem

Beispielschlüssel unter MEDIA_ROOT:

```text
originals/<ownerId>/<mediaId>/original
variants/<ownerId>/<mediaId>/<recipeVersion>/<kind>
uploads/<uploadId>.part
```

Dateinamen des Nutzers dienen höchstens als Metadaten, niemals als Pfadbestandteile. Originale bleiben unverändert; Papierkorb ist ein DB-Zustand, kein notwendiger Dateiumzug. Metadatencommit und Dateischreibvorgang werden über Upload-/Jobzustände gekoppelt.

## Android / Room

| Lokale Entität | Aufgabe |
| --- | --- |
| RemoteAlbum, RemoteMedia, RemoteAlbumMedia | Projektion der aktuell sichtbaren Serverdaten samt Revision |
| SourceAlbum | ausgewählte Quelle, Volume/Bucket, Client-ID, Scanstatus |
| SourceMedia | Content-URI, Indexversion/Generation, Client-ID, lokaler Fingerprint, Server-ID |
| OutboxOperation | stabile Operation-ID, Payload, erwartete Revision, Retry-/Fehlerzustand |
| LocalUpload | Upload-ID, bestätigter Offset, lokale Quelle, Hash |
| SyncState | Cursor und Snapshotzustand |
| CachedFile | Medium/Variante, relativer appinterner Pfad, Bytes, ETag, letzter Zugriff |
| OfflinePin (später) | Nutzer/Album/Medium, Modus optimized/original, Downloadzustand |

Tokens gehören in geschützten, vom Android Keystore unterstützten Speicher, nicht unverschlüsselt in Room. Dateien bleiben appintern; Room referenziert sie nur. Cachedateien können vom System verschwinden: fehlende Datei als Cache-Miss behandeln. Offline-Pins benötigen dauerhaften appinternen Speicher. Migrationen von Room und PostgreSQL werden mit dem ersten Schema versioniert.
