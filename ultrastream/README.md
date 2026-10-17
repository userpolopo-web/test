# UltraStream — Android Screen Sharing

App Android (Kotlin + WebRTC) que transmite **sua própria tela com autorização explícita** para um visualizador em navegador, usando captura MediaProjection, codificação WebRTC, STUN e um servidor de sinalização WebSocket.

## Funcionalidade atual (MVP)
- Transmissor Android + visualizador web, **1 transmissor e 1 espectador por sala**.
- Captura até 1920 px no maior lado, 30 fps (conforme capacidade do dispositivo).
- WebRTC P2P, negociação SDP e troca ICE pelo servidor Node.
- Sem áudio no MVP. Latência não é zero: varia com rede, dispositivo, codecs e sinal.
- Não há relay TURN configurado: conexões em redes restritivas podem falhar.
- Servidor de sinalização de demonstração sem autenticação: **não use na internet pública sem adicionar autenticação, TLS/WSS e controle de acesso**.

## Executar
1. Em `signaling/`, rode `npm install` e `npm start`.
2. Hospede `viewer/index.html` em servidor web; localmente pode ser `python3 -m http.server 8000` dentro da pasta viewer.
3. Compile o app no Android Studio (JDK 17, Android SDK 35) ou use o GitHub Actions.
4. Instale o APK no Android. Informe o WebSocket: `ws://IP_DA_MAQUINA:8080` (o padrão `10.0.2.2` serve **somente** para emulador Android).
5. No navegador, abra viewer, preencha o mesmo WebSocket e a mesma sala, depois conecte.
6. No Android, toque Iniciar, autorize a captura pelo diálogo do sistema e compartilhe a tela. Pare pelo app.

## Gerar APK no GitHub
O workflow `android-apk.yml` está dentro de `ultrastream/.github` por enquanto; **para execução automática pelo GitHub Actions ele deve ser colocado na raiz do repositório em `.github/workflows/android-apk.yml`**. Esse projeto foi colocado em subpasta para não mexer no conteúdo do repositório de testes. Uma vez movido, execute Actions > Build UltraStream APK > Run workflow e baixe `UltraStream-debug-apk`.

## Produção e desempenho
Configure WSS/HTTPS, TURN (coturn, credenciais temporárias), autenticação, ajuste dinâmico de bitrate via `RtpSender.setParameters`, recuperação da conexão WebSocket, otimização de perfil (720p60 ou 1080p30/60 conforme aparelho), métricas RTCStats e política de privacidade. O projeto é MVP e precisa de compilação/testes em dispositivo físico antes de ser considerado estável.
