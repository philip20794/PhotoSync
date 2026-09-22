# PhotoSync HTTP API v1

Implementierter Stand: zwei passwortgeschützte Accounts, widerrufbare Geräte-Credentials, stabile Albumidentität, private Auto-Backups mit Restore, Album-/Assetmetadaten, resumierbare Originaluploads, Medien-Derivate, Change-Feed und Papierkorb. Basis lokal: http://127.0.0.1:3000; Produktion verwendet https://philsync.duckdns.org.

## Authentifizierung und gemeinsame Regeln

Accounts werden ausschließlich lokal durch den Operator angelegt. Jeder Account besitzt eine UUID, einen eindeutigen normalisierten Benutzernamen (NFKC, getrimmt, kleingeschrieben), einen Anzeigenamen und optional einen Argon2id-Passwort-Hash. Passwörter werden nie als CLI-Argument, API-Log oder Klartext in PostgreSQL gespeichert.

POST /v1/auth/login ist neben den Health-Routen der einzige öffentliche Anwendungsendpunkt. Alle anderen Routen sind standardmäßig privat und verlangen Authorization: Bearer <accessToken>. Ein erfolgreicher Login erzeugt ein neues Gerät mit eigenem zufälligem 256-Bit-Token (psd_...); serverseitig liegt nur dessen zweckgebundener SHA-256-Hash. Eine Neuinstallation meldet sich am bestehenden Account an und erzeugt deshalb nur eine neue Device-Zeile, niemals einen neuen Nutzer.

Antworten tragen Cache-Control: no-store und x-request-id. Tokens und Passwörter dürfen nicht in URL, Queryparametern oder Logs erscheinen. 401 verwendet bewusst dieselbe Meldung für unbekannte Nutzer und falsche Passwörter. Login ist je IP und Route auf AUTH_RATE_LIMIT_MAX Versuche pro Minute begrenzt; Überschreitung liefert 429 mit Retry-After.

### POST /v1/auth/login

    {"username":"Philip","password":"<passwort>","deviceName":"Google Pixel 9"}

Erfolg 201:

    {
      "user":{"id":"<user-uuid>","username":"philip","displayName":"Philip"},
      "device":{"id":"<device-uuid>","name":"Google Pixel 9","createdAt":"<UTC>","revokedAt":null},
      "accessToken":"psd_<secret>",
      "tokenType":"Bearer"
    }

Android hält nur das Gerätetoken im Keystore-geschützten Speicher. Das Passwort wird nach dem Login verworfen. Jedes weitere oder neu installierte Gerät verwendet denselben Benutzernamen und dasselbe Passwort und erhält ein eigenes widerrufbares Token.

### Account und Geräte

GET /v1/me liefert eigenen Nutzer, aktuelles Gerät und den Partneraccount. PATCH /v1/me ändert Anzeigename und Gerätename. GET /v1/devices listet ausschließlich eigene aktive und widerrufene Geräte. DELETE /v1/devices/{id} widerruft ein eigenes Gerät idempotent, entfernt seinen Tokenhash und setzt revokedAt; andere Geräte bleiben gültig.

Es gibt keinen öffentlichen Admin-, Setup-, Pairing-, Passwortänderungs- oder Recovery-Endpunkt. Direkter lokaler Serverzugriff ist für Accountverwaltung erforderlich:

    docker compose --env-file /srv/photosync-storage/photosync/production/config/production.env -f compose.yaml -f compose.production.yaml exec server npm run --silent operator -- user list
    docker compose --env-file /srv/photosync-storage/photosync/production/config/production.env -f compose.yaml -f compose.production.yaml exec server npm run --silent operator -- user create Philip
    docker compose --env-file /srv/photosync-storage/photosync/production/config/production.env -f compose.yaml -f compose.production.yaml exec server npm run --silent operator -- user set-password Philip
    docker compose --env-file /srv/photosync-storage/photosync/production/config/production.env -f compose.yaml -f compose.production.yaml exec server npm run --silent operator -- device list Philip
    docker compose --env-file /srv/photosync-storage/photosync/production/config/production.env -f compose.yaml -f compose.production.yaml exec server npm run --silent operator -- device revoke <device-uuid>

