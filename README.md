# PhotoSync

Privates Teilen von Foto- und Videoalben zwischen zwei nativen Android-Geräten und einem Ubuntu-Server. Medien bleiben auf eigener Hardware.

Status: Fastify/TypeScript, Prisma 7.10 und PostgreSQL 17 bilden das Backend mit Migrationen, Health und zentraler Fehlerbehandlung. Authentifizierung, private Auto-Backups, Partnergalerie, Change-Feed, 90-Tage-Papierkorb sowie resumierbare Uploads und Offline-Downloads sind implementiert. Originale bleiben unverändert; persistente Sitzungen, Leases, Recovery und atomare Finalisierung überstehen Prozess- und Netzwerkabbrüche. Die native Android-App nutzt eine Room-/WorkManager-Queue und trennt alle Partnerdaten nach Server und Account.

## Struktur

- `android/`: Kotlin-/Compose-App mit Room, Retrofit/OkHttp und Keystore-geschützten Geräte-Credentials, siehe [Android-Start](android/README.md).
- `server/src/`: Konfiguration, Logging, Datenbankadapter, Health und Prozessstart.
- `server/prisma/`: Schema und eingecheckte SQL-Migrationen.
- `server/test/`: Unit-/HTTP-Tests und Integrationstests gegen PostgreSQL.
- `compose.yaml`: lokale Entwicklung und isoliertes Testprofil.
- `compose.production.yaml`: separate Produktionspfade und Projekt-/DB-Volumes.
- `docs/`: [Architektur](docs/architecture.md), [Sync-Protokoll](docs/sync-protocol.md), [Datenmodell](docs/data-model.md), [API](docs/api.md).

## Lokale Entwicklung starten

Voraussetzung: Docker Engine mit Compose **2.24.4 oder neuer** (für `!override` in der Produktionskonfiguration). Node.js auf dem Host ist für den Docker-Weg nicht erforderlich. Befehle aus dem Repository-Root ausführen.

```sh
cp .env.example .env
# In .env POSTGRES_PASSWORD mit einem eigenen URL-sicheren Passwort füllen.
# Beispielsweise mit `openssl rand -hex 24` ein Passwort erzeugen.
mkdir -p .local/media
docker compose config --quiet
docker compose up --build -d --wait server
curl --fail-with-body http://127.0.0.1:3000/health
```

Eine bereits vorhandene `.env` behalten. Bei der Erstinstallation dieser Backend-Basis wurde lokal eine ignorierte `.env` mit zufälligem Passwort erzeugt. Für eigene Pfade `PHOTOSYNC_DEV_MEDIA_PATH` in `.env` ändern und das Verzeichnis vorher anlegen. Der Server läuft als UID/GID 1000 und benötigt Lese-, Schreib- und Suchrechte auf diesem Verzeichnis; er erzeugt es nicht automatisch.

Compose startet zuerst PostgreSQL, danach den einmaligen Migrationscontainer und erst nach erfolgreicher Migration das Backend. Erwartete Antwort: HTTP **200** und

```json
{"status":"ok","checks":{"database":"ok","media":"ok"}}
```

`/health` ist eine Readiness-Prüfung: Datenbankabfrage samt Migrations-Baseline und Zugriff auf den Medienpfad. Bei Ausfall einer Abhängigkeit folgt HTTP **503**, ohne interne Pfade oder Verbindungsdaten. Die Prüfung schreibt keine Mediendateien und beweist noch nicht die Identität einer externen Festplatte. Der Start selbst schlägt bei ungültiger Konfiguration oder fehlenden Abhängigkeiten fehl.

```sh
docker compose ps -a
docker compose logs --tail=100 server migrate db
docker compose down
```

`down` erhält DB-Volume und Medienverzeichnis. Port `${SERVER_PORT:-3000}` wird nur an `127.0.0.1` gebunden, PostgreSQL besitzt keinen veröffentlichten Port. Ein Passwortwechsel in `.env` ändert nicht das Passwort eines bereits initialisierten PostgreSQL-Volumes.

## Migrationen

