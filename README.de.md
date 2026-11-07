# Muse Daily Recorder

[English](README.md) · [简体中文](README.zh-CN.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [Español](README.es.md) · [Français](README.fr.md) · Deutsch · [Português](README.pt.md) · [Русский](README.ru.md)

**Aktuelle Version: v1.14.0**

Eine Android-Begleit-App für Aufnahmen rund um die Uhr. Das Handy ist immer dabei – es ersetzt den klassischen Aufnahmestift: Aufnahmen werden automatisch segmentiert, in die eigene Cloud gesichert und mit Ereignismarkern, Transkripten und einer Tageszusammenfassung zu etwas, das sich wirklich durchsuchen und nachlesen lässt. Keine Kabelübertragung, keine verlorenen Dateien.

Muse selbst kann kein Mikrofon dauerhaft abhören, deshalb besteht das System aus drei Teilen:

1. **Android-App** (`MuseRecorder.apk`): ein Recorder mit Vordergrund-Dienst, der alle 60 Minuten ein neues Segment schneidet und jedes fertige Segment automatisch in den eigenen S3-kompatiblen Speicher hochlädt. Transkription und Zusammenfassungen laufen auf Muse-Seite.
2. **Ereignismarker**: Wichtige Momente werden während der Aufnahme markiert; die Marker werden zusammen mit dem Audio in den Bucket hochgeladen.
3. **Empfänger + Tageszusammenfassung**: Skripte in `receiver/` holen Audio und Marker eines Tages aus dem Bucket, transkribieren sie und speichern segmentweise Transkripte, Analysen und eine Tageszusammenfassung in Supabase.

## Funktionen

- **Segmentierte Aufnahme + automatischer Upload** – alle 60 Minuten ein neues .m4a-Segment (je ca. 21 MB), automatisch hochgeladen in S3-kompatiblen Objektspeicher (Cloudflare R2, Backblaze B2, Alibaba Cloud OSS, Tencent Cloud COS, AWS S3, Qiniu Kodo oder beliebige eigene Endpunkte; Presets inklusive). Fehlgeschlagene Uploads warten in einer Warteschlange und versuchen es von selbst erneut; eine Liste ausstehender Uploads zeigt, was noch fehlt.
- **Ereignismarker** – ein Gesprächs- und ein Besprechungsschalter rahmen die wichtigen Zeiträume ein; der Tagesbericht gliedert die Inhalte danach.
- **Self-Notes per Gedrückthalten** – Button gedrückt halten, sobald dir etwas einfällt; während einer Aufnahme markiert das den Moment, im Leerlauf nimmt es eine eigenständige Sprachnotiz auf.
- **Automatischer Snapshot (optional, standardmäßig aus)** – Handy aus der flachen Lage aufrichten, und es macht leise ein Foto (Rück-, Front- oder beide Kameras). Solange die Aufnahme läuft, funktionieren Snapshots auch bei ausgeschaltetem Bildschirm. Die Kameraauswahl gilt sofort.
- **Cloud-Tab** – Bucket nach Datum durchsuchen: Karte mit Speichernutzung (gegen die 10 GB der R2-Free-Tier), Foto-/Audio-Zähler pro Tag, Foto-Raster mit Vorschaubildern und Audio per Tippen abspielbar.
- **Berichte-Tab** – Tageszusammenfassungen aus Supabase und zu jedem Tag ein „Tages-Aktivitätsblatt": eine ca. 1 MB große Collage aus den schärfsten Snapshots des Tages, gruppiert nach Aktivitätsphasen, mit kurzer Bildunterschrift pro Phase.
- **Dunkelmodus** – System folgen, immer hell oder immer dunkel (Einstellungen → Sprache & System).
- **Oberfläche in 9 Sprachen** – Englisch, vereinfachtes Chinesisch, Japanisch, Koreanisch, Spanisch, Französisch, Deutsch, Portugiesisch, Russisch; der Wechsel gilt sofort. Der App-Name lautet in allen Sprachen „Muse Daily Recorder".

## Design

Design v2 „Cloud White" – klare Karten, ein einziges Blau als Akzent, Tableiste unten:

![Design v2 hell](docs/images/design-v2-light.png)

![Design v2 dunkel](docs/images/design-v2-dark.png)

Helle und dunkle Variante desselben Designs. Hinweis: Der Aufnahme-Tab wird im hier gezeigten klassischen, kompakten Layout ausgeliefert (Aufnahmeknopf und Wellenform in einer Zeile, feste Karten im Fußbereich) – ein früheres Redesign mit hoher Karte nahm zu viel vertikalen Platz ein und wurde in v1.14.0 zurückgenommen.

## Erste Schritte

### 1. Einen S3-Bucket vorbereiten (Beispiel Cloudflare R2)

1. Auf cloudflare.com registrieren und R2 aktivieren (Free-Tier: 10 GB, Downloads kostenlos; zur Aktivierung kann eine Karte verlangt werden, innerhalb des Freikontingents wird aber nichts berechnet). Einen Bucket anlegen, z. B. `recordings`.
2. Unter „Manage R2 API Tokens" **ein Token mit Lese- und Schreibzugriff nur auf diesen Bucket erstellen** (Object Read & Write, auf den Bucket beschränkt). Access Key ID, Secret Access Key und die Account ID notieren (der Endpunkt lautet `https://<AccountID>.r2.cloudflarestorage.com`).
3. In der App das Preset **Cloudflare R2** wählen, `<AccountID>` im Endpunkt durch die echte ID ersetzen, Bucket-Namen und beide Schlüssel eintragen und speichern.

Andere Anbieter funktionieren genauso (alle mit Presets in der App). Alibaba/Tencent/AWS/Qiniu brauchen den Haken bei „virtual-host style"; R2/B2 nicht. Tencent-Bucket-Namen tragen ein `-APPID`-Suffix und nutzen SecretId/SecretKey.

### 2. Installation und Alltag

1. Die APK aus den [Releases](https://github.com/skyvense/muse-recorder/releases) herunterladen (das fortlaufende `latest`-Release enthält immer den neuesten Build; versionierte Releases bleiben für immer erhalten), aufs Handy schicken und installieren (persönliche Sideload-App, also einmal „unbekannte Quellen" erlauben). Über eine ältere Version installieren behält die Einstellungen.
2. App öffnen → ⚙️ Einstellungen: S3 ausfüllen und speichern (Speichern löst sofort einen Upload aus) → Hintergrundausführung erlauben (Akku-Optimierung deaktivieren; auf Samsung-Handys zusätzlich den App-Akku auf „Uneingeschränkt" stellen) → zurück zum Aufnahme-Tab und den roten runden Button tippen (während der Aufnahme wird er zum Quadrat, mit Live-Wellenform und Fortschritt des aktuellen Segments).
3. **Uploads laufen vollautomatisch**: Die Warteliste zeigt den Status jedes Segments, während des Uploads erscheint ein Live-Fortschritt, und das Ergebnis bleibt danach auf dem Bildschirm – bei Fehlern mit der Server-Fehlermeldung. Der manuelle Button „Jetzt erneut hochladen" erscheint nur nach einer Einstellungsänderung oder nach über 30 Minuten fehlgeschlagener automatischer Versuche.
4. Der ⓘ-Button oben rechts öffnet eine eingebaute Hilfeseite (Aufnahme, Upload, Marker, Einstellungen, FAQ, Datenschutz).

Marker werden pro Tag im Bucket unter `markers/events-<date>.jsonl` gespeichert. Schalterzustände werden gemerkt, sodass Schließen und erneutes Öffnen der App nichts durcheinanderbringt.

## Tageszusammenfassungs-Pipeline (Muse-Seite)

```bash
# Umgebungsvariablen: S3_ENDPOINT / S3_BUCKET / S3_ACCESS_KEY / S3_SECRET_KEY / S3_REGION / S3_STYLE (path|virtual)
# Optional: S3_DELETE_AFTER=1 → Cloud-Originale nach dem Abruf löschen (Transkripte und Berichte bleiben)
python3 receiver/fetch_day_from_s3.py --date 2026-10-23
python3 receiver/summarize_day.py   --date 2026-10-23
```

Transkription, zwei Optionen: `OPENAI_API_KEY` setzen (Whisper API) oder `pip install faster-whisper` und lokal kostenlos transkribieren. Muse eine Berichtszeit nennen (z. B. jeden Morgen um 7:30 Uhr), und ein geplanter Job kann die Pipeline automatisch ausführen und den Bericht in den Chat posten.

## Datenschutz & Sicherheit

- Audio geht **ausschließlich in den eigenen Bucket** – kein Drittserver in der Kette.
- Die Transkription kann **komplett lokal** laufen (faster-whisper); außer dem gewählten Speicher muss nichts die eigene Hardware verlassen.
- **Keine Zugangsdaten in diesem Repository.** Der S3-Secret-Key existiert nur in den App-Einstellungen auf dem Handy; die Supabase-Schlüssel der Empfänger-Skripte liegen in lokalen Env-Dateien außerhalb des Repos. Speicher-Tokens auf einen einzigen Bucket beschränken, damit ein Leck eingedämmt bleibt.
- Andere Menschen aufzunehmen erfordert vielerorts deren Einwilligung – nur aufnehmen, wo es legal ist und die andere Seite Bescheid weiß.

## Realitätscheck

- **Akku**: Ganztägiges Aufnehmen verbraucht grob 3–6 % pro Stunde; wenn möglich, über Nacht am Ladegerät aufnehmen. „Nur per WLAN hochladen" ist verfügbar.
- **Speicher**: etwa 500 MB pro Tag. Lokale Kopien werden nach erfolgreichem Upload standardmäßig gelöscht; für die Cloud-Seite `S3_DELETE_AFTER=1` nutzen oder eine Bucket-Lifecycle-Regel, die nur die letzten Tage behält.
- **Stabilität**: Android schränkt Hintergrund-Aufnahmen stark ein; die dauerhafte Benachrichtigung in der Statusleiste ist Systemvorgabe (und zugleich der Beweis, dass aufgenommen wird).

## Build & Release

- `build-apk.sh` baut die APK direkt mit den Build-Tools des Android SDK (für den Release-Build ist kein Gradle nötig).
- Jeder Push auf `master` triggert GitHub Actions, baut eine **signierte** APK und veröffentlicht zwei Releases mit derselben Datei: ein fortlaufendes `latest`-Release und ein versioniertes `vX.Y.Z`-Release. Installation über die [Releases](https://github.com/skyvense/muse-recorder/releases).
- `muse-recorder.keystore` + `keystore-info.txt` sind der Signaturschlüssel – **gut aufbewahren**. Upgrades müssen mit demselben Schlüssel signiert sein, sonst lassen sie sich nicht über die alte Version installieren. Verschlüsselte Kopien liegen in `vault/` (AES-256-CBC + PBKDF2).

## Repository-Struktur

- `app-project/`: der komplette App-Quellcode (Java + Android-Framework-APIs; die S3-Signatur ist eine reine Java-SigV4-Implementierung, geprüft gegen die offiziellen AWS-Testvektoren).
- `receiver/`: die Muse-seitige Pipeline — `fetch_day_from_s3.py` (holt Audio + Marker + Snapshots aus dem Bucket; nur Python-Standardbibliothek, Signatur mit denselben offiziellen Vektoren geprüft), `summarize_day.py` (Transkription + Berichtsentwurf), Supabase-Schreibskripte und Snapshot-Blatt-Generatoren. `receiver/server.py` und `fetch_day_from_supabase.py` sind Archive des alten v1.0-/v1.1-Ansatzes.
- `docs/images/`: die oben verwendeten Designbilder.
- `README.<Sprache>.md`: diese Datei in den anderen 8 Sprachen.

Seit v1.2 ist die App **S3-only** (der selbst gehostete Empfänger aus v1.0 und der direkte Supabase-Upload aus v1.1 wurden aus der App entfernt; die Skripte bleiben als Referenz in `receiver/`).
