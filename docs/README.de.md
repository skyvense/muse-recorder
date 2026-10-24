# Muse Daily Recorder v1.8.2 (S3-Upload + Ereignismarkierungen + Tageszusammenfassung)

[English](../README.md) | [中文](../README.zh-CN.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Español](README.es.md) | [Français](README.fr.md) | Deutsch | [Português](README.pt.md) | [Русский](README.ru.md)

**Ersatz für ein Diktiergerät:** Das Telefon ist immer dabei, Aufnahmen werden automatisch segmentiert und in die eigene Cloud gesichert, und Ereignismarkierungen, Transkripte und die Tageszusammenfassung machen Audio durchsuchbar — ohne Kabel, ohne verlorene Dateien.

Muse selbst kann das Mikrofon nicht dauerhaft abhören, daher besteht diese Lösung aus drei Teilen:

1. **Android-App** (`MuseRecorder.apk`): ein Recorder als Vordergrunddienst, der **alle 60 Minuten ein neues Segment schneidet** (.m4a, ca. 21 MB pro Segment) und jedes fertige Segment automatisch in Ihren eigenen **S3-kompatiblen Objektspeicher** hochlädt. Fehlgeschlagene Uploads werden in eine Warteschlange gestellt und erneut versucht; die App zeigt eine **Liste ausstehender Uploads**.
2. **Ereignismarkierungen**: Markieren Sie wichtige Momente während der Aufnahme (siehe unten); die Markierungen werden zusammen mit dem Audio in den Bucket hochgeladen.
3. **Tageszusammenfassung** (auf Muses Seite): `receiver/fetch_day_from_s3.py` holt Audio und Markierungen des Vortags aus dem Bucket → transkribiert sie → Muse macht daraus einen Tagesbericht.

Seit v1.2 ist die App **nur noch S3** (der selbst gehostete Empfänger aus v1.0 und der direkte Supabase-Upload aus v1.1 wurden entfernt; die alten Empfängerskripte liegen in `receiver/` weiterhin als Referenz).

## 1. Einen S3-Bucket vorbereiten (Beispiel Cloudflare R2)

1. Registrieren Sie sich auf cloudflare.com und aktivieren Sie R2 (kostenlose Stufe: 10 GB, Downloads kostenlos; bei der Aktivierung wird möglicherweise eine Karte verlangt, innerhalb des kostenlosen Kontingents wird aber nichts berechnet). Erstellen Sie einen Bucket, z. B. `recordings`.
2. Erstellen Sie unter „R2-API-Token verwalten" **ein Token mit Lese- und Schreibzugriff nur für diesen Bucket** (Object Read & Write, auf den Bucket beschränkt). Notieren Sie sich Access Key ID, Secret Access Key und Ihre Account ID (der Endpunkt lautet `https://<AccountID>.r2.cloudflarestorage.com`).
3. Wählen Sie in der App die Voreinstellung **Cloudflare R2**, ersetzen Sie `<AccountID>` im Endpunkt durch Ihre echte ID, tragen Sie Bucket-Namen und die beiden Schlüssel ein und speichern Sie.

Andere Anbieter funktionieren genauso (alle haben Voreinstellungen in der App): Backblaze B2, Alibaba Cloud OSS (S3-kompatiblen Modus + Konto-AccessKey verwenden; Bucket in der Konsole erstellen), Tencent Cloud COS (Bucket-Namen tragen ein `-APPID`-Suffix; SecretId/SecretKey verwenden), AWS S3, Qiniu Kodo oder ein beliebiger S3-kompatibler Dienst über „Benutzerdefiniert". Alibaba/Tencent/AWS/Qiniu benötigen „Virtual-Host-Stil" (Häkchen setzen); R2/B2 nicht.

> Sicherheitshinweis: Der Secret Key lebt nur in der App auf dem Telefon — fügen Sie ihn niemals in einen Chat oder an eine öffentliche Stelle ein. Wenn das Token auf einen einzigen Bucket beschränkt ist, bleibt der Schaden bei einem Leck begrenzt.

## 2. Installieren und täglich verwenden