Die erste Migration legt `service_metadata` an, die zweite Accounts, Geräte und Pairing-Codes. Die dritte ergänzt Alben und Assets. Die vierte ergänzt widerrufbare Albumfreigaben sowie stabile Geräte-/Asset-IDs und erwartete SHA-256-Werte. Die fünfte ergänzt persistente Thumbnail-/Optimized-Jobs. Die sechste ergänzt erneuerbare Originalupload-Leases und setzt Schema-Version 6. Prisma verwaltet die Historie in `_prisma_migrations`. Originalbytes liegen außerhalb der Datenbank.

```sh
# Nach Schema-/Codeänderungen Images neu bauen:
docker compose build server migrate
# PostgreSQL starten und Migrationen explizit anwenden:
docker compose up -d --wait db
docker compose run --rm migrate
# Wiederholbar; bereits angewendete Migrationen werden nicht erneut ausgeführt:
docker compose run --rm migrate npm run migrate:status
# Danach Backend mit dem aktuellen Image starten:
docker compose up -d --wait server
```

Neue SQL-Migrationen unter `server/prisma/migrations/` versionieren. Bereits angewendete Migrationen nicht ändern. `prisma migrate dev` nur gegen eine dedizierte Entwicklerdatenbank nutzen; es benötigt eine Shadow-Datenbank und kann einen Reset vorschlagen. Produktionsmigrationen ausschließlich mit `migrate deploy` ausführen. Siehe [Migrationen](server/prisma/README.md).

## Tests

```sh
docker compose --profile test build test
docker compose --profile test run --rm test
# Isolierte Testdatenbank danach entfernen; Entwicklungs-DB bleibt erhalten:
docker compose --profile test stop test-db
docker compose --profile test rm -f test-db
```

Der Testcontainer migriert eine separate PostgreSQL-Datenbank `photosync_test`, baut TypeScript und führt Unit-/HTTP- sowie echte Datenbank-Integrationstests aus. Testdaten liegen im tmpfs des Testdatenbankcontainers und nicht im Entwicklungsvolume. Abgedeckt sind Konfigurationsfehler, Pfadauswahl, Health-Ausfälle und Wiederherstellung, Fehlerantworten, Log-Geheimhaltung, DB-Verbindung, angewendete Migration, Eigentumsgrenzen sowie vollständigen und abgebrochenen Medienupload, Freigabeentzug, Partnerfilter, Derivatstatus, Retry und Byte-Range-Streaming sowie idempotente Wiederholung anhand Geräte-ID und SHA-256. Crash-Recovery, ein absichtlich länger als zehn Sekunden laufender Upload und echte Video-Derivate werden als Integrationstests ausgeführt. Tests laden Binärdaten hoch, wieder herunter und vergleichen SHA-256; Sharp erzeugt ein echtes Testbildderivat. Ein zusätzlicher Qualitätslauf mit zwei Fotos und zwei Videos ist in [Medien-Derivate](docs/media-derivatives.md) dokumentiert. Der Exitcode des Testcontainers zeigt das Ergebnis an.

Der optionale Qualitätslauf nimmt mindestens zwei Fotos und zwei Videos als Argumente. Die Eingaben bleiben schreibgeschützt; der Prüfer arbeitet in einem temporären Verzeichnis:

```sh
docker run --rm \
  -v /absoluter/pfad/zu/testmedien:/quality-input:ro \
  photosync-dev-test \
  node test/quality/derivatives-quality.mjs \
  /quality-input/foto1.jpg /quality-input/foto2.jpg \
  /quality-input/video1.mp4 /quality-input/video2.mp4
```

## Ersten Nutzer und Partner verbinden

Die Entwicklungsdatenbank wird nicht mit Demo-Accounts befüllt. Setup bleibt ohne Betreiberfreigabe deaktiviert.

```sh
docker compose exec -T server node dist/auth/setup-token.js
```

