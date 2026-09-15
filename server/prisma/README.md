# Datenbank und Migrationen

Prisma ORM, Client und PostgreSQL-Adapter sind gemeinsam auf **7.10.0** fixiert. `prisma.config.ts` liest `DATABASE_URL` aus der Prozessumgebung. Keine gehosteten Prisma-Dienste.

`20260914000100_backend_baseline` erstellt nur die technische Tabelle `service_metadata` und deren Schema-Version. `/health` liest diese über den generierten Prisma-Client und erkennt so auch fehlende Migrationen. `20260914000200_device_auth` ergänzt Pair/User/Device/PairingCode und setzt die technische Schema-Version auf 2. `20260914000300_albums_assets` ergänzt Album/Asset und relationale Eigentumsregeln. `20260914000400_android_upload_queue` ergänzt Freigabestatus, stabile Client-Asset-IDs, erwartete SHA-256-Werte und Schema-Version 4. `20260914000500_media_derivatives` ergänzt persistente Thumbnail-/Optimized-Jobs, Ausgabe-Metadaten und Schema-Version 5. Originale und Derivate bleiben außerhalb PostgreSQL. Change-Log, Tombstones und Papierkorbmodelle bleiben Zukunftsentwurf.

Im Repository-Root:

```sh
docker compose up -d --wait db
docker compose run --rm migrate
docker compose run --rm migrate npm run migrate:status
```

Das Migrationsimage enthält Prisma CLI und SQL-Dateien; das Laufzeitimage enthält nur Produktionsabhängigkeiten und kompilierten Code. Clientgenerierung erfolgt beim Image-Build ohne Datenbankverbindung. Alle Migrationen werden als eigener Compose-Schritt vor dem Backendstart ausgeführt, nicht bei jeder HTTP-Anfrage.

Neue Migrationen auf einer separaten Entwicklerdatenbank mit `npm run migrate:dev -- --name beschreibung` erstellen (DATABASE_URL und Shadow-DB-Rechte nötig). Generierte SQL-Datei prüfen, einchecken und Image neu bauen. Kein `db push` als Ersatz für Migrationen und kein automatischer Reset bestehender Datenbanken. Integrationstests benutzen ausschließlich `photosync_test`.

Offizielle Grundlagen: [Prisma 7 Migrationen](https://www.prisma.io/docs/cli/v7/migrate) und [Prisma 7 Adapter/Konfiguration](https://www.prisma.io/docs/guides/upgrade-prisma-orm/v7).

Die CLI zieht zusätzliche Werkzeugbibliotheken mit. Zwei gezielte npm-Overrides aktualisieren `@prisma/config → deepmerge-ts` auf 8.0.2 und `prisma → mysql2` auf 3.24.4, um die beim Aufbau gemeldeten Advisories zu beheben. MySQL wird von PhotoSync nicht benutzt. Clientgenerierung und Migrationen werden mit diesen Overrides geprüft; bei Prisma-Upgrades erneut bewerten. Im Laufzeitimage werden Entwicklungs- und optionale Werkzeugabhängigkeiten entfernt, insbesondere Prisma CLI und TypeScript.
