# Muse Daily Recorder v1.8.2 (envoi vers S3 + marqueurs d'événements + résumé quotidien)

[English](README.md) | [中文](README.zh-CN.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Español](README.es.md) | Français | [Deutsch](README.de.md) | [Português](README.pt.md) | [Русский](README.ru.md)

Muse ne peut pas écouter le microphone en continu, donc cette solution comporte trois parties :

1. **Application Android** (`MuseRecorder.apk`) : un enregistreur en service de premier plan qui **découpe un nouveau segment toutes les 60 minutes** (.m4a, ~21 Mo par segment) et envoie automatiquement chaque segment terminé vers votre propre **stockage objet compatible S3**. Les envois échoués sont mis en file d'attente et réessayés ; l'application affiche une **liste des envois en attente**.
2. **Marqueurs d'événements** : marquez les moments importants pendant l'enregistrement (voir ci-dessous) ; les marqueurs sont envoyés dans le bucket avec l'audio.
3. **Résumé quotidien** (côté Muse) : `receiver/fetch_day_from_s3.py` récupère l'audio et les marqueurs de la veille depuis le bucket → les transcrit → Muse les transforme en rapport quotidien.

Depuis v1.2, l'application est **S3 uniquement** (le récepteur auto-hébergé de v1.0 et l'envoi direct vers Supabase de v1.1 ont été supprimés ; les anciens scripts du récepteur sont conservés dans `receiver/` pour référence).

## 1. Préparer un bucket S3 (exemple avec Cloudflare R2)

1. Inscrivez-vous sur cloudflare.com et activez R2 (offre gratuite : 10 Go, téléchargements gratuits ; l'activation peut demander une carte, mais rien n'est facturé dans la limite gratuite). Créez un bucket, par exemple `recordings`.
2. Sous « Gérer les jetons d'API R2 », **créez un jeton avec un accès en lecture et écriture limité à ce bucket** (Object Read & Write, restreint au bucket). Notez l'Access Key ID, la Secret Access Key et votre Account ID (l'endpoint est `https://<AccountID>.r2.cloudflarestorage.com`).
3. Dans l'application, choisissez le préréglage **Cloudflare R2**, remplacez `<AccountID>` dans l'endpoint par votre véritable ID, remplissez le nom du bucket et les deux clés, puis enregistrez.

Les autres fournisseurs fonctionnent de la même façon (tous ont des préréglages dans l'application) : Backblaze B2, Alibaba Cloud OSS (utilisez le mode compatible S3 + AccessKey du compte ; créez le bucket dans la console), Tencent Cloud COS (les noms de bucket portent un suffixe `-APPID` ; utilisez SecretId/SecretKey), AWS S3, Qiniu Kodo, ou tout service compatible S3 via « Personnalisé ». Alibaba/Tencent/AWS/Qiniu nécessitent l'option « style hôte virtuel » ; R2/B2 non.

> Remarque de sécurité : la Secret Key ne vit que dans l'application du téléphone — ne la collez jamais dans une conversation ni dans un endroit public. Limiter le jeton à un seul bucket limite les dégâts en cas de fuite.

## 2. Installation et usage quotidien

1. Envoyez `MuseRecorder.apk` sur le téléphone et installez-le (application personnelle non publiée, autorisez donc « sources inconnues » une fois). Une installation par-dessus une ancienne version conserve vos réglages.
2. Ouvrez l'application → touchez « ⚙️ Configuration », remplissez les informations S3, puis enregistrez (l'enregistrement déclenche aussitôt un envoi) → touchez « Autoriser l'exécution en arrière-plan (désactiver l'optimisation de la batterie) » → touchez le bouton rond rouge pour démarrer l'enregistrement (il devient un carré pendant l'enregistrement, avec une forme d'onde en direct et les minutes restantes du segment en cours en dessous), et accordez les autorisations microphone/notifications. Sur les téléphones Samsung, mettez aussi la batterie de l'application sur « Sans restriction ».
3. **Les envois sont entièrement automatiques** : la liste d'attente affiche l'état de chaque segment, la progression en direct apparaît pendant l'envoi, et le résultat reste affiché ensuite — avec le message d'erreur du serveur en cas d'échec. Les segments en échec (par exemple hors ligne) restent en file d'attente et sont réessayés automatiquement. Un bouton manuel « réessayer l'envoi maintenant » n'apparaît qu'après un changement de réglages ou après plus de 30 minutes d'échecs automatiques. Les réglages S3 et d'enregistrement sont repliés par défaut ; touchez « ⚙️ Configuration » pour les déplier.
4. **Langue** : le sélecteur en haut des réglages propose la langue du système plus l'anglais, le chinois, le japonais, le coréen, l'espagnol, le français, l'allemand, le portugais et le russe, et s'applique immédiatement. Le nom de l'application est toujours « Muse Daily Recorder », quelle que soit la langue.
5. Le bouton bleu ⓘ en haut à droite ouvre une page d'aide intégrée (enregistrement, envoi, marqueurs, réglages, FAQ, confidentialité).

