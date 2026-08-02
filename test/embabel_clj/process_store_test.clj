(ns embabel-clj.process-store-test
  "A tese B em testes: o objeto do processo é efêmero e despejável; a história
   dele não precisa ser. Usa as classes REAIS do Embabel (ActionInvocation,
   AgentProcessStatusCode, AgentProcessRepository) e um AgentProcess reify
   parcial — se a projeção tocasse num método não implementado sem blindagem,
   estouraria AbstractMethodError e o teste pegaria."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [embabel-clj.process-store :as ps])
  (:import [com.embabel.agent.core ActionInvocation AgentProcess AgentProcessRepository
            AgentProcessStatusCode]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Duration Instant]
           [org.springframework.context.support GenericApplicationContext]))

(defrecord Produto [nome preco])

(defn- stub-process
  "AgentProcess mínimo: só o que a projeção precisa. Tudo o mais (getAgent,
   getGoal, getPlanner, getProcessOptions...) fica ausente de propósito — o
   teste prova que a blindagem transforma AbstractMethodError em nil."
  (^AgentProcess [id] (stub-process id {}))
  (^AgentProcess [id {:keys [status history objects bindings]}]
   (reify AgentProcess
     (getId [_] id)
     (getParentId [_] nil)
     (getStatus [_] (or status AgentProcessStatusCode/RUNNING))
     (getHistory [_] (or history []))
     (getObjects [_] (or objects []))
     (getLlmInvocations [_] [])
     (getTimestamp [_] (Instant/parse "2026-07-30T18:00:00Z"))
     (getRunningTime [_] (Duration/ofMillis 42))
     (expressionEvaluationModel [_] (or bindings {})))))

(defn- invocation [nome ms]
  (ActionInvocation. nome (Instant/parse "2026-07-30T18:00:01Z") (Duration/ofMillis ms)))

(defn- temp-file ^java.io.File [nome]
  (let [dir (Files/createTempDirectory "embabel-clj-store" (make-array FileAttribute 0))]
    (io/file (str dir) nome)))

;; ---------------------------------------------------------------------------

(deftest edn-safe-marca-a-fronteira-valor-objeto
  (testing "escalares e coleções atravessam intactos"
    (is (= {:a 1 :b ["x" :y true nil]} (ps/edn-safe {:a 1 :b ["x" :y true nil]}))))
  (testing "tipos JVM temporais viram valores legíveis"
    (is (= "2026-07-30T18:00:00Z" (ps/edn-safe (Instant/parse "2026-07-30T18:00:00Z"))))
    (is (= 1500 (ps/edn-safe (Duration/ofMillis 1500))))
    (is (= :not-started (ps/edn-safe AgentProcessStatusCode/NOT_STARTED))))
  (testing "defrecord sobrevive COM o tipo de domínio — a camada tipada no log"
    (let [m (ps/edn-safe (->Produto "café" 12.5))]
      (is (= "café" (:nome m)))
      (is (= 12.5 (:preco m)))
      (is (= "embabel_clj.process_store_test.Produto" (:embabel-clj/type m)))))
  (testing "o que NÃO é valor vira lápide, com o tipo visível"
    (let [m (ps/edn-safe (java.io.ByteArrayInputStream. (byte-array 3)))]
      (is (= "java.io.ByteArrayInputStream" (:embabel-clj/type m)))
      (is (string? (:embabel-clj/repr m)))))
  (testing "o resultado inteiro faz round-trip por pr-str/read-string"
    (let [v {:objs [(ps/edn-safe (->Produto "café" 12.5))
                    (ps/edn-safe (Object.))]}]
      (is (= v (clojure.edn/read-string (pr-str v)))))))

(deftest projecao-nunca-derruba-o-processo-observado
  (let [m (ps/process->map (stub-process "p-0"))]
    (testing "o que o stub implementa chega"
      (is (= "p-0" (:id m)))
      (is (= :running (:status m)))
      (is (= "2026-07-30T18:00:00Z" (:at m)))
      (is (= 42 (:running-ms m))))
    (testing "o que ele NÃO implementa vira nil, não AbstractMethodError"
      (is (nil? (:agent m)))
      (is (nil? (:goal m)))
      (is (nil? (:planner m)))
      (is (nil? (:ephemeral? m))))
    (testing "custo e tokens vêm de graça do LlmInvocationHistory"
      (is (= 0.0 (:cost m)))
      (is (map? (:tokens m))))))

(deftest historia-e-bindings-entram-no-log
  (let [m (ps/process->map
           (stub-process "p-1" {:history  [(invocation "extrair" 120) (invocation "revisar" 80)]
                                :objects  [(->Produto "café" 12.5)]
                                :bindings {"it" "resultado"}}))]
    (is (= ["extrair" "revisar"] (mapv :action (:history m))))
    (is (= [120 80] (mapv :ms (:history m))))
    (is (= {"it" "resultado"} (:bindings m)))
    (is (= "café" (-> m :objects first :nome)))))

