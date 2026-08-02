(ns embabel-clj.events-test
  "AgenticEventListener como fn: constrói eventos REAIS do Embabel e chama a
   interface de verdade (sem Spring). Pega quebra de assinatura na hora."
  (:require [clojure.test :refer [deftest is testing]]
            [embabel-clj.core :as ec]
            [embabel-clj.events :as events])
  (:import [com.embabel.agent.api.event AgenticEventListener MulticastAgenticEventListener
            ProgressUpdateEvent]
           [com.embabel.agent.core AgentProcess ProcessOptions]))

(defn- stub-process
  "AgentProcess mínimo. É reify de propósito: se a projeção de evento tocasse
   em qualquer coisa além de getId (history, statusReport, blackboard...),
   este stub estouraria AbstractMethodError — o teste PROVA o skip-list."
  ^AgentProcess [id]
  (reify AgentProcess
    (getId [_] id)))

(defn- progress-event ^ProgressUpdateEvent [id]
  (ProgressUpdateEvent. (stub-process id) "carregando" (int 3) (int 10)))

(deftest evento-vira-mapa
  (let [m (events/event->map (progress-event "p-1") :process)]
    (testing "o tipo vira keyword, sem o sufixo Event"
      (is (= :progress-update (:event m)))
      (is (= "ProgressUpdateEvent" (:class m)))
      (is (= :process (:scope m))))
    (testing "as props do evento chegam em kebab-case"
      (is (= "p-1" (:process-id m)))
      (is (= "carregando" (:name m)))
      (is (= 3 (:current m)))
      (is (= 10 (:total m)))
      (is (instance? java.time.Instant (:timestamp m))))
    (testing "o objeto cru continua acessível"
      (is (instance? ProgressUpdateEvent (:raw m))))
    (testing "props caras/cíclicas ficam de fora (só via :raw)"
      (is (not (contains? m :agent-process)))
      (is (not (contains? m :history)))
      (is (not (contains? m :status))))))

(deftest listener-como-fn
  (testing "fn simples recebe os dois canais"
    (let [visto (atom [])
          ^AgenticEventListener l (events/listener #(swap! visto conj %))]
      (.onProcessEvent l (progress-event "p-2"))
      (is (= [:progress-update] (mapv :event @visto)))
      (is (= [:process] (mapv :scope @visto)))))

  (testing "mapa de canais roteia: só :on-process dispara em evento de processo"
    (let [proc (atom 0) plat (atom 0)
          ^AgenticEventListener l (events/listener {:on-process  (fn [_] (swap! proc inc))
                                                    :on-platform (fn [_] (swap! plat inc))})]
      (.onProcessEvent l (progress-event "p-3"))
      (is (= 1 @proc))
      (is (= 0 @plat))))

  (testing "canal ausente não quebra"
    (let [^AgenticEventListener l (events/listener {:on-platform (fn [_] :nunca)})]
      (is (nil? (.onProcessEvent l (progress-event "p-4"))))))

  (testing "typo no mapa de canais falha na CONSTRUÇÃO (:closed)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"disallowed key"
                          (events/listener {:on-proces (fn [_])}))))

  (testing "o framework aceita o nosso reify (round-trip pelo multicast dele)"
    (let [visto (atom [])
          mc    (MulticastAgenticEventListener.
                 [(events/listener #(swap! visto conj (:event %)))])]
      (.onProcessEvent mc (progress-event "p-5"))
      (is (= [:progress-update] @visto)))))

(deftest recording-listener-acumula
  (let [[l log] (events/recording-listener)]
    (.onProcessEvent ^AgenticEventListener l (progress-event "p-6"))
    (.onProcessEvent ^AgenticEventListener l (progress-event "p-6"))
    (is (= 2 (count @log)))
    (is (= [:progress-update :progress-update] (mapv :event @log))))

  (testing ":xf filtra/projeta na entrada (não guardar o mundo)"
    (let [[l log] (events/recording-listener
                   {:xf (comp (filter (comp #{:progress-update} :event))
                              (map :current))})]
      (.onProcessEvent ^AgenticEventListener l (progress-event "p-7"))
      (is (= [3] @log)))))

(deftest listeners-no-process-options
  (testing "fn, mapa de canais e instância pronta convivem no :listeners"
    (let [^ProcessOptions po
          (ec/process-options {:listeners [(fn [_ev] :ok)
                                           {:on-process (fn [_ev] :ok)}
                                           (events/listener (fn [_ev] :ok))]})]
      (is (= 3 (count (.getListeners po))))
      (is (every? #(instance? AgenticEventListener %) (.getListeners po)))))

  (testing "o listener plugado pelo run options recebe evento de verdade"
    (let [visto (atom [])
          ^ProcessOptions po (ec/process-options
                              {:listeners [#(swap! visto conj (:process-id %))]})]
      (doseq [^AgenticEventListener l (.getListeners po)]
        (.onProcessEvent l (progress-event "p-8")))
      (is (= ["p-8"] @visto))))

  (testing "typo dentro do listener é pego pelo RunOptions"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ec/process-options {:listeners [{:on-proces (fn [_])}]})))))
