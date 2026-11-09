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

As três primeiras espadas definem topo e mão direita, meio e mão esquerda, e base e boca. A busca percorre a hotbar da esquerda para a direita, depois as linhas do inventário de cima para baixo e da esquerda para a direita. A mão secundária pode completar a seleção. X inicia `enter_mode` por 65 ticks. Quando o clipe termina, o personagem passa ao `idle` e o título e os efeitos aparecem com a câmera quase de frente. A perspectiva anterior volta 30 ticks depois. O jogador fica imobilizado até o fim da cena. X novamente toca `exit_mode` por 50 ticks com velocidade reduzida a 15% e sem o zoom causado por essa redução. A caminhada agachada usa `walk_crouch` em terceira pessoa.

Os golpes 1 e 2 bloqueiam novos ataques por 10 ticks (0,5 s). O primeiro pode encadear o segundo dentro de 40 ticks (2 s) e o segundo pode encadear o terceiro dentro de 30 ticks (1,5 s). O terceiro bloqueia ataques por 20 ticks (1 s) e reinicia a sequência no próximo ataque. O dano segue a lógica de ataque do Minecraft.

## Animações e novas edições

`tools/export-santoryu.js` converte o projeto Blockbench para 26 clipes e 78 variantes de corpo inteiro, tronco superior e pernas. `walk_left` e `walk_right` são excluídos. Bezier e Catmull-Rom são convertidas para keyframes numéricos para o leitor linear.

Em primeira pessoa, o estilo usa `fp_idle`, `fp_walk` e `fp_run` para a locomoção. Agachar mantém a mesma seleção de clipes. A subida ajusta `fp_jump` à velocidade vertical do jogador. Na descida, `fp_fall` toca uma vez e `fp_fall_loop` repete até o pouso. `fp_embainhar` acompanha o tempo de saída do estilo.

Os golpes usam o braço direito, o esquerdo e ambos, respectivamente. O braço direito continua o golpe 1 quando o braço esquerdo começa o golpe 2. Os clipes de primeira pessoa dos golpes 1 e 2 tocam até 1,13 s e o do golpe 3 até 1,92 s. As transições entre clipes são suavizadas por braço. Os gestos aleatórios afetam um braço por vez e têm um intervalo sorteado de 60 a 120 segundos após cada gesto. Durante a queda, só podem continuar os gestos já iniciados. Atacar interrompe o gesto.

Os clipes estão em `src/main/resources/assets/fightingarts/animations/santoryu.json`; cópias de intercâmbio para Punchy Moves estão em `assets/punchy_moves/animations/fightingarts/`. A configuração de movimentos do Fighting Arts está no código; somente copiar um arquivo de animação com nome novo não cadastra um estilo.

O rig e a textura estão em `assets/fightingarts/styles/santoryu_rig.json` e `assets/fightingarts/textures/entity/`. Consulte `docs/RIG.md`. Preserve pivôs e hierarquia dos grupos: `left_item` é a espada principal esquerda, `left_item2` a cópia de transferência da esquerda, `right_item` a principal direita e `right_item2` a da boca. Não mova as espadas apenas por aproximação à mão: o ponto de referência inclui os grupos `espada_*_AJUSTE`.

Para o próximo estilo, crie primeiro um rig e clipes com as mesmas convenções de unidades, depois acrescente um estado/seleção de estilo em `StyleService`, uma biblioteca de clipes e seleção no `AnimationRenderer`, e seus pontos de ancoragem no renderizador. O estilo atual está explícito no código; um registro declarativo de estilos pode ser introduzido quando o segundo estilo definir quais contratos realmente precisam ser compartilhados.

O servidor decide entrada/saída, inventário, bloqueio de movimento e janela dos combos. O cliente interpreta clipes, mistura poses e desenha câmera, espadas e GUI. Não coloque regras de dano ou validação somente no cliente.

## Arte

Imagens originais geradas com image_gen, com transparência: `textures/gui/style_symbol.png`, `aura_back.png`, `slash_front.png`, `textures/effect/trail.png` e `particle.png`. Prompts: emblema triangular de três katanas; anel de aura verde/violeta; arcos de corte com faíscas; rastro de energia branco, verde e violeta; e uma partícula luminosa em pixel art. Título e símbolo são desenhados na interface; aura, arcos e partículas são renderizados no mundo ao redor do jogador. Os rastros são fitas geradas a partir de um histórico aproximado das posições dos pontos de ancoragem das espadas.

## Limites de verificação

A compilação e os checks de recursos são verificações diferentes do teste visual. No cliente de desenvolvimento foram observados a entrada no estilo, os efeitos no mundo, a espada da boca junto à cabeça, as mãos com espadas em primeira pessoa e o agrupamento das espadas embainhadas junto ao lado direito do corpo. Um travamento causado pela troca incorreta de buffers dos efeitos foi encontrado e corrigido; a ativação posterior não apresentou esse travamento. A inspeção não cobriu todos os quadros de todas as animações, orientação das espadas em todos os ângulos, precisão do rastro nas pontas, câmera em ambientes fechados, skins slim/armaduras, multiplayer ou coexistência com outros mods de animação. Os ajustes de primeira pessoa e rastros são aproximações que podem precisar de calibração visual adicional.

Correções posteriores: a pose do estilo substitui a locomoção vanilla nas partes controladas, preservando o olhar durante combate. Círculos de aura e corte são planos horizontais centrados no jogador e giram sobre o próprio eixo. A primeira pessoa usa ModelParts de braço/manga com pivô local e arma ancorada no punho; não reutiliza a transformação especial de renderRightHand.
