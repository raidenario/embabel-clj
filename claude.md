# CLAUDE.md — embabel-clj

**BIBLIOTECA** Clojure data-driven sobre o Embabel (framework de agentes GOAP
na JVM, Kotlin/Spring, de Rod Johnson). O usuário escreve agentes como mapas;
a lib fabrica os objetos Kotlin/Java e sobe o Spring — zero interop no projeto
dele. (Evoluída em 08/jul/2026 do experimento-app original; o app antigo vive
em `examples/app-caminho-b` como registro histórico.)

## Fonte da verdade sobre o Embabel

**https://hub.embabel.com é a doc oficial e a base de tudo.** Ordem de consulta:
hub → fonte local (`../embabel-agent/`, `javap` nos jars do `~/.m2`) → **nunca** memória
do modelo (a API mudou entre 0.4 → 0.5 → 1.0 e o conhecimento paramétrico confunde as
versões). Ao escrever qualquer coisa pública, usar o vocabulário do hub
(**Flow / Steps / Domain**), não só o daqui (action/goal/condição). Ver
[`../CLAUDE.md`](../CLAUDE.md).

## Decisões travadas (não reabrir)

- **É biblioteca, não app.** Raiz = deps.edn + tools.build (`build.clj`).
  Coordenada: `io.github.raidenario/embabel-clj`. Maven só no exemplo legado.
- **100% Clojure-fonte, SEM casca Java** (técnica do projeto fabulista,
  exigida pelo usuário em 08/jul): a classe `@SpringBootApplication` é uma
  **gen-class VAZIA com a anotação via metadata** (`embabel-clj.boot-class`),
  compilada SOB DEMANDA em runtime num temp dir e definida no
  DynamicClassLoader (`platform/boot-class`) — sem src-java, sem javac, sem
  `:deps/prep-lib`, sem target/classes no classpath. TCCL é apontado pro
  baseLoader antes do `SpringApplication.run`.
- **NO-AOT de Clojure no BUILD, sempre.** Os `.clj` vão crus no jar; a única
  compilação é a da boot-class, em runtime, pela própria lib.
- **Nível 3 data-oriented**: condições booleanas nomeadas + slots; o modelo
  TIPADO (IoBindings/domainTypes) é roadmap, exposto como dado quando vier.
- **malli nas duas fronteiras**: schemas `:closed` validam a API da lib
  (typo = erro humanizado na construção) e a saída EDN do LLM
  (`schema/create-edn!` com retry de auto-cura).
- **Deps duras mínimas**: clojure + embabel-agent-starter + malli. nrepl e
  provider de LLM são do usuário (aliases `:dev`/`:openai`). cheshire saiu.
- **Propriedades do `platform/start!` viram ARGS** (`--k=v`, precedência
  máxima). `builder.properties()` é defaultProperties (precedência MÍNIMA) e
  PERDE para defaults embutidos do framework (ex.: `embabel.models.default-llm`)
  — bug real encontrado na demo nature.

## Layout

```
deps.edn build.clj                       ; biblioteca (pura fonte)
src/embabel_clj/boot_class.clj           ; gen-class anotada (a "classe Java" que não é)
src/embabel_clj/{core,platform,blackboard,schema,specs,interop,dev}.clj
src/embabel_clj/{tools,events,interceptors,guardrails,termination}.clj  ; extensão
src/embabel_clj/process_store.clj        ; AgentProcessRepository -> log EDN
src/embabel_clj/{hitl,states}.clj        ; human-in-the-loop e escopo por estado
src/embabel_clj/rag.clj                  ; RagService como mapa de fns (dep OPT-IN)
test/embabel_clj/{core,schema,specs,platform,tools,events,interceptors,guardrails,termination,process_store,hitl,states,rag,prompt,stream}_test.clj
examples/nature/                         ; flagship: 1 ns, zero interop
examples/process-store/                  ; E2E da tese B: 3 rodadas, log lido pós-stop!
examples/app-caminho-b/                  ; app pré-biblioteca (histórico)
samples/                                 ; imagens das demos
```

