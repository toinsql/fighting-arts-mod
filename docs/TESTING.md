# Santoryu: teste integrado de cliente e servidor

NeoForge 21.1.265, Minecraft 1.21.1, Java 21. A fixture executa o cliente real e seu servidor integrado; não usa automação de mouse/teclado do sistema. Os mods de `run/mods` são copiados, permitindo verificar a integração com Punchy e Punchy Moves.

```powershell
.\tools\testing\run-client-check.ps1 -WorldPath 'C:\caminho\mundo-de-teste'
```

O mundo indicado é copiado para uma pasta temporária nova. A fixture altera terreno, inventário, vida e posições somente nessa cópia, fecha o cliente ao terminar e deixa log e screenshots na pasta informada pelo script. Execute sem interagir com a janela durante o teste. A compilação da instrumentação fica fora do JAR distribuído.

Verificações automatizadas:

- Entrada comum no chão sem `fall_ground` indevido.
- Mira e pouso balístico, ápice de dez blocos, câmera livre e registro da animação de aterrissagem.
- Bloqueio do ataque caindo automático por dez ticks após o pouso.
- Interrupção pela Investida após meio segundo: sem preparação e com um único dash de seis blocos, preservando a queda.
- Clique de ataque real durante o leap, confirmação do dano e entrada direta em `hit_falling`.
- Leap ativado no ar e interrupção por ataque sem alvo usando combo normal.
- Mira no chão sob um teto de três blocos, com redução do arco.
- Stun ainda ativo depois de 1,2 s e encerrado depois de 1,5 s.
- Mira no céu limitada a vinte blocos; mira no teto selecionando o topo alcançável.
- Corte em duas orientações de face e em profundidades diferentes.

As capturas permitem inspecionar a trajetória, aterrissagem, marcas em mobs e corte em cantos. O comando de debug recompõe energia e recargas entre cenários. Falhas de asserção interrompem a execução e aparecem no log; o script exige `FA-REVIEW PASS` e ausência de `FA-...FAIL`.

Última execução integrada aprovada: ápice de 10,0000015 blocos e diferença de 0,0764 bloco entre destino previsto e posição final no teste plano. Não é um teste de latência em servidor dedicado nem de todos os modelos de entidades de terceiros.

## Revisão do modo comum

A suíte também verifica energia insuficiente, recargas, pedidos duplicados, valores não finitos, segunda mão ocupada, descarte por Q/Ctrl+Q e pelo inventário, colocação e quebra de blocos, restauração da quebra fora do modo, entrada simples, cinematográfica de upgrade e cancelamento da skill ao remover uma espada.

Para verificar a inicialização e o encerramento em servidor dedicado, execute `tools/testing/run-server-check.ps1`. Esse script cria um mundo temporário, usa uma porta local automática e encerra após `ServerStartedEvent`. Ele exige a marca `FA-DEDICATED PASS`, pois a tarefa Gradle pode terminar com sucesso mesmo quando o carregamento de um mod falha. A instrumentação fica fora do JAR. Os complementos visuais do cliente permanecem na execução de cliente.

Veja `QA_SANTORYU.md` para os resultados e as limitações da revisão.
