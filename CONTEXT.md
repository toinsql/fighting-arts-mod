# Fighting Arts — contexto

- Mod de estilos de combate para Minecraft Java 1.21.1, NeoForge 21.1.265 e Java 21.
- Primeiro estilo: Três Espadas (Santoryu); a intenção é adicionar outros estilos e habilidades.
- Usa motor próprio de animações em camadas, inspirado no Punchy! Moves + Fluid & Fresh; não depende do PAL.
- São necessárias três espadas nos slots de armas acima da mão secundária, que deve estar vazia para entrar no estilo. F ativa o modo simples a partir de um slot vazio da hotbar e também sai dele. X aciona a entrada e a saída do modo comum existente.
- As espadas selecionadas correspondem, na ordem, à mão direita/topo, mão esquerda/meio e boca/base.
- O servidor controla estado, inventário, movimento e combos; o cliente renderiza animações, câmera e efeitos.
- `src/main/java/fightingarts/StyleService.java`, `StyleState.java` e `StyleNetwork.java`: regras e sincronização.
- `client/AnimationRenderer.java` e `FirstPersonStyleRenderer.java`: poses, camadas e primeira pessoa.
- `client/SwordLayer.java`, `SwordModel.java`, `StyleClient.java` e `WorldEffects.java`: espadas, câmera e efeitos.
- `client/mixin/`: integração com pose, câmera, mãos e itens do Minecraft.
- Assets: `src/main/resources/assets/fightingarts/animations/santoryu.json`, `styles/` e `textures/`.
- Modelo de autoria: `C:/Users/toins/OneDrive/Documentos/animações/santoryu_cespada.bbmodel`.
- `tools/export-santoryu.js` exporta animações; preserve o modelo e possíveis edições ainda abertas no Blockbench.
- Consulte `docs/RIG.md` para hierarquia, pivôs e pontos de fixação das espadas.
- Compile com `.\gradlew.bat build`; execute o cliente com `.\gradlew.bat runClient`.
- O JAR fica em `build/libs/`; teste visual no jogo é separado da compilação.
- Repositório privado: https://github.com/toinsql/fighting-arts-mod. Confira `git status` antes de editar.
- `CONTEXTO-PARA-OUTRA-IA.md` é histórico e contém informações antigas; priorize código, Git e este arquivo.