Nur den ausgegebenen `SETUP_TOKEN_HASH` in `.env` übernehmen, das rohe `setupToken` einmal sicher an den ersten Nutzer übergeben. Danach `docker compose up -d --wait server` ausführen. `POST /v1/auth/setup` mit diesem Setup-Token richtet den ersten Account und dessen erstes Gerät ein. Mit dem erhaltenen Geräte-Token erstellt `POST /v1/auth/pairing-codes` einen Partnercode; `POST /v1/auth/pair` verbindet das Partnergerät. Für weitere eigene Geräte `purpose=device` verwenden.

Private APIs benötigen `Authorization: Bearer <accessToken>`. Geräte-Tokens und Codes werden nur einmal ausgegeben; auf dem Server liegen ausschließlich Hashes. Nach dem Setup den Setup-Hash aus `.env` entfernen und den Server erneut starten. Genaue Bodies, Antworten, Widerruf und Recovery-Grenzen: [API-Dokumentation](docs/api.md).

Neue Authentifizierungsvariablen: `SETUP_TOKEN_HASH` (leer = Setup deaktiviert), `PAIRING_CODE_TTL_SECONDS` (Standard 600), `AUTH_RATE_LIMIT_MAX` (Standard 10 je IP/Endpunkt/Minute). Die Tests prüfen auch paralleles Setup/Pairing, Ablauf, Einmalverwendung, Accountgrenze, private APIs und Gerätewiderruf.

Album-, Asset-, Upload- und Variantenverträge stehen in der [API-Dokumentation](docs/api.md). Uploads verwenden zuerst JSON-Metadaten, danach eine persistente Sitzung und begrenzte Chunks mit exaktem `Upload-Offset`; die Volluploadroute bleibt kompatibel.

## Environment-Konfiguration

Compose liest `.env` im Repository-Root; die Anwendung selbst erhält strukturierte Prozessvariablen und validiert sie vor dem Listen. `.env` wird weder committed noch ins Docker-Image kopiert.

| Variable | Bedeutung / Standard |
| --- | --- |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Compose-DB und daraus gebildete `DATABASE_URL`; Passwort erforderlich, URL-sicher |
| `SERVER_PORT` | Hostport in Compose, Standard 3000 |
| `PHOTOSYNC_DEV_MEDIA_PATH` | Hostpfad Entwicklung, Standard `./.local/media` |
| `PHOTOSYNC_PROD_MEDIA_PATH` | vorhandener Hostpfad auf externer Festplatte, Beispiel `/mnt/photosync/media` |
| `NODE_ENV` | Backend: `development`, `test` oder `production`; wählt Medienpfad |
| `HOST`, `PORT` | Backend-Listenadresse/-Port; Compose setzt `0.0.0.0:3000` |
| `DATABASE_URL` | Backend/Prisma: PostgreSQL-Verbindungs-URL, erforderlich |
| `DATABASE_TIMEOUT_MS` | Verbindungs-/Abfragezeitlimit, Standard 2000, Bereich 100–30000 |
| `LOG_LEVEL` | `fatal`, `error`, `warn`, `info`, `debug`, `trace`, `silent`; Standard `info` |
| `MAX_UPLOAD_BYTES` | maximale deklarierte und empfangene Originalgröße; Standard 1073741824 Bytes (1 GiB) |
| `API_REQUEST_TIMEOUT_MS` | harte Deadline normaler API-Requests; Standard 10000 |
| `UPLOAD_REQUEST_TIMEOUT_MS` | harte Deadline ausschließlich für Originaluploads; Standard 3600000, mindestens API-Deadline |
| `UPLOAD_LEASE_MS` | Gültigkeit einer serverseitig erneuerten Upload-Lease; Standard 120000 |
| `UPLOAD_RECOVERY_INTERVAL_MS` | Intervall der idempotenten Stale-Upload-Recovery; Standard 30000, kleiner als Lease |
| `UPLOAD_SESSION_TTL_MS` | Ablauf inaktiver persistenter Upload-Sitzungen; Standard 604800000 (7 Tage), größer als Lease |
| `MAX_UPLOAD_CHUNK_BYTES` | maximale Chunkgröße; Standard 8388608 Bytes (8 MiB) |
| `DERIVATIVE_WORKER_ENABLED` | `true`/`false`; eingebauten Hintergrundworker aktivieren, Standard `true` |
| `DERIVATIVE_POLL_INTERVAL_MS` | Leerlaufintervall des Workers, Standard 1000, Bereich 100–60000 |
| `DERIVATIVE_MAX_ATTEMPTS` | automatische Versuche je Variante, Standard 5, Bereich 1–20 |
| `DERIVATIVE_TOOL_TIMEOUT_MS` | Zeitlimit je FFmpeg-/ffprobe-Aufruf, Standard 600000, Bereich 1000–3600000 |
| `DERIVATIVE_BACKLOG_WARNING` | Queuegröße, ab der Readiness den Derivatstatus als `degraded` meldet; Standard 100 |
| `MEDIA_DEV_ROOT`, `MEDIA_PROD_ROOT` | absolute getrennte Backend-Pfade; Compose setzt `/media/development` und `/media/production` |