## 3. Marqueurs d'événements (surligner l'essentiel pour le rapport quotidien)

- **Interrupteur de conversation** : activez-le quand une conversation importante commence, désactivez-le quand elle se termine.
- **Interrupteur de réunion** : activez-le avant une réunion, désactivez-le quand elle se termine.
- **Bouton 📌 de note personnelle** : maintenez-le enfoncé quand quelque chose vous vient à l'esprit — une idée, une tâche, une pensée. Pendant l'enregistrement, il ne fait que marquer le moment ; au repos, il enregistre une note vocale autonome.
- Les marqueurs sont stockés par jour dans le bucket, sous `markers/events-<date>.jsonl` ; le rapport quotidien découpe conversations et réunions selon les marqueurs et accorde une attention particulière au contenu proche des notes personnelles. L'état des interrupteurs est mémorisé, donc fermer puis rouvrir l'application ne les perturbe jamais.

## 4. Résumé quotidien (côté Muse)

```bash
# Variables d'environnement : S3_ENDPOINT / S3_BUCKET / S3_ACCESS_KEY / S3_SECRET_KEY / S3_REGION / S3_STYLE (path|virtual)
# Optionnel : S3_DELETE_AFTER=1 → supprime les originaux du cloud après récupération (économise l'espace cloud ; les transcriptions et rapports restent côté Muse)
python3 receiver/fetch_day_from_s3.py --date 2026-10-23
python3 receiver/summarize_day.py   --date 2026-10-23
```

Transcription, deux options : définissez `OPENAI_API_KEY` (API Whisper), ou `pip install faster-whisper` sur la machine de Muse (local, gratuit). Dites ensuite à Muse une heure de rapport (par exemple 7 h 30 chaque matin) et il mettra en place une tâche planifiée qui s'exécute seule et publie dans la conversation.

## 5. Rappels réalistes

- **Batterie** : un enregistrement toute la journée consomme environ 3 à 6 % par heure ; enregistrez pendant la charge nocturne si possible. Une option « envoi en Wi-Fi uniquement » existe.
- **Stockage** : environ 500 Mo par jour. Les copies locales sont supprimées par défaut après un envoi réussi ; côté cloud, utilisez `S3_DELETE_AFTER=1` ou une règle de cycle de vie du bucket qui ne conserve que les derniers jours.
- **Stabilité** : Android restreint strictement l'enregistrement en arrière-plan ; la notification persistante dans la barre d'état est une exigence du système (et aussi votre preuve que l'enregistrement est en cours). Un coup d'œil le matin et le soir à « enregistré depuis X minutes » dans l'application suffit.
- **Conformité** : enregistrer des conversations avec d'autres personnes exige leur consentement dans beaucoup d'endroits — utilisez-le uniquement là où c'est légal et où l'autre partie est au courant.

## 6. Arborescence

- `app-project/` : le code source complet de l'application (Java + API du framework Android ; la signature S3 est une implémentation Java pure, vérifiée avec les vecteurs de test officiels SigV4 d'AWS).
- `receiver/fetch_day_from_s3.py` : récupère l'audio et les marqueurs depuis le bucket S3 (bibliothèque standard Python uniquement ; son implémentation de signature a passé les mêmes vecteurs officiels).
- `receiver/summarize_day.py` : transcription + brouillon de rapport ; `receiver/server.py` et `fetch_day_from_supabase.py` sont des archives de l'ancienne approche.
- `build-apk.sh` : compile l'APK directement avec les build-tools du SDK Android.
- `.github/workflows/build.yml` : le CI compile à chaque push vers master et publie deux releases contenant le même APK — une release continue `latest` (toujours la dernière compilation) et une release versionnée par version (tag `vX.Y.Z`, conservée pour toujours). La dernière compilation se télécharge à tout moment ici : https://github.com/skyvense/muse-recorder/releases/tag/latest
- `muse-recorder.keystore` + `keystore-info.txt` : la clé de signature — **conservez-la précieusement**. Les mises à jour doivent être signées avec la même clé, sinon elles ne pourront pas s'installer par-dessus l'ancienne version. Des copies chiffrées se trouvent dans `vault/` (AES-256-CBC + PBKDF2).
