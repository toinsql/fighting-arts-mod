# Fighting Arts — desenvolvimento

Minecraft 1.21.1, NeoForge 21.1.265, Java 21. Projeto separado do antigo projeto PAL. Usa um motor próprio de animações em camadas; não requer PAL. Os JSONs exportados usam a estrutura de clipes do Punchy Moves, mas Fighting Arts controla as escolhas e a sincronização do seu estilo.

## Compilar e executar

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
node tools/verify-assets.js
```

O JAR fica em `build/libs/`. Instale o mesmo mod em servidor e clientes. A versão inicial implementa o Estilo Três Espadas; outras habilidades e estilos precisam de suas regras próprias.

## Uso

Passe o mouse sobre o slot de armas acima da mão secundária para expandi-lo. Ele mostra quatro posições quando vazio e passa a mostrar três ao equipar uma espada. O Santoryu usa as três espadas equipadas para mão direita, mão esquerda e boca, da esquerda para a direita.

Com as três espadas equipadas, a mão secundária vazia e um slot vazio da hotbar selecionado, pressione F para entrar no modo simples. A entrada dura cerca de um segundo, mantém a perspectiva atual e troca a hotbar vanilla pela hotbar com o emblema do Santoryu. O modo simples oferece os ataques normais e o ataque em queda. Pressione F novamente para embainhar com `exit_mode_simple`, que dura cerca de 1,4 segundo. Durante o modo de ataque, as teclas da hotbar e a roda do mouse deixam de trocar o slot selecionado.

X aciona a entrada cinematográfica que já existia e também exige a mão secundária vazia. O jogador recupera o controle e recebe a hotbar do estilo ao fim da cena.

No modo simples, a terceira espada permanece na cintura em todas as animações e não entra no dano do ataque em queda. X a partir desse modo usa `enter_mode_common2` para passar ao comum. Os ataques normais também varrem o caminho das lâminas, podendo atingir alvos fora da mira, com uma ocorrência de dano por alvo e golpe.

### Habilidades do modo comum

| Tecla | Habilidade | Energia | Recarga | Efeito |
| --- | --- | --- | --- | --- |
| 1 | Investida das Três Presas | 25 | 10 s | Preparação móvel, dash horizontal de até 6 blocos, soma das três espadas × 1,35. |
| 2 | Fenda da Lua Sombria | 15 | 15 s | Área de corte dobrada, dano da esquerda + metade da direita, stun de 1,5 s e fenda visual de 6 s. |
| 3 | Vendaval do Demônio | 75 | 40 s | Giro das espadas, dash de até 10 blocos aos 2,15 s, soma das três espadas × 2 e fogo após 0,6 s. |
| 4, segurar e soltar | Salto da Garça Rubra | 20 | 3 s | Prévia vermelha, alcance de até 20 blocos e arco com ápice de 10 blocos. Sem dano. |

Olhar para baixo reduz o alcance do salto. Paredes e tetos limitam o deslocamento. O Vendaval bloqueia movimento durante a animação e permite mirar até o dash. O fogo dura 2 s, causa 4 de dano inicial e 1 após mais um segundo. Os rastros das habilidades 1 e 3 duram 1 s e 1,5 s, respectivamente.

A energia começa em zero, vai até 200 e é persistida. Regenera 2 por segundo; um hit normal concede 2, derrotar um monstro concede 12 e um hit de habilidade concede 16. Custos, recargas, alvos, colisões e stun são validados no servidor. A HUD mostra as quatro habilidades, custos, recargas, energia e o progresso de despertar; a ativação do modo despertado será uma etapa própria.

A arte das katanas, ícones, molduras, fumaça e fogo está no atlas `textures/gui/santoryu_skills.png`, gerado com a skill `minecraft-imagegen`. Raios, fendas, giro e trajetória são calculados em código. Veja `docs/SANTORYU_ART.md` para o mapa do atlas e o briefing visual.

Os golpes 1 e 2 bloqueiam novos ataques por 10 ticks (0,5 s). O primeiro pode encadear o segundo dentro de 40 ticks (2 s) e o segundo pode encadear o terceiro dentro de 30 ticks (1,5 s). O terceiro bloqueia ataques por 20 ticks (1 s) e reinicia a sequência no próximo ataque. Durante os 50 ticks de saída do estilo, o jogador se move a 70% da velocidade normal. O dano segue a lógica de ataque do Minecraft.

Com o estilo ativo, descer olhando para baixo prepara o ataque caindo se houver uma queda de pelo menos três blocos entre o ponto mais alto e o chão abaixo do jogador. O golpe fica disponível por mais dois ticks (0,1 s) após o pouso e pode ser usado uma vez por queda. O avanço curto segue a direção horizontal da mira e só acontece quando o alvo recebe o golpe.

## Animações e novas edições

`tools/export-santoryu.js` converte os 47 clipes do projeto Blockbench em 141 faixas de corpo inteiro, tronco superior e pernas. Bezier e Catmull-Rom são convertidas para keyframes numéricos para o leitor linear.

Em primeira pessoa, o estilo usa `fp_idle`, `fp_walk` e `fp_run` para a locomoção. Agachar mantém a mesma seleção de clipes. A subida ajusta `fp_jump` à velocidade vertical do jogador. Na descida, `fp_fall` toca uma vez e `fp_fall_loop` repete até o pouso. `fp_embainhar` acompanha o tempo de saída do estilo.

Os golpes usam o braço direito, o esquerdo e ambos, respectivamente. O braço direito continua o golpe 1 quando o braço esquerdo começa o golpe 2. Os clipes de primeira pessoa dos golpes 1 e 2 tocam até 1,13 s e o do golpe 3 até 1,92 s. As transições entre clipes são suavizadas por braço. Os gestos aleatórios afetam um braço por vez e têm um intervalo sorteado de 60 a 120 segundos após cada gesto. Durante a queda, só podem continuar os gestos já iniciados. Atacar interrompe o gesto.

Os clipes estão em `src/main/resources/assets/fightingarts/animations/santoryu.json`. A configuração de movimentos do Fighting Arts está no código; somente copiar um arquivo de animação com nome novo não cadastra um estilo.

O rig e a textura estão em `assets/fightingarts/styles/santoryu_rig.json` e `assets/fightingarts/textures/entity/`. Consulte `docs/RIG.md`. Preserve pivôs e hierarquia dos grupos: `left_item` é a espada principal esquerda, `left_item2` a cópia de transferência da esquerda, `right_item` a principal direita e `right_item2` a da boca. Não mova as espadas apenas por aproximação à mão: o ponto de referência inclui os grupos `espada_*_AJUSTE`.

Para o próximo estilo, crie primeiro um rig e clipes com as mesmas convenções de unidades, depois acrescente um estado/seleção de estilo em `StyleService`, uma biblioteca de clipes e seleção no `AnimationRenderer`, e seus pontos de ancoragem no renderizador. O estilo atual está explícito no código; um registro declarativo de estilos pode ser introduzido quando o segundo estilo definir quais contratos realmente precisam ser compartilhados.

O servidor decide entrada/saída, inventário, bloqueio de movimento e janela dos combos. O cliente interpreta clipes, mistura poses e desenha câmera, espadas e GUI. Não coloque regras de dano ou validação somente no cliente.

## Arte

Imagens originais geradas com image_gen, com transparência: `textures/gui/style_symbol.png`, `aura_back.png`, `slash_front.png`, `textures/effect/trail.png` e `particle.png`. Prompts: emblema triangular de três katanas; anel de aura verde/violeta; arcos de corte com faíscas; rastro de energia branco, verde e violeta; e uma partícula luminosa em pixel art. Título e símbolo são desenhados na interface; aura, arcos e partículas são renderizados no mundo ao redor do jogador. Os rastros são fitas geradas a partir de um histórico aproximado das posições dos pontos de ancoragem das espadas.

## Limites de verificação

A compilação e os checks de recursos são verificações diferentes do teste visual. No cliente de desenvolvimento foram observados a entrada no estilo, os efeitos no mundo, a espada da boca junto à cabeça, as mãos com espadas em primeira pessoa e o agrupamento das espadas embainhadas junto ao lado direito do corpo. Um travamento causado pela troca incorreta de buffers dos efeitos foi encontrado e corrigido; a ativação posterior não apresentou esse travamento. A inspeção não cobriu todos os quadros de todas as animações, orientação das espadas em todos os ângulos, precisão do rastro nas pontas, câmera em ambientes fechados, skins slim/armaduras, multiplayer ou coexistência com outros mods de animação. Os ajustes de primeira pessoa e rastros são aproximações que podem precisar de calibração visual adicional.

Correções posteriores: a pose do estilo substitui a locomoção vanilla nas partes controladas, preservando o olhar durante combate. Círculos de aura e corte são planos horizontais centrados no jogador e giram sobre o próprio eixo. A primeira pessoa usa ModelParts de braço/manga com pivô local e arma ancorada no punho; não reutiliza a transformação especial de renderRightHand.

### Debug e verificação

`/fightingarts debug reset` restaura 200 de energia e zera as quatro recargas do jogador que executa o comando (operador, nível 2).

O salto usa um impulso balístico com gravidade e arrasto vanilla. A mira procura uma superfície alcançável em até 20 blocos horizontais, inclusive o topo de tetos. Ao mirar no chão sob um teto baixo, o arco diminui até caber no espaço. O salto pode ser lançado no ar. Outras skills interrompem o salto; a Investida pula a preparação após 10 ticks no ar. Atacar durante o salto usa os combos normais quando não há acerto. Um acerto confirmado executa diretamente `hit_falling`, inclusive contra mobs no chão. A preparação automática do ataque caindo fica suspensa até 10 ticks após o pouso, ou até um novo pulo.

Os testes integrados e as capturas podem ser repetidos com `tools/testing/run-client-check.ps1 -WorldPath <pasta-de-um-mundo-de-teste>`. O script usa uma cópia temporária do mundo e mantém a instrumentação fora do JAR. Veja `docs/TESTING.md`.

No modo ataque, colocação, descarte e quebra de blocos ficam bloqueados. Cliques em blocos executam ataques. As skills têm sons vanilla sincronizados e avisos de recarga, energia insuficiente e salto sem destino alcançável.
