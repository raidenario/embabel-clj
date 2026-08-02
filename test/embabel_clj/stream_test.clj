(ns embabel-clj.stream-test
  "Streaming: prova que o seam certo é `(.promptRunner oc)`, não `(.ai oc)` —
   e que a cadeia de opções da lib NÃO perde a capacidade pelo caminho.

   Não chama LLM: `.streaming` e `.withPrompt` são configuração; só
   `.generateStream` iria ao modelo."
  (:require [clojure.test :refer [deftest is testing]]
            [embabel-clj.blackboard :as bb]
            [embabel-clj.core :as ec]
            [embabel-clj.schema :as schema])
  (:import [com.embabel.agent.api.common.streaming StreamingPromptRunner]
           [com.embabel.agent.test.integration IntegrationTestUtils]))

(defn- na-action
  "Roda um agente com uma action {:llm? true} e devolve o que `f` viu do ctx."
  [f]
  (let [visto (atom nil)]
    (ec/run! (IntegrationTestUtils/dummyAgentPlatform)
             (ec/agent {:name "s" :description "…"
                        :goals   [{:name "g" :pre ["feito"]}]
                        :actions [{:name "a" :post ["feito"] :llm? true
                                   :fn (fn [{:keys [pc] :as ctx}]
                                         (reset! visto (f ctx))
                                         (bb/set-condition! pc "feito" true))}]}))
    @visto))

(deftest o-seam-certo-e-promptRunner-nao-ai
  (let [r (na-action (fn [{:keys [oc]}]
                       {:ai        (instance? StreamingPromptRunner (.ai oc))
                        :runner    (instance? StreamingPromptRunner (.promptRunner oc))
                        :ai-class  (.getSimpleName (class (.ai oc)))
                        :run-class (.getSimpleName (class (.promptRunner oc)))}))]
    (testing "`(.ai oc)` — o seam que o `ask` usa — NÃO é streaming"
      (is (false? (:ai r)))
      (is (= "OperationContextAi" (:ai-class r))))
    (testing "`(.promptRunner oc)` é, e é ele que o `stream` usa"
      (is (true? (:runner r)))
      (is (= "DelegatingStreamingPromptRunner" (:run-class r))))))

(deftest a-cadeia-de-opcoes-preserva-o-streaming
  (testing "configurar llm/tuning/prompt-contributors NÃO devolve um PromptRunner
            comum — a capacidade sobrevive à cadeia inteira. Com o modelo dummy
            a barreira seguinte é o próprio modelo, e o erro diz isso: prova que
            passamos do guard de tipo e chegamos no de capacidade."
    (let [r (na-action
             (fn [ctx]
               (try (schema/stream ctx {:prompt              "oi"
                                        :llm                 "gpt-4o-mini"
                                        :max-tokens          64
                                        :temperature         0.2
                                        :thinking            :extraction
                                        :prompt-contributors ["seja breve"
                                                              (fn [] "e objetivo")]})
                    (catch clojure.lang.ExceptionInfo e {:msg (ex-message e) :data (ex-data e)}))))]
      (is (= "stream: o modelo configurado não suporta streaming" (:msg r)))
      (is (= "gpt-4o-mini" (:llm (:data r))))
      (is (= "com.embabel.agent.api.common.support.DelegatingStreamingPromptRunner"
             (:runner (:data r)))
          "sobreviveu à cadeia: ainda é o runner streaming do framework"))))

(deftest modelo-sem-streaming-falha-como-dado
  (testing "sem a checagem de supportsStreaming, o framework lançaria
            UnsupportedOperationException do Kotlin ('Check supportsStreaming()
            before calling streaming()') — aqui vira ex-info com o modelo"
    (let [r (na-action
             (fn [ctx]
               (try (schema/stream ctx {:prompt "x"})
                    (catch clojure.lang.ExceptionInfo e (ex-data e))
                    (catch UnsupportedOperationException _ :vazou-o-kotlin))))]
      (is (not= :vazou-o-kotlin r))
      (is (contains? r :runner)))))

(deftest stream-exige-action-llm
  (testing "sem :oc o erro é humanizado, igual ao do ask"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ctx sem :oc"
                          (schema/stream {:pc nil} {:prompt "x"})))))
