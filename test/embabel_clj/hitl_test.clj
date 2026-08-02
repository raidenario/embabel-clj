(ns embabel-clj.hitl-test
  "Human-in-the-loop de ponta a ponta, contra uma AgentPlatform REAL — sem
   Spring, sem LLM, sem chave: o `IntegrationTestUtils.dummyAgentPlatform()`
   vem no próprio embabel-agent-api (é `src/main`, não `src/test`).

   O que se prova aqui é o ciclo inteiro: a action pede, o processo ESTACIONA
   em WAITING, alguém responde depois, e o processo retoma até o goal."
  (:require [clojure.test :refer [deftest is testing]]
            [embabel-clj.blackboard :as bb]
            [embabel-clj.core :as ec]
            [embabel-clj.hitl :as hitl])
  (:import [com.embabel.agent.core AgentPlatform]
           [com.embabel.agent.core.hitl ConfirmationRequest TypeRequest]
           [com.embabel.agent.test.integration IntegrationTestUtils]))

(defrecord Post [texto])

(defn- plataforma ^AgentPlatform [] (IntegrationTestUtils/dummyAgentPlatform))

;; --- o agente que pede confirmação -----------------------------------------
;;
;; Modelagem que faz o ciclo fechar: o goal é TIPADO (`:inputs [Post]`), então
;; ele é alcançado quando existe um Post no blackboard — e quem põe o Post lá é
;; o próprio framework, ao aceitar a confirmação. Se a action tentasse ligar uma
;; condição depois do `confirm!`, nunca chegaria lá: `confirm!` não retorna.

(def agente-confirmacao
  {:name        "publicador"
   :description "Publica um post, com confirmação humana"
   :goals       [{:name "publicado" :description "post publicado" :inputs [Post]}]
   :actions     [{:name    "publicar"
                  :outputs [Post]
                  :fn      (fn [_] (hitl/confirm! (->Post "olá mundo") "Pode publicar?"))}]})

(deftest confirmacao-estaciona-e-retoma
  (let [plat (plataforma)
        proc (ec/run! plat (ec/agent agente-confirmacao))]

    (testing "a action pediu confirmação e o processo PAROU (não falhou)"
      (is (hitl/waiting? proc))
      (is (= "WAITING" (str (.getStatus proc)))))

    (testing "o pedido é legível como dado"
      (let [p (hitl/pending proc)]
        (is (= :confirmation (:kind p)))
        (is (= "Pode publicar?" (:message p)))
        (is (= (->Post "olá mundo") (:payload p)))
        (is (string? (:id p)))
        (is (instance? ConfirmationRequest (:raw p)))))

    (testing "aceitar promove o payload ao blackboard"
      (is (= :updated (hitl/answer! proc {:accept? true})))
      (is (= (->Post "olá mundo") (bb/last-of proc Post))))

    (testing "retomar leva ao goal, na MESMA instância de processo"
      (let [id-antes (.getId proc)
            retomado (hitl/resume! proc)]
        (is (= "COMPLETED" (str (.getStatus retomado))))
        (is (= id-antes (.getId retomado)))
        (is (identical? proc retomado))))))

(deftest recusar-nao-alcanca-o-goal
  (let [plat (plataforma)
        proc (ec/run! plat (ec/agent agente-confirmacao))]
    (is (hitl/waiting? proc))
    (testing "recusar deixa o mundo como estava"
      (is (= :unchanged (hitl/answer! proc {:accept? false})))
      (is (nil? (bb/last-of proc Post))))
    (testing "e o processo não completa"
      (is (not= "COMPLETED" (str (.getStatus (hitl/resume! proc))))))))

;; --- pedir um valor de um TIPO ---------------------------------------------

(def agente-ask
  {:name        "coletor"
   :description "Pede um Post ao humano"
   :goals       [{:name "coletado" :inputs [Post]}]
   :actions     [{:name    "coletar"
                  :outputs [Post]
                  :fn      (fn [_] (hitl/ask! Post {:message "Qual post?"}))}]})

