# PhotoSync HTTP API v1

Implementierter Stand: Instanzeinrichtung, zwei passwortlose Accounts, Geräte-Credentials, Pairing und Widerruf, Album-/Assetmetadaten, resumierbare Originaluploads, asynchrone Medien-Derivate, Byte-Range-Streaming, Change-Feed und 90-Tage-Papierkorb. Basis lokal: `http://127.0.0.1:3000`; zwischen Android und Server ausschließlich über einen vertrauenswürdigen HTTPS-Zugang betreiben. TLS-Terminierung ist weiterhin eine Betriebsaufgabe.

## Authentifizierung und gemeinsame Regeln

Private Endpunkte verlangen `Authorization: Bearer <accessToken>`. Ein Account besitzt eine UUID und einen Anzeigenamen; der Name ist kein Login und muss nicht eindeutig sein. Es gibt keine Benutzerpasswörter und keinen Passwort-Login. Jedes Gerät besitzt eine eigene UUID sowie ein zufälliges, dauerhaftes, widerrufbares 256-Bit-Token (`psd_...`). Nur dessen SHA-256-Hash mit Zweckpräfix liegt in PostgreSQL. Das Token wird ausschließlich bei Setup oder Pairing ausgegeben und kann später nicht abgerufen werden.

Tokens in der Android-App in geschütztem, vom Keystore unterstütztem Speicher halten und von Backups ausschließen. Keine Tokens in URL, Queryparametern oder Cookies; der Server akzeptiert dort keine Anmeldung. Tokens und Pairing-Codes werden nicht geloggt. Antworten tragen `Cache-Control: no-store` und eine servergenerierte `x-request-id`. IDs sind UUIDs, Zeitpunkte UTC als ISO-8601.

Alle Routen sind standardmäßig privat, auch neu hinzugefügte. Explizite Ausnahmen: `GET /health`, `POST /v1/auth/setup`, `POST /v1/auth/pair`. Setup benötigt trotzdem sein separates Betreibergeheimnis, Pairing einen gültigen Einmalcode. Unbekannte URLs liefern ohne gültiges Geräte-Token 401 und mit gültigem Token 404.

Fehlerformat:

```json
{"error":{"code":"UNAUTHORIZED","message":"Valid device credentials required","requestId":"<uuid>"}}
```

401 setzt zusätzlich `WWW-Authenticate: Bearer`. Ungültige Eingaben liefern 400, nicht erlaubte Setup-Versuche 403, fremde/unbekannte Ressourcen 404, belegte Accounts oder erneutes Setup 409 und überschrittene Rate-Limits 429 mit `Retry-After` (Sekunden). Unerwartete Fehler liefern 500 ohne interne Details. Health verwendet sein eigenes Statusformat.

## Ersteinrichtung

Der Betreiber erzeugt **einmal lokal** ein Setup-Geheimnis:

```sh
docker compose exec -T server node dist/auth/setup-token.js
```

Die Ausgabe enthält `setupToken` und `SETUP_TOKEN_HASH`. Nur den Hash in die lokale `.env` bzw. `.env.production` übernehmen. Das Setup-Token dem ersten Nutzer sicher übergeben; es wird nicht in Konfigurationsdateien oder der Datenbank gespeichert. Nicht in Shell-Historie, Tickets oder Logs kopieren. Danach die Konfiguration übernehmen:

```sh
docker compose up -d --wait server
```

Leerer `SETUP_TOKEN_HASH` deaktiviert das Setup. Das Erzeugen eines Tokens konfiguriert noch keinen Account. Ein bestehendes Setup lässt sich durch Austausch des Hashes nicht zurücksetzen. Nach der Einrichtung den Hash aus der Umgebung entfernen und den Server erneut starten.

### POST /v1/auth/setup

Öffentlich erreichbar, aber nur mit `Authorization: Bearer <setupToken>` erlaubt. Das Geräte-Token kann diesen Vorgang nicht autorisieren.

```json
{"displayName":"Alice","deviceName":"Alice Pixel"}
```

Beide Namen: nach Trimmen 1–80 Zeichen. Unbekannte Felder werden abgelehnt. Erfolg **201**:

```json
{
  "user":{"id":"<user-uuid>","displayName":"Alice"},
  "device":{"id":"<device-uuid>","name":"Alice Pixel","createdAt":"<UTC>","revokedAt":null},
  "accessToken":"psd_<secret>",
  "tokenType":"Bearer"
}
```

Erzeugt atomar Nutzer in Mitgliedsplatz 1, dessen erstes Gerät und den dauerhaften Setup-Abschluss. Gleichzeitige Setup-Aufrufe können nur einen Erfolg erzeugen. Danach **409 ALREADY_CONFIGURED** bei gültigem Setup-Geheimnis, sonst **403 SETUP_FORBIDDEN**. Kein öffentliches Setup-Status-/Nutzerverzeichnis.

## Einladen und Geräte hinzufügen

### POST /v1/auth/pairing-codes

Geräte-Authentifizierung erforderlich. Body:

```json
{"purpose":"partner"}
```