user set-password verlangt ein interaktives TTY, liest Passwort und Wiederholung verdeckt und akzeptiert keine Passwortargumente oder Pipe-Eingabe. Passwörter müssen 12 bis 1024 Zeichen lang sein. Die Instanz ist auf die zwei Accountplätze Philip und Runa ausgelegt.

## Health

`GET /health/live` ist eine von Datenbank, Medienpfad und Konvertierungsqueue unabhängige Liveness-Antwort. `GET /health` bleibt ebenfalls ohne Token erreichbar und prüft Schema-Version 13, Medienverzeichnis sowie bei aktiviertem Worker die Verfügbarkeit von `ffmpeg` und `ffprobe`. Fehlende harte Voraussetzungen liefern **503**. Die Antwort enthält zusätzlich Queuezahlen. Ein großer Rückstand oder ein über Tool-Timeout hinaus festhängender Job erscheint als `derivatives: "degraded"`, bleibt aber **200**, damit ein Neustart die persistente Queue nicht verschlimmert. Keine Accounts, Geräte oder Credentials werden ausgegeben.

Offizielle Grundlagen: [Node.js Crypto](https://nodejs.org/docs/latest-v24.x/api/crypto.html), [Fastify Auth-Hooks](https://fastify.dev/docs/latest/Reference/Hooks/), [Rate-Limit-Plugin](https://github.com/fastify/fastify-rate-limit), [Prisma-Transaktionen](https://docs.prisma.io/docs/orm/v7/prisma-client/queries/transactions).


## Alben

Alle Album- und Assetendpunkte benötigen ein aktives Geräte-Token. Eigene Alben bleiben für den Eigentümer sichtbar. Der Partner kann ausschließlich Alben mit aktivem Freigabestatus lesen.

### POST /v1/albums

Legt ein Album für den authentifizierten Nutzer anhand seiner kontoweit stabilen Quelle an:

```json
{"clientAlbumId":"primary:camera","sourceVolume":"external_primary","sourceRelativePath":"pictures/kamera","title":"Kamera"}
```

sourceVolume und der normalisierte sourceRelativePath bilden zusammen mit dem authentifizierten Eigentümer die stabile Identität. clientAlbumId bleibt nur eine lokale Diagnose-ID. title umfasst 1–200 Zeichen; shared steuert die Partnerfreigabe und backedUp den privaten Backup-Marker.

```json
{
  "id":"<album-uuid>",
  "owner":{"id":"<user-uuid>","displayName":"Alice"},
  "title":"Kamera",
  "ownedByMe":true,
  "shared":true,
  "backedUp":false,
  "sourceDeviceId":"<device-uuid>",
  "clientAlbumId":"primary:camera",
  "sourceVolume":"external_primary",
  "sourceRelativePath":"pictures/kamera",
  "createdAt":"<UTC>",
  "updatedAt":"<UTC>"
}
```

Ein erneuter Aufruf desselben Accounts mit gleich normalisiertem Volume/RelativePath liefert dieselbe Server-ID – auch von einem neuen Gerät. Der vorhandene Share-Status wird dabei nicht überschrieben; eine bewusste Änderung erfolgt über PATCH. Ein bereits gesetzter Backup-Marker bleibt erhalten. Inhaltsgleiche Assets werden über den SHA-256-Constraint desselben Albums wiederverwendet.

### PATCH /v1/albums/{id}

Setzt als Eigentümer den Freigabestatus:

```json
{"shared":false}
```

Erfolg **200** liefert das Album mit `shared=false`. Eigene Metadaten und Originale bleiben erhalten, der Partner erhält für Album, Assets und Downloads anschließend **404**. PATCH mit shared=true aktiviert die Freigabe wieder. Fremde oder unbekannte IDs liefern **404**.

### GET /v1/albums

Liefert `{"albums":[...]}` mit allen eigenen Alben und ausschließlich aktiv geteilten Partneralben in stabiler Reihenfolge. Bei Partneralben ist `ownedByMe=false`; `sourceDeviceId` und `clientAlbumId` werden dort ausgelassen.

### GET /v1/albums/{id}

Liefert ein eigenes oder Partneralbum mit **200**. Unbekannte oder nicht zugängliche IDs liefern **404**.

## Assetmetadaten

### POST /v1/albums/{albumId}/assets

Nur der Eigentümer des Albums darf einen Metadatensatz anlegen. Der Body beschreibt das anschließend unverändert hochzuladende Original:

```json
{
  "originalFileName":"IMG_20260804_123456.jpg",
  "mimeType":"image/jpeg",
  "capturedAt":"2026-08-04T12:34:56.789+02:00",
  "fileSize":"4821931",
  "width":4032,
  "height":3024,
  "clientAssetId":"ms_<stabile-id>",
  "expectedSha256":"<64-kleinbuchstabige-hexzeichen>"
}
```

Für Videos ist `durationMillis` als nichtnegative Dezimalzeichenfolge erforderlich:

```json
{
  "originalFileName":"VID_20260804_123456.mp4",
  "mimeType":"video/mp4",
  "capturedAt":"2026-08-04T12:34:56Z",
  "fileSize":"28311931",
  "width":1920,
  "height":1080,
  "durationMillis":"12450",
  "clientAssetId":"ms_<stabile-id>",
  "expectedSha256":"<64-kleinbuchstabige-hexzeichen>"
}
```

`capturedAt` ist optional, muss aber bei Angabe ISO-8601 mit Offset sein. Dateiname: 1–255 Zeichen ohne Steuerzeichen. MIME-Typ: `image/*` oder `video/*`, höchstens 127 Zeichen. Breite/Höhe: positive Ganzzahlen bis 100000. `fileSize` muss größer null und höchstens `MAX_UPLOAD_BYTES` sein.
`clientAssetId` und `expectedSha256` sind gemeinsam optional, für Android-Sync aber erforderlich. Die Client-ID ist pro Quellgerät stabil; der erwartete Hash beschreibt die unveränderten Originalbytes. Wiederholtes POST mit derselben Geräte-/Client-ID oder demselben erwarteten SHA-256 im Album liefert den vorhandenen Datensatz. Ein fertiges Asset bleibt `ready`; ein zuvor fehlgeschlagenes Asset wird für einen neuen Versuch auf `pending` gesetzt. Eine wiederverwendete Client-ID mit anderem Inhalt liefert **409**.

Erfolg **201** erzeugt eine stabile Server-ID und Status `pending`:

```json
{
  "id":"<asset-uuid>",
  "ownerId":"<user-uuid>",
  "albumId":"<album-uuid>",
  "originalFileName":"IMG_20260804_123456.jpg",
  "mimeType":"image/jpeg",
  "capturedAt":"2026-08-04T10:34:56.789Z",
  "fileSize":"4821931",
  "width":4032,
  "height":3024,
  "durationMillis":null,
  "sha256":null,
  "integrityStatus":"healthy",
  "integrityError":null,
  "status":"pending",
  "derivatives":[],
  "createdAt":"<UTC>",
  "updatedAt":"<UTC>"
}
```

Der interne Speicherpfad wird nicht ausgegeben. Ein Partner darf keine Assets zu einem fremden Album hinzufügen; die API antwortet mit **404**.

### GET /v1/albums/{albumId}/assets

Liefert `{"assets":[...]}`. Der Eigentümer sieht alle Zustände. Der Partner sieht nur `ready`-Assets mit `integrityStatus=healthy`; `pending`, `uploading` und `failed` bleiben verborgen.

### GET /v1/assets/{id}

Liefert die oben gezeigten Metadaten. Der Eigentümer kann jeden verfügbaren Assetstatus lesen, der Partner ausschließlich `ready` mit gesunder Originalintegrität. Sonst **404**. Nach erfolgreichem Originalupload enthält `derivatives` die beiden persistierten Varianten, zum Beispiel:

```json
{
  "derivatives":[
    {"kind":"optimized","status":"processing","mimeType":null,"fileSize":null,"width":null,"height":null,"durationMillis":null,"attempts":0,"nextAttemptAt":"<UTC>","updatedAt":"<UTC>"},
    {"kind":"thumbnail","status":"ready","mimeType":"image/webp","fileSize":"13488","width":512,"height":287,"durationMillis":null,"attempts":0,"nextAttemptAt":"<UTC>","updatedAt":"<UTC>"}
  ]
}
```

Erlaubte Derivatstatus sind `pending`, `processing`, `ready` und `failed`. Interne Pfade und Fehlermeldungen der Medienwerkzeuge werden nicht ausgegeben.

## Original-Upload und Varianten

### POST /v1/assets/{id}/upload-session

Nur der Eigentümer darf für ein `pending`-Asset eine Sitzung anlegen. Wiederholung liefert dieselbe noch gültige Sitzung und den maßgeblichen serverbestätigten Offset. Die Antwort lautet:

```json
{"id":"<session-uuid>","assetId":"<asset-uuid>","offset":"4194304","size":"12000000","maxChunkBytes":"8388608","expiresAt":"<UTC>","completed":false,"asset":null}
```

### DELETE /v1/assets/{id}/upload

Nur der Eigentümer darf eine unvollständige Uploadressource abbrechen. Der Server claimt eine offene Sitzung, entfernt Part und noch nicht committetes Ziel idempotent und löscht die Metadatenzeile. Ist die Ressource wegen einer verlorenen Abschlussantwort bereits `ready`, verwendet die Route die normale 90-Tage-Papierkorblogik. Aktive fremde Claims liefern **409**; Wiederholung nach erfolgreicher Bereinigung liefert **404** und wird von Android ebenfalls als abgeschlossen behandelt.

### PATCH /v1/upload-sessions/{sessionId}

Ein Chunk benötigt:

- `Content-Type: application/octet-stream`
- explizites, positives `Content-Length` bis `MAX_UPLOAD_CHUNK_BYTES`
- `Upload-Offset` exakt gleich dem letzten serverbestätigten Offset
- Body enthält exakt die deklarierte Chunklänge und überschreitet die Assetgröße nicht

Beispiel:

```sh
curl --fail-with-body \
  -X PATCH \
  -H "Authorization: Bearer $DEVICE_TOKEN" \
  -H "Content-Type: application/octet-stream" \
  -H "Upload-Offset: 0" \
  -H "Content-Length: $(wc -c < chunk.bin)" \
  --data-binary @chunk.bin \
  "http://127.0.0.1:3000/v1/upload-sessions/$UPLOAD_SESSION_ID"
```

Nach jedem synchronisierten Teilstück bestätigt **200** den neuen Offset. Der letzte Chunk löst die Prüfung der Gesamtgröße und SHA-256 aus, danach atomare Umbenennung und den transaktionalen Wechsel auf `ready` einschließlich beider Derivatjobs. Die Abschlussantwort hat `completed:true` und enthält das fertige Asset. Sitzung, Offset, Part-Pfad und Ablaufzeit liegen in PostgreSQL; Android speichert Sitzungs-ID und Offset in Room. Ein Heartbeat verlängert den exklusiven Chunk-Claim. Nach einem Crash werden ausschließlich abgelaufene Leases übernommen: unbestätigte Dateibytes werden auf den DB-Offset gekürzt, eine bereits atomar umbenannte vollständige Datei wird fertig committed. Abgelaufene Sitzungen werden idempotent bereinigt.

Fehler:

- **400 INVALID_UPLOAD_HEADERS** bei fehlendem/ungültigem `Content-Length` oder `Upload-Offset`.
- **409 UPLOAD_OFFSET_MISMATCH** beziehungsweise **409 CONFLICT** bei falschem Offset oder aktivem Parallel-Claim.
- **413 UPLOAD_CHUNK_TOO_LARGE** oberhalb des Chunklimits.
- **415 UNSUPPORTED_MEDIA_TYPE** bei anderem Content-Type.
- **422 UPLOAD_CHUNK_INVALID/UPLOAD_SIZE_MISMATCH** bei Bereichs- oder Längenfehlern.
- **422 UPLOAD_HASH_MISMATCH** wenn die berechnete SHA-256 nicht `expectedSha256` entspricht.
- **507 STORAGE_FULL** bei serverseitigem `ENOSPC`; die bestätigte Sitzung bleibt fortsetzbar.
- **404** bei fremdem/unbekanntem Asset.

Transport- und Speicherfehler verwerfen nur den noch nicht bestätigten Chunk. Der Client ruft den Session-POST erneut auf und setzt beim gelieferten Offset fort. Nur ein finaler Hashfehler setzt das Asset auf `failed`. Bereits bestätigte `ready`-Assets werden nicht erneut übertragen.

### PUT /v1/assets/{id}/original

Die bisherige Volluploadroute bleibt kompatibel. Sie verwendet intern dieselbe Sitzung/Finalisierung, akzeptiert aber nur eine Sitzung mit Offset 0 und verlangt das vollständige Original in einem Request. Neue Android-Clients verwenden die Chunkrouten.

### GET /v1/assets/{id}/original
### GET /v1/assets/{id}/thumbnail
### GET /v1/assets/{id}/optimized

Alle drei Routen benötigen ein aktives Gerätetoken und prüfen die aktuelle Albumfreigabe. Der Eigentümer und der Partner können Varianten eines `ready`-Assets lesen. Das Original liefert exakt die hochgeladenen Bytes und den ursprünglichen MIME-Type. `thumbnail` ist WebP für Bilder beziehungsweise JPEG für Videos. `optimized` ist WebP für Bilder beziehungsweise H.264/AAC in MP4 für Videos. Das genaue Profil steht unter [Medien-Derivate](media-derivatives.md).

Ohne `Range` folgt **200** mit der gesamten Datei. Ein einzelner gültiger Bereich, beispielsweise `Range: bytes=1048576-2097151` oder `Range: bytes=-65536`, liefert **206**, `Content-Range` und genau diesen Abschnitt. Alle Antworten enthalten `Accept-Ranges: bytes`, geprüfte `Content-Length`, SHA-256 als `ETag`, MIME-Type und `Content-Disposition`; Mehrfachbereiche werden nicht unterstützt. Ungültige oder nicht erfüllbare Bereiche liefern **416 RANGE_NOT_SATISFIABLE** und `Content-Range: bytes */<Gesamtgröße>`.

Nicht fertige oder unzugängliche Assets liefern **404**. Ein noch nicht fertiges oder fehlgeschlagenes Derivat liefert **409 DERIVATIVE_NOT_READY**. Fehlt eine laut Datenbank fertige Datei oder stimmen Größe oder SHA-256 nicht, folgt **503 ORIGINAL_UNAVAILABLE** beziehungsweise **503 DERIVATIVE_UNAVAILABLE**.

### POST /v1/assets/{id}/derivatives/retry

Nur der Eigentümer kann fehlgeschlagene Derivate sofort erneut einplanen. Erfolg **202** setzt alle `failed`-Zeilen dieses Assets auf `pending`, löscht die interne Fehlermeldung und setzt den Versuchszähler zurück. `ready`- und derzeit `processing`-Varianten bleiben unberührt. Fremde, unbekannte oder nicht fertige Assets liefern **404**. Permanente Fehler werden automatisch bis `DERIVATIVE_MAX_ATTEMPTS` versucht. Temporäre Infrastrukturfehler bleiben darüber hinaus mit begrenztem exponentiellem Backoff retryfähig und heilen nach Entfall der Ursache ohne manuellen Aufruf.

## Medienkonfiguration

`MAX_UPLOAD_BYTES` begrenzt die Assetgröße, `MAX_UPLOAD_CHUNK_BYTES` einen Request und `UPLOAD_SESSION_TTL_MS` die inaktive Sitzung. `API_REQUEST_TIMEOUT_MS` schützt normale Routen; Chunk- und Volluploads verwenden `UPLOAD_REQUEST_TIMEOUT_MS`. `UPLOAD_LEASE_MS` und `UPLOAD_RECOVERY_INTERVAL_MS` steuern exklusiven Claim und Recovery. Derivatkonfiguration steuert den eingebauten Worker. Originale, Parts und Derivate liegen vollständig unter dem durch `NODE_ENV` gewählten `MEDIA_DEV_ROOT` beziehungsweise `MEDIA_PROD_ROOT`.

Der aktuelle Pfadaufbau ist intern:

```text
uploads/<assetId>-<uploadSessionId>.part
originals/<ownerId>/<assetId>/original
derivatives/<ownerId>/<assetId>/thumbnail.<webp|jpg>
derivatives/<ownerId>/<assetId>/optimized.<webp|mp4>
```

Clients dürfen daraus keine direkten URLs oder Dateisystempfade ableiten. Zugriff erfolgt ausschließlich über die authentifizierten Variantenrouten.


### GET /v1/partner/albums

Liefert ausschliesslich aktuell freigegebene Alben des anderen Accounts im selben Pair. Eigene Alben erscheinen nie in dieser Antwort. Jedes Album enthaelt assetCount (nur fertige Assets) und optional ein Cover mit assetId, Derivatversion und Hash. Das Cover verweist auf die normale autorisierte Thumbnail-Route; Albumuebersichten uebertragen keine Medienbytes.

### Partnergalerie: Paging

GET /v1/albums/{albumId}/assets akzeptiert limit zwischen 1 und 100 (Standard 60) und einen opaque cursor aus der vorherigen Antwort. Die Antwort enthaelt assets und nextCursor; die stabile absteigende Reihenfolge nach Erstellungszeit und ID erlaubt chronologisches Lazy-Paging. Partner erhalten weiterhin ausschliesslich ready-Assets.

### Papierkorb und 90-Tage-Aufbewahrung

`DELETE /v1/assets/{id}` verschiebt ein eigenes `ready`-Asset in den Papierkorb. `GET /v1/trash` listet ausschließlich eigene `deleted`-Assets und liefert `deletedAt`, `purgeAfter`, verbleibende Sekunden, ursprüngliches Album und verfügbare Varianten. `GET /v1/trash/assets/{id}/thumbnail` liefert nur dem Eigentümer die noch aufbewahrte Vorschau. `POST /v1/trash/assets/{id}/restore` sowie `POST /v1/trash/restore` stellen Assets ohne erneuten Upload wieder her.

`DELETE /v1/trash/assets/{id}` löscht ein Asset sofort endgültig; `DELETE /v1/trash/assets` und `DELETE /v1/trash` löschen mehrere beziehungsweise alle eigenen Papierkorb-Assets. Der Server claimt das Asset mit einer Cleanup-Lease als `purging`, entfernt Original und Derivate, markiert danach `purged` und entfernt nicht mehr gültige Derivatmetadaten. Nach Beginn der Dateilöschung bleibt ein Fehler in `purging` retryfähig; Restore ist dann ausgeschlossen. Nur abgelaufene Leases werden nach einem Prozessabbruch übernommen. Die verbindliche automatische Aufbewahrung beträgt 90 Tage.

Ein geteilter Lösch- oder Restore-Vorgang erzeugt genau eine fachliche DELETE- beziehungsweise RESTORE-Änderung. Rein private Auto-Backup-Assets erzeugen kein Partnerereignis. Nach physischer Bereinigung bleibt ein `purged`-Tombstone in PostgreSQL, damit ein lange offline gewesenes Gerät das Asset nicht wieder anlegt.

## Eigene Backups und Restore

GET /v1/backups liefert ausschließlich eigene Alben mit gesetztem backedUpAt, inklusive Assetanzahl und Größen. Partnerbackups sind nicht sichtbar. Android zeigt diese Liste unter **Backup wiederherstellen** und lädt auf Wunsch die Originalvarianten über dieselben autorisierten, resumierbaren und persistenten Offline-Transfers herunter. Ein neues Gerät kann damit eigene Backups abrufen, ohne Albumfreigaben oder einen erneuten Upload zu erzeugen.
