# Listening Time

App Android que mostra uma **notificação com cronômetro de tempo de tela** sempre que você
reproduz vídeos na **Netflix, Prime Video, YouTube, HBO Max, Crunchyroll, Apple TV ou Disney+**.

Também existe uma **extensão de navegador** (Chrome, Edge e Firefox) com as mesmas funções — veja abaixo.

O painel (visual HUD, igual no app e na extensão) mostra:
1. **Hoje** — mostrador circular dividido por app, "hoje vs. média", sequência de dias, cronômetro ao vivo e botão **Pausar/Retomar contagem**;
2. **Acumulado** — total geral (todos os dias) e, por mês: total, média por dia ativo e recorde;
3. **Calendário** — mapa de calor com as horas de cada dia do mês (toque num dia para ver o detalhe);
4. **Linha do tempo** — barras empilhadas por app, uma por dia;
5. **Ranking do mês** — apps ordenados, com % do mês, hoje e total;
6. **Registro diário** — cada dia com total e divisão por app;
7. **Sistema** — opções.

A contagem pode ser **pausada** pelo botão do app ou pelo botão da notificação.

## Como baixar
**Releases** → baixe o `ListeningTime-vX.Y.Z.apk` mais recente → instale.

Para atualizar, baixe o APK novo e instale **por cima**: não precisa desinstalar e os dados ficam.
Funciona porque:

| Requisito do Android | Como o projeto garante |
|---|---|
| Mesmo `applicationId` | fixo em `app/build.gradle.kts` (não altere) |
| Mesma assinatura | toda build usa a mesma keystore, guardada nos Secrets do GitHub |
| `versionCode` sempre maior | o workflow usa os minutos desde 01/01/2025 |

## Configuração única no GitHub (obrigatória)
Em *Settings → Secrets and variables → Actions → New repository secret*, crie:

- `KEYSTORE_BASE64` — a keystore em base64 (`base64 -w0 release.jks`)
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Guarde a keystore em local seguro. **Se ela for perdida ou trocada, a próxima versão não instala
por cima** (aí precisa desinstalar a antiga uma vez).

Para criar uma keystore nova:

```bash
keytool -genkeypair -keystore release.jks -alias listeningtime -keyalg RSA -keysize 2048 -validity 10000
```

## Primeiro uso (permissões)
1. **Acesso a notificações** (obrigatório): é assim que o app sabe que um vídeo está tocando.
   No Android 13+, APKs baixados fora da Play Store aparecem como "Configuração restrita":
   abra *Informações do app → ⋮ → Permitir configurações restritas* e tente de novo.
2. **Notificações** (Android 13+).
3. **Sem restrição de bateria**: para o sistema não fechar o contador.
4. **Acesso ao uso** (opcional): só para a opção "contar com o app aberto", útil se algum
   app não informar a reprodução ao sistema.

O tempo só conta com a tela ligada e o vídeo **tocando**. Pausas de até 1 minuto continuam a mesma sessão.

## Extensão de navegador
Conta o tempo quando um vídeo está tocando (aba visível ou picture-in-picture) em netflix.com, primevideo.com,
amazon.com(.br)/gp/video, youtube.com, max.com / hbomax.com, crunchyroll.com, tv.apple.com e disneyplus.com.

- **Ícone na barra**: mostra o tempo da sessão (na cor do app) ou `II` quando pausado;
- **Popup**: cronômetro ao vivo, botão pausar/retomar e resumo de hoje;
- **Painel completo**: o mesmo painel do app, com dicas ao passar o mouse e exportação dos dados em JSON;
- **Notificação** ao começar um vídeo, com botão *Pausar contagem* (no Firefox, sem botão);
- **Atalho** `Alt+Shift+P` para pausar/retomar.

Instalação (a cada Release saem dois zips):
- **Chrome/Edge**: extraia o zip `chrome`, abra `chrome://extensions`, ative o *Modo do desenvolvedor* →
  *Carregar sem compactação* → escolha a pasta. Para atualizar, extraia a versão nova por cima da mesma pasta
  e clique em ↻ na extensão — os dados são mantidos.
- **Firefox**: `about:debugging` → *Este Firefox* → *Carregar extensão temporária* → escolha o zip `firefox`
  (extensões não assinadas saem ao fechar o Firefox; para ficar permanente é preciso assinar no addons.mozilla.org).

Os dados da extensão ficam só no navegador e são separados dos dados do celular.

## Como é compilado
`.github/workflows/release.yml` roda a cada push (ou manualmente em *Actions → Run workflow*),
compila o APK release assinado, empacota a extensão e publica uma Release `v1.0.<número da build>`
com o APK e os zips da extensão anexados.
