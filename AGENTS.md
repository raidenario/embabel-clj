# CLAUDE.md — embabel-clj

**BIBLIOTECA** Clojure data-driven sobre o Embabel (framework de agentes GOAP
na JVM, Kotlin/Spring, de Rod Johnson). O usuário escreve agentes como mapas;
a lib fabrica os objetos Kotlin/Java e sobe o Spring — zero interop no projeto
dele. (Evoluída em 08/jul/2026 do experimento-app original; o app antigo vive
em `examples/app-caminho-b` como registro histórico.)

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
test/embabel_clj/{core,schema,specs,platform}_test.clj
examples/nature/                         ; flagship: 1 ns, zero interop
examples/app-caminho-b/                  ; app pré-biblioteca (histórico)
samples/                                 ; imagens das demos
```

- `interop.clj` = TODO proxy/reify (o "imposto pago uma vez"). Assinaturas
  VERIFICADAS por javap/reflexão contra embabel-agent-api 0.4.0 E 1.0.0 GA —
  não chutar interop nova: javap primeiro (jars em
  `~/.m2/repository/com/embabel/`). API que muda entre versões é resolvida
  por reflexão uma vez (qos-ctor; llm-by-name no schema.clj).
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

## Comandos

```
clojure -M:test            # 14 testes / 54 assertions (jars reais, sem Spring)
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
- PlannerType no 0.4.0 já tem GOAP, UTILITY e SUPERVISOR (`:planner` no
  process-options).
- **Value class Kotlin (IoBinding): membros manglados têm HÍFEN literal**
  (`constructor-impl`, `box-impl`) e o interop do Clojure munga hífen→
  underscore — acesso SÓ por java.lang.reflect (cacheado em `interop.clj`).
- **Camada TIPADA provada E2E**: `defrecord` É domain type — action
  `:outputs [Produto]` + goal `:inputs [Produto]` e o A* encadeia por TIPO,
  zero condição string. Binding default = "it".
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
