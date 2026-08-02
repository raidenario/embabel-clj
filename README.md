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

Status: **experimental**, verified against `embabel-agent 1.0.0` (GA, on Maven
Central) / Spring Boot 3.5 / JDK 21 / Clojure 1.12 — with adaptive interop
keeping 0.4.0 and 0.5.x working (`:probe-040` / `:probe-050` run the suite
against them). Extracted from two real Embabel-from-Clojure projects (a
calendar-mirror reconciler and a corporate e-mail hunter).

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
        ;; version MUST match the lib's embabel-agent-starter (1.0.0):
        com.embabel.agent/embabel-agent-starter-openai {:mvn/version "1.0.0"}}

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
| `ProcessOptions` / `Budget` / planners  | `{:options {:budget {:cost 2.0 :actions 40 :tokens 200000} :planner :goap/:utility/:hybrid/:supervisor}}` |
| `AgentMetadataReader` (reads annotations)| `(ec/agent-from-ns 'my.ns {...})` (reads var metadata tags) |
| `AgenticEventListener` class            | `{:options {:listeners [(fn [ev] ...)]}}` — a fn, event as a map |
| `ToolLoopInspector` / `ToolLoopTransformer` / `ToolCallInspector` classes | `{:tool-loop-inspectors [{:after-llm-call (fn [c] ...)}]}` — maps of fns |
| `UserInputGuardRail` / `AssistantMessageGuardRail` classes | `{:guardrails [{:on :user-input :name "..." :fn (fn [c] ...)}]}` — or a malli schema |
| `EarlyTerminationPolicy` / `StuckHandler` classes | `{:options {:early-termination [(fn [proc] ...)]}}` · `{:stuck-handler (fn [proc] ...)}` |
| `ctx.terminateAgent(...)` / `TerminateAgentException` | `(term/terminate-agent! ctx "...")` · `(term/terminate-agent-now! "...")` |
| `createAgentProcess` + `start` (`CompletableFuture`) | `(ec/run-async! platform ag {:on-complete f})` · `(ec/join! fut)` |
| `blackboard.last(Foo.class)`            | `(bb/last-of proc Foo)` · `(bb/last-result proc)` |
| `@Bean` in a `@Configuration` class     | `{:beans {:meuServico (reify ...)}}` in `platform/start!` |
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

## Extension points as fns

Embabel's extension surface is a set of **small interfaces**: one or two methods,
called by the framework at a known moment. Kotlin makes you write a class for
each. In Clojure each one is a fn — and the objects the framework hands you are
projected as plain maps.

**Watching a run** (`AgenticEventListener`). The listener goes in through
`ProcessOptions`, so no Spring bean and no `@Configuration` is involved:

```clojure
(require '[embabel-clj.events :as events])

(ec/run! platform ag
  {:options {:listeners [(fn [ev] (println (:event ev) (:process-id ev)))]}})
;; :agent-process-creation  a1b2...
;; :agent-process-plan-formulated a1b2...
;; :action-execution-start a1b2...
;; ...
```

Every event arrives as `{:event <type-keyword> :scope :process/:platform
:timestamp ... :raw <the Java object> ...}` plus the event's own fields in
kebab-case. Split the two channels with `{:on-process f :on-platform g}`, or
record the whole run in one line:

```clojure
(let [[l log] (events/recording-listener)]
  (ec/run! platform ag {:options {:listeners [l]}})
  (map :event @log))
;; => (:agent-process-creation :agent-process-ready-to-plan :goal-achieved ...)
```

**Intercepting the tool loop** (`ToolLoopInspector`, `ToolLoopTransformer`,
`ToolCallInspector`). Inspectors observe; transformers rewrite what flows
through — their return value replaces the original, and `nil` keeps it:

```clojure
(require '[embabel-clj.interceptors :as ic])

(schema/ask ctx
  {:prompt "..." :tools [soma]
   :tool-loop-inspectors   [{:after-llm-call (fn [c] (log/info :usage (:usage c)))}]
   ;; the cheapest, safest transform: cap a chatty tool result
   :tool-loop-transformers [{:after-tool-result
                             (fn [c] (subs (:result-as-string c)
                                           0 (min 500 (count (:result-as-string c)))))}]
   :tool-call-inspectors   [{:after-tool-call (fn [c] (metrics! (:duration-ms c)))}]})
```

