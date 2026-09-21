# Produktiver Ubuntu-Betrieb

Diese Anleitung beschreibt die produktionsnahe Einzelserver-Installation. Die 4-TB-Platte ist **Primärspeicher, kein Backup**. Keine der Befehlsfolgen formatiert einen Datenträger.

## Festplatte und Verzeichnislayout

Auf dem eingerichteten Server ist der PhotoSync-Datenträger:

| Eigenschaft | Wert |
| --- | --- |
| Blockdevice | `/dev/nvme0n1p1` |
| Modell | Lexar SSD NM790 4TB |
| Dateisystem | ext4 |
| UUID | `3c37799d-6279-43d7-8b50-362e2266535b` |
| Mountpoint | `/srv/photosync-storage` |

`/etc/fstab` muss diesen UUID-Mount enthalten. Vor jedem Produktionsstart prüft `ops/verify-production-storage.sh` UUID und tatsächliches Dateisystem. Die Compose-Bind-Mounts haben `create_host_path: false`; fehlen Platte oder Unterverzeichnisse, schlägt der Start fehl. Es gibt damit keinen stillen Fallback auf die Root-SSD.

```text
/srv/photosync-storage/photosync/production/
├── postgres/                 # PostgreSQL-Daten (UID/GID 999, 0700)
├── media/                    # Originale, Derivate, Thumbnails und Upload-.part-Dateien
├── catalog/                  # lokale Symlink-Ansicht, keine zweite Medienkopie
├── backups/postgres/         # konsistente PostgreSQL-Dumps
└── config/production.env     # 0600, außerhalb des Git-Repositories
```

Einmalig und erst nach `findmnt -T /srv/photosync-storage` mit der oben genannten UUID anlegen:

```bash
sudo install -d -m 0700 -o 999 -g 999 /srv/photosync-storage/photosync/production/postgres
sudo install -d -m 0750 -o 1000 -g 1000 /srv/photosync-storage/photosync/production/media
sudo install -d -m 0750 -o 1000 -g 1000 /srv/photosync-storage/photosync/production/catalog
sudo install -d -m 0750 -o "$USER" -g "$USER" /srv/photosync-storage/photosync/production/backups/postgres
sudo install -d -m 0700 -o "$USER" -g "$USER" /srv/photosync-storage/photosync/production/config
```

Die Containerbenutzer des aktuellen Images sind `postgres` (UID 999) und `node` (UID 1000). Bei abweichenden Images sind die Besitzer vor dem ersten Start erneut zu prüfen. Unterverzeichnisse unter einem nicht gemounteten `/srv/photosync-storage` dürfen niemals manuell angelegt werden.

## Lokale Medienansicht

Der optionale Katalog unter `catalog/` ist **nur auf dem Ubuntu-Host** sichtbar und wird weder von Fastify noch von Nginx Proxy Manager ausgeliefert. Er enthält ausschließlich relative Symlinks auf die Originale in `media/`; er belegt daher keine zweite Bild- oder Videokopie. Die Projektion wird beim Serverstart und danach mindestens jede Minute neu aufgebaut. Sie zeigt je Eigentümer `Alben/`, private `Auto-Backup/JJJJ/MM/`-Links und noch nicht physisch bereinigte `Papierkorb/`-Links. Upload-`.part`-Dateien, nicht fertige Assets, fehlerhafte aktive Originale und bereits purgte Tombstones erscheinen bewusst nicht.

Die Datei `.media` im Katalog ist ein vom Server verwalteter versteckter Anker auf das Geschwisterverzeichnis `media/`. Katalogordner nicht umbenennen oder mit eigenen Dateien befüllen: Eine spätere Aktualisierung ersetzt ausschließlich Verzeichnisse mit der Markerdatei `.photosync-catalog`. Wird ein Symlink lokal gelöscht, erzeugt der nächste Kataloglauf ihn wieder; das Löschen eines Symlinks löscht nie das Original. Der Katalog ist regenerierbar und muss nicht separat gesichert werden.

