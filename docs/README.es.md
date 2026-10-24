# Muse Daily Recorder v1.8.2 (subida a S3 + marcadores de eventos + resumen diario)

[English](../README.md) | [中文](../README.zh-CN.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | Español | [Français](README.fr.md) | [Deutsch](README.de.md) | [Português](README.pt.md) | [Русский](README.ru.md)

**Sustituye a una grabadora de voz:** el teléfono siempre va contigo, las grabaciones se segmentan y se copian automáticamente en tu propia nube, y los marcadores de eventos, las transcripciones y el resumen diario hacen que el audio se pueda buscar — sin cables ni archivos perdidos.

Muse por sí mismo no puede escuchar el micrófono de forma continua, así que esta solución tiene tres partes:

1. **App Android** (`MuseRecorder.apk`): un grabador en servicio en primer plano que **corta un segmento nuevo cada 60 minutos** (.m4a, ~21 MB por segmento) y sube cada segmento terminado automáticamente a tu propio **almacenamiento de objetos compatible con S3**. Las subidas fallidas quedan en cola y se reintentan; la app muestra una **lista de subidas pendientes**.
2. **Marcadores de eventos**: marca momentos importantes mientras grabas (ver abajo); los marcadores se suben al bucket junto con el audio.
3. **Resumen diario** (del lado de Muse): `receiver/fetch_day_from_s3.py` descarga el audio y los marcadores del día anterior desde el bucket → los transcribe → Muse los convierte en un informe diario.

Desde v1.2 la app es **solo S3** (el receptor autoalojado de v1.0 y la subida directa a Supabase de v1.1 se eliminaron; los scripts antiguos del receptor se conservan en `receiver/` como referencia).

## 1. Preparar un bucket S3 (ejemplo con Cloudflare R2)

1. Regístrate en cloudflare.com y activa R2 (plan gratuito: 10 GB, descargas gratuitas; la activación puede pedir una tarjeta, pero no se cobra nada dentro de la cuota gratuita). Crea un bucket, por ejemplo `recordings`.
2. En "Administrar tokens de API de R2", **crea un token con acceso de lectura y escritura solo para ese bucket** (Object Read & Write, limitado al bucket). Anota el Access Key ID, la Secret Access Key y tu Account ID (el endpoint es `https://<AccountID>.r2.cloudflarestorage.com`).
3. En la app, elige el ajuste predefinido **Cloudflare R2**, sustituye `<AccountID>` en el endpoint por tu ID real, completa el nombre del bucket y las dos claves, y guarda.

Otros proveedores funcionan igual (todos tienen ajustes predefinidos en la app): Backblaze B2, Alibaba Cloud OSS (usa el modo compatible con S3 + AccessKey de la cuenta; crea el bucket en la consola), Tencent Cloud COS (los nombres de bucket llevan un sufijo `-APPID`; usa SecretId/SecretKey), AWS S3, Qiniu Kodo, o cualquier servicio compatible con S3 mediante "Personalizado". Alibaba/Tencent/AWS/Qiniu necesitan "estilo de host virtual" activado; R2/B2 no.

> Nota de seguridad: la Secret Key vive solo dentro de la app del teléfono; nunca la pegues en un chat ni en ningún lugar público. Limitar el token a un solo bucket reduce el daño si alguna vez se filtra.

## 2. Instalar y usar a diario

1. Envía `MuseRecorder.apk` al teléfono e instálalo (es una app personal sin publicar, así que permite "orígenes desconocidos" una vez). Instalar sobre una versión anterior conserva tus ajustes.
2. Abre la app → toca "⚙️ Configuración" y completa los datos de S3, luego guarda (al guardar se lanza una subida de inmediato) → toca "Permitir ejecución en segundo plano (desactivar optimización de batería)" → toca el botón rojo redondo para empezar a grabar (se convierte en un cuadrado mientras graba, con una forma de onda en vivo y los minutos restantes del segmento actual debajo), y concede los permisos de micrófono y notificaciones. En teléfonos Samsung, además pon la batería de la app en "Sin restricciones".
3. **Las subidas son totalmente automáticas**: la lista de pendientes muestra el estado de cada segmento, durante la subida aparece el progreso en vivo, y el resultado queda en pantalla después, con el mensaje de error del servidor si falla. Los segmentos que fallan (por ejemplo, sin conexión) quedan en cola y se reintentan solos. Un botón manual de "reintentar subida ahora" solo aparece tras un cambio de ajustes o después de más de 30 minutos de reintentos automáticos fallidos. Los ajustes de S3 y de grabación están plegados por defecto; toca "⚙️ Configuración" para desplegarlos.
4. **Idioma**: el selector en la parte superior de los ajustes ofrece el idioma del sistema más inglés, chino, japonés, coreano, español, francés, alemán, portugués y ruso, y se aplica de inmediato. El nombre de la app es siempre "Muse Daily Recorder" en todos los idiomas.
5. El botón azul ⓘ en la parte superior derecha abre una página de ayuda integrada (grabación, subida, marcadores, ajustes, preguntas frecuentes, privacidad).

## 3. Marcadores de eventos (destacar lo importante para el informe diario)

- **Interruptor de conversación**: enciéndelo cuando empiece una conversación importante, apágalo cuando termine.
- **Interruptor de reunión**: enciéndelo antes de una reunión, apágalo cuando se levante.
- **Botón 📌 de nota personal**: mantenlo pulsado cuando se te ocurra algo: una idea, una tarea, un pensamiento. Mientras grabas solo marca el momento; en reposo graba una nota de voz independiente.
- Los marcadores se guardan por día en el bucket, en `markers/events-<fecha>.jsonl`; el informe diario divide conversaciones y reuniones según los marcadores y presta especial atención al contenido cercano a las notas personales. El estado de los interruptores se recuerda, así que cerrar y reabrir la app nunca los desordena.

## 4. Resumen diario (del lado de Muse)

```bash
# Variables de entorno: S3_ENDPOINT / S3_BUCKET / S3_ACCESS_KEY / S3_SECRET_KEY / S3_REGION / S3_STYLE (path|virtual)
# Opcional: S3_DELETE_AFTER=1 → borra los originales de la nube tras descargarlos (ahorra espacio en la nube; las transcripciones y los informes quedan del lado de Muse)
python3 receiver/fetch_day_from_s3.py --date 2026-10-23
python3 receiver/summarize_day.py   --date 2026-10-23
```

Transcripción, dos opciones: define `OPENAI_API_KEY` (API de Whisper) o instala `pip install faster-whisper` en la máquina de Muse (local, gratis). Después dile a Muse una hora para el informe (por ejemplo, las 7:30 cada mañana) y programará una tarea que se ejecute sola y lo publique en el chat.

## 5. Advertencias realistas

- **Batería**: grabar todo el día consume aproximadamente un 3–6 % por hora; si puedes, graba mientras cargas por la noche. Existe la opción "subir solo con Wi-Fi".
- **Almacenamiento**: unos 500 MB al día. Las copias locales se borran por defecto tras una subida correcta; para la nube, usa `S3_DELETE_AFTER=1` o una regla de ciclo de vida del bucket que solo conserve los últimos días.
- **Estabilidad**: Android restringe mucho la grabación en segundo plano; la notificación persistente en la barra de estado es un requisito del sistema (y también tu prueba de que está grabando). Basta con echar un vistazo por la mañana y por la noche a "grabando desde hace X minutos" en la app.
- **Legalidad**: grabar conversaciones con otras personas requiere su consentimiento en muchos lugares; úsalo solo donde sea legal y donde la otra parte lo sepa.

## 6. Estructura

- `app-project/`: el código fuente completo de la app (Java + APIs del framework Android; la firma S3 es una implementación en Java puro, verificada con los vectores de prueba oficiales SigV4 de AWS).
- `receiver/fetch_day_from_s3.py`: descarga audio y marcadores desde el bucket S3 (solo biblioteca estándar de Python; su implementación de firma superó los mismos vectores oficiales).
- `receiver/summarize_day.py`: transcripción + borrador del informe; `receiver/server.py` y `fetch_day_from_supabase.py` son archivos de la solución anterior.
- `build-apk.sh`: compila el APK directamente con las build-tools del SDK de Android.
- `.github/workflows/build.yml`: el CI compila con cada push a master y publica dos releases con el mismo APK: una release continua `latest` (siempre la compilación más reciente) y una release versionada por versión (etiqueta `vX.Y.Z`, conservada para siempre). La compilación más reciente se puede descargar en cualquier momento en https://github.com/skyvense/muse-recorder/releases/tag/latest
- `muse-recorder.keystore` + `keystore-info.txt`: la clave de firma; **guárdala bien**. Las actualizaciones deben firmarse con la misma clave o no podrán instalarse sobre la versión anterior. Hay copias cifradas en `vault/` (AES-256-CBC + PBKDF2).
