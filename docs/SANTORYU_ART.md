# Arte do Santoryu comum

Asset final: `src/main/resources/assets/fightingarts/textures/gui/santoryu_skills.png`.
PNG RGBA de 1254 × 1254, grade de 4 × 4 células, amostrada com transparência e sem filtragem suave.
Criado com o gerador de imagens integrado e a skill `.agents/skills/minecraft-imagegen/SKILL.md`.

## Briefing das gerações

- Atlas transparente com ícones legíveis em tamanho pequeno, pixel art compatível com Minecraft, bordas escuras, aço claro, detalhes dourados, verde esmeralda e violeta.
- Primeira linha: três cortes verdes cruzados; corte crescente violeta com centro negro; três lâminas cruzadas em um vórtice verde/violeta; salto vermelho em arco com círculo de aterrissagem.
- Segunda linha: duas katanas horizontais espelhadas para as laterais da hotbar; uma katana vertical; moldura vazia para ícones.
- Terceira linha: chama verde/violeta; nuvem de fumaça; raio; fenda escura com borda violeta.
- Quarta linha: painel vertical de quatro habilidades; símbolo de energia verde; cristal violeta; corte branco para sobreposição na tela.
- Refinamento após inspeção no jogo: limpar ruído e contornos, preservar grade e transparência, tornar aço e cabos das katanas mais definidos e manter áreas livres para os números desenhados pelo jogo.

O painel usa peças em `textures/gui/santoryu_hud_*.png`, geradas a partir da referência da moldura. O topo tem a esmeralda e os pingentes roxos. As laterais e divisórias alinham as quatro habilidades, e a base fecha a seção dos medidores. Em `santoryu_hud_materials.png`, as duas primeiras linhas da grade guardam quatro fundos de metal escuro com gravuras discretas, recortados para encostar nas laterais. A célula inferior esquerda guarda o metal do topo e da seção dos medidores. Os números, custos e recargas são desenhados pelo código sobre as texturas e os ícones.

Na segunda geração, as duas barras receberam molduras com canal escuro e faixas de preenchimento separadas. O código interpola a carga e revela a faixa da esquerda para a direita com recorte, preservando a moldura e a escala do detalhe luminoso.

As texturas de efeitos foram recortadas em sprites individuais, com margens transparentes. Os raios usam a textura ao longo de uma malha contínua com curvas, ramificações e faíscas. A fenda deforma a textura escura sobre a face atingida, estreita as extremidades e atenua os trechos que ultrapassam a superfície. As duas espadas de mão giram com as fitas de vento ao redor de um ponto comum à frente do jogador. A mira do salto reutiliza uma faixa curva vermelha. O fogo e a fumaça variam de tamanho e opacidade durante a animação.

## Índices usados pelo renderizador

| Célula | Conteúdo |
| --- | --- |
| 0–3 | Ícones das quatro habilidades |
| 4–5 | Katanas laterais da hotbar |
| 6 | Katana vertical do modo comum |
| 7 | Moldura de habilidade da versão anterior |
| 8–9 | Fogo e fumaça |
| 10–15 | Arte de referência preservada no atlas |

## Projeção das habilidades

A Fenda divide a textura em patches com UVs contínuos, projeta raios a partir do jogador e recorta cada polígono nas formas de colisão atingidas. Assim, o desenho acompanha faces e profundidades distintas. Nos seres vivos, uma camada usa os próprios vértices animados do modelo, guardando as UVs do impacto para o corte acompanhar a pele.

A trajetória do salto amostra a seção central brilhante de `aim_ribbon`, em vez de esticar a silhueta curva inteira. A largura varia com a distância e a opacidade diminui perto da câmera. As espadas do Vendaval usam o comprimento projetado do guarda à ponta da pose normal como referência; o centro é calculado entre as mãos de `fp_skill3`, e os efeitos compartilham a mesma escala.