## Produktions-Stack

Eine nicht versionierte Datei `/srv/photosync-storage/photosync/production/config/production.env` anlegen (Modus `0600`). Als Ausgangspunkt dient `.env.example`; mindestens diese Werte sind verpflichtend:

```dotenv
POSTGRES_DB=photosync
POSTGRES_USER=photosync
POSTGRES_PASSWORD=<zufälliges URL-sicheres Geheimnis>
SETUP_TOKEN_HASH=<Ausgabe von: docker compose run --rm server npm run setup:token>
PHOTOSYNC_STORAGE_ROOT=/srv/photosync-storage/photosync/production
PHOTOSYNC_STORAGE_UUID=3c37799d-6279-43d7-8b50-362e2266535b
PHOTOSYNC_PROD_POSTGRES_PATH=/srv/photosync-storage/photosync/production/postgres
PHOTOSYNC_PROD_MEDIA_PATH=/srv/photosync-storage/photosync/production/media
PHOTOSYNC_PROD_CATALOG_PATH=/srv/photosync-storage/photosync/production/catalog
PHOTOSYNC_PROD_BACKUP_PATH=/srv/photosync-storage/photosync/production/backups/postgres
PHOTOSYNC_PROXY_NETWORK=photosync-proxy
FCM_ENABLED=true
```

`FCM_ENABLED` erst aktivieren, wenn ADC/Service-Credentials außerhalb des Repositories sicher in den Servercontainer eingebunden wurden. Eine Datei mit Geheimnissen wird nie eingecheckt. Produktionsstart und Upgrade:

```bash
sudo install -m 0644 ops/photosync-prod.service /etc/systemd/system/photosync-prod.service
sudo systemctl daemon-reload
sudo systemctl enable --now photosync-prod.service
sudo systemctl status photosync-prod.service
```

Der Service verlangt den Datenträgermount und die Env-Datei. Die Datenbank veröffentlicht keinen Hostport. Auch die API veröffentlicht keinen Hostport; sie ist nur im internen `photosync-proxy`-Netz erreichbar. `db` und `server` verwenden `unless-stopped`; die Migration läuft vor dem Serverstart einmalig und kontrolliert. Docker-Logs rotieren mit fünf Dateien à 20 MiB je Service. Prüfen:

```bash
docker compose --env-file /srv/photosync-storage/photosync/production/config/production.env -f compose.yaml -f compose.production.yaml ps
```

Verliert ein bestehender Nutzer alle lokalen Geräte-Credentials, wird nach dem Upgrade ein kurzlebiger Einmalcode direkt im Servercontainer erzeugt:

```bash
docker compose --env-file /srv/photosync-storage/photosync/production/config/production.env \
  -f compose.yaml -f compose.production.yaml \
  exec server npm run --silent operator -- create-pairing-code --user <user-uuid>
```

