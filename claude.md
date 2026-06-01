# CLAUDE.md — embabel-clj

Projeto experimental: usar o **Embabel** (framework de agentes de IA na JVM, Kotlin/Spring) a partir de **Clojure**, mantendo **programação funcional**, **orientação a dados** e **desenvolvimento via REPL**.

## Decisões travadas (não reabrir)

- **Build/packaging: Maven.** Escolhido porque importa a BOM do Embabel nativamente, lê o repo Spring Milestones e fornece o plugin Spring Boot — resolve a infra chata de graça. Build tool não afeta paradigma, só ergonomia.
- **Nível 3: funcional / data-oriented, SEM AOT de Clojure.** Actions como funções, world state como mapa imutável, conditions e playbooks como dados. Casca imperativa mínima.
- **REPL-driven.** Subir o contexto Spring/Embabel uma vez e redefinir funções e dados ao vivo.

> Maven + Nível 3 preserva 100% do "brilho" do Clojure: o brilho é todo em runtime (dados imutáveis, structural sharing na busca do planner, hot-load de dados/regras). O Maven só monta o jar e sai do caminho. A única coisa que mata o hot-reload é AOT — por isso **AOT de Clojure é proibido**, salvo a exceção única descrita abaixo.

## A única ameaça ao Nível 3 (a Task 0 valida isto)

A viabilidade do Nível 3 *puro* depende de uma pergunta AINDA NÃO CONFIRMADA: o Embabel permite **registrar agentes/actions programaticamente em runtime**, ou só os descobre via **scanning de classes anotadas `@Agent`**?

- Se houver registro programático → **GO**: Nível 3 puro, zero AOT de Clojure.
- Se só houver scanning → **GO-WITH-BRIDGE**: UMA única classe-ponte anotada `@Agent` (Java de preferência; gen-class só se inevitável), cujo corpo **apenas delega** ao registry data-oriented em Clojure. Nada de lógica, world-state ou playbook dentro dela.

NUNCA cair silenciosamente para gen-class nas actions. A ponte é a fronteira; ela não engole o núcleo funcional.

## Modelo mental do Embabel (não reaprender errado)

- Conceitos: **Actions** (passos), **Goals** (metas), **Conditions** (pré/pós-condições, reavaliadas após cada action), **Domain model**, **Plan** (sequência montada pelo sistema, não pelo programador).
- Planner padrão = **GOAP**; ciclo **OODA**: replaneja após cada action. Também há Utility AI; o planner é plugável.
- **CRÍTICO:** no modelo anotado, o planner **infere pré/pós-condições a partir dos TIPOS** das assinaturas. No Nível 3 não temos isso — então as pré/pós-condições precisam ser declaradas **explicitamente como DADO** no registro de cada action (chaves do world-state), não inferidas.
- GOAP, na origem (jogos), opera sobre um world-state simbólico (fatos chave-valor), NÃO sobre tipos. A amarração a tipos JVM é açúcar do Embabel. O Nível 3 fala com a camada data-oriented por baixo desse açúcar — e fica mais fiel ao GOAP clássico.
- Modos de execução: `focused` (código pede função), `closed` (plataforma escolhe agente conhecido), `open` (plataforma monta agente sob medida para a intenção).

## Arquitetura alvo (Maven + Nível 3)

- **Casca imperativa = uma classe Java `@SpringBootApplication`** (compilada pelo maven-compiler-plugin). É o `-main`. No startup chama o Clojure para registrar o agente no `AgentPlatform`. Mantê-la burra.
- **Núcleo Clojure, sem AOT.** Os `.clj` vão no jar como fonte/resource e são carregados em runtime. Toda a lógica, o world-state e os playbooks vivem aqui, como dados e funções puras.
- **Domain types em Java** SOMENTE se o LLM precisar preencher objetos via JSON schema (anotações Jackson `@JsonClassDescription`/`@JsonPropertyDescription`). Se o Nível 3 permitir mapas direto no I/O do LLM, evitar até isso.
- **Sem `gen-class`**, salvo a ponte única do cenário GO-WITH-BRIDGE.

## Build (Maven)

O `pom.xml` deve:
- importar `com.embabel.agent:embabel-agent-dependencies` (`<type>pom</type>`, `<scope>import</scope>`) → versões de Spring AI/MCP resolvidas sozinhas pela BOM;
- declarar o repositório `spring-milestones` (`https://repo.spring.io/milestone`);
- usar maven-compiler-plugin para a casca Java + domain types;
- usar clojure-maven-plugin **SEM AOT** (presente pelos goals `clojure:repl`/`clojure:test`; a fonte `.clj` é empacotada como resource e carregada em runtime);
- usar spring-boot-maven-plugin para run/empacotamento.
- Confirmar a versão mais recente de `embabel-agent-starter` e do `clojure-maven-plugin`.

## Fluxo REPL

- Maven é a autoridade de build; gerar o classpath dele para o nREPL do editor.
- Subir o `AgentPlatform` uma vez; redefinir funções de action com `defn` e atualizar o registry de playbooks (um atom) ao vivo — sem restart, porque nada disso é AOT.

## Pegadinhas (vão morder)

- Repositório Spring Milestones obrigatório (dependência transitiva `mcp-bom`).
- A casca `@SpringBootApplication` precisa que o pacote do agente/ponte esteja no `scanBasePackages`, senão a plataforma sobe vazia.
- No Nível 3, o "shape" das pré/pós-condições que o planner lê precisa ser **declarado como dado** no registro da action — garantir que seja explícito, nunca dependente de tipo.
- AOT de Clojure proibido (exceto a ponte única). Se algo exigir AOT, PARAR e reavaliar — provavelmente é sinal de que se escorregou para o Nível 2.

## Showcase / prova de valor (Task 2)

Agente de auto-remediação em open mode:
- world state = mapa imutável de fatos do sistema (ex.: `{:db/connection-pool :saturated}`);
- playbooks = dados `{:id :pre :effects :fn}` num atom-registry;
- planner GOAP ramifica sobre estados imutáveis (structural sharing torna cada fork barato);
- LLM emite um playbook novo como EDN → `read-string` + `swap!` no registry → o planner em execução já o considera, **sem restart**.

Isto é o que justifica Clojure no Embabel. Nos outros caminhos, é só preferência pessoal.

## Convenções de código

- Funções puras, dados imutáveis, keywords namespaced no world-state.
- Casca imperativa isolada e mínima; zero lógica de negócio nela.
- Testes: núcleo puro testado sem Spring; testes de integração sobem o contexto.

## Ambiente

- `OPENAI_API_KEY` obrigatório; `ANTHROPIC_API_KEY` recomendado.
- Embabel via Maven Central: `com.embabel.agent:embabel-agent-starter` (confirmar versão).
- Docker Desktop + MCP tools (Brave Search, Fetch, Puppeteer, Wikipedia) para exemplos com web.

## Layout (provisório)

```
pom.xml
src/main/java/com/example/embabelclj/App.java        ; @SpringBootApplication, -main, casca
src/main/java/com/example/embabelclj/domain/         ; domain records Jackson (se necessário)
src/main/clojure/embabel_clj/core.clj                ; núcleo funcional (fns puras)
src/main/clojure/embabel_clj/agents.clj              ; actions/goals/conditions como dados
src/main/clojure/embabel_clj/playbooks.clj           ; registry (atom) + hot-load de EDN
src/main/clojure/embabel_clj/register.clj            ; ponto chamado pela casca p/ registrar no AgentPlatform
```
