# CountryRoss GPS (Forge 1.20.1)

Item "CountryRoss GPS": clique direito abre um mapa verde com curvas de nivel e a setinha preta do jogador.

## Configuracao
Ao iniciar o jogo/servidor o mod cria a pasta `config/countryross_gps/` com:
- `general.json`  resolucao do mapa, curvas de nivel, mensagens do chat, permissoes
- `regions.json`  regioes do mapa
- `jammers.json`  Bloqueadores de Sinal
- `triggers.json` gatilhos (o que libera/bloqueia regioes e liga/desliga bloqueadores, por coordenadas e/ou comando)
- `client.json`   preferencias visuais de cada jogador
- `LEIA-ME.txt`   explicacao completa

Depois de editar: `/countrygps reload`.

## Comandos
/countrygps trigger <id> | unlock <regiao> | lock <regiao> | jammer on|off|toggle <id> | list | rescan <regiao> | reload
Command block: `/countrygps trigger ligar_bloqueador`
Dar o item: /give @p countryross_gps:gps

## Gerar o .jar
GitHub: envie os arquivos, aba Actions > Build mod > Run workflow > baixe em Artifacts.
Local: JDK 17 + Gradle 8.1.1 -> `gradle build` -> `build/libs/countryross_gps-1.0.0.jar`.