Der Befehl verändert keine Nutzer-, Geräte- oder Mediendaten und ist nicht per HTTP erreichbar. Details, Anzeigenamenauswahl und Auditverhalten stehen unter [Lokales Betreiber-Recovery](api.md#lokales-betreiber-recovery).

## Reverse Proxy und HTTPS

Nginx Proxy Manager (NPM) ist der einzige öffentliche Einstieg (TCP 80/443). Das vorhandene `gandalf-home.duckdns.org` zeigt auf Home Assistant und kann nicht zugleich PhotoSync bedienen. Zuerst eine **eigene** DuckDNS-Subdomain bzw. einen eigenen DNS-Namen für PhotoSync anlegen und den DuckDNS-Updater entsprechend ergänzen. Danach einmalig das isolierte Netzwerk anlegen und NPM daran anschließen:

```bash
docker network create photosync-proxy
docker network connect photosync-proxy nginx-proxy-manager
```

In NPM einen Proxy Host für den neuen PhotoSync-FQDN anlegen:

- Forward scheme `http`, Forward host `photosync-api`, Port `3000`
- Zugriffsliste nur nach Bedarf; Websocket-Unterstützung ist nicht erforderlich
- neues Let's-Encrypt-Zertifikat für genau diesen FQDN, „Force SSL“ aktiv
- „Block Common Exploits“ aktiv
- Custom Nginx configuration:

```nginx
client_max_body_size 0;
proxy_request_buffering off;
proxy_buffering off;
proxy_connect_timeout 30s;
proxy_read_timeout 3700s;
proxy_send_timeout 3700s;
```

Nginx leitet `Range` standardmäßig weiter; die deaktivierte Pufferung und die Upload-Timeouts erhalten Resumable-Uploads, 206-Downloads und Videostreams. Nach DNS- und Zertifikatsausstellung extern prüfen:

```bash
curl --fail-with-body https://<photosync-fqdn>/health
curl -I -H 'Range: bytes=0-0' -H 'Authorization: Bearer <gerätetoken>' https://<photosync-fqdn>/v1/assets/<id>/original
```

Nur 80/443 des Reverse Proxys müssen für PhotoSync öffentlich sein. PostgreSQL, Docker API und die App-Ports bleiben privat. Android akzeptiert keine Cleartext-Verbindung (`usesCleartextTraffic=false`) und zeigt HTTPS als externe Serveradresse an; den neuen `https://<photosync-fqdn>`-Wert in der Verbindungsansicht eintragen. Es gibt keinen eingebauten Dev-Host als Produktionsstandard.

## Backups, Wiederherstellung und Betrieb

Die Datenbank und `media/` gehören immer zusammen: ein PostgreSQL-Dump allein enthält keine Originale/Derivate/Upload-Parts. Täglich nach `backups/postgres/` sichern und anschließend dieses Verzeichnis **zusammen mit** `media/` auf einen unabhängigen Speicher kopieren (z. B. verschlüsseltes Restic-Repository oder rotierende externe Platte). Mindestens eine Offsite-Kopie und regelmäßige Wiederherstellungstests vorsehen. Nicht benötigte alte Dumps erst nach erfolgreicher externer Sicherung löschen.

```bash
PHOTOSYNC_ENV_FILE=/srv/photosync-storage/photosync/production/config/production.env ops/backup-postgres.sh
ops/restore-postgres-test.sh /srv/photosync-storage/photosync/production/backups/postgres/photosync-<zeit>.dump
```

Der Restore-Test verwendet einen isolierten, nicht vernetzten PostgreSQL-Container mit `tmpfs`; er verändert weder Produktion noch die 4-TB-Daten. Ein vollständiger Notfallrestore erfolgt in dieser Reihenfolge: Stack stoppen, `postgres/` und `media/` aus demselben Sicherungsstand wiederherstellen, Eigentümerrechte korrigieren, Stack starten, `/health` und gezielte Medien-Downloads prüfen.

Der Server verarbeitet den 90-Tage-Papierkorb, Upload-Leases, derivative Jobs, Original-Integrität und Change-Feed-/Tombstone-Retention beim Start und fortlaufend. Physischer Purge erzeugt kein zweites fachliches Delete-Ereignis. Für Betrieb und Speicherüberwachung mindestens täglich ausführen bzw. alarmieren:

```bash
ops/verify-production-storage.sh
df -h /srv/photosync-storage
docker compose --env-file /srv/photosync-storage/photosync/production/config/production.env -f compose.yaml -f compose.production.yaml ps
```

Alarmgrenzen: ab 80 % Kapazität beobachten, ab 90 % neue Uploads und Derivaterzeugung planen bzw. Speicher erweitern; volle Dateisysteme dürfen nicht durch manuelles Löschen von `.part`- oder Papierkorbdateien „repariert“ werden. Erst den Serverzustand/Backups prüfen. Die automatische 90-Tage-Bereinigung ist kein Ersatz für die Kapazitätsüberwachung.
