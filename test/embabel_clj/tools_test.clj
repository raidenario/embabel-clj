(ns embabel-clj.tools-test
  "Fn Clojure como tool do LLM: round-trip completo pelo adapter Spring AI
   (o mesmo caminho que uma chamada real de tool percorre), sem LLM."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [embabel-clj.tools :as tools])
  (:import [com.embabel.agent.api.tool Tool]
           [com.embabel.agent.spi.support.springai SpringToolCallbackAdapter]))

(def soma
  (tools/tool
   {:name        "soma"
    :description "Soma dois números."
    :schema      [:map
                  [:a {:description "primeira parcela"} :double]
                  [:b {:description "segunda parcela"} :double]]
    :fn          (fn [{:keys [a b]}] (+ a b))}))

(deftest tool-de-fn-clojure
  (is (instance? Tool soma))
  (let [adapter (SpringToolCallbackAdapter. soma)
        defn*   (.getToolDefinition adapter)]
    (testing "o modelo vê nome/descrição/JSON Schema derivado do malli"
      (is (= "soma" (.name defn*)))
      (is (str/includes? (.inputSchema defn*) "\"a\""))
      (is (str/includes? (.inputSchema defn*) "primeira parcela")))
    (testing "chamada real (args JSON, com coerção string->double do malli)"
      (is (str/includes? (.call adapter "{\"a\": 2.5, \"b\": \"4.25\"}") "6.75")))))

(deftest tool-args-invalidos-viram-erro-legivel
  (let [t (tools/tool {:name "eco" :description "eco"
                       :schema [:map [:msg :string]]
                       :fn :msg})
        adapter (SpringToolCallbackAdapter. t)
        out (try (.call adapter "{\"outro\": 1}")
                 (catch Throwable e (str (.getMessage e) (ex-message (ex-cause e)))))]
    ;; o erro (Result/error) volta pro MODELO se corrigir — nunca some calado
    (is (str/includes? (str out) "msg"))))

(deftest json-helpers
  (is (= "{\"a\":1,\"co/dominio\":\"x\"}"
         (tools/->json {:a 1 :co/dominio "x"})))
  (is (= {:a 1.0 :b [1 2]} (tools/parse-json "{\"a\":1.0,\"b\":[1,2]}"))))
