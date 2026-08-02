(ns embabel-clj.prompt-test
  "Prompt contributors e thinking como DADO — as duas peças do nível 3 que são
   coerção, não wrapper: o framework já expõe as fábricas."
  (:require [clojure.test :refer [deftest is testing]]
            [embabel-clj.schema :as schema])
  (:import [com.embabel.common.ai.model Thinking]
           [com.embabel.common.ai.prompt PromptContributor]))

(deftest prompt-contributor-a-partir-de-dado
  (testing "string vira contribuição fixa"
    (let [pc (schema/->prompt-contributor "Você é um naturalista.")]
      (is (instance? PromptContributor pc))
      (is (= "Você é um naturalista." (.contribution pc)))))

  (testing "fn de zero args é RECALCULADA a cada chamada (PromptContributor/dynamic)"
    (let [n  (atom 0)
          pc (schema/->prompt-contributor (fn [] (str "chamada " (swap! n inc))))]
      (is (= "chamada 1" (.contribution pc)))
      (is (= "chamada 2" (.contribution pc)))
      (is (= 2 @n))))

  (testing "o que já é PromptContributor passa direto"
    (let [pronto (PromptContributor/fixed "x")]
      (is (identical? pronto (schema/->prompt-contributor pronto)))))

  (testing "os membros role/location são DEFAULT na interface — nada a implementar.
            `role` é NULÁVEL por default (só a location tem valor); é por isso
            que uma fn de zero args basta como contributor."
    (let [pc (schema/->prompt-contributor "oi")]
      (is (nil? (.getRole pc)))
      (is (some? (.getPromptContributionLocation pc)))
      (is (some? (.promptContribution pc)))))

  (testing "forma inválida falha com erro nomeado"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"prompt-contributor deve ser"
                          (schema/->prompt-contributor 42)))))

(deftest thinking-a-partir-de-dado
  (let [->t #'schema/->thinking]
    (testing ":extraction e orçamento de tokens"
      (is (instance? Thinking (->t :extraction)))
      (is (instance? Thinking (->t 2048))))
    (testing "um Thinking pronto passa direto"
      (let [pronto (Thinking/withExtraction)]
        (is (identical? pronto (->t pronto)))))
    (testing "forma inválida falha com erro nomeado"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":thinking deve ser"
                            (->t "muito"))))))

(deftest thinking-entra-no-llm-options
  (testing "pedir :thinking já liga o caminho de LlmOptions (tune?), sem
            precisar de :max-tokens/:temperature"
    (let [opts (#'schema/llm-options {:llm "gpt-4o-mini" :thinking :extraction})]
      (is (some? (.getThinking opts))))))
