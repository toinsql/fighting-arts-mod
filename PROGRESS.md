# Fighting Arts — progresso

- Estado observado em 09/11/2026; confira `git status` e o histórico, pois outros chats podem trabalhar no projeto.
- Estão implementados Santoryu, combos, animações por partes, espadas do inventário e renderização em primeira pessoa.
- A entrada usa `enter_mode`: após 65 ticks começa o idle e aparecem título e efeitos; a câmera continua por 30 ticks.
- A câmera de entrada se aproxima pela frente com zoom out, zoom in e shake; título e símbolo têm fade out.
- A saída usa `exit_mode`; a caminhada agachada em terceira pessoa usa `walk_crouch`.
- Combos 1 e 2 bloqueiam ataques por 0,5 s; combo 3 por 1 s. A janela do primeiro para o segundo é de 2 s.
- Último commit observado: `3135d43` — transições de animação mais suaves e seleção de idle em pé.
- Commits anteriores recentes atualizaram animações do Blockbench e ajustaram pouso e ciclo das pernas.
- Há alterações ainda não commitadas em `AnimationRenderer`, `FirstPersonStyleRenderer`, `SwordLayer` e `SwordModel`.
- Essas alterações incluem camadas de braços e ataques, postura agachada e transições de pouso; podem estar em andamento noutro chat.
- Preserve essas alterações e examine o diff antes de continuar. Não presumir que elas foram testadas ou concluídas.
- A última verificação conhecida da cinemática compilou e observou entrada, efeitos e câmera; não cobre todos os cenários visuais.
- A verificação geral de assets já apontou ausência de `fa_idle_crouch2`; confira o estado atual antes de tratar como pendência.
- Atualize este arquivo ao concluir mudanças relevantes, registrando também o que foi verificado e o que falta avaliar.

## 14/11/2026

- O modo simples do Santoryu usa F com um slot vazio da hotbar selecionado e a mão secundária vazia. A entrada usa `enter_mode_simple`, sem cinemática. F novamente toca `exit_mode_simple` em primeira ou terceira pessoa.
- Os slots de armas persistem no jogador. O painel abre com quatro posições quando vazio e três para o Santoryu. As espadas são lidas da esquerda para a direita. X ainda aciona a cinemática existente e tem a mesma exigência de mão secundária vazia.
- Verificados em uma cópia do mundo: equipar espadas, persistência após fechar e reabrir, entrada e saída com F, hotbar de um slot no Survival, bloqueio de troca pelas teclas numéricas e bloqueio de entrada com item na mão secundária. Build bem-sucedido.
- A exportação atual tem 47 clipes e passou na verificação geral de assets. O modo despertado ainda será implementado.

### Modo comum e habilidades

- Implementados `enter_mode_common2`, espadas de cintura/boca por modo, dano em queda com duas espadas no simples e varredura dos ataques normais pelas amostras do rig.
- Quatro habilidades com custos e recargas no servidor, energia persistida 0–200, HUD, duas/três katanas decorativas, ícones e atlas ImageGen. Briefing e células em `docs/SANTORYU_ART.md`.
- Investida de 6 blocos, corte ampliado com stun de 16 ticks, Vendaval de 10 blocos com fogo retardado e salto de 20 blocos com ápice de 10. Raios e fissuras procedurais, espadas reais no giro, fogo/fumaça por sprites.
- A prévia de salto considera a caixa de colisão do jogador. Os deslocamentos são limitados por colisões, chunks e borda do mundo. O fogo tem impacto independente do período de invulnerabilidade do golpe anterior.
- Testado em cópia isolada do mundo com cliente e servidor integrado: entrada simples, upgrade com X, uso das quatro habilidades, HUD, energia e cooldowns. Dois alvos laterais fora da mira receberam 7 de dano no ataque normal; a segunda habilidade causou 7,5 a cada um. Com espadas de diamante/ouro/ferro, o alvo central terminou com 138,05 de 200 após os dois dashes e os 5 pontos de fogo, conforme a fórmula. As posições confirmaram dashes de 6 e 10 blocos. O salto percorreu 20 blocos e pousou sem dano.
- Reexportado o Blockbench salvo em 14/11 às 22:53, incluindo as últimas alterações dos combos em primeira pessoa. `verify-assets.js` passou para os 47 clipes/141 faixas. `gradlew build --offline` passou após as alterações finais.
- Ainda não verificados em servidor dedicado/multiplayer: latência, stun entre jogadores e sincronização com muitos espectadores. A inspeção visual não cobre todos os quadros, skins e terrenos. A última revisão da prévia por caixa de colisão foi compilada, mas não repetida visualmente em todos os tipos de obstáculo.

- Revisão dos visuais: HUD com molduras individuais e preenchimento recortado, fenda texturizada apoiada na superfície, raios e faíscas ao longo do dash e giro no pivô das espadas. A Fenda usa um semicírculo à frente com raio de `√2 × alcance de ataque` e verifica a linha de visão.
- Verificação automatizada em uma cópia do mundo pelo cliente de desenvolvimento, sem automação da interface: capturas da HUD, do corte, do giro e dos dashes. O alvo além do alcance normal sofreu 7,5 de dano e stun. Alvos atrás da parede, fora do alcance e atrás do jogador conservaram a vida.

- Revisão do salto/Fenda: impulso único sem teletransporte por tick; mira compartilhada com a solução balística, topo de tetos alcançáveis, interrupções aéreas, pouso e proteção contra preparação do ataque caindo. Stun agora de 30 ticks. Fenda projetada em faces/níveis diferentes e na geometria animada dos mobs. Comando `/fightingarts debug reset`.
- Teste integrado com Punchy e Punchy Moves: ápice de 10,0000015 blocos, erro final horizontal de 0,0764 bloco no cenário plano, dash aéreo único de 6 blocos, câmera livre, pouso registrado, proteção de 10 ticks, acerto aéreo direto, stun e mira em teto aprovados. Projeção da fenda verificada em duas orientações de face e diferença de profundidade de 1,74 bloco. Testes em `tools/testing`; servidor dedicado não faz parte dessa execução.