;; ---------------------------------------------------------------------------

(defn- recording-delegate
  "Delegate que só lembra do ÚLTIMO processo salvo — o `windowSize` do framework
   levado ao extremo, para reproduzir num teste o que em produção acontece no
   processo 1001 (janela padrão 1000, despejo do mais antigo)."
  [state]
  (reify AgentProcessRepository
    (findById [_ id] (get @state id))
    (findByParentId [_ _] [])
    (save [_ p] (reset! state {(.getId ^AgentProcess p) p}) p)
    (update [_ _] nil)
    (delete [_ p] (swap! state dissoc (.getId ^AgentProcess p)) nil)))

(deftest decora-o-delegate-e-registra-cada-chamada
  (let [visto (atom [])
        state (atom {})
        ^AgentProcessRepository repo (ps/edn-repository {:delegate  (recording-delegate state)
                                                         :on-record #(swap! visto conj %)})
        p (stub-process "p-2")]
    (.save repo p)
    (.update repo p)
    (.delete repo p)
    (testing "um registro por chamada, na ordem, com :seq monotônico"
      (is (= [:save :update :delete] (mapv :event @visto)))
      (is (= [1 2 3] (mapv :seq @visto))))
    (testing "o delegate continua sendo quem responde pelo objeto vivo"
      (is (= {} @state)))
    (testing "cada registro carrega o processo projetado"
      (is (= "p-2" (get-in (first @visto) [:process :id]))))))

(deftest a-historia-sobrevive-ao-despejo
  (let [f     (temp-file "processes.edn")
        state (atom {})
        ^AgentProcessRepository repo (ps/edn-repository {:file     (str f)
                                                         :delegate (recording-delegate state)})
        p1 (stub-process "p-antigo" {:history [(invocation "extrair" 10)]})
        p2 (stub-process "p-novo")]
    (.save repo p1)
    (.update repo p1)
    (.update repo p1)
    (.save repo p2)                       ; <- despeja p-antigo do delegate

    (testing "o delegate esqueceu o processo antigo (é o comportamento do framework)"
      (is (nil? (.findById repo "p-antigo")))
      (is (some? (.findById repo "p-novo"))))

    (let [log (ps/read-log f)]
      (testing "…mas a história dele está inteira no log, e é lida de disco"
        (is (= 4 (count log)))
        (is (= [:save :update :update] (mapv :event (ps/timeline log "p-antigo"))))
        (is (= ["extrair"] (-> (ps/timeline log "p-antigo") last :process :history
                               (->> (mapv :action))))))
      (testing "e vira relatório sem tocar no framework"
        (is (= {:records 4 :processes 2 :cost 0.0 :ticks 4 :by-status {:running 2}}
               (ps/summary log)))
        (is (= ["p-antigo" "p-novo"] (mapv :id (ps/runs log))))
        (is (= 3 (:ticks (first (ps/runs log)))))))))

(deftest as-of-e-log-corrompido
  (let [f (temp-file "processes.edn")]
    (spit f (str (pr-str {:event :save :seq 1 :at "2026-07-30T18:00:00Z"
                          :process {:id "a" :status :running}}) "\n"
                 (pr-str {:event :update :seq 2 :at "2026-07-30T20:00:00Z"
                          :process {:id "a" :status :completed}}) "\n"
                 "{:event :update :seq 3 :process {:id \n"))   ; linha cortada por crash
    (let [log (ps/read-log f)]
      (testing "linha corrompida não derruba a leitura"
        (is (= 2 (count log))))
      (testing "as-of devolve o que o sistema sabia naquele instante"
        (is (= [:running]   (mapv :status (ps/as-of log "2026-07-30T19:00:00Z"))))
        (is (= [:completed] (mapv :status (ps/as-of log "2026-07-30T21:00:00Z")))))
      (testing "comparação é por Instant, não por string ISO"
        ;; "...:00.500Z" é DEPOIS de "...:00Z" no tempo e ANTES em ordem lexicográfica
        (is (= [:running] (mapv :status (ps/as-of log "2026-07-30T18:00:00.500Z"))))))))

;; ---------------------------------------------------------------------------

(deftest registra-como-bean-primary
  (let [repo  (ps/edn-repository {})
        outro (ps/edn-repository {})
        ctx   (GenericApplicationContext.)]
    (.registerSingleton (.getBeanFactory ctx) "frameworkRepo" outro)
    (.initialize (ps/as-primary-bean repo) ctx)
    (.refresh ctx)
    (testing "a definição é primary"
      (is (true? (.isPrimary (.getBeanDefinition ctx "embabelCljAgentProcessRepository")))))
    (testing "com DOIS candidatos do mesmo tipo (o @Bean do framework é incondicional),
              a resolução por tipo escolhe o nosso"
      (is (identical? repo (.getBean ctx AgentProcessRepository))))
    (.close ctx)))
