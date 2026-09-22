# Android Compose Previews

Die wichtigsten Android-Oberflächen haben zustandsbasierte Content-Composables:
Container bleiben für ViewModel, Paging, Berechtigungen und Netzwerk zuständig, die
Darstellung nimmt UI-State und Callbacks entgegen.

Zentrale realistische Fixtures und alle `@Preview`-Entrypoints liegen ausschließlich
unter `android/app/src/debug/java/de/photosync/ui/PhotoSyncPreviews.kt`. Dadurch
enthalten Release-Artefakte keinen Preview-Code. Die Previews benötigen weder Login,
Server, MediaStore noch Mediendownloads.

Android Studio zeigt sie über **View > Tool Windows > Preview** an, nachdem
`PhotoSyncPreviews.kt` geöffnet wurde.

Die Preview **Hauptnavigation** zeigt die neue App-Struktur mit den zwei primären
Tabs **Meine Alben** und **Partner**. Einstellungen, Papierkorb und Abmelden sind
sekundäre Ziele im Overflow-Menü der Top-App-Bar. Eigene Previews zeigen außerdem
die modernisierten Album-, Partner-, Einstellungs- und Papierkorb-Inhalte.

Für die Galeriearbeit stehen zusätzlich **Albumübersicht – dicht**, **Geöffnetes
Album – groß**, **Geöffnetes Album – dicht** und **Lokale Bildansicht** bereit.
Die Dichte-Previews verwenden dieselben Content-Composables und Startwerte wie die
interaktive Pinch-to-Zoom-Darstellung.
