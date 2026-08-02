(ns embabel-clj.events
  "AgenticEventListener como FN — o telemetry do Embabel como dado.

   O framework emite o processo inteiro como eventos: plano formulado, action
   iniciada/terminada, goal alcançado, chamada de LLM, tool pedida/respondida,
   processo concluído/travado. Em Kotlin/Java você implementa a interface
   `AgenticEventListener` numa classe; aqui é uma fn que recebe um MAPA.

     (require '[embabel-clj.events :as events])

     (ec/run! platform ag
       {:options {:listeners [(fn [ev] (println (:event ev) (:process-id ev)))]}})

   ou, separando os dois canais:

     {:listeners [{:on-process  (fn [ev] ...)     ; eventos de um processo
                   :on-platform (fn [ev] ...)}]}  ; deploy, ranking, criação

   Cada evento vira um mapa com `:event` (o tipo, keyword), `:scope`
   (:process|:platform), `:timestamp`, as propriedades do evento lidas por
   reflexão, e `:raw` (o objeto Java, para o que a projeção não cobre):

     {:event      :action-execution-start
      :scope      :process
      :class      \"ActionExecutionStartEvent\"
      :process-id \"a1b2...\"
      :timestamp  #object[java.time.Instant ...]
      :action     #object[...AbstractAction ...]
      :raw        #object[...ActionExecutionStartEvent ...]}

   A projeção é genérica de propósito: os tipos concretos de evento mudam a
   cada release do Embabel, e listar campo a campo envelheceria. O que a lib
   promete é a FORMA (mapa com :event/:scope/:raw), não o conjunto de chaves.

   O listener entra pelo `ProcessOptions` (`:listeners` no mapa de run options
   — ver embabel-clj.core/process-options), que existe desde o 0.4.0: não
   precisa de bean Spring nem de @Configuration.

   ⚠️ **Esse canal é PARCIAL — medido, não suposto.** O `ProcessContext` compõe
   `processOptions.listeners + platformServices.eventListener`, mas o
   `AbstractAgentProcess` emite AgentProcessCompleted/Failed/Waiting/Paused
   DIRETO no `platformServices.eventListener`, pulando o composite. Medição em
   40 execuções (experiments/exp/e5): pelo `:listeners` chegaram **120 eventos
   de 1 tipo** (`:object-bound`); pelo listener registrado como BEAN de
   plataforma, **760 eventos de 8 tipos**. Só o bean vê `:action-execution-start`,
   `:action-execution-result`, `:agent-process-creation`, `:goal-achieved`,
   `:agent-process-completed`, `:agent-process-plan-formulated` e
   `:agent-process-ready-to-plan`.

   Regra prática: `:listeners` serve para reagir a um processo específico;
   para TRAÇO DURÁVEL (chronicle, auditoria, dataset de avaliação) registre o
   listener como bean — `(platform/start! {:beans {:meuTracador (events/listener f)}})`.
   O `@Bean @Primary eventListener(listeners: List<AgenticEventListener>)` do
   framework compõe todos os beans desse tipo, então um `reify` registrado por
   `:beans` entra na composição.

   Nota de segurança do framework: exceção lançada por um listener é logada e
   engolida pelo MulticastAgenticEventListener — um listener que quebra NÃO
   derruba o processo, mas também não avisa alto. Trate os seus erros."
  (:require [embabel-clj.interop :as interop]
            [embabel-clj.specs :as specs])
  (:import [com.embabel.agent.api.event AgenticEventListener]))

(def ^:private skip-props
  "Propriedades que a projeção NÃO lê:
   - :class          — ruído (getClass)
   - :agent-process  — o AgentProcess inteiro (é @JsonIgnore no framework
                       justamente por arrastar o mundo); está em :raw
   - :history/:status — derivadas do agentProcess a CADA acesso; caras para
                       pagar em todo evento. Leia de :raw quando precisar."
  #{:class :agent-process :history :status})

(defn event->map
  "Evento do Embabel -> mapa Clojure. `scope` é :process ou :platform."
  [ev scope]
  (assoc (interop/props ev skip-props)
         :event (interop/simple-name ev "Event")
         :scope scope
         :class (.getSimpleName (class ev))
         :raw   ev))

(defn listener
  "AgenticEventListener a partir de uma fn ou de um mapa de fns.

   - fn        — recebe TODO evento (de processo e de plataforma)
   - {:on-process f :on-platform g} — um canal cada (ambos opcionais)

   A fn recebe o mapa de `event->map`; o retorno é ignorado."
  ^AgenticEventListener [f-or-map]
  (let [{:keys [on-process on-platform]}
        (if (map? f-or-map)
          (specs/validate! specs/ListenerHandlers f-or-map "listener")
          {:on-process f-or-map :on-platform f-or-map})]
    (reify AgenticEventListener
      (onProcessEvent [_ ev]
        (when on-process (on-process (event->map ev :process))))
      (onPlatformEvent [_ ev]
        (when on-platform (on-platform (event->map ev :platform)))))))

(defn ->listener
  "Coerção usada pelo `:listeners` do run options: já é um
   AgenticEventListener? passa. Senão, vira um por `listener`."
  ^AgenticEventListener [x]
  (if (instance? AgenticEventListener x) x (listener x)))

(defn recording-listener
  "Devolve `[listener log]`, onde `log` é um atom acumulando os eventos como
   mapas, na ordem em que chegaram.

     (let [[l log] (events/recording-listener)]
       (ec/run! platform ag {:options {:listeners [l]}})
       (map :event @log))
     ;; => (:agent-process-creation :agent-process-ready-to-plan ...)

   É o crônico mínimo: a mesma ideia do dice-chronicle (a execução como
   sequência de fatos legíveis), aqui em três linhas porque o evento já é
   dado. Use `:xf` para filtrar/projetar na entrada e não guardar o mundo:

     (events/recording-listener {:xf (comp (filter (comp #{:goal-achieved} :event))
                                           (map :event))})"
  ([] (recording-listener nil))
  ([{:keys [xf]}]
   (let [log  (atom [])
         conj* (if xf (xf conj) conj)]
     [(listener (fn [ev] (swap! log conj* ev))) log])))
