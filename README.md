# Listening Time

App Android que mostra uma **notificação com cronômetro de tempo de tela** sempre que você
reproduz vídeos na **Netflix, Prime Video, YouTube, HBO Max, Crunchyroll, Apple TV ou Disney+**.

O painel do app (tema escuro) mostra:
- ao vivo: o app que está tocando e o cronômetro da sessão;
- **hoje**, **total geral** (todos os dias) e, para cada mês: total, média por dia ativo e dia recorde;
- **gráfico de horas por dia do mês**, empilhado por app (toque numa barra para ver o detalhe do dia);
- **por app**: horas no mês, hoje e total geral;
- **dias do mês**: lista de cada dia com o total e a divisão por app;
- navegação entre meses (‹ ›).

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

## Como é compilado
`.github/workflows/release.yml` roda a cada push (ou manualmente em *Actions → Run workflow*),
compila o APK release assinado e publica uma Release `v1.0.<número da build>` com o APK anexado.
