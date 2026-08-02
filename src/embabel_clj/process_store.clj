(ns embabel-clj.process-store
  "AgentProcessRepository que grava a HISTÓRIA do processo como EDN append-only.

   O Embabel só tem uma implementação de `AgentProcessRepository`:
   `InMemoryAgentProcessRepository`, com janela padrão de **1000 processos** e
   despejo do mais antigo (`ProcessRepositoryProperties.windowSize`). O processo
   1001 apaga a história do primeiro; e tudo morre com a JVM. Ao mesmo tempo o
   processo JÁ carrega o próprio log — `AgentProcess.history` — e o framework
   chama `update(this)` a cada tick (`AbstractAgentProcess.tick`). Ou seja:
   **o log existe, ele só não é persistido.**

   Esta ns é a resposta mínima: um repositório-decorador que delega o objeto
   vivo (identidade, hierarquia, despejo) para um delegate e, a cada save/update/
   delete, projeta o processo em EDN e acrescenta um registro num log. O objeto
   é efêmero; a história é durável e consultável como dado.

     (require '[embabel-clj.process-store :as ps]
              '[embabel-clj.platform :as platform])

     (def repo (ps/edn-repository {:file \"target/processes.edn\"}))

     (platform/start! {:properties {...}
                       :initializers [(ps/as-primary-bean repo)]})

     ;; depois — inclusive noutra JVM, com a primeira já morta:
     (def log (ps/read-log \"target/processes.edn\"))
     (ps/summary log)             ; => {:records 128 :processes 7 :cost 0.0142 ...}
     (ps/runs log)                ; => uma linha por processo
     (ps/timeline log \"a1b2...\") ; => a trajetória daquele processo
     (ps/as-of log \"2026-07-30T18:00:00Z\") ; => o que o sistema sabia às 18h

   ## Fronteira honesta (leia antes de prometer resume)

   Isto torna a HISTÓRIA durável, não o PROCESSO retomável. Reidratar um
   `AgentProcess` vivo a partir do log exigiria reconstruir blackboard, planner
   e platform services — e o blackboard guarda objetos JVM arbitrários. O
   framework é explícito nisso: `AgentProcess` é anotado
   `@JsonSerialize(using = ComputerSaysNoSerializer::class)` — ele se recusa a
   serializar. `edn-safe` marca essa fronteira em vez de escondê-la: o que não é
   valor vira uma lápide `{:embabel-clj/type ... :embabel-clj/repr ...}`. Um
   blackboard 100% de valores (defrecord/mapas) sai inteiro no log; um que
   guarda um handle de conexão mostra exatamente onde a disciplina foi quebrada.

   Retomada real é o passo seguinte, e o preço dela é uma regra de domínio:
   só valores entram no blackboard."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [com.embabel.agent.core ActionInvocation AgentProcess AgentProcessRepository]
           [com.embabel.agent.spi.config.spring ProcessRepositoryProperties]
           [com.embabel.agent.spi.support InMemoryAgentProcessRepository]
           [java.time Duration Instant]
           [java.util.function Supplier]
           [org.springframework.beans.factory.support BeanDefinitionRegistry RootBeanDefinition]
           [org.springframework.context ApplicationContextInitializer ConfigurableApplicationContext]))

(defmacro ^:private attempt
  "Valor de `body`, ou nil se QUALQUER Throwable escapar — inclusive
   AbstractMethodError, que é o que um reify parcial de AgentProcess lança.
   Projeção nunca derruba o processo observado (mesma política de events.clj)."
  [& body]
  `(try ~@body (catch Throwable _# nil)))

;; ---------------------------------------------------------------------------
;; edn-safe: a fronteira valor/objeto, explícita
;; ---------------------------------------------------------------------------

(def ^:private scalar? (some-fn nil? boolean? number? string? keyword? symbol?))

(defn edn-safe
  "Converte `v` em algo que `pr-str` escreve e `clojure.edn/read-string` lê de
   volta — sem tagged literals, sem `#object[...]`.

   - escalares passam
   - Instant -> string ISO; Duration -> millis; Enum -> keyword kebab-case
   - defrecord -> mapa + `:embabel-clj/type` (o tipo de domínio sobrevive)
   - mapas/coleções (Clojure ou Java) descem recursivamente
   - **qualquer outra coisa vira lápide** `{:embabel-clj/type :embabel-clj/repr}`

   A lápide é design, não fallback preguiçoso: é onde a disciplina \"só valores
   no blackboard\" aparece ou falha, por escrito, no log."
  [v]
  (cond
    (scalar? v)                  v
    (instance? Instant v)        (str v)
    (instance? Duration v)       (.toMillis ^Duration v)
    (instance? java.util.Date v) (str (.toInstant ^java.util.Date v))
    (instance? Enum v)           (-> (.name ^Enum v) str/lower-case
                                     (str/replace "_" "-") keyword)
    (record? v)                  (-> (into {} (map (fn [[k x]] [(edn-safe k) (edn-safe x)])) v)
                                     (assoc :embabel-clj/type (.getName (class v))))
    (map? v)                     (into {} (map (fn [[k x]] [(edn-safe k) (edn-safe x)])) v)
    (coll? v)                    (mapv edn-safe v)
    (instance? java.util.Map v)  (into {} (map (fn [e] [(edn-safe (key e)) (edn-safe (val e))]))
                                       (seq ^java.util.Map v))
    (instance? Iterable v)       (mapv edn-safe (seq ^Iterable v))
    :else                        {:embabel-clj/type (.getName (class v))
                                  :embabel-clj/repr (attempt (str v))}))

;; ---------------------------------------------------------------------------
;; Projeção do processo
;; ---------------------------------------------------------------------------

(defn- conditions-of
  "Nomes das condições booleanas que o planner enxerga, separados dos slots de
   dados. É possível porque o `setCondition` do blackboard grava SÓ no `_map`
   (\"Only store in _map, not in _entries\" — InMemoryBlackboard), enquanto o
   `set` de um slot grava nos dois. Logo: booleano que está no modelo de
   expressão e NÃO está em `objects` é condição.

   Ambiguidade honesta: um slot de dados cujo valor é `true` também põe
   `Boolean/TRUE` (interned) em objects, então um `true` de dado \"protege\"
   as condições verdadeiras da separação. Por isso o mapa é devolvido inteiro
   e a chave `:conditions` é uma PROJEÇÃO, não um contrato — quem retoma um
   processo deve reaplicar tudo (ver examples/experiments, E1)."
  [^AgentProcess p]
  (let [m    (.expressionEvaluationModel p)
        objs (set (.getObjects p))]
    (into {} (keep (fn [e]
                     (let [v (val e)]
                       (when (and (instance? Boolean v) (not (contains? objs v)))
                         [(key e) v]))))
          (seq m))))

(defn- invocation->map [^ActionInvocation ai]
  {:action (attempt (.getActionName ai))
   :at     (attempt (str (.getTimestamp ai)))
   :ms     (attempt (.toMillis (.getRunningTime ai)))})

(defn process->map
  "AgentProcess -> mapa EDN-safe. Todo acesso é blindado: um campo que o
   framework mude de nome (ou que um stub não implemente) vira nil, não exceção.

   `:history` é a lista de actions já executadas — o log que o Embabel mantém em
   memória e descarta. `:cost`/`:tokens`/`:models` vêm de `LlmInvocationHistory`,
   que o AgentProcess já implementa: o log nasce sendo dataset de custo."
  [^AgentProcess p]
  {:id         (attempt (.getId p))
   :parent-id  (attempt (.getParentId p))
   :ephemeral? (attempt (.getEphemeral (.getProcessOptions p)))
   :status     (attempt (edn-safe (.getStatus p)))
   :agent      (attempt (.getName (.getAgent p)))
   :goal       (attempt (some-> (.getGoal p) .getName))
   :planner    (attempt (.getSimpleName (class (.getPlanner p))))
   :at         (attempt (str (.getTimestamp p)))
   :running-ms (attempt (.toMillis (.getRunningTime p)))
   :history    (attempt (mapv invocation->map (.getHistory p)))
   :cost       (attempt (.cost p))
   :tokens     (attempt {:prompt     (.getPromptTokens (.usage p))
                         :completion (.getCompletionTokens (.usage p))})
   :models     (attempt (mapv #(.getName %) (.modelsUsed p)))
   :bindings   (attempt (edn-safe (.expressionEvaluationModel p)))
   :conditions (attempt (conditions-of p))
   :objects    (attempt (mapv edn-safe (.getObjects p)))})

;; ---------------------------------------------------------------------------
;; O repositório
;; ---------------------------------------------------------------------------

(defn- in-memory-delegate ^AgentProcessRepository [window]
  (InMemoryAgentProcessRepository.
   (cond-> (ProcessRepositoryProperties.)
     window (doto (.setWindowSize (int window))))))

(defn edn-repository
  "AgentProcessRepository que delega o objeto vivo e acrescenta a história ao log.

   Opções:
   :file       caminho do log EDN append-only (um registro por linha). Sem ele,
               nada é escrito em disco — útil com `:on-record`.
   :delegate   AgentProcessRepository que guarda os objetos vivos
               (default: InMemoryAgentProcessRepository, o mesmo do framework)
   :window     windowSize do delegate default (o do framework é 1000)
   :on-record  fn chamada com cada registro — o gancho para mandar o mesmo fato
               para o dice-chronicle, um tópico Kafka ou um Datalevin

   Cada registro é `{:event :save|:update|:delete :seq n :at \"iso\" :process {...}}`.
   `:seq` é monotônico e é a ordem canônica: `:at` em ISO NÃO ordena
   lexicograficamente (Instant/toString omite fração de segundo, então
   \"...:00Z\" e \"...:00.123Z\" comparam ao contrário do tempo real)."
  ^AgentProcessRepository
  [{:keys [file delegate window on-record]}]
  (let [^AgentProcessRepository delegate (or delegate (in-memory-delegate window))
        n     (atom 0)
        f     (when file (io/file file))
        lock  (Object.)
        emit! (fn [event p]
                (let [rec {:event   event
                           :seq     (swap! n inc)
                           :at      (str (Instant/now))
                           :process (process->map p)}]
                  (locking lock
                    (when f
                      (io/make-parents f)
                      (with-open [out (io/writer f :append true)]
                        (.write out (pr-str rec))
                        (.write out "\n")))
                    (when on-record (on-record rec)))
                  rec))]
    (reify AgentProcessRepository
      (findById [_ id] (.findById delegate id))
      (findByParentId [_ pid] (.findByParentId delegate pid))
      (save [_ p] (emit! :save p) (.save delegate p))
      (update [_ p] (emit! :update p) (.update delegate p))
      (delete [_ p] (emit! :delete p) (.delete delegate p)))))

;; ---------------------------------------------------------------------------
;; O seam do Spring
;;
;; ATENÇÃO (verificado no fonte, 1.0.0-RC1-SNAPSHOT): o @Bean
;; `agentProcessRepository` do AgentPlatformConfiguration é INCONDICIONAL — não
;; tem @ConditionalOnMissingBean. Um `registerSingleton` (o `:beans` do
;; platform/start!) criaria um SEGUNDO candidato do mesmo tipo e o
;; SpringContextPlatformServices, que injeta por tipo, estouraria
;; NoUniqueBeanDefinitionException. Por isso aqui é BeanDefinition com
;; instanceSupplier + primary: nome diferente (não sobrescreve nada, e o Spring
;; Boot proíbe override por padrão) e prioridade na resolução por tipo.
;; ---------------------------------------------------------------------------

(defn as-primary-bean
  "ApplicationContextInitializer que registra `repo` como bean @Primary do tipo
   AgentProcessRepository — para o `:initializers` do `platform/start!`:

     (platform/start! {:initializers [(ps/as-primary-bean repo)]})"
  (^ApplicationContextInitializer [repo]
   (as-primary-bean repo "embabelCljAgentProcessRepository"))
  (^ApplicationContextInitializer [repo ^String bean-name]
   (reify ApplicationContextInitializer
     (initialize [_ ctx]
       (let [^BeanDefinitionRegistry reg (.getBeanFactory ^ConfigurableApplicationContext ctx)
             bd (doto (RootBeanDefinition. (class repo))
                  (.setInstanceSupplier (reify Supplier (get [_] repo)))
                  (.setPrimary true))]
         (.registerBeanDefinition reg bean-name bd))))))

;; ---------------------------------------------------------------------------
;; Consultas puras sobre a história (nenhuma toca no framework)
;; ---------------------------------------------------------------------------

(defn read-log
  "Lê o log EDN de volta como vetor de registros. Linhas em branco são ignoradas;
   uma linha corrompida (escrita pela metade num crash) não derruba a leitura."
  [file]
  (with-open [r (io/reader file)]
    (into [] (keep #(when-not (str/blank? %) (attempt (edn/read-string %))))
          (line-seq r))))

(defn timeline
  "Registros de um processo, em ordem de `:seq`."
  [records process-id]
  (->> records
       (filter #(= process-id (get-in % [:process :id])))
       (sort-by :seq)
       vec))

(defn runs
  "Uma linha por processo: o último estado conhecido + o que só a história sabe
   (quantos ticks, quando começou, a sequência de actions)."
  [records]
  (->> records
       (group-by #(get-in % [:process :id]))
       (map (fn [[id recs]]
              (let [recs (sort-by :seq recs)
                    p    (:process (last recs))]
                {:id      id
                 :agent   (:agent p)
                 :goal    (:goal p)
                 :status  (:status p)
                 :ticks   (count recs)
                 :actions (mapv :action (:history p))
                 :cost    (:cost p)
                 :tokens  (:tokens p)
                 :from    (:at (first recs))
                 :to      (:at (last recs))})))
       (sort-by :from)
       vec))

(defn as-of
  "O último estado conhecido de cada processo ATÉ o instante `t` (Instant ou
   string ISO) — o `replay :upto` do dice-chronicle aplicado ao processo.
   Compara Instant parseado, nunca string (ver nota em `edn-repository`)."
  [records t]
  (let [^Instant t (if (instance? Instant t) t (Instant/parse (str t)))]
    (->> records
         (remove #(some-> (:at %) Instant/parse (.isAfter t)))
         runs)))

(defn summary
  "Números agregados do log — o resumo que se lê depois que a JVM morreu."
  [records]
  (let [rs (runs records)]
    {:records   (count records)
     :processes (count rs)
     :cost      (reduce + 0.0 (keep :cost rs))
     :ticks     (reduce + 0 (keep :ticks rs))
     :by-status (frequencies (keep :status rs))}))
