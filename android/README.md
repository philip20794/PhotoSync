# PhotoSync Android

Native Kotlin-App mit Jetpack Compose für Android **8.0/API 26 oder neuer**. Die App konfiguriert eine Serveradresse, prüft die Verbindung, richtet das erste Gerät ein oder verbindet es per Pairing-Code und speichert Geräte-Credentials verschlüsselt im Android Keystore. Der Tab **Mein Handy** zeigt vorhandene MediaStore-Alben und synchronisiert ausdrücklich freigegebene Alben über eine persistente Upload-Queue. Originale werden unverändert gestreamt; eine Partnergalerie ist noch nicht enthalten.

## Architektur

- `ui/`: Compose-Screens, Navigation und ViewModel.
- `domain/`: kleine, plattformunabhängige Regeln wie die Prüfung der Serveradresse.
- `data/local/`: Room-Datenbank für Server- und nicht geheime Sitzungsmetadaten sowie Keystore-geschützte Credentials.
- `data/media/`: lesende MediaStore-Abfragen, Albumgruppierung, batchweise Inventarisierung und seitenweises Laden.
- `data/sync/`: WorkManager-Planung, Room-Queue, SHA-256 und gestreamter Originalupload.
- `data/remote/`: Retrofit/OkHttp-Verträge zum bestehenden `/health`- und Auth-API.
- `domain/model/`: lokale Album- und Medienmodelle ohne Uploadlogik.
- `ui/gallery/`: Berechtigungsfluss, Albumübersicht und paginiertes Medienraster.

Room 2.7, Retrofit 3, OkHttp, Coroutines, Kotlin Serialization, Paging 3.3 und Coil 3.2 einschließlich Video-Decoder sind fest versioniert in `gradle/libs.versions.toml`. Paging lädt ein geöffnetes Album in Seiten; Coil decodiert Bilder und Video-Frames passend zur Größe der Compose-Kachel. WorkManager 2.11 führt die dauerhafte Queue bei verfügbarem Netzwerk aus. SHA-256 und Upload lesen Content-URIs als Streams, sodass weder Vorschaubilder noch Originale vollständig in den RAM geladen werden. Tokens werden weder in Room noch im Klartext in `SharedPreferences` gespeichert. `allowBackup=false` verhindert System-Backups dieser App-Daten und Credentials.

Die zwei Starttabs heißen **Mein Handy** und **Partner**. Lokale Alben zeigen Name, Cover, getrennte Bild-/Videoanzahl, den Schalter **Teilen** sowie Anzahl und Bytefortschritt. Ausschalten beendet den Partnerzugriff serverseitig und bewahrt bereits hochgeladene Originale sowie die lokale Zuordnung. Die App schreibt nichts in MediaStore und erzeugt keine Medienordner. Der Partnername kann erst mit einer späteren Partnerprofil-/Album-API zuverlässig geladen werden; das aktuelle Backend liefert ihn bei einem leeren Albumstand nicht.

Auf Android 13 und neuer fordert die App `READ_MEDIA_IMAGES` und `READ_MEDIA_VIDEO` an. Android 14 und neuer unterstützt zusätzlich den systemseitig eingeschränkten Zugriff auf ausgewählte Fotos und Videos. Bis Android 12 wird `READ_EXTERNAL_STORAGE` mit `maxSdkVersion=32` verwendet. Bei eingeschränktem Zugriff zeigt die App nur die vom System freigegebenen Medien und bietet **Ändern** zum erneuten Öffnen des Berechtigungsdialogs.

## Build und Installation

Benötigt werden JDK 17, Android SDK Platform 35 und ein angeschlossenes oder per ADB sichtbares Gerät mit API 26+.

```sh
cd android
./gradlew test
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Der Instrumentierungstest läuft gegen den echten MediaStore-Provider eines angeschlossenen Testgeräts oder Emulators und prüft Gruppierung, Zähler, Thumbnail-Decodierung und Paging mit 500 Bildern und 100 Videos:

```sh
./gradlew connectedDebugAndroidTest
```

Der Test erzeugt und entfernt dafür ausschließlich `DCIM/PhotoSyncLoadTest/`. Er ist für einen Emulator oder ein entbehrliches Testgerät gedacht; die normale App erzeugt dieses Verzeichnis nicht.
Zusätzliche Instrumentierungstests prüfen die Room-Migration von Version 1, Queue-Erhalt nach Schließen und erneutem Öffnen der Datenbank, Recovery eines laufenden Uploads, einen während des Request-Bodys getrennten Server sowie den vollständigen Share-to-Upload-Ablauf. Ein erfolgreicher Originalupload wird nach App-/Datenbankneustart nicht wiederholt.

In der Entwicklungsumgebung ist eine `http://`-Adresse möglich, etwa `http://10.0.2.2:3000/` für den Emulator oder die LAN-IP des Rechners für ein echtes Gerät. Vor dem Verlassen des privaten Netzes ausschließlich eine vertrauenswürdige HTTPS-Adresse verwenden; der Server selbst benötigt dafür weiterhin einen TLS-Reverse-Proxy oder VPN-Zugang.

Der Backendvertrag steht in [../docs/api.md](../docs/api.md). Für ein weiteres eigenes Gerät erzeugt ein bestehendes Gerät einen Pairing-Code mit `purpose: "device"`; ein Partner verwendet `purpose: "partner"`.