(deftest ask-pede-um-tipo-e-o-valor-entra-no-blackboard
  (let [plat (plataforma)
        proc (ec/run! plat (ec/agent agente-ask))]
    (is (hitl/waiting? proc))
    (let [p (hitl/pending proc)]
      (is (= :type-request (:kind p)))
      (is (= "Qual post?" (:message p)))
      (is (= Post (:type p)))
      (is (instance? TypeRequest (:raw p))))
    (testing "o valor respondido entra no blackboard e fecha o goal tipado"
      (is (= :updated (hitl/answer! proc {:value (->Post "veio do humano")})))
      (is (= "COMPLETED" (str (.getStatus (hitl/resume! proc)))))
      (is (= (->Post "veio do humano") (bb/last-of proc Post))))))

;; --- awaitable próprio: mapa de fns ----------------------------------------

(def agente-custom
  {:name        "aprovador"
   :description "Awaitable próprio, com on-response do usuário"
   :goals       [{:name "aprovado" :pre ["aprovado"]}]
   :actions     [{:name "pedir-aprovacao"
                  :post ["aprovado"]
                  :fn   (fn [_]
                          (hitl/wait-for!
                           (hitl/awaitable
                            {:payload     {:valor 1000}
                             :on-response (fn [v proc]
                                            (bb/put! proc :parecer v)
                                            (bb/set-condition! proc "aprovado" true)
                                            :updated)})))}]})

(deftest awaitable-proprio-como-mapa-de-fns
  (let [plat (plataforma)
        proc (ec/run! plat (ec/agent agente-custom))]
    (is (hitl/waiting? proc))
    (testing "o awaitable próprio também se lê como dado"
      (let [p (hitl/pending proc)]
        (is (= :custom (:kind p)))
        (is (= {:valor 1000} (:payload p)))))
    (testing "o :on-response recebe o valor e escreve no processo"
      (is (= :updated (hitl/answer! proc {:value "aprovado pelo gerente"})))
      (is (= "aprovado pelo gerente" (bb/fetch proc :parecer)))
      (is (true? (bb/condition? proc "aprovado")))
      (is (= "COMPLETED" (str (.getStatus (hitl/resume! proc))))))))

(deftest on-response-pode-dizer-que-nada-mudou
  (let [aw (hitl/awaitable {:payload :x :on-response (fn [_ _] :unchanged)})
        proc (ec/run! (plataforma) (ec/agent
                                    {:name    "nulo"
                                     :description "…"
                                     :goals   [{:name "g" :pre ["nunca"]}]
                                     :actions [{:name "pedir" :post ["nunca"]
                                                :fn (fn [_] (hitl/wait-for! aw))}]}))]
    (is (= :unchanged (hitl/answer! proc {:value 1})))))

;; --- bordas ----------------------------------------------------------------

(deftest responder-sem-pedido-pendente-e-erro-humanizado
  (let [proc (ec/run! (plataforma) (ec/agent
                                    {:name    "simples"
                                     :description "sem hitl"
                                     :goals   [{:name "ok" :pre ["feito"]}]
                                     :actions [{:name "fazer" :post ["feito"]
                                                :fn (fn [{:keys [pc]}]
                                                      (bb/set-condition! pc "feito" true))}]}))]
    (is (= "COMPLETED" (str (.getStatus proc))))
    (is (nil? (hitl/pending proc)))
    (is (false? (hitl/waiting? proc)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nada pendente"
                          (hitl/answer! proc {:accept? true})))))

(deftest answer-and-resume-encadeia
  (let [proc (ec/run! (plataforma) (ec/agent agente-confirmacao))]
    (is (= "COMPLETED" (str (.getStatus (hitl/answer-and-resume! proc {:accept? true})))))))

;; --- o efeito colateral da delegação ao ActionRunner ------------------------

(deftest action-agora-tem-running-time-real
  (testing "antes da delegação ao ActionRunner do framework, TODA action
            registrava runningTime 0ms — visível no log do process-store"
    (let [proc (ec/run! (plataforma)
                        (ec/agent {:name    "lento"
                                   :description "…"
                                   :goals   [{:name "ok" :pre ["feito"]}]
                                   :actions [{:name "dormir" :post ["feito"]
                                              :fn (fn [{:keys [pc]}]
                                                    (Thread/sleep 12)
                                                    (bb/set-condition! pc "feito" true))}]}))
          inv (first (.getHistory proc))]
      (is (= "dormir" (.getActionName inv)))
      (is (>= (.toMillis (.getRunningTime inv)) 10)))))
