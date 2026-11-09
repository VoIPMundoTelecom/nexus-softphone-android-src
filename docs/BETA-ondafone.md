# Beta fechado do Ondafone (06/11/2026)

Android pelo **teste interno do Google Play** e desktop por instalador. Este roteiro é para quem testa e para quem
acompanha. Cada item tem o resultado esperado; o que ainda depende de outra entrega está marcado como **[depende]**.

## O que entra no beta

| Entrega | PR | Observação |
|---|---|---|
| Modo limitado (qualquer provedor SIP) e completo (nos proxies Voipmundo) | Android #18, desktop #15 | "TLS primeiro" **desligado** no beta: o app não troca o transporte da conta. No desktop, flag `ENABLE_ONDAFONE_MODOS` |
| Conta Ondafone: e-mail + código, Google, sair, excluir | Android #21 | Back #390 (na main do givm desde 05/11) |
| Conta Ondafone no desktop: e-mail + código, sair, excluir (sem Google) | desktop #16 | Flag `ENABLE_ONDAFONE_CONTA` |
| Instaladores do desktop (Windows `.exe`/`.msi`, macOS Apple Silicon, Linux AppImage) | desktop #17 | Disparo manual com `ondafone` marcado (liga as duas flags); sem assinatura de código |
| Build assinado do beta (AAB) | Android #24 | Envio manual ao teste interno por quem administra o Play Console; nada é publicado |

