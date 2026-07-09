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
            [embabel-clj.interop :as interop]
            [embabel-clj.specs :as specs])
  (:import [com.embabel.agent.core Action Agent AgentPlatform AgentProcess
            Budget Condition Goal ProcessOptions Verbosity]
           [com.embabel.agent.api.common PlannerType]))

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
     (-> m
         (update :goals #(mapv ->goal %))
         (update :actions #(mapv (fn [a] (->action a after)) %))
         (update :conditions #(mapv ->condition (or % [])))))))

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
  {:goap       PlannerType/GOAP
   :utility    PlannerType/UTILITY
   :supervisor PlannerType/SUPERVISOR})

(defn process-options
  "ProcessOptions a partir de um mapa (specs/RunOptions); nil/{} = DEFAULT.

   {:budget {:cost 2.0 :actions 40 :tokens 200000}  ; teto NATIVO (dólares,
                                                    ; nº de actions, tokens)
    :verbosity {:prompts? true :responses? true :planning? true :long-plans? false}
    :planner :goap|:utility|:supervisor
    :ephemeral? true :prune? true}"
  ^ProcessOptions [m]
  (when (seq m) (specs/validate! specs/RunOptions m "run options"))
  (let [{:keys [budget verbosity planner ephemeral? prune?]} m
        default-budget Budget/DEFAULT]
    (cond-> ProcessOptions/DEFAULT
      budget     (.withBudget (Budget. (double (:cost budget (.getCost default-budget)))
                                       (int (:actions budget (.getActions default-budget)))
                                       (int (:tokens budget (.getTokens default-budget)))))
      verbosity  (.withVerbosity (Verbosity. (boolean (:prompts? verbosity))
                                             (boolean (:responses? verbosity))
                                             (boolean (:planning? verbosity))
                                             (boolean (:long-plans? verbosity))))
      planner    (.withPlannerType ^PlannerType (planner-types planner))
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
