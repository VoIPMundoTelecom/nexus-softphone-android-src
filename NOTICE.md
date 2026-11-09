# NOTICE — Nexus Android

**Nexus Android** é um fork do **Linphone Android** (https://github.com/BelledonneCommunications/linphone-android),
© 2010-2026 **Belledonne Communications SARL**, distribuído sob a **GNU General Public License versão 3**
(`LICENSE.txt`). As modificações — © 2026 IVM Telecomunicação (CNPJ 10.285.037/0001-67) — também são GPLv3.

## Componentes e licenças

| Componente | Versão usada | Licença | Fonte correspondente |
|---|---|---|---|
| Linphone Android (base do app) | tag/commit do upstream na história deste fork | GPLv3 | https://github.com/BelledonneCommunications/linphone-android |
| linphone-sdk (liblinphone, mediastreamer2, belle-sip, oRTP, bctoolbox, bzrtp, lime…) — AAR `org.linphone:linphone-sdk-android` | `5.6.0-alpha.185+9224908066` | **AGPLv3** (LICENSE do repositório linphone-sdk nesse commit; o POM do Maven ainda diz GPLv3) | `linphone-sdk-<commit>.tar.gz` no Release desta tag (commit `9224908066` com os submódulos) e https://github.com/BelledonneCommunications/linphone-sdk/tree/9224908066 (espelho de https://gitlab.linphone.org/BC/public/linphone-sdk) |
| Bibliotecas de terceiros embutidas no SDK e no app | — | ver Ajuda → "Licenças de terceiros" no app e os arquivos de licença de cada componente | — |

O SDK é **AGPLv3**: o app (GPLv3) é combinado com ele conforme a seção 13 da GPLv3. Não modificamos o SDK; usamos
o binário publicado pela Belledonne na versão acima, cujo código-fonte correspondente está no link indicado.

## Mudanças da IVM Telecomunicação (até 2026-11-09, tag `v6.3.0-nexus.1`)

| Desde | Mudança |
|---|---|
| 2026-10 | Marca **Nexus** (nome, ícone, cores, `applicationId br.com.grupoivm.nexus`), boas-vindas e textos pt-BR |
| 2026-10 | Provisionamento remoto **só do givm** (QR, `https://api.givm.com.br/api/prov/<token>`, `nexus-config:`), confirmação do servidor antes de aplicar, token mascarado nos logs; sem `linphone-config:` |
| 2026-10 | Sem servidores da Belledonne (login sip.linphone.org, upload de logs, arquivos do chat, checagem de versão, STUN); conta manual sem STUN próprio fica sem ICE |
| 2026-10 | Serviço em primeiro plano para receber chamadas sem push; explicação e pedido de isenção da otimização de bateria |
| 2026-10 | linphone-sdk com versão exata; item "Código-fonte" da Ajuda aponta para este espelho na versão instalada |
| 2026-10 | Marca **Ondafone** (nome, cores, textos "por Voipmundo" e "Feito com Ondafone"); fontes Inter e Sora no lugar da Noto Sans |
| 2026-11 | Logos, ícones e splash do Ondafone (SVGs oficiais da marca, não GPL); cores principais Onda/Onda Funda, Coral só no botão principal; só Sora Bold (700), sem a ExtraBold |

Para o diff completo, compare esta árvore com o upstream na versão indicada acima.

## Fontes

Inter (© 2016 The Inter Project Authors) e Sora (© 2019 The Sora Project Authors), sob a **SIL Open Font License 1.1**:
`licenses/Inter-OFL.txt` e `licenses/Sora-OFL.txt`.

## Marcas

Os nomes e logotipos **Ondafone**, **Voipmundo**, **i9c** e **Nexus** (nome anterior do app), marcas da IVM Telecomunicação (CNPJ 10.285.037/0001-67), **não** são licenciados pela GPL: veja `TRADEMARKS.md`.

## Esta versão

Gerado a partir da tag `v6.3.0-nexus.1` (commit `95db3ac0f95142faefd2d6eec9fb849c4b9cbe86`).
