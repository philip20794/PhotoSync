# Medien-Derivate

Stand: 15.09.2026. Nach einem vollständig bestätigten Original legt das Backend zwei persistente Jobs in PostgreSQL an. Der HTTP-Upload wartet nicht auf Bild- oder Videokonvertierung. Ein Worker im Backend-Prozess beansprucht Jobs einzeln und schreibt ausschließlich unter `derivatives/`; `originals/` wird nur gelesen.

## Gewähltes Profil

| Quelle | Variante | Format und Codec | Abmessung | Qualität |
| --- | --- | --- | --- | --- |
| Bild | `thumbnail` | WebP über Sharp/libvips | maximal 512 px lange Kante, kein Hochskalieren | Quality 76, Effort 4, Photo-Preset, Smart Subsample |
| Bild | `optimized` | WebP über Sharp/libvips | maximal 2560 px lange Kante, kein Hochskalieren | Quality 82, Effort 5, Photo-Preset, Smart Subsample |
| Video | `thumbnail` | JPEG über FFmpeg | maximal 512 px lange Kante | FFmpeg `q:v=3`; Frame bei 1 s, bei Clips bis 2 s das erste Frame |
| Video | `optimized` | MP4 mit H.264/AVC und AAC | maximal 1920 px lange Kante, Seitenverhältnis bleibt erhalten, kein Hochskalieren, maximal 30 fps | libx264 High Level 4.1, CRF 23, Preset `medium`, `yuv420p`; AAC 128 kbit/s Stereo; `faststart` |

2560 Pixel geben einem hochauflösenden Smartphone-Display Reserve für Zoom und Hochformat, während WebP bei Fotoinhalten deutlich weniger Speicher als hochauflösende Kameraoriginale benötigt. Das 1920-Pixel-H.264-Profil deckt Full-HD im Hoch- und Querformat ab. H.264 High, 8-Bit-`yuv420p`, AAC und MP4 wurden wegen ihrer breiten Android-Hardwareunterstützung gewählt. `faststart` legt MP4-Steuerdaten vor die Nutzdaten und verkürzt den Start beim HTTP-Streaming.

Sharp richtet Bilder anhand EXIF aus, skaliert mit libvips und entfernt Metadaten aus den Derivaten. Das unveränderte Original behält Metadaten und Orientierung. Der Server verwendet Sharp 0.35.4; die [Sharp-Ausgabeoptionen](https://sharp.pixelplumbing.com/api-output/) dokumentieren WebP-Qualität und Effort, [Sharp autoOrient](https://sharp.pixelplumbing.com/api-operation/#autoorient) die Orientierungsbehandlung. Videoverarbeitung erfolgt mit dem FFmpeg-Paket des Debian-Bookworm-Images; die [FFmpeg-Formatdokumentation](https://ffmpeg.org/ffmpeg-formats.html) beschreibt MP4 und die [FFmpeg-Codecübersicht](https://ffmpeg.org/general.html#Video-Codecs) H.264/libx264-Unterstützung.

## Zustände, Atomarität und Wiederholung

Jedes Asset besitzt genau je eine Zeile der Art `thumbnail` und `optimized`:

```text
pending → processing → ready
                  └→ failed → processing …
```

Bei Fehlern steigt `attempts`; `lastError` und `nextAttemptAt` werden gespeichert. Automatische Versuche folgen exponentiellem Backoff. Permanente Medien- oder Codecfehler enden bei `DERIVATIVE_MAX_ATTEMPTS`; temporäre Infrastrukturfehler wie ENOSPC, Quota-, I/O-, Timeout- oder DB-Ausfälle bleiben darüber hinaus mit einem Abstand bis höchstens 24 Stunden automatisch retryfähig. Der Eigentümer kann fehlgeschlagene Varianten über `POST /v1/assets/{id}/derivatives/retry` sofort wieder freigeben. Beim Serverstart werden durch einen Prozessabbruch verbliebene `processing`-Zeilen auf `pending` gesetzt. Fertige Alt-Assets ohne Jobs werden schrittweise ergänzt.

Jeder Lease-Claim schreibt eine eigene `<kind>-<claimId>.<ext>.part` und übernimmt sie nach Größen- und SHA-Prüfung, Datei-`fsync`, atomarem Rename und Verzeichnis-`fsync` auf den ebenfalls claim-eigenen Finalpfad. Ein Heartbeat verlängert die Lease während Sharp/FFmpeg. Vor dem DB-Commit wird der Claim erneut geprüft. Ein Worker mit verlorenem Claim löscht nur seine eigene Ausgabe und kann deshalb nie die bereits committete Datei eines parallelen Gewinners entfernen. Die Reconciliation entfernt gealterte, nicht referenzierte Claim-Ausgaben nach dem Crashfenster. Das Original wird nie als Ausgabeziel geöffnet.

Der aktuelle Worker verarbeitet Jobs sequenziell im einzelnen Backend-Prozess. CPU-/RAM-Limits, separate Worker-Prozesse und Hardware-Encoding werden erst nötig, wenn die private Zwei-Personen-Last das verlangt. `DERIVATIVE_TOOL_TIMEOUT_MS` begrenzt FFmpeg- und ffprobe-Unterprozesse. Sharp verarbeitet Bildjobs direkt und sequenziell im Backend-Prozess; ein sauberer Prozessstopp wartet auf den aktiven Job. `GET /health` prüft FFmpeg/ffprobe und meldet Queuezahlen, Rückstand sowie festhängende `processing`-Jobs. Fehlende Werkzeuge machen Readiness fehlerhaft; Rückstand oder festhängende Jobs werden als `degraded` gemeldet, lösen aber keine Restart-Schleife aus. `GET /health/live` bleibt davon unabhängig.

## Gemessene Stichprobe

Der reproduzierbare Prüfer `server/test/quality/derivatives-quality.mjs` lief am 14.09.2026 mit zwei installierten Ubuntu-Fotohintergründen und zwei lokalen, real codierten Full-HD-Videos. Er dekodiert jede Ausgabe, vergleicht SHA-256 des Originals vor und nach beiden Konvertierungen und verlangt mindestens 20 % Größenersparnis sowie PSNR ≥ 30 dB für Bilder und SSIM ≥ 0,90 für Videos.

| Datei | Typ | Original | Optimized | Ersparnis | Qualitätsmetrik | Ausgabe |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| `Monument_valley_by_orbitelambda.jpg` | Foto | 1.957.897 B | 106.316 B | 94,6 % | PSNR 41,80 dB | 2560×1436 |
| `Clouds_by_Tibor_Mokanszki.jpg` | Foto | 4.032.506 B | 73.142 B | 98,2 % | PSNR 42,83 dB | 2560×1440 |
| `cave.mp4` | Video | 4.327.956 B | 2.646.865 B | 38,8 % | SSIM 0,9935 | 1920×1080 |
| `wolken.mp4` | Video | 10.560.470 B | 6.652.686 B | 37,0 % | SSIM 0,9894 | 1920×1080 |

Die Stichprobe belegt die konkrete Pipeline, ist aber kein vollständiger Wahrnehmungstest für alle Kameras. Vor allem HDR-/10-Bit-Video, ungewöhnliche Farbräume, sehr stark vorkomprimierte Medien und animierte Bilder benötigen später einen eigenen Gerätekorpus und gegebenenfalls Tone-Mapping oder zusätzliche Profile.
