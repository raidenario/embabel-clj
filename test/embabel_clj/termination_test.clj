(ns embabel-clj.termination-test
  "As duas terminações como fns, contra as interfaces reais (sem Spring)."
  (:require [clojure.test :refer [deftest is testing]]
            [embabel-clj.core :as ec]
            [embabel-clj.termination :as t])
  (:import [com.embabel.agent.api.common StuckHandler StuckHandlingResultCode]
           [com.embabel.agent.api.tool TerminateActionException
            TerminateAgentException TerminationException]
           [com.embabel.agent.core AgentProcess Budget EarlyTermination
            EarlyTerminationPolicy ProcessOptions]))

;; O AgentProcess só precisa responder getId: nem a policy nem o handler da lib
;; tocam em mais nada dele (quem toca é a SUA fn, e aí é escolha sua).
(def ^:private proc (reify AgentProcess (getId [_] "p-1")))

;; --- EarlyTerminationPolicy --------------------------------------------------

(deftest policy-null-significa-continuar
  (testing "nil e false devolvem NULL — o contrato do framework para 'siga'"
    (is (nil? (.shouldTerminate ^EarlyTerminationPolicy (t/policy (constantly nil)) proc)))
    (is (nil? (.shouldTerminate ^EarlyTerminationPolicy (t/policy (constantly false)) proc)))))

(deftest policy-formas-de-veredito
  (testing "string vira a reason, e erro por default"
    (let [^EarlyTermination et (.shouldTerminate
                                ^EarlyTerminationPolicy
                                (t/policy {:name "chega" :terminate? (constantly "passou do ponto")})
                                proc)]
      (is (= "passou do ponto" (.getReason et)))
      (is (true? (.getError et)))
      (is (= "chega" (.getName (.getPolicy et))))))

  (testing "true termina com reason genérica (o nome da policy)"
    (let [^EarlyTermination et (.shouldTerminate
                                ^EarlyTerminationPolicy (t/policy {:name "p" :terminate? (constantly true)})
                                proc)]
      (is (= "p" (.getReason et)))
      (is (true? (.getError et)))))

  (testing ":error? false = 'parei porque quis', não porque estourou"
    (let [^EarlyTermination et (.shouldTerminate
                                ^EarlyTerminationPolicy
                                (t/policy (constantly {:reason "fim normal" :error? false}))
                                proc)]
      (is (= "fim normal" (.getReason et)))
      (is (false? (.getError et))))))

(deftest policy-embutidas-e-composicao
  (testing "as quatro embutidas do framework"
    (is (instance? EarlyTerminationPolicy (t/on-stuck)))
    (is (instance? EarlyTerminationPolicy (t/max-actions 5)))
    (is (instance? EarlyTerminationPolicy (t/max-tokens 100)))
    (is (instance? EarlyTerminationPolicy (t/hard-budget-limit 0.5)))
    (testing "on-stuck é @JvmStatic val: método getON_STUCK(), NÃO campo"
      (is (= "OnStuckEarlyTerminationPolicy" (.getName (t/on-stuck))))))

  (testing "first-of termina na primeira que disser sim"
    (let [p (t/first-of [(t/policy (constantly nil))
                         (t/policy (constantly "a segunda mandou parar"))
                         (t/policy (constantly "esta nem roda"))])]
      (is (= "a segunda mandou parar" (.getReason ^EarlyTermination (.shouldTerminate p proc))))))

  (testing "first-of com todas dizendo não devolve null"
    (is (nil? (.shouldTerminate (t/first-of [(t/policy (constantly nil))
                                             (t/policy (constantly false))])
                                proc)))))

(deftest o-budget-JA-E-uma-policy
  ;; O reenquadramento que a matriz do plano não tinha: `:budget` não é uma
  ;; alternativa às policies — é três delas compostas por firstOf.
  (let [p (.earlyTerminationPolicy Budget/DEFAULT)]
    (is (instance? EarlyTerminationPolicy p))
    (is (= "FirstOfEarlyTerminationPolicy" (.getName p)))))

(deftest policy-coercao-e-validacao
  (is (instance? EarlyTerminationPolicy (t/->policy (constantly nil))))
  (is (instance? EarlyTerminationPolicy (t/->policy {:terminate? (constantly nil)})))
  (let [pronta (t/on-stuck)]
    (is (identical? pronta (t/->policy pronta))))
  (testing "typo no mapa falha na construção"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"disallowed key"
                          (t/policy {:terminate? (constantly nil) :nome "x"}))))
  (testing ":terminate? é obrigatório"
    (is (thrown? clojure.lang.ExceptionInfo (t/policy {:name "só nome"})))))

;; --- StuckHandler ------------------------------------------------------------

(defn- codigo [h] (str (.getCode (.handleStuck ^StuckHandler h proc))))