- `interop.clj` = interop do NÚCLEO (action/goal/agent/condition/qos/IoBinding)
  + os helpers de reflexão compartilhados (`props`/`simple-name`, a projeção
  genérica objeto→mapa). Cada PONTO DE EXTENSÃO user-facing tem ns própria com
  o seu reify — `tools.clj`, `events.clj`, `interceptors.clj` — porque é o que
  o usuário lê. Assinaturas VERIFICADAS por javap/reflexão contra
  embabel-agent-api 0.4.0, 0.5.0-SNAPSHOT E 1.0.0 GA — não chutar interop nova:
  javap primeiro (jars em `~/.m2/repository/com/embabel/`). API que muda entre
  versões é resolvida por reflexão uma vez (qos-ctor; llm-by-name no schema.clj;
  planner-types no core.clj).
- `core.clj` = fachada pública (action/goal/condition/agent/agent-from-ns/
  deploy!/run!/result/process-options). `condition` = **ComputedBooleanCondition**
  (classe do próprio framework; ctor `(String, double, Function2<OperationContext,
  Condition, Boolean>)`) — o @Condition lazy, PROVADO E2E: goal cuja única pre
  é a derivada foi alcançado sem nenhuma action setá-la.
- QoS default da lib = **FAIL-FAST (1 tentativa)**, lição do fabulista (o
  default do framework 5×10s→60s vira agonia num bug; 1º arg do ActionQos é
  maxAttempts, NÃO retries — 0 = zero execuções). `:retries` opt-in por action.
- Tags de metadata: `:action/pre|post|cost|rerun|llm|description` e
  `:condition/name|cost`; `agent-from-ns` registra as fns como VARS
  (redef no REPL vale sem re-deploy).
- **Pontos de extensão como fn** (Onda 1, 25/jul/2026): `events.clj`
  (AgenticEventListener via `:listeners` do run options — entra pelo
  `ProcessOptions.withListeners`, que existe desde o 0.4.0: SEM bean Spring),
  `interceptors.clj` (ToolLoopInspector/Transformer + ToolCallInspector via
  `:tool-loop-inspectors`/`-transformers`/`:tool-call-inspectors` do
  `schema/ask`, que os instala no PromptRunner), `guardrails.clj`
  (UserInput/AssistantMessage via `:guardrails` do `schema/ask` →
  `PromptRunner.withGuardRails`) e `termination.clj` (EarlyTerminationPolicy via
  `:early-termination` do run options + StuckHandler como fn no `:stuck-handler`
  do AgentDef, que antes exigia instância + a terminação COOPERATIVA:
  `terminate-agent!`/`terminate-action!` graceful e `-now!` por exceção, com
  `termination-reason`/`termination-scope` para ler o throwable sem importar
  as classes do framework). O padrão que unifica os quatro:
  **interface pequena do Kotlin = mapa de fns no Clojure**, e o objeto que o
  framework passa vira mapa por `interop/props` (+ `:raw` com o original).
  Projeção GENÉRICA de propósito — os tipos concretos mudam a cada release; o
  que a lib promete é a FORMA, não o conjunto de chaves.

## Comandos

```
clojure -M:test            # 98 testes / 439 assertions (jars reais; hitl/states rodam
                           #  contra AgentPlatform REAL via IntegrationTestUtils/dummyAgentPlatform
                           #  — vem no embabel-agent-api, sem Spring, sem LLM, sem chave)
clojure -M:probe-040:test  # a mesma suíte no 0.4.0 (e :probe-050 no Darwin)
clojure -T:build install   # jar puro-fonte + install no ~/.m2
cd examples/nature && clojure -M:run ../../samples/nature.jpg   # demo real (OPENROUTER_APIKEY)
```

Nada a compilar pós-clone: a boot class nasce em runtime no primeiro
`platform/start!`. Validado E2E em 08/jul/2026: hello-agent GOAP completo com
chave dummy (boot via gen-class runtime, sem gastar token), prova da condição
lazy consultada pelo planner, e demo nature real (GPT-4o visão via OpenRouter
→ EDN → malli).

## Pegadinhas verificadas (vão morder de novo)

- **base-url OpenRouter SEM `/v1`** (Spring AI acrescenta `/v1/chat/completions`;
  com `/v1` na base = 404 em HTML).
- **`:max-tokens` importa no OpenRouter**: ele PRÉ-AUTORIZA o teto contra o
  saldo; ausente vale o output máximo do modelo (16k no gpt-4o) → 402 em conta
  com pouco crédito. O `max_tokens` do models yml é METADADO; o teto real da
  requisição é LlmOptions (`ask`/`create-edn!` `:max-tokens`).
- **Slugs não-OpenAI exigem override de `models/openai-models.yml`** nos
  resources do APP (primeiro match do classpath vence); resolução byName.
