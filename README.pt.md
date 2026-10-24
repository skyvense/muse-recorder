# Muse Daily Recorder v1.8.2 (envio para S3 + marcadores de eventos + resumo diário)

[English](README.md) | [中文](README.zh-CN.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Español](README.es.md) | [Français](README.fr.md) | [Deutsch](README.de.md) | Português | [Русский](README.ru.md)

O Muse, por si só, não consegue ouvir o microfone continuamente, por isso esta solução tem três partes:

1. **App Android** (`MuseRecorder.apk`): um gravador em serviço de primeiro plano que **corta um novo segmento a cada 60 minutos** (.m4a, ~21 MB por segmento) e envia cada segmento concluído automaticamente para o seu próprio **armazenamento de objetos compatível com S3**. Os envios com falha ficam na fila e são repetidos; o app mostra uma **lista de envios pendentes**.
2. **Marcadores de eventos**: marque momentos importantes enquanto grava (veja abaixo); os marcadores são enviados ao bucket junto com o áudio.
3. **Resumo diário** (do lado do Muse): o `receiver/fetch_day_from_s3.py` baixa o áudio e os marcadores do dia anterior do bucket → transcreve → o Muse transforma tudo em um relatório diário.

Desde a v1.2, o app é **somente S3** (o receptor auto-hospedado da v1.0 e o envio direto para o Supabase da v1.1 foram removidos; os scripts antigos do receptor continuam em `receiver/` como referência).

## 1. Preparar um bucket S3 (exemplo com Cloudflare R2)

1. Cadastre-se em cloudflare.com e ative o R2 (plano gratuito: 10 GB, downloads gratuitos; a ativação pode pedir um cartão, mas nada é cobrado dentro da cota gratuita). Crie um bucket, por exemplo `recordings`.
2. Em "Gerenciar tokens de API do R2", **crie um token com acesso de leitura e escrita apenas para esse bucket** (Object Read & Write, restrito ao bucket). Anote o Access Key ID, a Secret Access Key e o seu Account ID (o endpoint é `https://<AccountID>.r2.cloudflarestorage.com`).
3. No app, escolha a predefinição **Cloudflare R2**, substitua o `<AccountID>` no endpoint pelo seu ID real, preencha o nome do bucket e as duas chaves, e salve.

Outros provedores funcionam da mesma forma (todos têm predefinições no app): Backblaze B2, Alibaba Cloud OSS (use o modo compatível com S3 + AccessKey da conta; crie o bucket no console), Tencent Cloud COS (os nomes de bucket trazem um sufixo `-APPID`; use SecretId/SecretKey), AWS S3, Qiniu Kodo, ou qualquer serviço compatível com S3 via "Personalizado". Alibaba/Tencent/AWS/Qiniu precisam da opção "estilo de host virtual" marcada; R2/B2 não.

> Nota de segurança: a Secret Key vive apenas dentro do app do telefone — nunca a cole em um chat nem em nenhum lugar público. Limitar o token a um único bucket reduz o dano caso ele vaze algum dia.

## 2. Instalar e usar no dia a dia

1. Envie o `MuseRecorder.apk` para o telefone e instale-o (é um app pessoal não publicado, então permita "fontes desconhecidas" uma vez). Instalar sobre uma versão anterior mantém as suas configurações.
2. Abra o app → toque em "⚙️ Configuração" e preencha os dados do S3, depois salve (ao salvar, um envio é disparado na hora) → toque em "Permitir execução em segundo plano (desativar otimização de bateria)" → toque no botão vermelho redondo para começar a gravar (ele vira um quadrado durante a gravação, com uma forma de onda ao vivo e os minutos restantes do segmento atual abaixo), e conceda as permissões de microfone e notificações. Em telefones Samsung, coloque também a bateria do app em "Sem restrições".
3. **Os envios são totalmente automáticos**: a lista de pendentes mostra o estado de cada segmento, o progresso ao vivo aparece durante o envio, e o resultado fica na tela depois — com a mensagem de erro do servidor em caso de falha. Os segmentos que falham (por exemplo, sem conexão) ficam na fila e são repetidos automaticamente. Um botão manual "tentar envio agora" só aparece após uma mudança de configuração ou após mais de 30 minutos de tentativas automáticas com falha. As configurações de S3 e de gravação começam recolhidas; toque em "⚙️ Configuração" para expandi-las.
4. **Idioma**: o seletor no topo das configurações oferece o idioma do sistema mais inglês, chinês, japonês, coreano, espanhol, francês, alemão, português e russo, e aplica na hora. O nome do app é sempre "Muse Daily Recorder" em todos os idiomas.
5. O botão azul ⓘ no canto superior direito abre uma página de ajuda integrada (gravação, envio, marcadores, configurações, perguntas frequentes, privacidade).

