(ns embabel-clj.interop
  "Fábrica interna dos objetos Embabel. TODO proxy/reify da biblioteca mora
   aqui — este arquivo é o \"imposto de interop pago uma vez\" que o usuário
   da biblioteca nunca precisa escrever.

   Assinaturas verificadas por javap contra embabel-agent-api 0.4.0:
   - AbstractAction: ctor de 13 args (name, description, pre, post, cost,
     value, inputs, outputs, toolGroups, canRerun, readOnly, clearBlackboard,
     qos); cost/value são kotlin Function1<WorldState, Double>.
   - Goal: ctor de 9 args (name, description, pre, inputs, outputType, value,
     tags, examples, export); Export tem ctor sem args.
   - Condition: INTERFACE — evaluate(OperationContext) -> ConditionDetermination,
     getCost() (ConditionMetadata), getName() (Named). Reificável.
   - Agent: ctors de conveniência de 6/7/8 args (…goals, actions[, conditions
     [, stuckHandler]]).
   - ActionQos: ctor sem args com defaults do framework."
  (:require [embabel-clj.blackboard :as bb])
  (:import [com.embabel.agent.core.support AbstractAction]
           [com.embabel.agent.core ProcessContext ActionStatus ActionStatusCode
            ActionQos Agent Goal Export Condition ComputedBooleanCondition
            IoBinding ToolGroupRequirement]
           [com.embabel.agent.api.common TransformationActionContext
            TerminationScope]
           [java.time Duration]))

;; ---------------------------------------------------------------------------
;; IoBinding — a camada TIPADA do planner ("name:pkg.Type").
;;
;; IoBinding é uma value class Kotlin: os membros manglados têm HÍFEN literal
;; no nome JVM (constructor-impl, box-impl) e o interop do Clojure munga
;; hífen->underscore — então o acesso é por java.lang.reflect, resolvido uma
;; vez e cacheado.
;; ---------------------------------------------------------------------------

(def ^:private io-ctor2
  (delay (doto (.getMethod IoBinding "constructor-impl"
                           (into-array Class [String String]))
           (.setAccessible true))))

(def ^:private io-box
  (delay (doto (.getMethod IoBinding "box-impl" (into-array Class [String]))
           (.setAccessible true))))

(def default-binding
  "O nome de binding default do Embabel (\"it\")."
  IoBinding/DEFAULT_BINDING)

(defn- type-name ^String [t]
  (cond
    (class? t)  (.getName ^Class t)
    (symbol? t) (str t)
    :else       (str t)))

(defn io-binding
  "IoBinding a partir de: Class (binding default \"it\"), string
   \"name:pkg.Type\" (ou só \"pkg.Type\"), ou mapa {:name \"x\" :type Class}."
  ^IoBinding [x]
  (cond
    (instance? IoBinding x) x

    (class? x)
    (io-binding {:name default-binding :type x})

    (string? x)
    (let [[n t] (if (.contains ^String x ":")
                  (let [i (.indexOf ^String x ":")]
                    [(subs x 0 i) (subs x (inc i))])
                  [default-binding x])]
      (io-binding {:name n :type t}))

    (map? x)
    (let [{:keys [name type]} x
          raw (.invoke ^java.lang.reflect.Method @io-ctor2 nil
                       (object-array [(if name (bb/key->str name) default-binding)
                                      (type-name type)]))]
      (.invoke ^java.lang.reflect.Method @io-box nil (object-array [raw])))

    :else
    (throw (ex-info "io-binding: esperava Class, string ou {:name :type}"
                    {:value x}))))