- Modelagem GOAP 0.4.x: condição nunca setada = FALSE; sem `pre = NÃO x`
  (polos positivos); sem `:` em nome de condição; `:rerun? true` p/ actions
  trabalhadoras (`hasRun_<name>`); posts otimistas re-derivados; padrão gate.
- `Blackboard.set` NPEia com nil → `bb/put!` usa sentinela `:embabel-clj/none`.
- **PlannerType**: o 0.4.0 tem GOAP, UTILITY e SUPERVISOR; **HYBRID só a partir
  do 0.5.0**. Como a lib é NO-AOT (os `.clj` vão crus no jar), `PlannerType/HYBRID`
  literal derrubaria a CARGA da ns inteira sob `:probe-040`, não só a chamada —
  por isso `planner-types` resolve por `Enum/valueOf` num `try`, e o
  `process-options` lança erro nomeando os planners que existem naquela versão.
  Mesmo cuidado vale p/ QUALQUER campo estático novo do framework.
  O `:hybrid` só é útil com o goal `NIRVANA` (`ec/nirvana`) registrado junto do
  goal terminal real: ele escolhe por netValue e sai quando um goal fecha; o
  NIRVANA existe para NUNCA fechar (pre `__unobtanium__`).
- **Interceptors NÃO são novidade do 1.0** (premissa errada do PLANO): o pacote
  `api.tool.callback` completo e os três `PromptRunner.withToolLoop*`/
  `withToolCallInspectors` existem já no 0.4.0 — verificado por javap nos três
  jars. Idem `ProcessOptions.withListeners`. O que faltava era só a ponte
  Clojure.
- **Kotlin interface com método default É implementável por reify parcial**: o
  Clojure só emite os métodos que você lista, e os demais caem no default da
  interface (as do Embabel são compiladas com `jvm-default=all`, aparecem como
  `public default` no javap). É o que permite `{:after-llm-call f}` sem
  implementar os outros três hooks.
- **`AgenticEvent` é sealed interface** (tem `PermittedSubclasses` no bytecode):
  não dá para reify/Proxy um evento em teste. O jeito de testar offline é
  construir um evento CONCRETO (ex.: `ProgressUpdateEvent`) com um `AgentProcess`
  reify-ado só com `getId` — o que de quebra PROVA o skip-list da projeção: se
  ela tocasse em `history`/`statusReport`, estouraria AbstractMethodError.
- **`Tool` não tem `getName`** — o nome está em `.getDefinition.getName`
  (`ToolInfo`). Morde ao inspecionar `:tools` num contexto de interceptor.
- **Guardrails — o `isValid` do ValidationResult é IGNORADO no caminho do LLM.**
  `spi/support/guardrails/llmOperationGuardRails.kt/handleValidationResult` olha
  só a MAIOR SEVERIDADE ENTRE OS ERROS: `CRITICAL` lança
  `GuardRailViolationException`; `ERROR`/`WARNING`/`INFO` apenas logam. Logo
  `ValidationResult(false, [])` (inválido sem erros) **não faz nada** — nem log.
  Por isso `guardrails.clj` nunca emite resultado inválido sem ao menos um erro,
  e a severidade default da lib é `:critical`. (São QUATRO severidades —
  INFO/WARNING/ERROR/CRITICAL; o hub lista só três, ERROR fica de fora.)
- **`validate` é sobrecarga ERASED**: o `ContentValidator<T>` compila para
  `validate(Object, Blackboard)`, e as subinterfaces somam `validate(List,…)`,
  `validate(MultimodalContent,…)`, `validate(ThinkingResponse,…)` — todas com a
  mesma aridade. No `reify` é preciso hint **de parâmetro E de retorno**:
  `(^ValidationResult validate [_ ^Object c ^Blackboard bb] …)`. Sem o hint de
  retorno o erro é "Mismatched return type"; sem o de parâmetro, "Can't find
  matching overloaded method".
- As classes `com.embabel.common.core.validation.*` vêm **dentro do
  embabel-agent-api** (não do embabel-common-core) — nas três versões.
- Métodos DEFAULT do Kotlin têm checagem de não-nulo nos parâmetros: passar
  `nil` como `Blackboard` em teste estoura NPE ANTES de chegar na sua fn. Use
  `(reify Blackboard)` como stub.
