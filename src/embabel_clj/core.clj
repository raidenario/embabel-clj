(ns embabel-clj.core
  "API pública do embabel-clj: agentes GOAP do Embabel como DADOS.

   Tudo aqui recebe mapas Clojure simples, validados por malli (typo em chave
   = erro humanizado na construção, não silêncio em runtime). O grafo do
   agente — actions, goals, conditions — é escrito como dado; a fabricação
   dos objetos Kotlin/Java fica em embabel-clj.interop.

   Fluxo típico:

     (require '[embabel-clj.core :as ec]
              '[embabel-clj.platform :as platform]
              '[embabel-clj.blackboard :as bb])

     (def meu-agente
       (ec/agent
        {:name \"hello\" :description \"diz oi\"
         :goals   [{:name \"done\" :pre [:greeted?] :value 1.0}]
         :actions [{:name \"greet\" :post [:greeted?]
                    :fn (fn [ctx]
                          (bb/put! ctx :greeting \"Olá!\")
                          (bb/set-condition! ctx :greeted? true))}]}))

     (let [{:keys [platform]} (platform/start! {...})]
       (ec/deploy! platform meu-agente)
       (-> (ec/run! platform meu-agente {:bindings {:x 1}})
           (ec/result {:slots [:greeting] :conditions [:greeted?]})))"
  (:refer-clojure :exclude [agent run! condition])
  (:require [embabel-clj.blackboard :as bb]
            [embabel-clj.events :as events]
            [embabel-clj.interop :as interop]
            [embabel-clj.specs :as specs]
            [embabel-clj.termination :as termination])
  (:import [com.embabel.agent.core Action Agent AgentPlatform AgentProcess
            Budget Condition Goal ProcessOptions Verbosity]
           [com.embabel.agent.api.common PlannerType]
           [java.util.concurrent CompletableFuture TimeUnit]))

;; ---------------------------------------------------------------------------
;; Construtores (mapa -> objeto Embabel)
;; ---------------------------------------------------------------------------

(defn action
  "Action a partir de um mapa (specs/ActionDef). Ver interop/make-action para
   o contrato do ctx passado a `:fn`."
  ^Action [m]
  (specs/validate! specs/ActionDef m "action")
  (interop/make-action m))

(defn goal
  "Goal a partir de um mapa (specs/GoalDef)."
  ^Goal [m]
  (specs/validate! specs/GoalDef m "goal")
  (interop/make-goal m))

(defn condition
  "Condição LAZY a partir de um mapa (specs/ConditionDef) — o equivalente do
   @Condition anotado: avaliada pelo planner sob demanda."
  ^Condition [m]
  (specs/validate! specs/ConditionDef m "condition")
  (interop/make-condition m))

(defn- ->action ^Action [x after]
  (if (instance? Action x)
    x
    (interop/make-action (cond-> x
                           (and after (nil? (:after x))) (assoc :after after)))))

(defn- ->goal ^Goal [x]
  (if (instance? Goal x) x (interop/make-goal x)))

(defn- ->condition ^Condition [x]
  (if (instance? Condition x) x (interop/make-condition x)))

(defn agent
  "Agent a partir de um mapa (specs/AgentDef). `:goals`/`:actions`/
   `:conditions` aceitam mapas OU objetos já construídos.

   `:after` (opcional) é aplicado a TODA action definida como mapa que não
   traga o próprio `:after` — o padrão \"recalcular condições derivadas após
   cada action\" quando você não usa conditions lazy. (Prefira conditions
   lazy: sem janela de estado obsoleto.)"
  ^Agent [m]
  (specs/validate! specs/AgentDef m "agent")
  (let [{:keys [after]} m]
    (interop/make-agent
     (cond-> m
       true (update :goals #(mapv ->goal %))
       true (update :actions #(mapv (fn [a] (->action a after)) %))
       true (update :conditions #(mapv ->condition (or % [])))
       (:stuck-handler m) (update :stuck-handler termination/->stuck-handler)))))

;; ---------------------------------------------------------------------------
;; Agente a partir de metadata de namespace (o leitor de tags do artigo)
;; ---------------------------------------------------------------------------

(defn- action-var? [v] (contains? (meta v) :action/post))
(defn- condition-var? [v] (contains? (meta v) :condition/name))

(defn- var->action-map [v]
  (let [mt (meta v)]
    (cond-> {:name  (str (:name mt))
             :pre   (vec (:action/pre mt []))
             :post  (vec (:action/post mt []))
             :cost  (:action/cost mt 1.0)
             :fn    v}                       ; a VAR: redef no REPL vale na hora
      (:action/rerun mt)              (assoc :rerun? true)
      (:action/llm mt)                (assoc :llm? true)
      (:action/retries mt)            (assoc :retries (:action/retries mt))
      ;; camada tipada/tools nas tags: strings "name:pkg.Type" e roles
      (:action/inputs mt)             (assoc :inputs (vec (:action/inputs mt)))
      (:action/outputs mt)            (assoc :outputs (vec (:action/outputs mt)))
      (:action/tool-groups mt)        (assoc :tool-groups
                                             (vec (:action/tool-groups mt)))
      (or (:action/description mt)
          (:doc mt))                  (assoc :description
                                             (or (:action/description mt)
                                                 (:doc mt))))))

(defn- var->condition-map [v]
  (let [mt (meta v)]
    {:name (:condition/name mt)
     :cost (:condition/cost mt 0.0)
     :fn   v}))

(defn agent-from-ns
  "Monta um Agent varrendo `ns-sym`:

   - vars com metadata `:action/post` viram actions
     (tags: :action/pre :action/post :action/cost :action/rerun :action/llm
      :action/description; a docstring vira description se a tag faltar);
   - vars com metadata `:condition/name` viram condições LAZY
     (tags: :condition/name :condition/cost).

   `opts` completa o AgentDef (:name :description :goals :after ...).
   As fns são registradas como VARS: redefinir no REPL vale imediatamente,
   sem re-deploy."
  ^Agent [ns-sym opts]
  (require ns-sym)
  (let [vars    (vals (ns-interns ns-sym))
        actions (->> vars
                     (filter action-var?)
                     (sort-by (comp str :name meta))
                     (mapv var->action-map))
        conds   (->> vars
                     (filter condition-var?)
                     (sort-by (comp str :condition/name meta))
                     (mapv var->condition-map))]
    (when (empty? actions)
      (throw (ex-info (str "agent-from-ns: nenhuma var com metadata :action/post em " ns-sym)
                      {:ns ns-sym})))
    (agent (merge {:actions actions :conditions conds} opts))))

;; ---------------------------------------------------------------------------
;; Execução
;; ---------------------------------------------------------------------------

(def ^:private planner-types
  ;; Resolvido por Enum/valueOf, NÃO por PlannerType/GOAP literal: HYBRID só
  ;; existe a partir do 0.5.0 e a lib carrega os .clj crus (NO-AOT) — um campo
  ;; estático ausente derrubaria a CARGA da ns inteira sob :probe-040, não só a
  ;; chamada. Mesmo espírito do qos-ctor em interop.clj.
  (into {}
        (keep (fn [[k ^String n]]
                (when-let [v (try (Enum/valueOf PlannerType n)
                                  (catch IllegalArgumentException _ nil))]
                  [k v])))
        {:goap "GOAP" :utility "UTILITY" :hybrid "HYBRID" :supervisor "SUPERVISOR"}))

(defn nirvana
  "O goal NIRVANA do framework (`com.embabel.agent.core.support.NIRVANA`):
   \"nothing more to do\", com uma pré-condição INALCANÇÁVEL (`__unobtanium__`)
   de propósito — ele nunca é satisfeito.

   É a peça que falta no `:planner :hybrid`: o planner híbrido escolhe a cada
   tick a action de maior netValue (como o :utility) MAS termina no instante em
   que algum goal registrado já está satisfeito. Registre NIRVANA junto com o
   seu goal terminal de verdade — as actions oportunistas disparam por
   netValue enquanto o plano do NIRVANA vence, e o processo sai assim que o
   goal real é alcançado. Sem ele, o :hybrid não tem para onde correr."
  ^Goal []
  (com.embabel.agent.core.support.NirvanaKt/getNIRVANA))

(defn process-options
  "ProcessOptions a partir de um mapa (specs/RunOptions); nil/{} = DEFAULT.

   {:budget {:cost 2.0 :actions 40 :tokens 200000}  ; teto NATIVO (dólares,
                                                    ; nº de actions, tokens)
    :verbosity {:prompts? true :responses? true :planning? true :long-plans? false}
    :planner :goap|:utility|:hybrid|:supervisor
    :listeners [(fn [ev] ...)]                      ; AgenticEventListener (ver
                                                    ; embabel-clj.events)
    :early-termination [(fn [proc] ...)]            ; policies SOMADAS às do
                                                    ; budget (ver termination)
    :tool-call-context {:tenant \"acme\" :correlation-id \"...\"}  ; visível a
                                                    ; TODA tool do processo
    :ephemeral? true :prune? true}

   `:hybrid` exige embabel-agent 0.5.0+ (o 0.4.0 tem só GOAP/UTILITY/SUPERVISOR)
   e pede o goal `nirvana` no agente."
  ^ProcessOptions [m]
  (when (seq m) (specs/validate! specs/RunOptions m "run options"))
  (let [{:keys [budget verbosity planner listeners early-termination
                tool-call-context ephemeral? prune?]} m
        default-budget Budget/DEFAULT]
    (when (and planner (nil? (planner-types planner)))
      (throw (ex-info (str "embabel-clj: planner " planner " não existe nesta versão do "
                           "embabel-agent (disponíveis: "
                           (pr-str (vec (sort (keys planner-types)))) ")")
                      {:type ::unsupported-planner
                       :planner planner
                       :available (vec (sort (keys planner-types)))})))
    (cond-> ProcessOptions/DEFAULT
      budget     (.withBudget (Budget. (double (:cost budget (.getCost default-budget)))
                                       (int (:actions budget (.getActions default-budget)))
                                       (int (:tokens budget (.getTokens default-budget)))))
      verbosity  (.withVerbosity (Verbosity. (boolean (:prompts? verbosity))
                                             (boolean (:responses? verbosity))
                                             (boolean (:planning? verbosity))
                                             (boolean (:long-plans? verbosity))))
      planner    (.withPlannerType ^PlannerType (planner-types planner))
      (seq listeners) (.withListeners ^java.util.List
                                      (mapv events/->listener listeners))
      ;; SOMA à policy do budget (que já é maxActions+maxTokens+hardBudgetLimit
      ;; compostas por firstOf) — não substitui. Por isso vem depois do :budget.
      (seq early-termination)
      (.withAdditionalEarlyTerminationPolicy
       (if (next early-termination)
         (termination/first-of early-termination)
         (termination/->policy (first early-termination))))
      (seq tool-call-context)
      (.withToolCallContext ^java.util.Map
                            (into {} (map (fn [[k v]] [(bb/key->str k) v]))
                                  tool-call-context))
      (some? ephemeral?) (.withEphemeral (boolean ephemeral?))
      (some? prune?)     (.withPrune (boolean prune?)))))

(defn deploy!
  "Deploya o agente na plataforma. Devolve o agente (para threading)."
  ^Agent [^AgentPlatform platform ^Agent ag]
  (.deploy platform ag)
  ag)

(defn run!
  "Roda o agente SÍNCRONO a partir de `:bindings` (slots iniciais do
   blackboard; chaves string ou keyword). Devolve o AgentProcess — leia
   slots/condições dele com embabel-clj.blackboard ou com `result`.

   `:options` é o mapa de process-options (budget/verbosity/planner/...)."
  (^AgentProcess [platform ag] (run! platform ag {}))
  (^AgentProcess [^AgentPlatform platform ^Agent ag {:keys [bindings options]}]
   (.runAgentFrom platform ag
                  (process-options options)
                  (into {} (map (fn [[k v]] [(bb/key->str k) v]))
                        (or bindings {})))))

(defn run-async!
  "Como `run!`, mas NÃO bloqueia: cria o processo e o dispara, devolvendo o
   `CompletableFuture<AgentProcess>` do framework.

     (let [fut (ec/run-async! platform ag {:bindings {:x 1}})]
       ;; ... faça outra coisa ...
       (ec/result (ec/join! fut) {:slots [:saida]}))

   Opções, além de `:bindings`/`:options` do `run!`:
     :on-complete (fn [proc] ...)   ; roda quando terminar bem
     :on-error    (fn [throwable] ...)

   Os dois callbacks entram por `whenComplete` — rodam na thread do pool do
   Embabel, não na sua. O future devolvido é o do framework: `.thenApply` e
   companhia continuam disponíveis para quem quiser compor."
  (^CompletableFuture [platform ag] (run-async! platform ag {}))
  (^CompletableFuture [^AgentPlatform platform ^Agent ag
                       {:keys [bindings options on-complete on-error]}]
   (let [proc (.createAgentProcess platform ag
                                   (process-options options)
                                   (into {} (map (fn [[k v]] [(bb/key->str k) v]))
                                         (or bindings {})))
         fut  (.start platform proc)]
     (if (or on-complete on-error)
       (.whenComplete fut (reify java.util.function.BiConsumer
                            (accept [_ p t]
                              (cond
                                (and t on-error)    (on-error t)
                                (and p on-complete) (on-complete p)))))
       fut))))

(defn join!
  "Bloqueia até o future de `run-async!` completar e devolve o AgentProcess.
   `:timeout-s` opcional (estoura TimeoutException). Existe para você não
   precisar importar java.util.concurrent no seu projeto."
  (^AgentProcess [fut] (join! fut nil))
  (^AgentProcess [^CompletableFuture fut {:keys [timeout-s]}]
   (if timeout-s
     (.get fut (long timeout-s) TimeUnit/SECONDS)
     (.join fut))))

(defn result
  "Resumo data-friendly de um AgentProcess:
   {:status \"COMPLETED\" :slots {...} :conditions {...}}.
   `:slots`/`:conditions` do opts escolhem o que ler do blackboard."
  ([proc] (result proc nil))
  ([^AgentProcess proc {:keys [slots conditions]}]
   (cond-> {:status (str (.getStatus proc))}
     (.getFailureInfo proc) (assoc :failure (.getFailureInfo proc))
     (seq slots)      (assoc :slots (into {} (map (fn [k] [k (bb/fetch proc k)]))
                                          slots))
     (seq conditions) (assoc :conditions (bb/conditions proc conditions)))))