Ficam **fora** do beta: Mensagens (SMS, WhatsApp, Telegram — #19, #20, #22).

## Antes de começar

- **Android:** aceite o convite do teste interno (link do Play Console) e instale o Ondafone pela Play Store. Se já
  houver um APK de teste instalado (assinatura diferente), desinstale antes.
- **Desktop:** baixe o instalador do run "Instaladores (SDK pré-compilado)" com `ondafone` marcado (artefatos
  terminados em `-ondafone`). Os instaladores **não são assinados** no beta:
  - Windows: o SmartScreen avisa; **Mais informações → Executar assim mesmo**.
  - macOS (só Apple Silicon, M1 ou mais novo): clique com o botão direito no app → **Abrir** → **Abrir**.
  - Linux: `chmod +x Nexus-*.AppImage` e rode o arquivo.
- Anote a versão do app (Ajuda → Solução de problemas → Versão do aplicativo) para os relatos.

## O que testar

Em cada passo, marque **OK** ou abra um relato (modelo no fim).

### 1. Cadastro por e-mail
1. Menu lateral → **Conta Ondafone** → informe um e-mail seu.
2. Sem marcar "Aceito os termos…", peça o código e digite-o: conta nova é **recusada** e o app volta ao passo do
   e-mail com o aviso para aceitar os termos (o código chega, mas não cria a conta sem o aceite).
3. Marque o aceite (o de novidades por e-mail é opcional e começa desmarcado) → **Receber código**.
4. O e-mail chega com o código de 6 dígitos (confira o spam; o código **não** aparece no assunto).
5. Digite o código → volta para a tela da conta com "Conectado como <seu e-mail>".
6. Código errado: mensagem de código inválido. Código velho: mensagem de vencido.

### 2. Login com Google
1. Saia (passo 6) e volte em **Conta Ondafone** → **Fazer login com o Google** (botão oficial do Google).
2. Escolha a conta Google → entra direto, com o e-mail da conta Google.
3. Conta Google que nunca entrou: sem o aceite marcado, deve pedir o aceite.
4. Mesmo e-mail que já entrou por código: entra na **mesma** conta (não cria outra).
- **[depende: client ID do Google]** sem ele no build, o botão não aparece; teste só o e-mail.
- Só no Android. No desktop a conta é só por e-mail + código (Configurações → Conta Ondafone).

### 3. Chamada app-to-app
1. Duas pessoas com conta Ondafone, cada uma num aparelho.
2. Uma liga para a outra pelo usuário SIP da conta Ondafone → toca, atende, áudio nos dois sentidos por 1 minuto.
3. Repita com um dos aparelhos no 4G/5G e o outro no Wi-Fi.
- **[depende: provisionamento SIP da conta Ondafone ligado no givm]** o #390 já está na main, mas a conta SIP
  app-to-app só é entregue quando a equipe ligar o provisionamento; até lá, faça este teste com contas SIP de teste
  entregues pela equipe.

### 4. Conta SIP de outro provedor (modo limitado)
1. Assistente → **Usar minha operadora** → usuário, senha e servidor SIP de qualquer provedor.
2. Registra (bolinha verde) e faz/recebe chamada.
3. O app mostra **Modo: seu provedor (recursos básicos)**.
4. Os recursos do modo completo (vídeo, gravação, conferência, transferência assistida, mais de uma conta) aparecem
   com o selo **Disponível com a Voipmundo**; o toque abre a explicação e **não** libera o recurso.
5. Áudio: só os codecs básicos (G.711 e Opus); DTMF funciona (URA de teste).

### 5. Modo completo nos proxies Voipmundo
1. Configure uma conta SIP Voipmundo (QR do givm ou dados entregues pela equipe).
2. Depois do registro, o app mostra **Modo: Voipmundo (todos os recursos)** e os selos somem.
3. Vídeo, gravação e conferência funcionam.
4. Troque o servidor da conta para outro provedor → volta para o modo limitado.
5. Conta Voipmundo em UDP passa sozinha para **TCP** (Configurações da conta mostram TCP): em UDP o INVITE com a
   autenticação passava de 1,5 KB, se perdia fragmentado e a chamada sainte terminava em 408. O app **não** troca
   para TLS sozinho no beta. Se o registro em TCP falhar com rede, a conta volta para UDP e o app só tenta TCP de novo
   em 24 h.
6. **Chamada sainte e entrante** com a conta Voipmundo, no Wi-Fi **e** no 4G/5G: liga para um fixo e um celular
   (completa, áudio nos dois sentidos) e recebe uma chamada com a tela bloqueada. Antes da correção a sainte dava 408.
7. Feche o app, abra de novo sem rede e depois com rede: continua no modo completo enquanto a conta registrar
   (volta ao limitado só depois de 24 h sem registro num proxy Voipmundo).
- A lista assinada dos proxies ainda usa a cópia embutida no app **[depende: chave pública nova "Yw4s…"]**; para o
  beta isso não muda o comportamento.

### 6. Sair e excluir a conta
1. **Conta Ondafone** → **Sair neste aparelho** → volta ao Entrar; as chamadas da conta SIP continuam.
2. Entre de novo → **Excluir conta** → confirmação explicando que não dá para desfazer → **Excluir de vez**.
3. A conta some: entrar de novo com o mesmo e-mail é uma **conta nova** (pede o aceite outra vez).
4. A exclusão também deve funcionar pela página web `https://api.givm.com.br/api/ondafone/conta/excluir` (a mesma
   da ficha do Play), sem o app: e-mail → código → excluir.
5. Desktop: os mesmos passos em Configurações → Conta Ondafone.

## Aparelhos

Cubra pelo menos um de cada linha:

| Grupo | Exemplos | Por quê |
|---|---|---|
| Android 9 ou 10 (mínimo do app) | Moto G7, Galaxy A10 | API mínima 28 |
| Android 13 ou 14, gama de entrada | Galaxy A14/A15, Moto G54 | Economia de bateria agressiva, chamada com o app fechado |
| Android 15 ou 16, Pixel | Pixel 7/8/9 | Versão mais nova, Credential Manager do Google |
| Xiaomi/Redmi (MIUI/HyperOS) | Redmi Note 12/13 | Restrições de segundo plano |
| Desktop | Windows 11, macOS 14+ (Apple Silicon), Ubuntu 24.04 | Os três instaladores |

Em cada aparelho: Wi-Fi **e** dados móveis, tela bloqueada recebendo chamada, e uma troca de rede durante a chamada.

## Como reportar um bug

1. Reproduza uma vez, se der, e anote a **hora exata** (com minutos): é por ela que a equipe acha o registro no
   servidor SIP e no givm.
2. Grave a tela (gravador do Android, `Win+G` no Windows, `Cmd+Shift+5` no macOS) ou tire um print.
3. Envie para **[definir: canal do beta]** com o modelo abaixo. Não mande senha SIP nem código de e-mail.

**Log do aparelho (Android):** logo depois de reproduzir, abra Ajuda → Solução de problemas → **Compartilhar logs**.
O app gera um `.zip` no próprio aparelho, com senha, ha1, tokens e o link do QR **mascarados**, e abre o menu de
compartilhar: mande o arquivo pelo canal do beta, junto do relato. Nada sobe para servidor nenhum sozinho. **Limpar
logs**, na mesma tela, apaga o histórico (útil antes de reproduzir de novo). No desktop ainda não há essa opção: se a
equipe precisar do log, ela orienta caso a caso.

```
Título: <o que deu errado, em uma linha>
Aparelho / sistema: <ex.: Galaxy A14, Android 14>   Versão do app: <ex.: 6.3.0 (610012)>
Rede: <Wi-Fi / 4G / 5G>   Conta: <Ondafone / outro provedor / Voipmundo>   Modo mostrado: <limitado / completo>
Passos: 1. … 2. … 3. …
Esperado: …
Aconteceu: …
Hora: <dd/mm hh:mm>   Número/usuário SIP envolvido: <se houver>
Print ou vídeo: <se tiver>
```

Gravidade, para a triagem: **bloqueia** (não registra, não liga, app fecha), **grave** (recurso errado, mas há
contorno), **menor** (texto, visual).