(deftest stuck-handler-formas-de-veredito
  (testing "string = REPLAN com essa mensagem"
    (let [r (.handleStuck ^StuckHandler (t/stuck-handler (constantly "destravei")) proc)]
      (is (= "destravei" (.getMessage r)))
      (is (= StuckHandlingResultCode/REPLAN (.getCode r)))))

  (testing "true/:replan = REPLAN; nil/false/:no-resolution = NO_RESOLUTION"
    (is (= "REPLAN" (codigo (t/stuck-handler (constantly true)))))
    (is (= "REPLAN" (codigo (t/stuck-handler (constantly :replan)))))
    (is (= "NO_RESOLUTION" (codigo (t/stuck-handler (constantly nil)))))
    (is (= "NO_RESOLUTION" (codigo (t/stuck-handler (constantly false)))))
    (is (= "NO_RESOLUTION" (codigo (t/stuck-handler (constantly :no-resolution))))))

  (testing "mapa dá controle total"
    (let [r (.handleStuck ^StuckHandler
                          (t/stuck-handler {:name "h" :handle (constantly {:code :replan
                                                                           :message "m"})})
                          proc)]
      (is (= "m" (.getMessage r)))
      (is (= StuckHandlingResultCode/REPLAN (.getCode r)))))

  (testing "o handler recebe o AgentProcess (que é um Blackboard — resolver é
            efeito colateral nele)"
    (let [visto (atom nil)]
      (.handleStuck ^StuckHandler (t/stuck-handler #(do (reset! visto (.getId %)) nil)) proc)
      (is (= "p-1" @visto)))))

(deftest stuck-handler-validacao
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"disallowed key"
                        (t/stuck-handler {:handle (constantly nil) :nome "x"})))
  (is (thrown? clojure.lang.ExceptionInfo (t/stuck-handler {:name "só nome"}))))

;; --- integração: AgentDef e RunOptions --------------------------------------

(defn- agente-com [sh]
  (ec/agent {:name "t" :description "d" :stuck-handler sh
             :goals   [{:name "done" :pre [:ok?] :value 1.0}]
             :actions [{:name "w" :post [:ok?] :fn (fn [_] :ok)}]}))

(deftest stuck-handler-no-agent-def
  (testing "fn, mapa e instância pronta — o schema exigia INSTÂNCIA antes do 1.5"
    (doseq [sh [(constantly nil)
                {:handle (constantly nil)}
                (t/stuck-handler (constantly nil))]]
      (is (instance? StuckHandler (.getStuckHandler (agente-com sh))))))

  (testing "o handler chega vivo no Agent e responde"
    (let [ag (agente-com (fn [_] "resolvi"))]
      (is (= "resolvi" (.getMessage (.handleStuck (.getStuckHandler ag) proc))))))

  (testing "typo dentro do mapa é pego pelo AgentDef"
    (is (thrown? clojure.lang.ExceptionInfo (agente-com {:handel (constantly nil)})))))

(deftest early-termination-no-run-options
  (testing "uma policy, várias policies e instância pronta"
    (doseq [ps [[(constantly nil)]
                [{:terminate? (constantly nil)} (constantly nil)]
                [(t/on-stuck)]]]
      (is (instance? ProcessOptions (ec/process-options {:early-termination ps})))))

  (testing "compõe COM o budget (o with... devolve uma cópia, não muta)"
    (let [base (ec/process-options {:budget {:cost 0.1}})
          com* (ec/process-options {:budget {:cost 0.1}
                                    :early-termination [(constantly nil)]})]
      (is (not (identical? base com*)))
      (is (= 0.1 (-> com* .getBudget .getCost))
          "o budget sobrevive à adição da policy")))

  (testing "typo dentro da policy é pego pelo RunOptions"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ec/process-options {:early-termination [{:terminar? (constantly nil)}]})))))

;; --- terminação COOPERATIVA (a de dentro da action) -------------------------

(deftest terminacao-imediata-por-excecao
  (testing "as exceções do framework, sem o usuário importar nada"
    (is (thrown? TerminateAgentException (t/terminate-agent-now! "acabou")))
    (is (thrown? TerminateActionException (t/terminate-action-now! "essa chega"))))

  (testing "reason e escopo lidos sem instance? na ns do usuário"
    (let [e (try (t/terminate-agent-now! "orçamento estourou") (catch Throwable e e))]
      (is (= "orçamento estourou" (t/termination-reason e)))
      (is (= :agent (t/termination-scope e))))
    (let [e (try (t/terminate-action-now! "sem dados") (catch Throwable e e))]
      (is (= "sem dados" (t/termination-reason e)))
      (is (= :action (t/termination-scope e)))))

  (testing "as duas descendem de TerminationException (o catch genérico pega)"
    (is (thrown? TerminationException (t/terminate-agent-now! "x")))
    (is (thrown? TerminationException (t/terminate-action-now! "x"))))

  (testing "throwable qualquer não é terminação"
    (is (nil? (t/termination-reason (RuntimeException. "outra coisa"))))
    (is (nil? (t/termination-scope (RuntimeException. "outra coisa"))))
    (is (nil? (t/termination-reason nil)))))

(deftest terminacao-graceful-precisa-do-process-context
  (testing "erro explícito quando o ctx não tem :pc (em vez de NPE lá dentro)"
    (let [e (try (t/terminate-agent! {} "x") (catch clojure.lang.ExceptionInfo e e))]
      (is (= :embabel-clj.termination/no-process-context (:type (ex-data e))))
      (is (re-find #":pc" (.getMessage e))))
    (is (thrown? clojure.lang.ExceptionInfo (t/terminate-action! nil "x")))))