1. Senden Sie `MuseRecorder.apk` an das Telefon und installieren Sie es (eine persönliche App ohne Store-Eintrag, erlauben Sie also einmal „unbekannte Quellen"). Eine Installation über eine ältere Version behält Ihre Einstellungen.
2. Öffnen Sie die App → tippen Sie auf „⚙️ Konfiguration", tragen Sie die S3-Daten ein und speichern Sie (beim Speichern startet sofort ein Upload) → tippen Sie auf „Hintergrundausführung erlauben (Akkuoptimierung deaktivieren)" → tippen Sie auf den roten runden Knopf, um die Aufnahme zu starten (während der Aufnahme wird er zu einem Quadrat, darunter erscheinen Live-Wellenform und die verbleibenden Minuten des aktuellen Segments), und erteilen Sie die Mikrofon-/Benachrichtigungsberechtigungen. Auf Samsung-Telefonen stellen Sie den Akku der App zusätzlich auf „Uneingeschränkt".
3. **Uploads sind vollautomatisch**: Die Warteliste zeigt den Status jedes Segments, während des Uploads erscheint der Live-Fortschritt, und das Ergebnis bleibt danach auf dem Bildschirm stehen — bei Fehlern mit der Fehlermeldung des Servers. Fehlgeschlagene Segmente (z. B. offline) bleiben in der Warteschlange und werden automatisch erneut versucht. Ein manueller Knopf „Jetzt erneut hochladen" erscheint nur nach einer Einstellungsänderung oder nach mehr als 30 Minuten fehlgeschlagener automatischer Versuche. S3- und Aufnahmeeinstellungen sind standardmäßig eingeklappt; tippen Sie auf „⚙️ Konfiguration", um sie auszuklappen.
4. **Sprache**: Die Sprachauswahl oben in den Einstellungen bietet die Systemsprache plus Englisch, Chinesisch, Japanisch, Koreanisch, Spanisch, Französisch, Deutsch, Portugiesisch und Russisch und wird sofort wirksam. Der App-Name lautet in allen Sprachen immer „Muse Daily Recorder".
5. Der blaue ⓘ-Knopf oben rechts öffnet eine eingebaute Hilfeseite (Aufnahme, Upload, Markierungen, Einstellungen, FAQ, Datenschutz).

## 3. Ereignismarkierungen (Wichtiges für den Tagesbericht hervorheben)

- **Gesprächsschalter**: Schalten Sie ihn ein, wenn ein wichtiges Gespräch beginnt, und aus, wenn es endet.
- **Besprechungsschalter**: Schalten Sie ihn vor einer Besprechung ein und aus, wenn sie endet.
- **📌 Selbstnotiz-Knopf**: Halten Sie ihn gedrückt, wann immer Ihnen etwas einfällt — eine Idee, eine Aufgabe, ein Gedanke. Während der Aufnahme markiert er nur den Moment; im Leerlauf nimmt er eine eigenständige Sprachnotiz auf.
- Markierungen werden pro Tag im Bucket unter `markers/events-<Datum>.jsonl` gespeichert; der Tagesbericht teilt Gespräche/Besprechungen anhand der Markierungen auf und achtet besonders auf Inhalte in der Nähe von Selbstnotizen. Schalterzustände werden gespeichert, sodass Schließen und erneutes Öffnen der App sie nie durcheinanderbringt.

## 4. Tageszusammenfassung (auf Muses Seite)

```bash
# Umgebungsvariablen: S3_ENDPOINT / S3_BUCKET / S3_ACCESS_KEY / S3_SECRET_KEY / S3_REGION / S3_STYLE (path|virtual)
# Optional: S3_DELETE_AFTER=1 → löscht die Cloud-Originale nach dem Abruf (spart Cloud-Speicher; Transkripte und Berichte bleiben auf Muses Seite)
python3 receiver/fetch_day_from_s3.py --date 2026-10-23
python3 receiver/summarize_day.py   --date 2026-10-23
```

Transkription, zwei Möglichkeiten: Setzen Sie `OPENAI_API_KEY` (Whisper-API) oder installieren Sie `pip install faster-whisper` auf Muses Rechner (lokal, kostenlos). Nennen Sie Muse dann eine Berichtszeit (z. B. jeden Morgen 7:30 Uhr), und es wird ein geplanter Auftrag eingerichtet, der automatisch läuft und in den Chat postet.

## 5. Realistische Hinweise

- **Akku**: Ganztägige Aufnahme verbraucht etwa 3–6 % pro Stunde; nehmen Sie nach Möglichkeit auf, während das Telefon nachts lädt. Eine Option „Nur per WLAN hochladen" ist verfügbar.
- **Speicher**: ca. 500 MB pro Tag. Lokale Kopien werden nach erfolgreichem Upload standardmäßig gelöscht; für die Cloud-Seite verwenden Sie `S3_DELETE_AFTER=1` oder eine Lifecycle-Regel des Buckets, die nur die letzten Tage behält.
- **Stabilität**: Android schränkt Hintergrundaufnahmen streng ein; die dauerhafte Benachrichtigung in der Statusleiste ist eine Systemanforderung (und zugleich Ihr Beleg dafür, dass aufgenommen wird). Ein kurzer Blick morgens und abends auf „Seit X Minuten aufgenommen" in der App genügt.
- **Rechtliches**: Das Aufnehmen von Gesprächen mit anderen Personen erfordert vielerorts deren Einwilligung — verwenden Sie es nur dort, wo es legal ist und die andere Seite Bescheid weiß.

## 6. Verzeichnisstruktur

- `app-project/`: der vollständige App-Quellcode (Java + Android-Framework-APIs; die S3-Signatur ist eine reine Java-Implementierung, verifiziert anhand der offiziellen SigV4-Testvektoren von AWS).
- `receiver/fetch_day_from_s3.py`: holt Audio und Markierungen aus dem S3-Bucket (nur Python-Standardbibliothek; auch diese Signaturimplementierung hat dieselben offiziellen Testvektoren bestanden).
- `receiver/summarize_day.py`: Transkription + Berichtsentwurf; `receiver/server.py` und `fetch_day_from_supabase.py` sind Archive des alten Ansatzes.
- `build-apk.sh`: baut das APK direkt mit den Android-SDK-Build-Tools.
- `.github/workflows/build.yml`: Die CI baut bei jedem Push auf master und veröffentlicht zwei Releases mit demselben APK — ein rollierendes `latest`-Release (immer der neueste Build) und ein versioniertes Release pro Version (Tag `vX.Y.Z`, wird für immer behalten). Den neuesten Build gibt es jederzeit hier: https://github.com/skyvense/muse-recorder/releases/tag/latest
- `muse-recorder.keystore` + `keystore-info.txt`: der Signaturschlüssel — **gut aufbewahren**. Upgrades müssen mit demselben Schlüssel signiert werden, sonst lassen sie sich nicht über die alte Version installieren. Verschlüsselte Kopien liegen in `vault/` (AES-256-CBC + PBKDF2).