## 3. Marcadores de eventos (destacar o essencial para o relatório diário)

- **Interruptor de conversa**: ligue-o quando uma conversa importante começar, desligue-o quando ela terminar.
- **Interruptor de reunião**: ligue-o antes de uma reunião, desligue-o quando ela terminar.
- **Botão 📌 de nota pessoal**: mantenha-o pressionado quando algo lhe ocorrer — uma ideia, uma tarefa, um pensamento. Durante a gravação, ele só marca o momento; em repouso, grava uma nota de voz independente.
- Os marcadores são salvos por dia no bucket, em `markers/events-<data>.jsonl`; o relatório diário divide conversas e reuniões pelos marcadores e presta atenção especial ao conteúdo perto das notas pessoais. O estado dos interruptores é lembrado, então fechar e reabrir o app nunca os bagunça.

## 4. Resumo diário (do lado do Muse)

```bash
# Variáveis de ambiente: S3_ENDPOINT / S3_BUCKET / S3_ACCESS_KEY / S3_SECRET_KEY / S3_REGION / S3_STYLE (path|virtual)
# Opcional: S3_DELETE_AFTER=1 → apaga os originais da nuvem após baixá-los (economiza espaço na nuvem; as transcrições e os relatórios ficam do lado do Muse)
python3 receiver/fetch_day_from_s3.py --date 2026-10-23
python3 receiver/summarize_day.py   --date 2026-10-23
```

Transcrição, duas opções: defina `OPENAI_API_KEY` (API do Whisper) ou instale `pip install faster-whisper` na máquina do Muse (local, gratuito). Depois diga ao Muse um horário para o relatório (por exemplo, 7h30 toda manhã) e ele criará uma tarefa agendada que roda sozinha e publica no chat.

## 5. Avisos realistas

- **Bateria**: gravar o dia todo consome cerca de 3–6% por hora; se puder, grave enquanto carrega à noite. Existe a opção "enviar apenas por Wi-Fi".
- **Armazenamento**: cerca de 500 MB por dia. As cópias locais são apagadas por padrão após um envio bem-sucedido; para a nuvem, use `S3_DELETE_AFTER=1` ou uma regra de ciclo de vida do bucket que mantenha apenas os últimos dias.
- **Estabilidade**: o Android restringe bastante a gravação em segundo plano; a notificação persistente na barra de status é uma exigência do sistema (e também a sua prova de que está gravando). Basta dar uma olhada de manhã e à noite em "gravando há X minutos" no app.
- **Conformidade**: gravar conversas com outras pessoas exige o consentimento delas em muitos lugares — use apenas onde for legal e onde a outra parte saiba.

## 6. Estrutura

- `app-project/`: o código-fonte completo do app (Java + APIs do framework Android; a assinatura S3 é uma implementação em Java puro, verificada com os vetores de teste oficiais SigV4 da AWS).
- `receiver/fetch_day_from_s3.py`: baixa áudio e marcadores do bucket S3 (somente biblioteca padrão do Python; a sua implementação de assinatura passou nos mesmos vetores oficiais).
- `receiver/summarize_day.py`: transcrição + rascunho do relatório; `receiver/server.py` e `fetch_day_from_supabase.py` são arquivos da abordagem anterior.
- `build-apk.sh`: compila o APK diretamente com as build-tools do SDK do Android.
- `.github/workflows/build.yml`: o CI compila a cada push para master e publica dois releases com o mesmo APK — um release contínuo `latest` (sempre a compilação mais recente) e um release versionado por versão (tag `vX.Y.Z`, mantido para sempre). A compilação mais recente pode ser baixada a qualquer momento em https://github.com/skyvense/muse-recorder/releases/tag/latest
- `muse-recorder.keystore` + `keystore-info.txt`: a chave de assinatura — **guarde-a bem**. As atualizações devem ser assinadas com a mesma chave, ou não poderão ser instaladas sobre a versão anterior. Há cópias criptografadas em `vault/` (AES-256-CBC + PBKDF2).
