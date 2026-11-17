# Revisão do modo comum — 17/11/2026

O estado anterior à revisão foi salvo e enviado em `d5eaf62` na branch `main`.

## Acabamento e correções

- Sons vanilla acompanham a ativação das skills, o giro crescente, o disparo dos dashes e o pouso do leap.
- A barra de ação informa recarga restante, energia insuficiente e ausência de superfície alcançável.
- A proteção contra descarte pelo inventário agora encaminha o pacote para a thread do servidor antes de acessar o jogador e sincronizar o inventário.
- A suíte ganhou verificações de recursos, pedidos inválidos, descarte pelo inventário e cancelamento das habilidades.

## Resultados

| Cenário | Resultado |
| --- | --- |
| Build e verificação dos 48 clipes e do rig | Aprovado |
| Entrada comum no chão | Aprovado, sem aterrissagem indevida |
| Entrada simples e upgrade para comum | Aprovado, incluindo duração cinematográfica e retorno do controle |
| Leap, câmera livre, pouso e proteção do ataque caindo | Aprovado |
| Leap sob teto baixo e salto já no ar | Aprovado |
| Investida durante a queda | Aprovado, sem preparação após meio segundo e sem impulso vertical extra |
| Ataque aéreo por clique real | Aprovado, dano confirmado e `hit_falling` somente no acerto |
| Ataque aéreo sem alvo | Aprovado, combo normal |
| Stun da Fenda | Ativo após 1,2 s e encerrado depois de 1,5 s |
| Fenda em cantos e profundidades diferentes | Aprovado |
| Shake crescente, impacto e recuperação da câmera | Aprovado, preservando a mira |
| Custos, recarga, pouca energia e pedidos duplicados | Aprovado, sem cobrança indevida |
| Pedidos com NaN/infinito e segunda mão ocupada | Rejeitados sem gasto de recursos |
| Q, Ctrl+Q e descarte pelo inventário | Bloqueados, pilha preservada |
| Colocação e quebra de blocos no modo ataque | Bloqueadas no cliente e servidor integrado |
| Quebra depois de sair do modo | Restaurada |
| Remoção de espada durante a skill 3 | Cancela a skill, libera movimento e restaura a HUD |
| Fighting Arts em servidor dedicado | Inicialização e encerramento aprovados |

No teste plano, o ápice foi de 10,0000015 blocos e a posição final ficou a aproximadamente 0,079 bloco do destino previsto. Sob teto de três blocos, o ápice caiu para aproximadamente 1,2 bloco. Esses valores descrevem os cenários da suíte.

## Ocorrências externas

O Punchy 3.1b falhou quando colocado no servidor dedicado. A exceção aponta para `punchy.neoforge.PunchyNeoForge`, que tenta carregar `net.minecraft.client.gui.screens.Screen` em `DEDICATED_SERVER`. O Fighting Arts passou no servidor dedicado com os complementos visuais mantidos no cliente. A integração de cliente foi testada com Punchy e Punchy Moves instalados.

Uma rodada registrou uma resposta HTML “Service unavailable” em uma chamada da authlib/Mojang. O carregamento do mundo e os testes funcionais prosseguiram. Essa ocorrência pertence ao serviço externo.

## Evidências e alcance

As duas rodadas integradas ampliadas terminaram com `FA-REVIEW PASS`. O teste de recursos emitiu `FA-GUARDS PASS`. O servidor dedicado terminou com `FA-DEDICATED PASS`. Logs e capturas locais estão em `build/reports/santoryu-polish/`.

O teste dedicado cobre carregamento e encerramento. Sessões com dois clientes, PvP sob latência e modelos de entidades de terceiros ainda precisam de uma rodada própria. A validação automatizada dos sons cobre sua integração e execução no jogo, enquanto a preferência de volume e timbre pode ser ajustada durante o uso.
