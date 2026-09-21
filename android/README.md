# PhotoSync Android

Native Kotlin-App mit Jetpack Compose für Android **8.0/API 26 oder neuer**. Die App konfiguriert eine Serveradresse, prüft die Verbindung, richtet das erste Gerät ein oder verbindet es per Pairing-Code und speichert Geräte-Credentials verschlüsselt im Android Keystore. Der Tab **Mein Handy** zeigt vorhandene MediaStore-Alben und synchronisiert ausdrücklich freigegebene Alben über eine persistente Upload-Queue. Originale werden unverändert gestreamt; der Tab Partner zeigt freigegebene Partneralben.

## Architektur

- `ui/`: Compose-Screens, Navigation und ViewModel.
- `domain/`: kleine, plattformunabhängige Regeln wie die Prüfung der Serveradresse.
- `data/local/`: Room-Datenbank für Server- und nicht geheime Sitzungsmetadaten sowie Keystore-geschützte Credentials.
- `data/media/`: lesende MediaStore-Abfragen, Albumgruppierung, batchweise Inventarisierung und seitenweises Laden.
- `data/sync/`: WorkManager-Planung, Room-Queue, SHA-256 und chunkweise fortsetzbarer Originalupload.
- `data/remote/`: Retrofit/OkHttp-Verträge zum bestehenden `/health`- und Auth-API.
- `domain/model/`: lokale Album- und Medienmodelle ohne Uploadlogik.
- `ui/gallery/`: Berechtigungsfluss, Albumübersicht und paginiertes Medienraster.

Room 2.7, Retrofit 3, OkHttp, Coroutines, Kotlin Serialization, Paging 3.3 und Coil 3.2 einschließlich Video-Decoder sind fest versioniert. WorkManager führt die dauerhafte Queue bei verfügbarem Netzwerk aus. SHA-256 und Upload lesen Content-URIs als Streams. Room-Schema 8 speichert pro Upload die Server-Sitzung und den bestätigten Offset; 4-MiB-Chunks setzen nach Prozess-Kill, Neustart oder Netzwechsel dort fort. Tokens werden weder in Room noch im Klartext in `SharedPreferences` gespeichert. `allowBackup=false` verhindert System-Backups dieser App-Daten und Credentials.

Die zwei Starttabs heißen **Mein Handy** und **Partner**. Lokale Alben zeigen Name, Cover, getrennte Bild-/Videoanzahl, den Schalter **Teilen** sowie Anzahl und Bytefortschritt. Ausschalten beendet den Partnerzugriff serverseitig und bewahrt bereits hochgeladene Originale sowie die lokale Zuordnung. Die App schreibt nichts in MediaStore und erzeugt keine Medienordner. Der Partnername kann erst mit einer späteren Partnerprofil-/Album-API zuverlässig geladen werden; das aktuelle Backend liefert ihn bei einem leeren Albumstand nicht.

Auf Android 13 und neuer fordert die App `READ_MEDIA_IMAGES` und `READ_MEDIA_VIDEO` an. Android 14 und neuer unterstützt zusätzlich den systemseitig eingeschränkten Zugriff auf ausgewählte Fotos und Videos. Bis Android 12 wird `READ_EXTERNAL_STORAGE` mit `maxSdkVersion=32` verwendet. Bei eingeschränktem Zugriff zeigt die App nur die vom System freigegebenen Medien und bietet **Ändern** zum erneuten Öffnen des Berechtigungsdialogs.

## Build und Installation

Für optionales FCM werden lokale Gradle-Properties `firebaseAppId`, `firebaseSenderId`,
`firebaseApiKey` und `firebaseProjectId` aus demselben Firebase-Projekt benötigt.
Ohne diese Werte bleibt FCM inaktiv; der periodische WorkManager-Abgleich funktioniert
weiter. Keine `google-services.json` oder Service-Account-Datei einchecken.

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
Zusätzliche Instrumentierungstests prüfen Room-Migrationen, Queue-/Session-Erhalt nach erneutem Öffnen der Datenbank, einen während eines Chunks getrennten Server sowie den vollständigen Share-to-Upload-Ablauf. Diese Tests müssen auf Emulator oder Gerät ausgeführt werden; der normale JVM-Testlauf simuliert dagegen DB-/Prozessneustarts.

