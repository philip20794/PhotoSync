# Android

Native Kotlin-App mit Jetpack Compose. Noch keine Activities, Features oder Gradle-Builddateien.

Reservierter Paketpfad: `app/src/main/java/de/photosync/`.

Geplante Pakete: `ui/` (Compose/ViewModels), `data/local/` (Room), `data/remote/` (Retrofit/OkHttp), `data/media/` (MediaStore), `sync/` (WorkManager/Outbox).

Vor dem ersten Build Android-Versionen beider Geräte erfassen und minSdk, targetSdk, compileSdk sowie eine kompatible AGP/Kotlin/Compose/KSP/Room-Kombination fixieren. Gradle Wrapper mit Prüfsumme einchecken. Android Studio, JDK und SDK dann entsprechend dokumentieren. Bibliotheken mit ihrem ersten Einsatz einbinden; keine Cloud-SDKs.
