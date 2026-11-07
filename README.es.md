# Muse Daily Recorder

[English](README.md) · [简体中文](README.zh-CN.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · Español · [Français](README.fr.md) · [Deutsch](README.de.md) · [Português](README.pt.md) · [Русский](README.ru.md)

**Versión actual: v1.14.0**

Una aplicación complementaria para Android pensada para grabar todo el día. El teléfono siempre va contigo, así que sustituye a la grabadora de voz: las grabaciones se segmentan solas, se respaldan en tu propio almacenamiento en la nube y —con marcadores de eventos, transcripciones y un resumen diario— se convierten en algo que puedes buscar y revisar. Sin cables, sin archivos perdidos.

Muse no puede escuchar un micrófono de forma continua, así que el sistema tiene tres partes:

1. **La app de Android** (`MuseRecorder.apk`): un grabador con servicio en primer plano que corta un segmento nuevo cada 60 minutos y sube cada segmento terminado a tu propio almacenamiento compatible con S3. La transcripción y los resúmenes se hacen del lado de Muse.
2. **Marcadores de eventos**: marca los momentos importantes mientras grabas; los marcadores se suben al bucket junto con el audio.
3. **Receptor + resumen diario**: los scripts de `receiver/` descargan el audio y los marcadores de un día desde el bucket, los transcriben y guardan en Supabase las transcripciones por segmento, los análisis y un resumen diario.

## Funciones

- **Grabación segmentada + subida automática**: un segmento .m4a nuevo cada 60 minutos (unos 21 MB cada uno), subido automáticamente a un almacenamiento de objetos compatible con S3 (Cloudflare R2, Backblaze B2, Alibaba Cloud OSS, Tencent Cloud COS, AWS S3, Qiniu Kodo o cualquier endpoint personalizado; hay ajustes predefinidos). Las subidas fallidas quedan en cola y se reintentan solas; una lista de pendientes muestra lo que aún falta.
- **Marcadores de eventos**: un interruptor de Conversación y otro de Reunión delimitan lo importante; el informe diario organiza el contenido por ellos.
- **Notas propias con pulsación larga**: mantén pulsado el botón cuando se te ocurra algo; si estás grabando, marca el momento, y si no, graba una nota de voz independiente.
- **Foto automática (opcional, desactivada por defecto)**: levanta el teléfono desde plano hasta vertical y hace una foto en silencio (cámara trasera, frontal o ambas). Mientras la grabación está activa, también funciona con la pantalla apagada. La elección de cámara se aplica al instante.
- **Pestaña Nube**: explora tu bucket por fecha: tarjeta de uso de almacenamiento (frente a los 10 GB gratuitos de R2), conteo de fotos y audios por día, cuadrícula de miniaturas de fotos y audio que se reproduce con un toque.
- **Pestaña Informes**: los resúmenes diarios de Supabase y, para cada día, una «hoja de actividad diaria»: un collage de ~1 MB con las fotos más nítidas del día, agrupadas por periodos de actividad y con una breve descripción por periodo.
- **Modo oscuro**: seguir el sistema, siempre claro o siempre oscuro (Ajustes → Idioma y sistema).
- **Interfaz en 9 idiomas**: inglés, chino simplificado, japonés, coreano, español, francés, alemán, portugués y ruso; el cambio se aplica al instante. El nombre de la app es siempre «Muse Daily Recorder».

## Diseño

Diseño v2 «Cloud White»: tarjetas limpias, un único acento azul, barra de pestañas inferior:

![Diseño v2 claro](docs/images/design-v2-light.png)

![Diseño v2 oscuro](docs/images/design-v2-dark.png)

Variantes clara y oscura del mismo diseño. Nota: la pestaña de grabación se entrega con el diseño compacto clásico de la imagen (botón de grabación y forma de onda en la misma fila, tarjetas fijas al pie); un rediseño anterior con una tarjeta alta ocupaba demasiado espacio vertical y se revirtió en la v1.14.0.

## Primeros pasos

### 1. Prepara un bucket S3 (ejemplo con Cloudflare R2)

1. Regístrate en cloudflare.com y activa R2 (plan gratuito: 10 GB, descargas gratis; la activación puede pedir una tarjeta, pero no se cobra nada dentro de la cuota gratuita). Crea un bucket, por ejemplo `recordings`.
2. En «Manage R2 API Tokens», **crea un token con acceso de lectura y escritura solo a ese bucket** (Object Read & Write, limitado al bucket). Anota el Access Key ID, la Secret Access Key y tu Account ID (el endpoint es `https://<AccountID>.r2.cloudflarestorage.com`).
3. En la app, elige el ajuste **Cloudflare R2**, sustituye `<AccountID>` en el endpoint por tu ID real y rellena el nombre del bucket y las dos claves; luego guarda.

Los demás proveedores funcionan igual (todos tienen ajustes predefinidos). Alibaba/Tencent/AWS/Qiniu necesitan marcar «virtual-host style»; R2/B2 no. Los buckets de Tencent llevan el sufijo `-APPID` y usan SecretId/SecretKey.

### 2. Instalación y uso diario

1. Descarga el APK desde [Releases](https://github.com/skyvense/muse-recorder/releases) (la release continua `latest` siempre tiene la compilación más reciente; las releases con versión se conservan para siempre), envíalo al teléfono e instálalo (es una app personal de instalación manual, así que permite «orígenes desconocidos» una vez). Instalar sobre una versión anterior conserva tus ajustes.
2. Abre la app → ⚙️ Ajustes: rellena S3 y guarda (guardar dispara una subida de inmediato) → permite la ejecución en segundo plano (desactiva la optimización de batería; en teléfonos Samsung, pon además la batería de la app en «Sin restricciones») → vuelve a la pestaña de grabación y toca el botón redondo rojo para empezar (se convierte en un cuadrado mientras grabas, con forma de onda en vivo y el progreso del segmento actual).
3. **Las subidas son totalmente automáticas**: la lista de pendientes muestra el estado de cada segmento, durante la subida se ve el progreso en vivo y el resultado queda en pantalla (con el mensaje de error del servidor si falla). El botón manual de «reintentar ahora» solo aparece tras cambiar un ajuste o tras más de 30 minutos de reintentos automáticos fallidos.
4. El botón ⓘ de arriba a la derecha abre una página de ayuda integrada (grabación, subida, marcadores, ajustes, preguntas frecuentes, privacidad).

Los marcadores se guardan por día en el bucket, en `markers/events-<date>.jsonl`. El estado de los interruptores se recuerda, así que cerrar y reabrir la app nunca los desordena.

## Pipeline de resumen diario (lado de Muse)

```bash
# Variables de entorno: S3_ENDPOINT / S3_BUCKET / S3_ACCESS_KEY / S3_SECRET_KEY / S3_REGION / S3_STYLE (path|virtual)
# Opcional: S3_DELETE_AFTER=1 → borra los originales de la nube tras descargarlos (las transcripciones y los informes quedan)
python3 receiver/fetch_day_from_s3.py --date 2026-10-23
python3 receiver/summarize_day.py   --date 2026-10-23
```

Transcripción, dos opciones: define `OPENAI_API_KEY` (API de Whisper) o `pip install faster-whisper` y transcribe localmente y gratis. Dile a Muse una hora de informe (p. ej., cada mañana a las 7:30) y una tarea programada podrá ejecutar el pipeline automáticamente y publicar el informe en el chat.

## Privacidad y seguridad

- El audio va **solo a tu propio bucket**: no hay ningún servidor de terceros en el camino.
- La transcripción puede ejecutarse **completamente en local** (faster-whisper); nada tiene que salir de tu hardware salvo el almacenamiento que tú elijas.
- **No hay credenciales en este repositorio.** La clave secreta de S3 existe únicamente en los ajustes de la app en el teléfono, y las claves de Supabase de los scripts receptores viven en archivos env locales fuera del repo. Limita los tokens de almacenamiento a un único bucket para que una filtración quede contenida.
- Grabar a otras personas requiere su consentimiento en muchos lugares: graba solo donde sea legal y donde la otra parte lo sepa.

## La realidad, sin adornos

- **Batería**: grabar todo el día consume aproximadamente un 3–6 % por hora; si puedes, graba cargando durante la noche. Está disponible «subir solo por Wi-Fi».
- **Almacenamiento**: unos 500 MB al día. Las copias locales se borran tras una subida correcta por defecto; en la nube, usa `S3_DELETE_AFTER=1` o una regla de ciclo de vida del bucket que conserve solo los últimos días.
- **Estabilidad**: Android restringe mucho la grabación en segundo plano; la notificación persistente en la barra de estado es un requisito del sistema (y también tu prueba de que está grabando).

## Compilación y publicación

- `build-apk.sh` compila el APK directamente con las build-tools del SDK de Android (la compilación de publicación no necesita Gradle).
- Cada push a `master` dispara GitHub Actions, que compila un APK **firmado** y publica dos releases con el mismo archivo: una continua, `latest`, y una con versión, `vX.Y.Z`. Instala desde [Releases](https://github.com/skyvense/muse-recorder/releases).
- `muse-recorder.keystore` + `keystore-info.txt` son la clave de firma: **guárdalos bien**. Las actualizaciones deben firmarse con la misma clave o no podrán instalarse sobre la versión anterior. Hay copias cifradas en `vault/` (AES-256-CBC + PBKDF2).

## Estructura del repositorio

- `app-project/`: el código completo de la app (Java + API del framework Android; la firma S3 es una implementación SigV4 en Java puro, verificada con los vectores de prueba oficiales de AWS).
- `receiver/`: el pipeline del lado de Muse — `fetch_day_from_s3.py` (descarga audio + marcadores + fotos del bucket; solo biblioteca estándar de Python, firma verificada con los mismos vectores oficiales), `summarize_day.py` (transcripción + borrador del informe), scripts de escritura en Supabase y generadores de hojas de fotos. `receiver/server.py` y `fetch_day_from_supabase.py` son archivos del antiguo enfoque de v1.0/v1.1.
- `docs/images/`: las imágenes de diseño usadas arriba.
- `README.<idioma>.md`: este mismo archivo en los otros 8 idiomas.

Desde v1.2 la app es **solo S3** (el receptor autoalojado de v1.0 y la subida directa a Supabase de v1.1 se eliminaron de la app; los scripts quedan en `receiver/` como referencia).