Typos are caught at construction (`:after-tool-results` is a closed-schema
error, not a hook that silently never fires). All three interfaces — and the
`PromptRunner.withToolLoop*` methods that install them — were verified with
`javap` against **0.4.0, 0.5.0 and 1.0.0**: this is not new 1.0 surface.

**Guarding the borders** (`UserInputGuardRail`, `AssistantMessageGuardRail`).
A guardrail is a validator with a name — and a malli schema is already exactly
that, so it can *be* the guard:

```clojure
(require '[embabel-clj.guardrails :as gr])

(schema/ask ctx
  {:prompt "..."
   :guardrails [{:on :user-input :name "no-secrets"
                 :fn (fn [{:keys [content]}]
                       (when (re-find #"sk-[A-Za-z0-9]{20,}" content)
                         "the prompt contains what looks like an API key"))}
                ;; no fn at all — the schema's own errors become the violations
                (gr/assistant-message {:name "length" :schema [:string {:max 4000}]})]})
```

Your fn returns a *verdict*, in whatever shape is natural: `nil`/`true` passes;
a string is one violation; a vector is several; a map gives you `:code` and
`:severity`; a `ValidationResult` passes straight through. Guards see the
`Blackboard`, so they can be context-aware.

Two field notes, both read out of `llmOperationGuardRails.kt` rather than the
docs. Only **`:critical`** aborts the call (`GuardRailViolationException`) —
`:error`, `:warning` and `:info` merely log, so `:critical` is the default
severity here. And enforcement looks at the *errors'* severity, ignoring the
`ValidationResult` boolean: "invalid with no errors" is silently a no-op
upstream, so this library never produces one — a bare `{:valid? false}` is
materialized as a critical violation.

**Stopping** (`EarlyTerminationPolicy`, `StuckHandler`). The reframing first:
the `:budget` this library always exposed *is* three early-termination policies
composed with `firstOf` — so what was missing was never "policies", it was the
ability to **add** one. `:early-termination` does exactly that, composing with
the budget rather than replacing it:

```clojure
(require '[embabel-clj.termination :as term])

(ec/run! platform ag
  {:options {:budget {:cost 0.10}
             :early-termination [(fn [proc] (when (done-enough? proc) "good enough"))]}})
```

`nil` means carry on; a string is the reason; `{:reason "..." :error? false}`
distinguishes "I chose to stop" from "I blew the budget". The four built-ins are
`term/on-stuck`, `max-actions`, `max-tokens`, `hard-budget-limit`.

`:stuck-handler` on the agent is now a fn too (it used to demand an instance).
It fires when the planner can't reach any goal, and it resolves by *side effect*
— the `AgentProcess` **is** a `Blackboard`, so `embabel-clj.blackboard` works
straight on it:

```clojure
{:stuck-handler (fn [proc]
                  (bb/set-condition! proc :fallback/ok? true)
                  "opened the fallback path")}   ; => REPLAN
```

Those two are the platform deciding from *outside*, once per tick. The third
kind is **cooperative**: the action body itself says it's done. Graceful signal
or immediate exception, agent scope or action scope — and none of it makes your
project import `com.embabel`:

```clojure
(term/terminate-agent! ctx "found what we came for")   ; graceful signal
(term/terminate-action-now! "no data for this branch") ; throws, unwinds now

;; reading it back, without instance? checks on framework classes:
(catch Throwable t
  (when-let [why (term/termination-reason t)]
    (log/info (term/termination-scope t) why)))       ; => :agent / :action
```

The graceful *action* variant only works inside an LLM action with a tool loop
(a plain transformation action has nowhere to observe the signal — use the
exception there), and the action needs `:rerun? true` to be resumable.

## Durable process history

Embabel has exactly one `AgentProcessRepository` implementation —
`InMemoryAgentProcessRepository`, with a default window of **1000 processes** and
oldest-first eviction. Process 1001 erases the first one's history, and all of it
dies with the JVM. Meanwhile the process **already carries its own log**
(`AgentProcess.history`) and the framework calls `update(this)` on **every tick**.

*The log already exists — it simply is not persisted.*

