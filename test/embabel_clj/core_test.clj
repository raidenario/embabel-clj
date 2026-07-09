(ns embabel-clj.core-test
  "Smoke de interop: constrói os objetos REAIS do Embabel 0.4.0 (sem subir
   Spring). Pega quebra de assinatura de ctor/interface na hora."
  (:require [clojure.test :refer [deftest is testing]]
            [embabel-clj.core :as ec])
  (:import [com.embabel.agent.core Agent ProcessOptions]
           [com.embabel.agent.api.common PlannerType]))

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
