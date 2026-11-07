# Muse Daily Recorder

[English](README.md) · [简体中文](README.zh-CN.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [Español](README.es.md) · [Français](README.fr.md) · [Deutsch](README.de.md) · Português · [Русский](README.ru.md)

**Versão atual: v1.14.0**

Um aplicativo Android complementar feito para gravar o dia inteiro. O celular está sempre com você, então ele substitui a caneta gravadora: as gravações são segmentadas automaticamente, salvas no seu próprio armazenamento em nuvem e — com marcadores de eventos, transcrições e um resumo diário — viram algo que dá para pesquisar e revisar de verdade. Sem cabos, sem arquivos perdidos.

O Muse não consegue ouvir um microfone continuamente, por isso o sistema tem três partes:

1. **O app Android** (`MuseRecorder.apk`): um gravador com serviço em primeiro plano que corta um novo segmento a cada 60 minutos e envia cada segmento concluído para o seu próprio armazenamento compatível com S3. A transcrição e os resumos ficam do lado do Muse.
2. **Marcadores de eventos**: marque os momentos importantes enquanto grava; os marcadores sobem para o bucket junto com o áudio.
3. **Receptor + resumo diário**: os scripts em `receiver/` baixam o áudio e os marcadores de um dia do bucket, transcrevem e guardam no Supabase as transcrições por segmento, as análises e um resumo diário.

## Recursos

- **Gravação segmentada + envio automático** — um novo segmento .m4a a cada 60 minutos (cerca de 21 MB cada), enviado automaticamente para um armazenamento de objetos compatível com S3 (Cloudflare R2, Backblaze B2, Alibaba Cloud OSS, Tencent Cloud COS, AWS S3, Qiniu Kodo ou qualquer endpoint personalizado; há predefinições). Envios que falham entram na fila e tentam de novo sozinhos; uma lista de envios pendentes mostra o que ainda falta.
- **Marcadores de eventos** — um interruptor de Conversa e outro de Reunião delimitam o que importa; o relatório diário organiza o conteúdo por eles.
- **Notas próprias com toque longo** — segure o botão sempre que algo lhe ocorrer; gravando, ele marca o momento; parado, grava um recado de voz separado.
- **Foto automática (opcional, desligada por padrão)** — levante o celular da posição plana para a vertical e ele tira uma foto discretamente (câmera traseira, frontal ou as duas). Enquanto a gravação está ativa, as fotos também funcionam com a tela apagada. A escolha da câmera vale na hora.
- **Aba Nuvem** — navegue pelo bucket por data: cartão de uso de armazenamento (em relação aos 10 GB gratuitos do R2), contagem de fotos e áudios por dia, grade de miniaturas de fotos e áudio que toca com um toque.
- **Aba Relatórios** — os resumos diários do Supabase e, para cada dia, uma "folha de atividades do dia": uma colagem de ~1 MB com as fotos mais nítidas do dia, agrupadas por período de atividade, com uma legenda curta por período.
- **Modo escuro** — seguir o sistema, sempre claro ou sempre escuro (Configurações → Idioma e sistema).
- **Interface em 9 idiomas** — inglês, chinês simplificado, japonês, coreano, espanhol, francês, alemão, português e russo; a troca é imediata. O nome do app é sempre "Muse Daily Recorder".

## Design

Design v2 "Cloud White" — cartões limpos, um único destaque azul, barra de abas inferior:

![Design v2 claro](docs/images/design-v2-light.png)

![Design v2 escuro](docs/images/design-v2-dark.png)

Variantes clara e escura do mesmo design. Observação: a aba de gravação é entregue no layout compacto clássico mostrado aqui (botão de gravação e forma de onda na mesma linha, cartões fixos no rodapé) — um redesign anterior com um cartão alto ocupava espaço vertical demais e foi revertido na v1.14.0.

## Primeiros passos

### 1. Prepare um bucket S3 (exemplo com Cloudflare R2)

1. Cadastre-se em cloudflare.com e ative o R2 (plano gratuito: 10 GB, downloads grátis; a ativação pode pedir um cartão, mas nada é cobrado dentro da cota gratuita). Crie um bucket, por exemplo `recordings`.
2. Em "Manage R2 API Tokens", **crie um token com leitura e escrita somente nesse bucket** (Object Read & Write, restrito ao bucket). Anote o Access Key ID, a Secret Access Key e o seu Account ID (o endpoint é `https://<AccountID>.r2.cloudflarestorage.com`).
3. No app, escolha a predefinição **Cloudflare R2**, troque `<AccountID>` no endpoint pelo seu ID real, preencha o nome do bucket e as duas chaves e salve.

Os outros provedores funcionam do mesmo jeito (todos têm predefinições no app). Alibaba/Tencent/AWS/Qiniu precisam da opção "virtual-host style" marcada; R2/B2, não. Os buckets da Tencent têm o sufixo `-APPID` e usam SecretId/SecretKey.

### 2. Instalação e uso diário

1. Baixe o APK das [Releases](https://github.com/skyvense/muse-recorder/releases) (a release contínua `latest` sempre tem a compilação mais nova; as releases versionadas ficam para sempre), envie para o celular e instale (é um app pessoal de instalação manual, então permita "fontes desconhecidas" uma vez). Instalar por cima de uma versão anterior mantém as configurações.
2. Abra o app → ⚙️ Configurações: preencha o S3 e salve (salvar dispara um envio na hora) → permita a execução em segundo plano (desative a otimização de bateria; em celulares Samsung, coloque também a bateria do app em "Sem restrições") → volte à aba de gravação e toque no botão redondo vermelho para começar (ele vira um quadrado durante a gravação, com forma de onda ao vivo e o progresso do segmento atual).
3. **Os envios são totalmente automáticos**: a lista de pendentes mostra o estado de cada segmento, o progresso aparece durante o envio e o resultado fica na tela depois — com a mensagem de erro do servidor, se falhar. O botão manual "tentar de novo agora" só aparece depois de mudar uma configuração ou após mais de 30 minutos de tentativas automáticas falhando.
4. O botão ⓘ no canto superior direito abre uma ajuda integrada (gravação, envio, marcadores, configurações, perguntas frequentes, privacidade).

Os marcadores são guardados por dia no bucket, em `markers/events-<date>.jsonl`. O estado dos interruptores é lembrado, então fechar e reabrir o app nunca embaralha nada.

## Pipeline de resumo diário (lado do Muse)

```bash
# Variáveis de ambiente: S3_ENDPOINT / S3_BUCKET / S3_ACCESS_KEY / S3_SECRET_KEY / S3_REGION / S3_STYLE (path|virtual)
# Opcional: S3_DELETE_AFTER=1 → apaga os originais da nuvem após baixar (transcrições e relatórios permanecem)
python3 receiver/fetch_day_from_s3.py --date 2026-10-23
python3 receiver/summarize_day.py   --date 2026-10-23
```

Transcrição, duas opções: defina `OPENAI_API_KEY` (API do Whisper) ou `pip install faster-whisper` e transcreva localmente, de graça. Diga ao Muse um horário de relatório (por exemplo, toda manhã às 7:30) e um trabalho agendado pode rodar o pipeline automaticamente e postar o relatório no chat.

## Privacidade e segurança

- O áudio vai **apenas para o seu próprio bucket** — não há servidor de terceiros no caminho.
- A transcrição pode rodar **inteiramente no local** (faster-whisper); nada precisa sair do seu hardware além do armazenamento que você escolheu.
- **Não há credenciais neste repositório.** A chave secreta do S3 existe só nas configurações do app no celular; as chaves do Supabase dos scripts receptores ficam em arquivos env locais, fora do repositório. Restrinja os tokens de armazenamento a um único bucket para que um vazamento fique contido.
- Gravar outras pessoas exige o consentimento delas em muitos lugares — grave apenas onde for legal e onde a outra parte souber.

## A realidade, sem rodeios

- **Bateria**: gravar o dia todo consome cerca de 3–6% por hora; se puder, grave carregando durante a noite. Existe a opção "enviar só por Wi-Fi".
- **Armazenamento**: cerca de 500 MB por dia. As cópias locais são apagadas após um envio bem-sucedido, por padrão; na nuvem, use `S3_DELETE_AFTER=1` ou uma regra de ciclo de vida do bucket que guarde só os últimos dias.
- **Estabilidade**: o Android restringe bastante a gravação em segundo plano; a notificação persistente na barra de status é uma exigência do sistema (e também a sua prova de que está gravando).

## Compilação e lançamento

- `build-apk.sh` compila o APK diretamente com as build-tools do SDK Android (a compilação de lançamento não precisa de Gradle).
- Cada push para `master` dispara o GitHub Actions, que compila um APK **assinado** e publica duas releases com o mesmo arquivo: uma contínua, `latest`, e uma versionada, `vX.Y.Z`. Instale pelas [Releases](https://github.com/skyvense/muse-recorder/releases).
- `muse-recorder.keystore` + `keystore-info.txt` são a chave de assinatura — **guarde-os bem**. As atualizações precisam ser assinadas com a mesma chave, senão não instalam por cima da versão antiga. Há cópias criptografadas em `vault/` (AES-256-CBC + PBKDF2).

## Estrutura do repositório

- `app-project/`: o código completo do app (Java + APIs do framework Android; a assinatura S3 é uma implementação SigV4 em Java puro, verificada com os vetores de teste oficiais da AWS).
- `receiver/`: o pipeline do lado do Muse — `fetch_day_from_s3.py` (baixa áudio + marcadores + fotos do bucket; só biblioteca padrão do Python, assinatura verificada com os mesmos vetores oficiais), `summarize_day.py` (transcrição + rascunho do relatório), scripts de escrita no Supabase e geradores das folhas de fotos. `receiver/server.py` e `fetch_day_from_supabase.py` são arquivos da abordagem antiga da v1.0/v1.1.
- `docs/images/`: as imagens de design usadas acima.
- `README.<idioma>.md`: este arquivo nos outros 8 idiomas.

Desde a v1.2, o app é **somente S3** (o receptor auto-hospedado da v1.0 e o envio direto ao Supabase da v1.1 foram removidos do app; os scripts continuam em `receiver/` como referência).