`embabel-clj.process-store` is the minimal answer: a decorating repository that
delegates the live object (identity, hierarchy, eviction) to a delegate and, on
every save/update/delete, projects the process into EDN and appends a record to a
log. The object stays ephemeral; the history becomes durable and queryable data.

```clojure
(require '[embabel-clj.process-store :as ps])

(def repo (ps/edn-repository {:file "target/processes.edn"}))
(platform/start! {:initializers [(ps/as-primary-bean repo)]})

;; later — including from another JVM, with the first one long dead:
(def log (ps/read-log "target/processes.edn"))
(ps/summary log)                          ; => {:records 128 :processes 7 :cost 0.0142 ...}
(ps/runs log)                             ; => one row per process
(ps/timeline log "a1b2...")               ; => that process's trajectory
(ps/as-of log "2026-07-30T18:00:00Z")     ; => what the system knew at 18:00
```

The bean must go in as **`@Primary`**: the framework's `@Bean
agentProcessRepository` is unconditional, so `registerSingleton` alone raises
`NoUniqueBeanDefinitionException`.

**The honest boundary, stated up front.** This makes the *history* durable. Making
the *process* resumable is a further step, and its price is a domain discipline:
only values go on the blackboard. `AgentProcess` is annotated
`@JsonSerialize(using = ComputerSaysNoSerializer::class)` — it refuses to
serialize — so `edn-safe` marks the boundary instead of hiding it: anything that
is not a value becomes a tombstone `{:embabel-clj/type … :embabel-clj/repr …}`.
A blackboard made purely of values comes out whole; one holding a connection
handle shows you exactly where the discipline broke.

That the resumption actually works is not a claim here — it is
[E1](#experiments), measured.

## Experiments

Claims in this repository come with numbers, and the numbers come from
experiments written so they could come out **negative**. Five live here and run
offline — no LLM key, no Docker:

```bash
cd experiments && ./run-all.sh     # or clojure -M:e1-crash / -M:e1-resume / :e2 ...
```

| | Question | Result |
|---|---|---|
| **E1** | Is a process *resumable*, or only its history durable? | **Resumable.** Phase 1 calls `System/exit 9` **inside** the third action — real JVM death, no `finally`, no shutdown hook. Phase 2 is a different OS process: it reads the log, restores blackboard and conditions, and runs `["pack" "ship"]` — not the two actions already done — to `COMPLETED`, with **0 tombstones**. |
| **E2** | What does the 1000-process window actually cost? | 1500 runs: the framework remembered **964 of 1416** unique ids — **452 forgotten**, eviction exactly FIFO. Log overhead **~0.5–0.8 ms/tick**, 4,765 B/process. |
| **E3** | How much of an agent is really data? | An agent as pure EDN (671 bytes) read in another JVM: **58 leaves, 54 data, 4 code references → 93.1% data**. Deleting an action *in the file* makes the goal unreachable (`STUCK`); changing one precondition reroutes the plan. Zero lines of code touched. |
| **E4** | Does a log-derived cache save tokens? | **Yes — 100%, with a label.** With a control arm: *without* cache the agent genuinely oscillates (12/20 runs took the original path); *with* cache, 20/20 identical and zero calls. The cache does not fail under non-determinism — it **erases** it. Excellent for audit and regression, unsafe as transparent memoisation. |
| **E5** | Can the log alone drive an LLM judge? | **Yes.** 40 runs, half with a planted routing bug that is invisible in the results (all 40 orders delivered, all `COMPLETED`) and visible only in the *path*: **8 detectable, 8 caught, 0 false positives.** |

Two findings nobody went looking for came out of these: **process ids collide**
(~2.8% in 1500 runs with the default `MobyNameGenerator`, while `AgentProcess.id`
documents itself as unique), and **the two listener seams are not equivalent** —
`ProcessOptions.listeners` saw 120 events of 1 type where a platform bean saw 760
of 8, because `AbstractAgentProcess` emits lifecycle events straight to
`platformServices.eventListener`, bypassing the composite. For durable tracing,
**the bean is the correct seam**.

Full write-ups: [docs/experiments.md](docs/experiments.md). Three more
experiments (indexed history, sharding, and DICE's transaction axis) live in the
sibling [dice-chronicle](https://github.com/raidenario/dice-chronicle).

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

### Plugging in your own services, without a `@Configuration` class

Spring's `:sources` wants an annotated *class*, which is exactly what this
library refuses to make you write. `:beans` goes through
`ApplicationContextInitializer` instead — it runs before the refresh, so a
plain Clojure object can be registered as a ready-made singleton. Since Embabel
resolves `LlmService`, `EmbeddingService`, `OptionsConverter` and friends **by
type**, a `reify` of the interface is indistinguishable from an `@Bean`:

```clojure
(platform/start!
 {:properties   {...}
  :beans        {:meuLlmService (reify com.embabel.agent.spi.LlmService ...)}
  ;; for what :beans doesn't cover — property sources, profiles, listeners
  :initializers [(fn [ctx] (.setId ctx "meu-contexto"))]})