- `partner`: belegt beim Einlösen den zweiten Accountplatz. Wenn schon zwei Accounts existieren: **409 PAIR_FULL**.
- `device`: verbindet ein weiteres Gerät mit dem Account des Aufrufers. Kein `userId`-/`targetUserId`-Parameter erlaubt; niemand kann damit Geräte für den Partner anlegen.

Erfolg **201**:

```json
{"id":"<code-uuid>","code":"ABCD-1234-ABCD-1234-ABCD-1234-ABCD-1234","purpose":"partner","expiresAt":"<UTC>"}
```

Der Beispielcode ist nur ein Formatbeispiel. Echte Codes enthalten 128 zufällige Bits (acht Vierergruppen Hex). Lebensdauer standardmäßig zehn Minuten; `PAIRING_CODE_TTL_SECONDS` konfiguriert 60–3600 Sekunden. Ablauf wird anhand der PostgreSQL-Uhr geprüft. Der Klartextcode wird nur in dieser Antwort zurückgegeben. Nur dessen zweckgebundener Hash wird gespeichert.

Ein späterer QR-Code kann denselben Code und die Serveradresse transportieren; QR-Erzeugung und -Scan sind noch nicht implementiert. Ein solcher QR-Code ist ebenfalls ein Geheimnis, kein dauerhafter öffentlicher Link.

### POST /v1/auth/pair

Ohne vorhandenes Geräte-Token. Für den Partner:

```json
{"code":"<pairing-code>","displayName":"Bob","deviceName":"Bob Samsung"}
```

Für ein weiteres eigenes Gerät:

```json
{"code":"<pairing-code>","deviceName":"Alice Tablet"}
```

Bei `partner` ist `displayName` erforderlich; bei `device` ist es nicht erlaubt. Namen unterliegen denselben Grenzen wie beim Setup. Der Code wird in dargestellter Gruppierung oder als 32 Hexzeichen akzeptiert, Groß-/Kleinschreibung ist egal. Andere Felder werden abgelehnt.

Erfolg **201**, Antwort wie beim Setup: Account, neues Gerät und ein neues eigenes `accessToken`. Ein zusätzlicher Gerätezugang behält die User-ID und erhält eine neue Geräte-ID und ein anderes Token. Ein Partnerzugang erhält eine andere User-ID.

Unbekannte, abgelaufene, verbrauchte oder widerrufene Codes liefern einheitlich **400 INVALID_PAIRING_CODE**. Fehlende/unerlaubte Eingabefelder liefern **400 INVALID_REQUEST** und verbrauchen keinen Code. Nutzer-/Geräteanlage und Codeverbrauch werden zusammen committed. Zwei gleichzeitige Einlösungen desselben Codes führen zu genau einem Erfolg; unterschiedliche konkurrierende Partnercodes können keinen dritten Account erzeugen.

### DELETE /v1/auth/pairing-codes/{id}

Geräte-Authentifizierung erforderlich. Widerruft einen Code, den eines der eigenen Geräte erzeugt hat. **204**; bei fremder oder unbekannter ID **404**. Ein erneuter Widerruf desselben eigenen Codes ist unschädlich. Verbrauchte oder widerrufene Codes werden nie wieder gültig.

## Account und Geräte

### GET /v1/me

Geräte-Authentifizierung erforderlich. **200**, aktuelle Account-/Geräteidentität:

```json
{"user":{"id":"<uuid>","displayName":"Alice"},"device":{"id":"<uuid>","name":"Alice Pixel","createdAt":"<UTC>","revokedAt":null},"partner":{"id":"<uuid>","displayName":"Bob"}}
```

`partner` ist `null`, solange kein Partneraccount existiert. Anzeigename und Gerätename werden mit `PATCH /v1/me` geändert:

```json
{"displayName":"Alice neu","deviceName":"Neues Pixel"}
```

Die Änderung wird gemeinsam für Account und Gerät committed und erscheint bei der nächsten `GET /v1/me`-Antwort beider Accounts.

### GET /v1/devices

Geräte-Authentifizierung erforderlich. **200**, ausschließlich Geräte des eigenen Accounts, einschließlich widerrufener Einträge:

```json
{"devices":[{"id":"<uuid>","name":"Alice Pixel","createdAt":"<UTC>","revokedAt":null}]}
```

Keine Tokens, Hashes oder Geräte des Partners in der Antwort.

### DELETE /v1/devices/{id}

Geräte-Authentifizierung erforderlich. Widerruft ein eigenes Gerät, auch das aufrufende Gerät: **204**. Fremde oder unbekannte Geräte: **404**. Wiederholter Widerruf durch ein anderes aktives eigenes Gerät liefert ebenfalls 204.

Widerruf setzt `revokedAt`, entfernt den Credential-Hash und widerruft offene Einladungen dieses Geräts. Danach werden dessen Requests mit **401** abgelehnt. Andere Geräte behalten ihre Credentials. Authentifizierung fragt bei jedem Request PostgreSQL ab; es gibt keinen Token-Cache und keine JWT-Nachlaufzeit. Bereits laufende Leseanfragen können noch fertig werden; Auth-Mutationen prüfen den Gerätezustand nach Erwerb der Transaktionssperre nochmals.

