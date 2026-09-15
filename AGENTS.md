# PhotoSync – Arbeitsregeln für Codex

* Lies vor Änderungen die relevanten Dateien und bestehende Dokumentation unter `docs/`.
* Nutze Serena bevorzugt für Symbolsuche, Referenzen und gezielte Code-Navigation, sofern verfügbar.
* Vermeide unnötiges Einlesen großer vollständiger Dateien und breitflächige Repository-Suchen.
* Prüfe vor Änderungen `git status`.
* Begrenze Änderungen auf die aktuelle Aufgabe und implementiere keine späteren Roadmap-Schritte vorweg.
* Bestehende Architektur, APIs und Datenmodelle bevorzugt erweitern statt parallele Lösungen einzuführen.
* Bei Änderungen immer Auswirkungen auf Android und Backend mitdenken, wenn beide Seiten betroffen sind.
* Persistente Sync-, Queue- und Transferzustände robust gegenüber Prozess-Kills, Neustarts und Netzwerkabbrüchen halten.
* Sync- und Serveroperationen möglichst idempotent bauen.
* Keine teilweise geschriebenen Dateien als gültig übernehmen.
* Nach Änderungen passende Tests und Builds tatsächlich ausführen.
* Danach `git diff` und `git diff --check` prüfen.
* Wenn Tests oder Builds nicht möglich sind, konkret nennen, was fehlt und warum.
* Dauerhafte Architektur- oder Sync-Änderungen in der passenden Datei unter `docs/` dokumentieren.
* Keine Secrets, Tokens oder echten Zugangsdaten ins Repository schreiben.

Am Ende jeder größeren Aufgabe kurz nennen:

* was geändert wurde
* welche Tests/Builds gelaufen sind
* welche relevanten Risiken oder Blocker offen bleiben