```

`:beans` are registered first, then `:initializers`, so yours can read or
override what the library put there.

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
   *Planner note:* `:hybrid` (0.5.0+) picks the highest-netValue action each
   tick like `:utility`, but exits the moment any registered goal is satisfied.
   It needs the framework's unsatisfiable `NIRVANA` goal alongside your real
   terminal one — `(ec/nirvana)` returns it.
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
- [`examples/process-store`](examples/process-store) — durable process history
  end to end: three runs on the real platform, `platform/stop!`, then the history
  read back **from disk** with Spring already dead — 15 records, 3 processes,
  `runs` / `timeline` / `as-of`.
- [`examples/app-caminho-b`](examples/app-caminho-b) — the historical pre-library
  app (own pom, `App.java`, hand-rolled `agents.clj`): kept as the *before*
  picture of what this library deletes.

## Related

- **[dice-chronicle](https://github.com/raidenario/dice-chronicle)** — sibling
  project: a durable, queryable event log for [Embabel DICE](https://github.com/embabel/dice).
  Same philosophy, no shared code. Where this library is *definition as data* and
  *execution state as value*, the chronicle is *history as data*. The reasoning
  common to both, with its counter-evidence, is written up in
  [docs/agent-as-data.md](https://github.com/raidenario/dice-chronicle/blob/main/docs/agent-as-data.md).

## Dev

```
clojure -M:test            # 98 tests / 439 assertions against the real jars
clojure -M:probe-040:test  # same suite against embabel-agent 0.4.0
clojure -M:probe-050:test  # ...and 0.5.0-SNAPSHOT ("Darwin")
clojure -T:build install   # local Maven install (pure-source jar)
cd experiments && ./run-all.sh   # the five experiments, offline
```

`embabel-clj.dev/start-nrepl!` embeds an nREPL next to a live platform (add
`nrepl/nrepl` to your dev deps).

## Roadmap

- ~~Verify against Embabel 0.5.x ("Darwin")~~ done — and against 1.0.0 GA
  (suite + Spring boot + live LLM call; `LlmOptions.fromModel` renamed to
  `withModel`, resolved reflectively).
- ~~Event listeners, tool-loop interceptors, guardrails, `StuckHandler`,
  `EarlyTerminationPolicy` and cooperative termination as fns~~ ·
  ~~the `:hybrid` planner~~ · ~~a `:beans`/`:initializers` seam on
  `platform/start!`~~ · ~~async invocation, `last(Class)`,
  `:tool-call-context`~~ — done. **Every extension point the framework exposes
  as a small interface is now a Clojure map of fns**, and plugging in your own
  `LlmService`/`EmbeddingService`/`OptionsConverter` no longer needs a Java
  shell.
- Cost tracking (`Usage`) surfaced in `result` — the data already crosses the
  border (`:usage` arrives in the `:after-llm-call` interceptor), it just isn't
  aggregated yet.
- Streaming and thinking; states (`@State`, `WaitFor.formSubmission`) and the
  DSL builders (scatter-gather, consensus, repeat-until) as data.
- Closed/Open execution modes (intent → agent selection / novel composition).
- Explicit `domainTypes` registration; `createObject`-style native data
  binding onto records.
- Pure actions mode (bodies return `{:fatos {...} :polos {...}}` effects —
  the 2nd-generation glue from the sibling projects).
- Playbook hot-load helper (LLM-proposed EDN remedies validated by malli into
  an atom registry) as an optional namespace.
- Publish to Clojars.

## License

Apache-2.0 (same as Embabel).