- **O `:budget` JÁ É três EarlyTerminationPolicies** compostas por `firstOf`
  (`Budget.earlyTerminationPolicy()` = maxActions + maxTokens + hardBudgetLimit;
  o método é MEMBRO do Budget, não extension). Nunca foi "budget sim, policy
  não" — faltava SOMAR uma customizada, e o seam é
  `ProcessOptions.withAdditionalEarlyTerminationPolicy` (compõe, não substitui →
  no `cond->` do process-options ele TEM que vir depois do `:budget`). A quarta
  embutida é `ON_STUCK`.
- **`@JvmStatic val` de companion vira MÉTODO, não campo**:
  `EarlyTerminationPolicy/ON_STUCK` falha; o certo é
  `(EarlyTerminationPolicy/getON_STUCK)`. Mesma família do
  `(.getVALID ValidationResult/Companion)`. `@JvmStatic` ≠ `@JvmField` (o
  `AgenticEventListener/DevNull` é `@JvmField` e aí o acesso por campo funciona).
- `EarlyTerminationPolicy.shouldTerminate` devolve **null para "continua"** —
  retorno nullable do Kotlin; um `when` em Clojure já dá isso de graça.
- **Streaming: a lib estava no seam errado.** `(.ai oc)` devolve
  `OperationContextAi`, que **não** é `StreamingPromptRunner`; `(.promptRunner oc)`
  devolve `DelegatingStreamingPromptRunner`, que é (medido em teste). O `ask`
  usa `.ai` e por isso nunca streamou — não faltava feature no framework, faltava
  usar a outra porta. `schema/stream` usa `.promptRunner`, e a cadeia
  `.withLlm`/`.withPromptContributors` PRESERVA a capacidade (também em teste).
  Dois guards: o runner ainda é streaming? e `.supportsStreaming` — sem o
  segundo, `.streaming()` lança `UnsupportedOperationException` do Kotlin
  ("Check supportsStreaming() before calling streaming()"). `stream-seq`
  converte o `Flux` em seq para não obrigar o usuário a importar Reactor.
- **`->oc` devolve o PRÓPRIO ctx quando não há `:oc`**, então um guard
  `(nil? oc)` deixa passar um mapa e a falha vira "No matching field found:
  promptRunner". Checar `instance? OperationContext`. (O `ask` ainda tem o
  guard antigo — vale alinhar.)
