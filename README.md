# embabel-clj

**[Embabel](https://github.com/embabel/embabel-agent) agents as Clojure data.**

[Embabel](https://github.com/embabel/embabel-agent) (by Rod Johnson) plans agent
flows with GOAP: goals, actions, pre/post-conditions, costs — searched by A*,
replanned after every action. That world model *is a data structure*. This
library lets you write it as one:

```clojure
(require '[embabel-clj.core :as ec]
         '[embabel-clj.platform :as platform]
         '[embabel-clj.blackboard :as bb])

(def hello
  (ec/agent
   {:name        "hello"
    :description "says hi"
    :goals       [{:name "done" :pre [:greeted?] :value 1.0}]
    :actions     [{:name "greet" :post [:greeted?]
                   :fn (fn [ctx]
                         (bb/put! ctx :greeting "Olá do embabel-clj!")
                         (bb/set-condition! ctx :greeted? true))}]}))

(let [{:keys [platform]} (platform/start! {})]   ; Spring + AgentPlatform, no Java shell
  (ec/deploy! platform hello)
  (-> (ec/run! platform hello {})
      (ec/result {:slots [:greeting] :conditions [:greeted?]})))
;; => {:status "COMPLETED"
;;     :slots {:greeting "Olá do embabel-clj!"}
;;     :conditions {:greeted? true}}
```

No `App.java`, no Spring Boot pom, no hand-rolled `proxy`/`reify` interop layer
in *your* project. The interop tax is paid once, inside the library — and the
library itself is **pure Clojure source**: even the `@SpringBootApplication`
class is an empty `gen-class` carrying the annotation as metadata, compiled on
demand at runtime and defined straight into Clojure's DynamicClassLoader. No
`src-java`, no `javac`, no prep step.

Status: **experimental**, verified against `embabel-agent 0.4.0` / Spring Boot
3.5 / JDK 21 / Clojure 1.12. Extracted from two real Embabel-from-Clojure
projects (a calendar-mirror reconciler and a corporate e-mail hunter).

## Why Clojure for Embabel agents?

- **The plan graph is written, not modeled.** Conditions are keywords, actions
  are maps, the whole agent is one literal you can `pprint`, diff, store as EDN
  and generate.
- **malli guards both borders.** The same schema-as-data validates *your* agent
  definitions (closed schemas: a typo like `:cots` fails at construction with a
  humanized error) and the *LLM's* output (`create-edn!` parses EDN, coerces
  types and re-asks the model with the validation errors — a self-healing loop).
- **REPL against a live platform.** `agent-from-ns` registers your action fns
  as **vars**: redefine a `defn`, run again — no rebuild, no redeploy.
- **Lazy conditions included.** The library builds
  `ComputedBooleanCondition`s (the framework's own class), so you get the
  `@Condition` semantics — evaluated on demand by the planner, no stale-state
  window, no `:after` refresh hook. Verified end-to-end: a goal whose only
  precondition is a lazy condition is achieved.

## Install

```clojure
;; deps.edn
{:deps {io.github.raidenario/embabel-clj
        {:git/url "https://github.com/raidenario/embabel-clj"
         :git/sha "..."}                       ; or {:local/root "..."}
        ;; pick an LLM provider starter (the lib doesn't force one):
        com.embabel.agent/embabel-agent-starter-openai {:mvn/version "0.4.0"}}

 ;; REQUIRED: tools.deps does not inherit repos from dependencies.
 :mvn/repos {"spring-milestones" {:url "https://repo.spring.io/milestone"}
             "clojars"           {:url "https://repo.clojars.org/"}}}
```

Nothing to compile, ever: git deps, `:local/root` and the jar all work as-is
(the boot class is generated at runtime on the first `platform/start!`).

Maven users: `clojure -T:build install` publishes
`io.github.raidenario:embabel-clj:0.1.0` to your local `~/.m2`.

## Concept map

| Embabel (Kotlin/Java)                  | embabel-clj                                    |
|----------------------------------------|------------------------------------------------|
| `@Agent` class                          | `(ec/agent {...})` — one map                   |
| `@Action fun` + annotation fields       | `{:name ... :pre [...] :post [...] :cost 0.1 :rerun? true :fn (fn [ctx] ...)}` |
| `@Condition fun` (lazy, on-demand)      | `{:name :co/needs-evidence? :fn (fn [ctx] ...)}` under `:conditions` |
| `@AchievesGoal` on terminal action      | `{:name "done" :pre [...] :value 1.0}` under `:goals` |
| Blackboard `set`/`get`/`setCondition`   | `bb/put!` `bb/fetch` `bb/set-condition!` `bb/set-conditions!` `bb/condition?` |
| `createObject<T>()` (Jackson data class)| `(schema/create-edn! ctx {:schema MalliSchema ...})` |
| `ProcessOptions` / `Budget` / planners  | `{:options {:budget {:cost 2.0 :actions 40 :tokens 200000} :planner :goap/:utility/:supervisor}}` |
| `AgentMetadataReader` (reads annotations)| `(ec/agent-from-ns 'my.ns {...})` (reads var metadata tags) |
| `@SpringBootApplication` + your pom     | `(platform/start! {:properties {...}})`        |

The metadata tags mirror the annotation model:

```clojure
(defn generate-verify
  "Generate candidate e-mails and verify them."
  {:action/pre  [:co/domain-known?]
   :action/post [:mail/verified?]
   :action/cost 0.2
   :action/rerun true
   :action/llm  true}          ; ctx gets :oc (LLM access) only when asked
  [ctx] ...)

(defn needs-evidence?
  {:condition/name :co/needs-evidence?}   ; lazy condition — no :after hook needed
  [ctx]
  (and (not (bb/condition? ctx :co/evidence-ready?))
       (or (bb/condition? ctx :co/domain-ambiguous?)
           (bb/condition? ctx :co/gen-empty?))))

(ec/agent-from-ns 'my.agents.hunter
  {:name "email-hunter" :description "..."
   :goals [{:name "email-found" :pre [:mail/verified?] :value 1.0}
           {:name "needs-review" :pre [:reviewed?] :value 0.3}]})
```

## The typed layer: records as domain types

Embabel's flagship idea — *types are planning signal* — works from Clojure:
a `defrecord` **is** a domain type. Declare what an action produces/consumes
and the planner chains by type, no string conditions at all:

```clojure
(defrecord Produto [id nome])

(ec/agent
 {:name "typed" :description "encadeia por tipo"
  :goals   [{:name "done" :inputs [Produto] :value 1.0}]   ; "a Produto exists"
  :actions [{:name "produz" :outputs [Produto]             ; "produces a Produto"
             :fn (fn [ctx] (bb/put! ctx "it" (->Produto 1 "caneca")))}]})
;; planner: produz -> goal done achieved, COMPLETED — chained purely by TYPE
```

`:inputs`/`:outputs` accept a Class (default binding `"it"`), a string
`"name:pkg.Type"`, or `{:name "pedido" :type Pedido}`. Works on goals too
(`:inputs` = the typed precondition). Interop note: `IoBinding` is a Kotlin
value class whose mangled members have literal hyphens — Clojure interop
can't call those; the library goes through `java.lang.reflect` once, cached.

## Clojure fns as LLM tools

No `@Tool` annotation needed — Embabel 0.4 has a functional tool API
(`Tool/create`), and the library bridges it to malli: one schema describes
the arguments (becomes the JSON Schema the model sees), validates/coerces the
call, and your fn gets a plain map:

```clojure
(def soma
  (tools/tool
   {:name        "soma"
    :description "Soma dois números."
    :schema      [:map
                  [:a {:description "primeira parcela"} :double]
                  [:b {:description "segunda parcela"} :double]]
    :fn          (fn [{:keys [a b]}] (+ a b))}))

;; inside an action {:llm? true}:
(schema/ask ctx {:llm "openai/gpt-4o-mini" :max-tokens 200
                 :tools [soma]
                 :prompt "Use a tool soma para calcular 2.5 + 4.25."})
;; => "6.75"  (verified live via OpenRouter: the model called the Clojure fn)
```

Errors are honest: invalid args come back to the model as a readable
`Tool.Result/error` (it can self-correct), and your fn's exceptions never
kill the plan. For **MCP / platform tool groups**, actions declare
`:tool-groups [:web]` (→ `ToolGroupRequirement`; `CoreToolGroups`: web, math,
maps, github, browser-automation) and prompts can pull them with
`{:tool-groups ["web"]}` — the groups themselves are provided by the
platform (e.g. Docker MCP servers via Embabel's standard configuration).

## Structured LLM output with malli

One schema is the whole contract — it generates the prompt, validates the
response, coerces the types, and re-asks on failure:

```clojure
(def Insights
  [:map
   [:resumo    {:description "2-3 sentence summary"} :string]
   [:bioma     {:description "Identified biome"} :string]
   [:confianca {:optional true :description "0.0 to 1.0"}
    [:double {:min 0.0 :max 1.0}]]])

;; inside an action tagged {:action/llm true}:
(schema/create-edn! ctx
  {:schema     Insights
   :llm        "openai/gpt-4o"
   :image      (bb/fetch ctx :image)       ; multimodal
   :max-tokens 1200                        ; sent as real LlmOptions
   :retries    1                           ; re-ask with humanized errors
   :prompt     (schema/edn-prompt Insights {:preamble "You are a field naturalist."})})
;; => {:resumo "..." :bioma "Taiga" :confianca 0.8}   ; validated + coerced
```

`schema/json-schema` derives a JSON Schema from the same value (tools/MCP).
`:ask-fn` injects a fake transport for tests — no LLM required.

## Booting the platform

```clojure
(platform/start!
 {:properties {:embabel.agent.platform.models.openai.base-url "https://openrouter.ai/api"
               :embabel.agent.platform.models.openai.api-key  (System/getenv "OPENROUTER_APIKEY")
               :embabel.models.default-llm "openai/gpt-4o-mini"}})
```

Properties are passed as command-line args (`--k=v`, highest precedence), so
what you set always wins over framework defaults. `:web :none` (default) starts
no Tomcat; `stop!` closes the context; `await!` parks the main thread for
fat-jar deployments. Your own Spring components go in `:sources [MyConfig]`.

### Provider field notes (hard-won)

- **OpenRouter base-url must NOT include `/v1`** — Spring AI appends
  `/v1/chat/completions` (with `/v1` you get `/api/v1/v1/...` → an HTML 404).
- **Non-OpenAI model names need a `models/openai-models.yml` override** in
  *your* resources (first classpath match wins); `:llm`/`default-llm` resolve
  **by name** against it. See `examples/nature/resources/models/`.
- **Set `:max-tokens`** on OpenRouter: it pre-authorizes the cap against your
  balance; absent, the model's max output (16k for gpt-4o) is reserved and a
  low-credit account gets `402` before generating a single token.
- The yml `max_tokens` is metadata; the *request* cap is `LlmOptions` — which
  is what `create-edn!`/`ask` `:max-tokens` sets.

## GOAP modeling rules (Embabel 0.4.x)

Learned in the source and in production-toy runs; the library enforces what it
can and documents the rest:

1. **A condition never set is FALSE** (not UNKNOWN). Model **positive poles**:
   `ok?` set true when there is no error — there is no `pre = NOT x`.
2. **No `:` in condition names** (it triggers the determiner's data-binding
   branch) — the schemas reject it; namespaced keywords are the happy path.
3. **`:rerun? true` for worker actions** — default `canRerun=false` injects a
   `hasRun_<name>` precondition and the action runs once per process.
4. **Optimistic `:post`**: declare the goal condition an action *may* achieve
   (so A* can chain to the goal), re-derive its real value at runtime.
5. **Gate pattern**: give worker actions a single positive gate
   (`work/unblocked?`) that every remedy action optimistically posts —
   otherwise A* retries the doomed action until the budget blows.
6. Budgets are first-class: `{:options {:budget {:cost 2.0 :actions 40}}}`
   replaces hand-rolled replan counters.
7. **Retries are fail-fast by default** (1 attempt). The framework default —
   5 attempts with 10s→60s backoff — turns a bug in an action body into
   minutes of agony. Opt in per action with `:retries 2` (or a full `:qos`
   map). Field note: `ActionQos`'s first arg is `maxAttempts`, *not* retries —
   0 means the action never runs.

## Examples

- [`examples/nature`](examples/nature) — the flagship: photo → GPT-4o vision →
  malli-validated EDN insights. One namespace, zero interop.
  `cd examples/nature && clojure -M:run ../../samples/nature.jpg`
  (needs `OPENROUTER_APIKEY`).
- [`examples/app-caminho-b`](examples/app-caminho-b) — the historical pre-library
  app (own pom, `App.java`, hand-rolled `agents.clj`): kept as the *before*
  picture of what this library deletes.

## Dev

```
clojure -M:test            # 14 tests / 54 assertions against the real jars
clojure -T:build install   # local Maven install (pure-source jar)
```

`embabel-clj.dev/start-nrepl!` embeds an nREPL next to a live platform (add
`nrepl/nrepl` to your dev deps).

## Roadmap

- Verify against Embabel 0.5.x ("Darwin") and re-check the 0.4.0 planner
  gotchas; expose 0.5 features (guardrails, retry-with-exception-classification,
  streaming, agent-skills) as data.
- Closed/Open execution modes (intent → agent selection / novel composition);
  async `start` + event listeners as fns; `StuckHandler` as a fn.
- Explicit `domainTypes` registration; `createObject`-style native data
  binding onto records.
- Pure actions mode (bodies return `{:fatos {...} :polos {...}}` effects —
  the 2nd-generation glue from the sibling projects).
- Playbook hot-load helper (LLM-proposed EDN remedies validated by malli into
  an atom registry) as an optional namespace.
- Publish to Clojars.

## License

Apache-2.0 (same as Embabel).
