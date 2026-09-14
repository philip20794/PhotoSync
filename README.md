# PhotoSync

Privates Teilen von Foto- und Videoalben zwischen zwei nativen Android-Geräten und einem Ubuntu-Server. Medien bleiben auf eigener Hardware.

Status: Architektur und Gerüst, keine Produktfeatures. Der Server startet einen leeren HTTP-Prozess (alle Pfade: 404); Android ist noch kein baubares Gradle-Projekt.

## Struktur

- `android/app/src/main/java/de/photosync/`: spätere Kotlin-/Compose-App.
- `server/src/`: TypeScript-Bootstrap.
- `server/prisma/`: Platz für Schema und Migrationen.
- `docs/`: [Architektur](docs/architecture.md), [Sync-Protokoll](docs/sync-protocol.md), [Datenmodell](docs/data-model.md).
- `compose.yaml`: Server und PostgreSQL.

## Entwicklungsstart

Voraussetzungen: Docker Engine mit Compose v2+, lokal alternativ Node.js 24 LTS und npm.

1. `cp .env.example .env`
2. Eigenes PostgreSQL-Passwort eintragen (URL-sichere Zeichen). `PHOTOSYNC_MEDIA_PATH` auf ein vorhandenes Verzeichnis der eingebundenen externen Festplatte setzen. Dieses muss später für UID/GID 1000 schreibbar sein. Für Strukturtests ist ein leeres lokales Testverzeichnis ausreichend.
3. `docker compose config --quiet`
4. `docker compose up --build -d`
5. `docker compose ps` und `docker compose logs server`
6. `curl -i http://127.0.0.1:3000/` liefert erwartungsgemäß 404.

`docker compose down` stoppt die Container und erhält das Datenbankvolume. Keine Tabellen oder Migrationen vorhanden. Der Bootstrap nutzt Datenbank und Medienablage noch nicht. Der HTTP-Port ist nur lokal erreichbar; TLS und Authentifizierung fehlen noch.

Alternativ den Bootstrap lokal starten:

```sh
cd server
npm ci
npm run typecheck
npm run build
npm start
```

Hierfür sind noch keine Datenbank und keine Umgebungsdatei nötig. Spätere DB-Entwicklung erfolgt zunächst im Compose-Netz mit Host `db`; PostgreSQL veröffentlicht keinen Host-Port.

Android-Start siehe [android/README.md](android/README.md). Noch kein Gradle Wrapper oder APK-Build vorhanden.
