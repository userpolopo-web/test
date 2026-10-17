# UltraStream — experimento Miracast sem receptor instalado

**Estado:** diagnóstico de descoberta, não é transmissor de vídeo.

## Objetivo
Investigar compatibilidade com televisores Samsung, LG e Philco que anunciem Wi-Fi Display (Miracast), sem abrir Smart View, navegador ou instalar APK na TV.

## O que foi implementado
- Botão "Testar descoberta Miracast" no UltraStream Android.
- Atividade de diagnóstico com Wi-Fi Direct (WifiP2pManager).
- Solicitação de permissão adequada ao Android 13+ (NEARBY_WIFI_DEVICES) e versões anteriores (ACCESS_FINE_LOCATION).
- Lista de pares Wi-Fi Direct e leitura de informações Wi-Fi Display quando disponíveis (API 30+).
- Não tenta estabelecer conexão nem exibe botão "Transmitir", porque ainda não temos um transmissor Miracast viável e testado.
- Branch separada para não afetar o funcionamento do app WebRTC existente.
- GitHub Actions compila os APKs experimentalmente como artefatos de execução. Esta branch **não publica** Releases.

## Limitação fundamental (Android sem root)
WifiP2pManager.setWfdInfo exige `android.permission.CONFIGURE_WIFI_DISPLAY`, uma permissão de sistema. Um APK comum não consegue se anunciar com garantias como fonte Miracast independentemente da pilha do fabricante. A API pública Wi-Fi Direct permite descoberta e conexão P2P, mas **isso não é um transmissor Miracast completo**. Um protótipo de RTSP + RTP + MediaCodec pode não ser reconhecido como WFD Source pela TV. A compatibilidade só pode ser estabelecida com teste de aparelhos específicos; não prometer "qualquer TV" nem "zero delay".

Referências:
- https://developer.android.com/develop/connectivity/wifi/wifip2p
- https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pDevice#getWfdInfo()
- https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager#setWfdInfo(android.net.wifi.p2p.WifiP2pManager.Channel,android.net.wifi.p2p.WifiP2pWfdInfo,android.net.wifi.p2p.WifiP2pManager.ActionListener)

## Teste
1. Instalar APK de artefatos da execução de GitHub Actions da branch `feature/ultrastream-miracast-discovery`.
2. No Samsung, habilitar Wi-Fi + Localização, aceitar permissão de dispositivos próximos (ou localização em Android antigo).
3. Colocar a TV no modo de espelhamento Wi-Fi Display/Miracast.
4. No UltraStream, tocar em "Testar descoberta Miracast" e depois "Buscar TVs".
5. Anotar modelo exato do Galaxy, versão Android, modelo da TV e resultados listados.
6. Confirmar tecnicamente se a pilha WFD do aparelho permite fazer conexão como app de terceiros antes de gastar tempo implementando mídia.

## Próxima etapa, condicionada à viabilidade
- Avaliar viabilidade real do Wi-Fi Display Source neste Galaxy específico.
- Se a pilha do aparelho permitir, implementar negociação RTSP / envio RTP H.264 via MediaProjection/MediaCodec com medição de latência, FPS e bitrate. Isso **não** está feito.
- Se exigir privilégios do sistema, documentar a impossibilidade para APK convencional e propor alternativas sem prometer capacidades inexistentes.