## Grenzen und Betrieb

Setup, Codeeinlösung und Codeerstellung sind standardmäßig auf zehn Anfragen je IP und Endpunkt pro Minute begrenzt (`AUTH_RATE_LIMIT_MAX`). Der begrenzte In-Memory-Zähler gilt für einen Backend-Prozess und wird bei Neustart geleert; bei mehreren Replikas einen gemeinsamen Rate-Limit-Speicher ergänzen. Forwarded-IP-Header werden nicht vertraut. Hinter einem Reverse-Proxy teilen sich Clients daher zunächst dessen Limit; Proxy-Vertrauen nur mit enger Proxy-Allowlist konfigurieren.

Die Instanz hat maximal zwei Accounts, aber beliebig viele eigene Geräte. Es gibt noch keine Accountlöschung, Passwort-/Recovery-Anmeldung oder administrative Übernahme fremder Accounts. Mindestens einen funktionierenden Zugang pro Nutzer behalten. Wer alle Geräte widerruft oder verliert, benötigt einen späteren lokalen Betreiber-Recovery-Workflow. Die Ersteinrichtung darf dafür nicht erneut freigeschaltet werden.

Da rohe Credentials nicht gespeichert werden, können verlorene Erfolgsantworten nicht erneut abgerufen werden. Bei einem zusätzlichen Gerät von einem vorhandenen Gerät aus einen neuen Code erzeugen und den verwaisten Zugang widerrufen. Geht die allererste Setup-/Partner-Erfolgsantwort verloren, ist ebenfalls Betreiber-Recovery erforderlich. HTTP-Clients dürfen fehlgeschlagene Einlösungen daher nicht als erfolgreich behandeln oder unbemerkt neue Accounts erwarten.

DB-Backups enthalten nur Credential-/Code-Hashes, aber weiterhin private Metadaten. Eine Wiederherstellung alter Backups kann alte Widerrufszustände zurücksetzen; anschließend Gerätezugänge überprüfen. Abgelaufene Codezeilen bleiben vorerst als Metadaten erhalten; sie enthalten keine Klartextcodes.

## Health

`GET /health/live` ist eine von Datenbank, Medienpfad und Konvertierungsqueue unabhängige Liveness-Antwort. `GET /health` bleibt ebenfalls ohne Token erreichbar und prüft Schema-Version 9, Medienverzeichnis sowie bei aktiviertem Worker die Verfügbarkeit von `ffmpeg` und `ffprobe`. Fehlende harte Voraussetzungen liefern **503**. Die Antwort enthält zusätzlich Queuezahlen. Ein großer Rückstand oder ein über Tool-Timeout hinaus festhängender Job erscheint als `derivatives: "degraded"`, bleibt aber **200**, damit ein Neustart die persistente Queue nicht verschlimmert. Keine Accounts, Geräte oder Credentials werden ausgegeben.

Offizielle Grundlagen: [Node.js Crypto](https://nodejs.org/docs/latest-v24.x/api/crypto.html), [Fastify Auth-Hooks](https://fastify.dev/docs/latest/Reference/Hooks/), [Rate-Limit-Plugin](https://github.com/fastify/fastify-rate-limit), [Prisma-Transaktionen](https://docs.prisma.io/docs/orm/v7/prisma-client/queries/transactions).


## Alben

Alle Album- und Assetendpunkte benötigen ein aktives Geräte-Token. Eigene Alben bleiben für den Eigentümer sichtbar. Der Partner kann ausschließlich Alben mit aktivem Freigabestatus lesen.

### POST /v1/albums

Legt ein Album für den authentifizierten Nutzer und das aufrufende Quellgerät an:

```json
{"clientAlbumId":"primary:camera","title":"Kamera"}
```

`clientAlbumId`: 1–255 Zeichen und innerhalb des Geräts eindeutig. `title`: 1–200 Zeichen. `shared` (Standard `true`) steuert die Partnerfreigabe, `backedUp` (Standard `false`) aktiviert den dauerhaften privaten Backup-Marker. Erfolg **201**:

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
  "createdAt":"<UTC>",
  "updatedAt":"<UTC>"
}
```

Ein erneuter Aufruf mit derselben `clientAlbumId` auf demselben Gerät liefert dieselbe Server-ID, aktualisiert den Titel und setzt die angeforderten Zustände idempotent. Ein bereits gesetzter privater Backup-Marker wird beim Ausschalten des Schalters nicht entfernt. Wird das Album später geteilt, werden vorhandene Assets wiederverwendet. Dadurch kann Android nach einem verlorenen Response sicher wiederholen. Andere Geräte desselben Accounts besitzen einen eigenen Namensraum.

### PATCH /v1/albums/{id}

Setzt als Eigentümer den Freigabestatus:

```json
{"shared":false}
```

Erfolg **200** liefert das Album mit `shared=false`. Eigene Metadaten und Originale bleiben erhalten, der Partner erhält für Album, Assets und Downloads anschließend **404**. Erneutes `POST /v1/albums` oder `PATCH` mit `shared=true` aktiviert die Freigabe wieder. Fremde oder unbekannte IDs liefern **404**.

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
