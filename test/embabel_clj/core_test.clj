(ns embabel-clj.core-test
  "Smoke de interop: constrói os objetos REAIS do Embabel 0.4.0 (sem subir
   Spring). Pega quebra de assinatura de ctor/interface na hora."
  (:require [clojure.test :refer [deftest is testing]]
            [embabel-clj.blackboard :as bb]
            [embabel-clj.core :as ec])
  (:import [com.embabel.agent.core Agent AgentPlatform AgentProcess Blackboard
            ProcessOptions]
           [com.embabel.agent.api.common PlannerType]
           [java.util.concurrent CompletableFuture CompletionException]))

;; --- vars taggeadas para o agent-from-ns -----------------------------------

(defn seed-domains
  "Junta domínios candidatos."
  {:action/post [:co/domain-known?] :action/cost 0.1 :action/llm true}
  [_ctx] :ok)

(defn generate-verify
  {:action/pre [:co/domain-known?] :action/post [:mail/verified?]
   :action/cost 0.2 :action/rerun true}
  [_ctx] :ok)

(defn needs-evidence?
  {:condition/name :co/needs-evidence? :condition/cost 0.0}
  [_ctx] false)

;; ----------------------------------------------------------------------------

(deftest constroi-agente-completo
  (let [ag (ec/agent
            {:name "smoke" :description "agente de fumaça"
             :goals      [{:name "done" :description "fim" :pre [:ok?] :value 1.0}]
             :conditions [{:name :derived/ok? :fn (fn [_] true)}]
             :actions    [{:name "work" :post [:ok?] :cost 0.1 :rerun? true
                           :fn (fn [_ctx] :done)}]})]
    (is (instance? Agent ag))
    (is (= "smoke" (.getName ag)))
    (is (= ["work"] (mapv #(.getName %) (.getActions ag))))
    (is (= ["derived/ok?"] (mapv #(.getName %) (seq (.getConditions ag)))))
    (testing "cost do action alimenta o A* de verdade (não é 0.0 fixo)"
      (is (= 0.1 (-> ag .getActions first .getCost (.invoke nil)))))
    (testing "canRerun repassado (sem ele o Embabel injeta hasRun_<name>)"
      (is (true? (-> ag .getActions first .getCanRerun))))
    (testing "value do goal"
      (is (= 1.0 (-> ag .getGoals first .getValue (.invoke nil)))))))

(deftest agent-from-ns-le-as-tags
  (let [ag (ec/agent-from-ns 'embabel-clj.core-test
                             {:name "email-hunter-mini"
                              :description "lê tags desta própria ns"
                              :goals [{:name "found" :pre [:mail/verified?]
                                       :value 1.0}]})]
    (is (= ["generate-verify" "seed-domains"]
           (sort (mapv #(.getName %) (.getActions ag)))))
    (is (= ["co/needs-evidence?"]
           (mapv #(.getName %) (seq (.getConditions ag)))))
    (testing "a docstring vira description quando :action/description falta"
      (let [seed (first (filter #(= "seed-domains" (.getName %)) (.getActions ag)))]
        (is (= "Junta domínios candidatos." (.getDescription seed)))))))

(deftest process-options-a-partir-de-mapa
  (testing "default"
    (is (instance? ProcessOptions (ec/process-options nil))))
  (testing "budget nativo (o anti-loop de primeira classe)"
    (let [po (ec/process-options {:budget {:cost 2.0 :actions 40 :tokens 100000}})]
      (is (= 2.0 (-> po .getBudget .getCost)))
      (is (= 40  (-> po .getBudget .getActions)))))
  (testing "planner selecionável — GOAP, UTILITY e SUPERVISOR existem no 0.4.0"
    (is (= PlannerType/UTILITY
           (.getPlannerType (ec/process-options {:planner :utility}))))
    (is (= PlannerType/SUPERVISOR
           (.getPlannerType (ec/process-options {:planner :supervisor})))))
  (testing "typo em run options"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ec/process-options {:bugdet {:cost 1.0}})))))

(deftest planner-hybrid-adaptativo
  ;; HYBRID entrou no 0.5.0. A lib resolve o enum por Enum/valueOf em vez de
  ;; PlannerType/HYBRID literal: como os .clj vão CRUS no jar (NO-AOT), um campo
  ;; estático ausente derrubaria a carga da ns inteira sob :probe-040 — não só
  ;; esta chamada. Por isso o teste é condicional à versão do classpath.
  (let [tem-hybrid? (some? (try (Enum/valueOf PlannerType "HYBRID")
                                (catch IllegalArgumentException _ nil)))]
    (if tem-hybrid?
      (testing "0.5.0+: :hybrid chega ao ProcessOptions"
        (is (= "HYBRID"
               (str (.getPlannerType (ec/process-options {:planner :hybrid}))))))
      (testing "0.4.0: :hybrid falha com erro nomeando os planners disponíveis"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"não existe nesta versão"
                              (ec/process-options {:planner :hybrid})))))
    (testing "planner desconhecido é typo, pego pelo :closed do RunOptions"
      (is (thrown? clojure.lang.ExceptionInfo
                   (ec/process-options {:planner :goapp}))))))

(deftest nirvana-e-o-par-do-hybrid
  (let [n (ec/nirvana)]
    (is (instance? com.embabel.agent.core.Goal n))
    (testing "a pré-condição é inalcançável de propósito — NIRVANA nunca é satisfeito"
      (is (= #{"__unobtanium__"} (set (.getPre n)))))
    (testing "serve como goal de um agente, junto do goal terminal de verdade"
      (let [ag (ec/agent {:name "hib" :description "utility + saída limpa"
                          :goals   [n {:name "done" :pre [:ok?] :value 1.0}]
                          :actions [{:name "work" :post [:ok?] :fn (fn [_] :ok)}]})]
        (is (= 2 (count (.getGoals ag))))))))

(deftest condicao-lazy-avalia
  (let [c (ec/condition {:name :sempre/sim? :fn (fn [_] true)})]
    (is (= "sempre/sim?" (.getName c)))
    (is (= 0.0 (.getCost c)))))

;; --- camada TIPADA (IoBindings) + tool groups -------------------------------

(defrecord Pedido [id])

(deftest camada-tipada-io-bindings
  (testing "outputs/inputs tipados chegam ao Action (Class, string e mapa)"
    (let [a (ec/action {:name "produz" :outputs [Pedido]
                        :fn (fn [_] :ok)})
          b (ec/action {:name "consome"
                        :inputs [{:name "pedido" :type Pedido}]
                        :fn (fn [_] :ok)})]
      (is (= 1 (count (.getOutputs a))))
      (is (.contains (str (first (.getOutputs a))) "Pedido"))
      (is (.contains (str (first (.getInputs b))) "pedido:"))))
  (testing "goal com :inputs (a pré-condição tipada)"
    (let [g (ec/goal {:name "done" :inputs [Pedido] :value 1.0})]
      (is (= 1 (count (.getInputs g))))))
  (testing "a string \"name:pkg.Type\" também vale"
    (let [a (ec/action {:name "x" :inputs ["it:java.lang.String"]
                        :fn (fn [_] :ok)})]
      (is (= 1 (count (.getInputs a)))))))

(deftest tool-groups-na-action
  (let [a (ec/action {:name "pesquisa" :tool-groups [:web]
                      :llm? true :fn (fn [_] :ok)})]
    (is (= ["web"] (mapv #(.getRole %) (.getToolGroups a))))))

;; --- invoking (item 1.7) ----------------------------------------------------

(deftest last-of-le-a-camada-tipada
  ;; O lado de LEITURA da camada tipada: a action declara :outputs [Pedido], e
  ;; no fim você pega o objeto pelo TIPO, sem saber o nome do slot.
  (let [objs [(->Pedido 1) "um texto" (->Pedido 2)]
        bbd  (reify Blackboard (getObjects [_] objs))]
    (testing "devolve o ÚLTIMO daquele tipo"
      (is (= (->Pedido 2) (bb/last-of bbd Pedido))))
    (testing "funciona com qualquer Class, não só defrecord"
      (is (= "um texto" (bb/last-of bbd String))))
    (testing "tipo ausente = nil"
      (is (nil? (bb/last-of bbd java.util.Date))))
    (testing "last-result não precisa do tipo"
      (is (= (->Pedido 2) (bb/last-result bbd))))))

(deftest tool-call-context-no-run-options
  (testing "contexto visível a toda tool do processo; keywords viram strings"
    (let [m (.toMap (.getToolCallContext
                     (ec/process-options {:tool-call-context {:tenant "acme"
                                                              :corr/id "7"
                                                              "raw" 1}})))]
      (is (= "acme" (get m "tenant")))
      (is (= "7" (get m "corr/id")) "keyword namespaced vira \"ns/nome\"")
      (is (= 1 (get m "raw")))))
  (testing "ausente = o contexto vazio do framework"
    (is (true? (.isEmpty (.getToolCallContext (ec/process-options nil)))))))

(defn- fake-platform
  "AgentPlatform mínimo: só os dois métodos que run-async! usa."
  [proc fut visto]
  (reify AgentPlatform
    (createAgentProcess [_ _ag opts bindings]
      (reset! visto {:options opts :bindings (into {} bindings)})
      proc)
    (start [_ p] (swap! visto assoc :started p) fut)))

(deftest run-async-cria-e-dispara
  (let [proc  (reify AgentProcess (getId [_] "async-1"))
        fut   (CompletableFuture/completedFuture proc)
        visto (atom {})
        ag    (ec/agent {:name "a" :description "d"
                         :goals   [{:name "done" :pre [:ok?] :value 1.0}]
                         :actions [{:name "w" :post [:ok?] :fn (fn [_] :ok)}]})
        r     (ec/run-async! (fake-platform proc fut visto) ag
                             {:bindings {:x 1 :co/y "z"}
                              :options  {:budget {:cost 0.5}}})]
    (testing "bindings viram chaves string, como no run! síncrono"
      (is (= {"x" 1 "co/y" "z"} (:bindings @visto))))
    (testing "as run options chegam como ProcessOptions de verdade"
      (is (= 0.5 (-> @visto :options .getBudget .getCost))))
    (testing "o processo criado é o que foi dado ao start"
      (is (identical? proc (:started @visto))))
    (testing "devolve o future do framework, e join! o resolve"
      (is (instance? CompletableFuture r))
      (is (identical? proc (ec/join! r)))
      (is (identical? proc (ec/join! r {:timeout-s 5}))))))

(deftest run-async-callbacks
  (let [proc (reify AgentProcess (getId [_] "async-2"))]
    (testing ":on-complete recebe o processo"
      (let [chegou (atom nil)
            fut    (CompletableFuture/completedFuture proc)]
        (ec/join! (ec/run-async! (fake-platform proc fut (atom {}))
                                 (ec/agent {:name "a" :description "d"
                                            :goals [{:name "g" :pre [:ok?]}]
                                            :actions [{:name "w" :post [:ok?]
                                                       :fn (fn [_] :ok)}]})
                                 {:on-complete #(reset! chegou (.getId %))}))
        (is (= "async-2" @chegou))))

    (testing ":on-error recebe o throwable"
      (let [erro (atom nil)
            fut  (doto (CompletableFuture.)
                   (.completeExceptionally (RuntimeException. "explodiu")))
            r    (ec/run-async! (fake-platform proc fut (atom {}))
                                (ec/agent {:name "a" :description "d"
                                           :goals [{:name "g" :pre [:ok?]}]
                                           :actions [{:name "w" :post [:ok?]
                                                      :fn (fn [_] :ok)}]})
                                {:on-error #(reset! erro (.getMessage %))})]
        (is (thrown? CompletionException (ec/join! r)))
        (is (re-find #"explodiu" @erro))))))