- **RAG é dep OPT-IN e tem duas armadilhas.** `com.embabel.agent.rag.*` vive no
  `embabel-agent-rag-pipeline` (resolve do Central em 1.0.0), NÃO no starter —
  por isso `rag.clj` só carrega se o usuário trouxe o módulo (alias `:rag`).
  (a) **`ChunkImpl` é package-private**: o javap mostra o construtor público mas
  a CLASSE não é — instanciar do Clojure dá IllegalAccessError; a fábrica é
  `Chunk/create`, e a ordem dela é `(text, parentId, metadata, id, urtext)`,
  não `(id, text)` — chamar errado NÃO dá erro, só põe o id dentro do texto.
  (b) **`RagRequest`/`RagResponse` têm parâmetros não-nulos COM default do
  Kotlin**: passar `nil` estoura "Parameter specified as non-null is null". O
  jeito é o construtor sintético com MÁSCARA de bits (bit N = "use o default do
  parâmetro N") — `RagRequest` usa 120, `RagResponse` usa 56. Padrão
  generalizável para qualquer data class Kotlin com defaults.
- **PromptContributor não precisa de reify da interface**: o framework expõe
  `PromptContributor/fixed(String)` e `/dynamic(Function0<String>)`, e `role`/
  `promptContributionLocation` são DEFAULT em `PromptElement` (`role` é nulável).
  Uma fn Clojure de zero args vira contributor dinâmico — recalculado a cada
  chamada, provado em teste. Idem `Thinking`: `withExtraction()`/
  `withTokenBudget(int)` são estáticos, e `:thinking` é só mais uma chave do
  `ask` que liga o caminho de LlmOptions.
- **Toda action roda DENTRO do `ActionRunner.Companion.execute` do framework**
  (campo público estático, existe desde o 0.4.0). Antes a lib montava
  `ActionStatus(0ms, SUCCEEDED)` na mão, e isso custava: WAITING impossível (o
  `AwaitableResponseException` do HITL vazava como erro), sinais de controle
  dependendo de escapar por acidente, e runningTime SEMPRE 0ms em toda action —
  visível no log do process-store.
- **HITL exige processo durável.** `hitl/confirm!`/`ask!`/`wait-for!` estacionam
  o processo em WAITING; quem responde depois usa `answer!` + `resume!`, na
  MESMA instância (id e history preservados — contrato fixado pelo próprio
  framework em `AgentProcessResumeEventContractTest`). Com o repositório padrão
  (in-memory, janela 1000, despejo) um processo que espera muito some: use
  `process-store`. Processo `ephemeral` não espera — o
  `AbstractAgentProcessRepository` recusa e loga.
- **`@State` funciona num tipo Clojure via metadata** (`(deftype ^{State true} X [..])`)
  e o `isStateType` do framework reconhece. Mas o COMPORTAMENTO (esconder os
  outros estados) vive no `MultiTransformationAction` do modelo anotado, por
  onde a lib não passa — por isso a transição é explícita: `states/enter!`.
  Ele liga por BINDING NOMEADO, não por `addObject`: medido que `addObject` só
  anexa em `_entries` e o planner lê o binding, então um estado só adicionado é
  invisível para `:inputs`/`:outputs` e a action seguinte roda duas vezes.
- **`AgentProcessRepository` NÃO se troca por `:beans`** — o `@Bean
  agentProcessRepository` do `AgentPlatformConfiguration` é **incondicional**
  (sem `@ConditionalOnMissingBean`, diferente de `LoggingAgenticEventListener`/
  `ColorPalette`). Um `registerSingleton` cria um SEGUNDO candidato do mesmo
  tipo e o `SpringContextPlatformServices`, que injeta por tipo, estoura
  `NoUniqueBeanDefinitionException`. O caminho é `BeanDefinition` com
  `setInstanceSupplier` + `setPrimary true` e **nome diferente** (o Spring Boot
  proíbe override de definição por padrão) — é o que `process-store/as-primary-bean`
  faz, provado E2E contra o autoconfigure real em `examples/process-store`.
  Mesmo cuidado para qualquer outro bean incondicional do framework.
- **`AgentProcess` se recusa a serializar**: é anotado
  `@JsonSerialize(using = ComputerSaysNoSerializer::class)` (o `Agent` também).
  Persistir processo é projetar campo a campo, não jogar no ObjectMapper. E o
  framework chama `agentProcessRepository.update(this)` a CADA tick
  (`AbstractAgentProcess.tick`) — é o gancho que dá um registro por tick de graça.
- **`:beans` do `platform/start!` é a saída para o "sem casca Java"**: o
  `:sources` do Spring exige CLASSE anotada, mas `ApplicationContextInitializer`
  roda antes do refresh e `registerSingleton` aceita objeto pronto. Como o
  Embabel resolve `LlmService`/`EmbeddingService`/`OptionsConverter` **por
  TIPO**, um `reify` da interface registrado assim é indistinguível de um
  `@Bean` (provado em teste com `GenericApplicationContext`: `getBean(Class)`
  acha o reify).
- **Async**: não existe `invokeAsync` no AgentPlatform — o par é
  `createAgentProcess(agent, options, bindings)` + `start(process)` →
  `CompletableFuture<AgentProcess>` (`start` é método DEFAULT da interface, dá
  para reify-ar um AgentPlatform falso em teste com só esses dois métodos).
- `Blackboard.last(Class)` é método DEFAULT que varre `getObjects()` — um
  `reify Blackboard` com só `getObjects` já faz `bb/last-of` funcionar
  (usado no teste da camada tipada).
- `Termination.terminateAgent/terminateAction` são extension functions do
  Kotlin: viram MÉTODOS ESTÁTICOS numa classe `Termination` que recebem o
  `ProcessContext` como 1º arg. `TerminateAgentException`/`TerminateActionException`
  descendem de `TerminationException` (RuntimeException com `getReason()`).
- **Value class Kotlin (IoBinding): membros manglados têm HÍFEN literal**
  (`constructor-impl`, `box-impl`) e o interop do Clojure munga hífen→
  underscore — acesso SÓ por java.lang.reflect (cacheado em `interop.clj`).
- **⚠️ NÃO use `defrecord` na camada TIPADA — use `deftype`.** Correção de uma
  afirmação antiga deste arquivo ("defrecord É domain type, provado E2E"): o
  E2E passava porque tinha UM tipo só, e com um tipo só a resposta é a mesma
  certa ou errada. Com dois tipos a camada tipada **desmonta**, por duas causas
  independentes, ambas com teste em `states_test.clj`:
  1. `BlackboardWorldStateDeterminer.determineCondition` curto-circuita:
     *"If the variable is a map, we are satisfied by having the name bound
     rather than checking the type"* — com um `TODO may want to add type
     checking here` no fonte. **Todo `defrecord` é `java.util.Map`**, então
     QUALQUER condição `it:Tipo` vira TRUE. Medido: goal `:inputs [Fatura]`
     fecha COMPLETED com um `Produto` no blackboard; com `deftype`, STUCK
     (correto).
  2. `.equals` de `defrecord` é igualdade de MAPA, **sem o tipo** —
     `(.equals (->A 1) (->B 1))` é `true` no Java enquanto `=` do Clojure é
     `false`, e os `hashCode` batem. Como `Blackboard.hide` guarda os
     escondidos num `Set`, esconder um record esconde records de OUTRO tipo com
     os mesmos campos, e os iguais adicionados DEPOIS.
  `deftype` não é Map e tem igualdade por identidade — resolve os dois. Binding
  default = "it".
- **Tools sem anotação**: `Tool/create` + `Tool$Handler` reify +
  `Tool$InputSchema` reify (toJsonSchema = malli→JSON via Jackson, que já
  está no classpath — nada de dep JSON nova). PROVADO com gpt-4o-mini real
  chamando fn Clojure via OpenRouter. Teste offline = round-trip pelo
  `SpringToolCallbackAdapter` (o mesmo caminho da chamada real).
- Jackson devolve LinkedHashMap/ArrayList e clojure.walk NÃO desce em coleção
  Java — `tools/parse-json` converte recursivamente (java->clj).
- MCP no 0.4.0 = ToolGroups da plataforma: action `:tool-groups [:web]`
  (→ ToolGroupRequirement) / prompt `:tool-groups ["web"]`; os grupos vêm da
  config padrão do Embabel (ex.: Docker MCP gateway).
- **NVIDIA API** (build.nvidia.com) é OpenAI-compatível: base-url
  `https://integrate.api.nvidia.com` (SEM /v1), key via env; models yml com
  slugs NVIDIA. VERIFICADO 09/07: no free tier o 70B/gpt-oss-120b dão TIMEOUT
  (fila) e o Embabel reexecuta ~10× × 60s = agonia; o `llama-3.1-8b-instruct`
  responde em ~2s. Lição p/ a lib: `schema/ask`/`create-edn!` aceitam
  `:timeout-s` (LlmOptions.withTimeout) p/ falhar rápido em vez de retry-storm.
- **create-edn! com schema ANINHADO + modelo pequeno**: o `edn-prompt`
  genérico só lista os campos de topo; um 8B precisa VER o shape
  (`{:cores [{:fruta ... :cor ...}]}`). Passe um `:prompt` explícito com o
  formato exato; o `:schema` segue validando. (Modelo grande tolera o genérico.)
- Ler uma condição LAZY do blackboard no fim (`bb/condition?`/`result`)
  devolve o valor ARMAZENADO (ausente = false), não a avaliação da
  ComputedBooleanCondition — quem a avalia é o planner, na determinação de
  world state.

## Genealogia da técnica

A eliminação da casca Java e o glue de 2ª geração vêm de dois irmãos deste
repo: **fritas/fabulista** (`fabulista.boot-class`/`boot.clj` — gen-class
anotada + compile runtime + defineClass; ações puras devolvendo efeitos) e
**fritas/beautiful-linkedin-clj-dev** (`agents/embabel.clj` — adapter 2ª
geração: ctx {:fato :polo? :ask :log!}, efeitos {:fatos :polos}, cadeia de
modelos com gate, ComputedBooleanCondition, QoS fail-fast). Roadmap: adotar
também as AÇÕES PURAS (efeito-como-dado) como modo opcional da lib.

## Base de conhecimento

As notas de campo dos dois projetos-mãe (agendas/reconciliador e
beautiful-linkedin/email-hunter) vivem no Obsidian:
`C:\Users\jpedr\OneDrive\Documentos\Obsidian Vault\embabel clj\` —
em especial `clojureS2embabel.md` (receita genérica verificada) e
`beautiful-agents-clj.md` (quando GOAP se paga). Consultar antes de
reimplementar qualquer padrão.
