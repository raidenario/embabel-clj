(ns embabel-clj.specs
  "Schemas malli da PRÓPRIA API do embabel-clj.

   Todos os mapas de definição (action/goal/condition/agent) são validados
   com schemas `:closed` — um typo (`:cots` em vez de `:cost`) falha NA
   CONSTRUÇÃO, com erro humanizado, em vez de virar silêncio em runtime.
   É o equivalente data-driven do que o compilador Kotlin dá ao usuário das
   anotações do Embabel."
  (:require [clojure.string :as str]
            [embabel-clj.blackboard :as bb]
            [malli.core :as m]
            [malli.error :as me])
  (:import [com.embabel.agent.core Action Goal Condition]
           [com.embabel.agent.api.common StuckHandler]))

(def ConditionName
  "Nome de condição booleana: string ou keyword, SEM ':' no texto final
   (`:` desvia o determiner do Embabel para o ramo de data-binding)."
  [:and
   [:or :string :keyword]
   [:fn {:error/message "nome de condição não pode conter ':' (use '/' e '?')"}
    (fn [k] (not (str/includes? (bb/key->str k) ":")))]])

(def IoLike
  "Entrada da camada TIPADA (IoBinding): Class, string \"name:pkg.Type\" (ou
   só \"pkg.Type\", binding default \"it\"), ou {:name ... :type Class|string}."
  [:or
   [:fn {:error/message "uma Class"} class?]
   :string
   [:map {:closed true}
    [:name {:optional true} [:or :string :keyword]]
    [:type [:or [:fn class?] :string :symbol]]]])

(def ToolGroupLike
  "Role de ToolGroup (ex.: :web, \"math\") ou
   {:role ... :tools #{...} :termination :agent|:action}."
  [:or :string :keyword
   [:map {:closed true}
    [:role [:or :string :keyword]]
    [:tools {:optional true} [:sequential :string]]
    [:termination {:optional true} [:enum :agent :action]]]])

(def Qos
  [:map {:closed true}
   [:max-attempts       {:optional true} pos-int?]
   [:backoff-millis     {:optional true} nat-int?]
   [:backoff-multiplier {:optional true} number?]
   [:backoff-max-millis {:optional true} nat-int?]
   [:idempotent?        {:optional true} :boolean]])

(def ActionDef
  [:map {:closed true}
   [:name        [:or :string :keyword]]
   [:fn          ifn?]
   [:description {:optional true} :string]
   [:pre         {:optional true} [:sequential ConditionName]]
   [:post        {:optional true} [:sequential ConditionName]]
   [:cost        {:optional true} [:or number? ifn?]]
   [:value       {:optional true} [:or number? ifn?]]
   [:rerun?      {:optional true} :boolean]
   [:llm?        {:optional true} :boolean]
   [:read-only?  {:optional true} :boolean]
   [:clear-blackboard? {:optional true} :boolean]
   [:after       {:optional true} ifn?]
   ;; camada TIPADA: pré/pós-condições derivadas de tipos no blackboard
   [:inputs      {:optional true} [:sequential IoLike]]
   [:outputs     {:optional true} [:sequential IoLike]]
   ;; ToolGroups exigidos pela action (tools de MCP chegam por aqui)
   [:tool-groups {:optional true} [:sequential ToolGroupLike]]
   ;; re-tentativas opt-in (default da lib = fail-fast, 1 tentativa);
   ;; :qos explícito vence :retries
   [:retries     {:optional true} nat-int?]
   [:qos         {:optional true} Qos]])

(def GoalDef
  [:and
   [:map {:closed true}
    [:name        [:or :string :keyword]]
    [:description {:optional true} :string]
    [:pre         {:optional true} [:sequential ConditionName]]
    ;; versão TIPADA da pré: alcançado quando existe um objeto do tipo no bb
    [:inputs      {:optional true} [:sequential IoLike]]
    [:value       {:optional true} [:or number? ifn?]]
    [:tags        {:optional true} [:sequential :string]]
    [:examples    {:optional true} [:sequential :string]]]
   [:fn {:error/message "goal precisa de :pre e/ou :inputs"}
    (fn [g] (boolean (or (seq (:pre g)) (seq (:inputs g)))))]])

(def ConditionDef
  "Condição LAZY (avaliada pelo planner sob demanda — o equivalente do
   @Condition do modelo anotado). `:fn` recebe o ctx `{:oc ...}` e devolve
   truthy/falsy; nil também conta como FALSE (nunca produza UNKNOWN sem
   querer: >1 UNKNOWN derruba o planner 0.4.x)."
  [:map {:closed true}
   [:name ConditionName]
   [:fn   ifn?]
   [:cost {:optional true} number?]])

(defn- instance-of [^Class c] [:fn {:error/message (str "instância de " (.getName c))}
                               (fn [x] (instance? c x))])

(def AgentDef
  [:map {:closed true}
   [:name          [:or :string :keyword]]
   [:description   :string]
   [:provider      {:optional true} :string]
   [:version       {:optional true} :string]
   [:goals         [:sequential [:or GoalDef (instance-of Goal)]]]
   [:actions       [:sequential [:or ActionDef (instance-of Action)]]]
   [:conditions    {:optional true} [:sequential [:or ConditionDef (instance-of Condition)]]]
   [:after         {:optional true} ifn?]
   [:stuck-handler {:optional true} (instance-of StuckHandler)]])

(def RunOptions
  [:map {:closed true}
   [:budget     {:optional true} [:map {:closed true}
                                  [:cost    {:optional true} number?]
                                  [:actions {:optional true} pos-int?]
                                  [:tokens  {:optional true} pos-int?]]]
   [:verbosity  {:optional true} [:map {:closed true}
                                  [:prompts?    {:optional true} :boolean]
                                  [:responses?  {:optional true} :boolean]
                                  [:planning?   {:optional true} :boolean]
                                  [:long-plans? {:optional true} :boolean]]]
   [:planner    {:optional true} [:enum :goap :utility :supervisor]]
   [:ephemeral? {:optional true} :boolean]
   [:prune?     {:optional true} :boolean]])

(defn validate!
  "Valida `value` contra `schema`; inválido lança ex-info com o erro
   HUMANIZADO do malli (ex.: {:cots [\"disallowed key\"]}). Devolve `value`."
  [schema value what]
  (if-let [expl (m/explain schema value)]
    (throw (ex-info (str "embabel-clj: definição inválida de " what ": "
                         (pr-str (me/humanize expl)))
                    {:type   ::invalid
                     :what   what
                     :errors (me/humanize expl)
                     :value  value}))
    value))
