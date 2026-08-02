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
  (:import [com.embabel.agent.core Action Goal Condition EarlyTerminationPolicy]
           [com.embabel.agent.api.common StuckHandler]
           [com.embabel.agent.api.event AgenticEventListener]))

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

(defn- fn-not-map
  "`ifn?` casa com MAPA também (mapa é IFn). Sem esta guarda, um typo dentro do
   mapa escaparia pelo ramo da fn num `:or`."
  [msg]
  [:and ifn? [:fn {:error/message msg} (complement map?)]])

;; --- terminação (ver embabel-clj.termination) -------------------------------

(def PolicyDef
  [:map {:closed true}
   [:name       {:optional true} [:or :string :keyword]]
   [:terminate? ifn?]])

(def PolicyLike
  "EarlyTerminationPolicy como dado: mapa, fn de 1 arg (o AgentProcess) ou
   instância pronta (as embutidas do framework caem aqui)."
  [:or PolicyDef
   (fn-not-map "early-termination: fn de 1 arg ou {:terminate? ...}")
   (instance-of EarlyTerminationPolicy)])

(def StuckHandlerDef
  [:map {:closed true}
   [:name   {:optional true} [:or :string :keyword]]
   [:handle ifn?]])

(def StuckHandlerLike
  [:or StuckHandlerDef
   (fn-not-map "stuck-handler: fn de 1 arg ou {:handle ...}")
   (instance-of StuckHandler)])

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
   ;; fn, {:handle ...} ou instância — ver embabel-clj.termination
   [:stuck-handler {:optional true} StuckHandlerLike]])

(def ListenerHandlers
  "Mapa de canais de um AgenticEventListener (ver embabel-clj.events)."
  [:map {:closed true}
   [:on-process  {:optional true} ifn?]
   [:on-platform {:optional true} ifn?]])

(def ListenerLike
  "Listener como dado: mapa de canais, fn (recebe TODO evento) ou instância.
   O mapa vem PRIMEIRO no :or — um mapa também satisfaz `ifn?`, então sem a
   guarda `(not map?)` um typo em `:on-proces` escaparia pelo ramo da fn."
  [:or
   ListenerHandlers
   (fn-not-map "listener: fn de 1 arg ou {:on-process/:on-platform}")
   (instance-of AgenticEventListener)])

;; --- guardrails (ver embabel-clj.guardrails) --------------------------------

(def GuardRailDef
  "Guarda como dado. `:on` só é lido pelo `guardrails/guardrail` (a forma 100%
   EDN); `user-input`/`assistant-message` já sabem qual são."
  [:and
   [:map {:closed true}
    [:name        [:or :string :keyword]]
    [:description {:optional true} :string]
    [:fn          {:optional true} ifn?]
    ;; schema malli como guarda: o explain/humanize vira a lista de erros
    [:schema      {:optional true} some?]
    ;; severidade default dos erros desta guarda; só :critical BLOQUEIA
    [:severity    {:optional true} [:enum :info :warning :error :critical]]
    ;; só faz sentido em assistant-message (respostas com thinking)
    [:thinking-fn {:optional true} ifn?]
    [:on          {:optional true} [:enum :user-input :assistant-message]]]
   [:fn {:error/message "guardrail precisa de :fn e/ou :schema"}
    (fn [g] (boolean (or (:fn g) (:schema g))))]])

;; --- interceptors do tool loop (ver embabel-clj.interceptors) ---------------
;; :closed em todos: `:after-tool-results` (plural por engano) é exatamente o
;; tipo de typo que viraria silêncio — o hook nunca dispararia e o laço
;; pareceria não ter interceptor nenhum.

(def ToolLoopInspectorDef
  [:map {:closed true}
   [:before-llm-call   {:optional true} ifn?]
   [:after-llm-call    {:optional true} ifn?]
   [:after-tool-result {:optional true} ifn?]
   [:after-iteration   {:optional true} ifn?]])

(def ToolLoopTransformerDef ToolLoopInspectorDef)

(def ToolCallInspectorDef
  [:map {:closed true}
   [:before-tool-call {:optional true} ifn?]
   [:after-tool-call  {:optional true} ifn?]])

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
   ;; :hybrid exige embabel-agent 0.5.0+ (o 0.4.0 não tem o valor no enum);
   ;; a checagem por VERSÃO é do core/process-options, aqui é só o vocabulário.
   [:planner    {:optional true} [:enum :goap :utility :hybrid :supervisor]]
   [:listeners  {:optional true} [:sequential ListenerLike]]
   ;; policies SOMADAS às do :budget (o budget já é três delas), não substituem
   [:early-termination {:optional true} [:sequential PolicyLike]]
   ;; contexto visível a TODA tool do processo (auth, correlation-id, tenant)
   [:tool-call-context {:optional true} [:map-of [:or :string :keyword] :any]]
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