Die Standard-Compose-Konfiguration bindet den Entwicklungsserver absichtlich nur an `127.0.0.1`. Ein Emulator erreicht ihn über `http://10.0.2.2:3000/`. Für ein per USB/ADB verbundenes echtes Gerät zuerst `adb reverse tcp:3000 tcp:3000` ausführen und in der App `http://127.0.0.1:3000/` verwenden. Dadurch wird kein Port im LAN geöffnet. Eine LAN-IP funktioniert mit der sicheren Standardbindung nicht; eine bewusste LAN-Freigabe benötigt eine geänderte Portbindung, Firewall-Regeln und ein vertrauenswürdiges Netz. Vor Zugriff außerhalb des lokalen Testaufbaus ausschließlich eine vertrauenswürdige HTTPS-Adresse über TLS-Reverse-Proxy oder VPN verwenden.

Der Backendvertrag steht in [../docs/api.md](../docs/api.md). Für ein weiteres eigenes Gerät erzeugt ein bestehendes Gerät einen Pairing-Code mit `purpose: "device"`; ein Partner verwendet `purpose: "partner"`.


## Partnergalerie und Cache

Der Tab Partner ruft ausschliesslich GET /v1/partner/albums auf. Diese Serverroute liefert nur aktuell freigegebene Alben des anderen Accounts, deren Anzahl fertiger Medien und hoechstens ein Cover; sie laedt keine Albumbytes. Beim Oeffnen laedt Paging 3 Metadaten in Seiten zu 60 Assets mit einem Prefetch von 18 Positionen. Auch ein Album mit vielen Gigabyte wird daher weder als Metadatenliste noch als Medieninhalt vollstaendig geladen.

Sichtbare Kacheln laden nur thumbnail. Beim Oeffnen eines Fotos bleibt das Thumbnail sichtbar, bis ausschliesslich optimized geladen ist; das Original wird nie automatisch angefordert. Videos werden mit Media3 ueber die optimierte Variante und HTTP-Range-Requests abgespielt.

Partner-Varianten liegen nur unter cacheDir/partner-variant-images und cacheDir/partner-variant-video. Bilddateien und Media3-Videosegmente haben getrennte LRU-Budgets. Cache-Keys enthalten Server-/Account-Scope, Asset-ID, Variantentyp, Derivatzeitpunkt und Hash; gleiche Keys nutzen Single-Flight. Logout löscht den Cache synchron, ein Fehler wird persistent zur Wiederholung vorgemerkt.


## Dauerhafte Offline-Verfügbarkeit

Offline-Verfügbarkeit gilt ausschließlich für Partneralben. Pro Album werden NONE, OPTIMIZED oder ORIGINAL als Wunsch in Room gespeichert. Die UI zeigt vor der Auswahl die aus Servermetadaten aggregierten Größen. Einzelne Assets können den Albumstandard überschreiben.

Dauerhafte Dateien liegen unter filesDir/partner-offline-v2/<scope-sha256> und damit außerhalb der Cache-Verzeichnisse. Room trennt sie nach Server, Nutzer, Album und Asset. Pro Asset verweist Room auf höchstens eine tatsächlich vorhandene Variante. Beim Wechsel wird die neue Datei per Range fortgesetzt, vollständig per SHA-256 geprüft und atomar übernommen; erst danach wird die bisherige Variante gelöscht.

WorkManager hält Albumwunsch, Metadaten-Seitencursor, tatsächliche Variante, DOWNLOADING, RETRY/FAILED, Teilfortschritt und Fehler über Prozess- und App-Neustarts hinweg. Der Worker persistiert Fortschritt während des Streams; nach Neustart ist die `.part`-Länge maßgeblich. 200/206/416 werden getrennt behandelt, Antwort- und Gesamtgröße sowie SHA-256 geprüft und erst danach atomar finalisiert. Bereits als READY markierte Dateien werden vor Wiederverwendung ebenfalls validiert.