Entwicklung/Test wählen `MEDIA_DEV_ROOT`, Produktion `MEDIA_PROD_ROOT`. Beide Werte sind erforderlich und dürfen weder gleich noch ineinander verschachtelt sein. Nur der ausgewählte Pfad muss existieren. Auch die Hostpfade müssen getrennt bleiben; keine Symlinks oder Bind-Mounts auf dieselben Daten verwenden. Die alte Variable `PHOTOSYNC_MEDIA_PATH` wird durch die beiden Hostvariablen ersetzt.

Logging erfolgt als Pino-JSON auf stdout mit Ereignissen, Level, Zeit, Request-ID und HTTP-Status. `x-request-id` wird vom Server erzeugt. Header, Bodies, rohe URLs/Queryparameter, Konfiguration und rohe DB-Fehler werden nicht protokolliert. Fehlerantworten verwenden `{ "error": { "code", "message", "requestId" } }`; `/health` verwendet das oben gezeigte Readiness-Format. SIGINT/SIGTERM schließen HTTP-Server und Datenbankpool, mit maximal zehn Sekunden für das Herunterfahren.

## Separate Produktionspfade vorbereiten

```sh
cp .env.example .env.production
# Eigenes Passwort, PHOTOSYNC_PROD_MEDIA_PATH auf die eingebundene Festplatte setzen.
# Bei gleichzeitig laufender Entwicklung einen anderen SERVER_PORT wählen.
docker compose --env-file .env.production -f compose.yaml -f compose.production.yaml config --quiet
docker compose --env-file .env.production -f compose.yaml -f compose.production.yaml up --build -d --wait server
```

Das Produktions-Override heißt `photosync-prod` und hat ein eigenes PostgreSQL-Volume/Netz. Es ersetzt den Entwicklungs-Bind-Mount vollständig: Nur der Produktivpfad ist für das Backend sichtbar. Das Verzeichnis muss bereits existieren und für UID/GID 1000 zugänglich sein. Vor echten Medienuploads folgt noch die Prüfung der Datenträgeridentität. Der Port bleibt lokal; Geräteanmeldung ist implementiert; TLS-Zugang muss noch eingerichtet werden. Diese Konfiguration wurde validiert, nicht mit echten Produktivdaten gestartet.

## Optional: Backend direkt mit Node.js 24 LTS

Eine separat erreichbare PostgreSQL-17-Instanz sowie FFmpeg mit `ffmpeg` und `ffprobe` im `PATH` bereitstellen. Die Compose-DB veröffentlicht absichtlich keinen Host-Port. In `server/.env` die Verbindungsdaten und zwei absolute Medienpfade eintragen; ausgewähltes Verzeichnis vorher anlegen.

```sh
cd server
cp .env.example .env
# Werte anpassen, dann:
npm ci
node --env-file=.env node_modules/prisma/build/index.js generate
node --env-file=.env node_modules/prisma/build/index.js migrate deploy
npm run typecheck
npm test
```

Backend anschließend starten:

```sh
node --env-file=.env dist/index.js
```

`npm start` allein verwendet die bereits gesetzte Prozessumgebung und lädt keine `.env` automatisch.
