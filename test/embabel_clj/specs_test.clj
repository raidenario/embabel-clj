(ns embabel-clj.specs-test
  (:require [clojure.test :refer [deftest is testing]]
            [embabel-clj.core :as ec]))

(defn- humanized-errors [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo e
      (:errors (ex-data e)))))

(deftest typo-em-chave-falha-na-construcao
  (testing "o typo clássico :cots (era :cost) — a dor do artigo — vira erro humanizado"
    (let [errors (humanized-errors
                  #(ec/action {:name "x" :post ["ok?"] :cots 0.1
                               :fn identity}))]
      (is (some? errors))
      (is (contains? errors :cots))))
  (testing "typo em goal"
    (let [errors (humanized-errors
                  #(ec/goal {:name "g" :pre ["ok?"] :valeu 1.0}))]
      (is (contains? errors :valeu)))))

(deftest nome-de-condicao-nao-pode-ter-dois-pontos
  (testing "':' no nome dispara o ramo de data-binding do determiner — rejeitado"
    (is (some? (humanized-errors
                #(ec/action {:name "x" :post [":work/done?"] :fn identity}))))
    (is (some? (humanized-errors
                #(ec/goal {:name "g" :pre ["a:b"]}))))))

(deftest keywords-namespaced-valem-como-nome-de-condicao
  (testing "keyword :graph/consistent? é aceita (vira \"graph/consistent?\")"
    (let [a (ec/action {:name "x" :pre [:graph/unblocked?]
                        :post [:graph/consistent?] :fn identity})]
      (is (= ["graph/unblocked?"] (vec (.getPre a))))
      (is (= ["graph/consistent?"] (vec (.getPost a)))))))

(deftest agent-def-fechado
  (testing "chave desconhecida no agent"
    (is (contains? (humanized-errors
                    #(ec/agent {:name "a" :description "d"
                                :goals [] :actions [] :extra 1}))
                   :extra)))
  (testing "description é obrigatória (higiene: o LLM/plataforma a lê)"
    (is (some? (humanized-errors
                #(ec/agent {:name "a" :goals [] :actions []}))))))