(defn tool-group-requirement
  "ToolGroupRequirement a partir de um role (string/keyword, ex.: :web —
   CoreToolGroups: web, math, maps, github, browser-automation) ou de um mapa
   {:role \"web\" :tools #{\"fetch\"} :termination :agent|:action}."
  ^ToolGroupRequirement [x]
  (if (instance? ToolGroupRequirement x)
    x
    (let [{:keys [role tools termination]}
          (if (map? x) x {:role x})]
      (ToolGroupRequirement. (bb/key->str role)
                             (set (map str (or tools #{})))
                             (case termination
                               :action TerminationScope/ACTION
                               (nil :agent) TerminationScope/AGENT)))))

(defn fn1
  "Embrulha `f` (1 arg) como kotlin.jvm.functions.Function1 devolvendo Double.
   Aceita número puro (vira função constante) — é o formato que o Embabel
   exige para cost/value de actions e goals."
  ^kotlin.jvm.functions.Function1 [f-or-n]
  (let [f (if (number? f-or-n) (constantly f-or-n) f-or-n)]
    (reify kotlin.jvm.functions.Function1
      (invoke [_ world-state] (Double/valueOf (double (f world-state)))))))

(defn qos
  "ActionQos a partir de mapa. Default da BIBLIOTECA = FAIL-FAST (1 tentativa,
   lição do fabulista: o default do framework, 5 tentativas com backoff
   10s→60s, transforma um bug no corpo em minutos de agonia). CUIDADO: o 1º
   arg do ActionQos é maxAttempts, NÃO retries — 0 = zero execuções."
  ^ActionQos [m]
  (ActionQos. (int    (:max-attempts m 1))
              (long   (:backoff-millis m 5000))
              (double (:backoff-multiplier m 2.0))
              (long   (:backoff-max-millis m 20000))
              (boolean (:idempotent? m false))))

(defn- cond-names ^java.util.List [ks]
  (mapv bb/key->str ks))

(defn make-action
  "Constrói uma Action do Embabel a partir do mapa (ver specs/ActionDef).

   `:fn` recebe UM ctx: {:pc ProcessContext, :action Action, :oc OperationContext?}
   — `:oc` só existe quando `:llm? true` (só aí pagamos a criação do
   TransformationActionContext; é o campo aberto `:action/llm` do artigo).
   `:after` (opcional) roda após o corpo — o lugar do refresh de condições
   derivadas EAGER quando você não usa conditions lazy."
  ^AbstractAction
  [{:keys [name description pre post cost value rerun? read-only?
           clear-blackboard? llm? after retries inputs outputs tool-groups]
    :or   {pre [] post [] cost 1.0 value 0.0}
    f :fn qos-opts :qos}]
  (let [nm (bb/key->str name)]
    (proxy [AbstractAction]
           [nm
            (or description nm)
            (cond-names pre)
            (cond-names post)
            (fn1 cost)
            (fn1 value)
            (set (map io-binding inputs))   ; camada TIPADA: pré "existe X"
            (set (map io-binding outputs))  ; pós "passa a existir X"
            (set (map tool-group-requirement tool-groups))
            (boolean rerun?)             ; canRerun (false => roda 1x: o
                                         ;  Embabel injeta pre hasRun_<name>)
            (boolean read-only?)
            (boolean clear-blackboard?)
            (qos (or qos-opts
                     (when retries {:max-attempts (inc retries)})))]
      (execute [^ProcessContext pc]
        (let [ctx (cond-> {:pc pc :action this}
                    llm? (assoc :oc (TransformationActionContext.
                                     nil pc this Object Object)))]
          (f ctx)
          (when after (after ctx)))
        (ActionStatus. (Duration/ofMillis 0) ActionStatusCode/SUCCEEDED))
      (referencedInputProperties [_variable] #{}))))

(defn make-goal
  "Goal a partir do mapa (ver specs/GoalDef). `:value` default 1.0.
   `:inputs` (IoBindings) = a versão TIPADA da pré-condição: o goal é
   alcançado quando existe um objeto daquele tipo no blackboard."
  ^Goal
  [{:keys [name description pre value tags examples inputs]
    :or   {pre [] value 1.0 tags [] examples []}}]
  (let [nm (bb/key->str name)]
    (Goal. nm
           (or description nm)
           (set (map bb/key->str pre))
           (set (map io-binding inputs))
           nil                                   ; outputType (DomainType)
           (fn1 value)
           (set tags)
           (set examples)
           (Export.))))

(defn make-condition
  "Condição LAZY via ComputedBooleanCondition (a classe do próprio framework,
   provada em execução real nos projetos fabulista/beautiful-linkedin) — o
   equivalente do @Condition anotado: o planner a avalia SOB DEMANDA, sem
   janela de estado obsoleto e sem hook :after.

   `:fn` recebe {:oc <contexto>} e devolve truthy/falsy (nil/false => FALSE).
   Leia o estado com embabel-clj.blackboard (aceita o ctx direto)."
  ^Condition
  [{:keys [name cost] :or {cost 0.0} f :fn}]
  (let [nm (bb/key->str name)]
    (ComputedBooleanCondition.
     nm (double cost)
     (reify kotlin.jvm.functions.Function2
       (invoke [_ oc _condition]
         (boolean (f {:oc oc})))))))

(defn make-agent
  "Agent do Embabel: metadados + goals + actions (+ conditions lazy
   [+ stuckHandler]). Usa os ctors de conveniência de 7/8 args do 0.4.0."
  ^Agent
  [{:keys [name provider version description goals actions conditions
           stuck-handler]
    :or   {provider "embabel-clj" version "0.1.0"}}]
  (let [nm (bb/key->str name)]
    (if stuck-handler
      (Agent. nm provider version description
              (set goals) (vec actions) (set conditions) stuck-handler)
      (Agent. nm provider version description
              (set goals) (vec actions) (set conditions)))))
