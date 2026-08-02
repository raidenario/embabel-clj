(ns embabel-clj.states-test
  "States num tipo Clojure: a anotação @State entra por metadata (mesma técnica
   da boot class) e o escopo por `hide` é explícito, porque a lib não passa pelo
   MultiTransformationAction do modelo anotado.

   O teste usa `deftype`, não `defrecord`, e isso é a lição principal daqui —
   ver `defrecord-parece-um-map-para-o-java` e `defrecord-anula-a-camada-tipada`."
  (:require [clojure.test :refer [deftest is testing]]
            [embabel-clj.blackboard :as bb]
            [embabel-clj.core :as ec]
            [embabel-clj.states :as st])
  (:import [com.embabel.agent.api.annotation State]
           [com.embabel.agent.core AgentPlatform]
           [com.embabel.agent.core.support InMemoryBlackboard]
           [com.embabel.agent.test.integration IntegrationTestUtils]))

(deftype ^{State true} Triagem [caso])
(deftype ^{State true} Analise [caso])
(deftype SemEstado [x])

(defn- plataforma ^AgentPlatform [] (IntegrationTestUtils/dummyAgentPlatform))

(deftest anotacao-state-em-tipo-clojure
  (testing "a anotação do framework entra via metadata no deftype"
    (is (true? (.isAnnotationPresent Triagem State)))
    (is (st/state-type? Triagem))
    (is (st/state? (->Triagem "c-1"))))
  (testing "tipo comum não é estado"
    (is (false? (st/state-type? SemEstado)))
    (is (false? (st/state? (->SemEstado 1))))
    (is (false? (st/state? nil)))))

;; --- fluxo de duas fases ----------------------------------------------------

(def ^:private triagem (->Triagem "c-1"))
(def ^:private analise (->Analise "c-1"))

(def agente
  "Duas fases: abrir a triagem e promover para análise.

   Duas lições de modelagem que este teste custou a aprender:

   1. **Não declare como `:inputs` tipado um estado que vai ser ESCONDIDO.**
      Esconder o input reabre a pré-condição, o planner replaneja para
      reproduzi-lo e o fluxo cicla — a primeira versão deste teste rodou
      `abrir` 50 vezes até TERMINATED. Gate por CONDIÇÃO e leia com `current`.
   2. **Não use `defrecord` como tipo de domínio nem como estado** (ver os dois
      testes no fim). Aqui os estados são `deftype`."
  {:name        "atendimento"
   :description "Triagem -> Análise"
   :goals       [{:name "analisado" :pre ["analisado"]}]
   :actions     [{:name "abrir"
                  :post ["triado"]
                  :fn   (fn [{:keys [pc] :as ctx}]
                          (st/enter! ctx triagem)
                          (bb/set-condition! pc "triado" true))}
                 {:name "promover"
                  :pre  ["triado"]
                  :post ["analisado"]
                  :fn   (fn [{:keys [pc] :as ctx}]
                          (is (identical? triagem (st/current ctx)))
                          (st/enter! ctx analise)
                          (bb/set-condition! pc "analisado" true))}]})

(deftest transicao-esconde-o-estado-anterior
  (let [eventos (atom [])
        proc    (ec/run! (plataforma) (ec/agent agente)
                         {:options {:listeners [(fn [ev] (swap! eventos conj ev))]}})]

    (testing "o fluxo percorreu as duas fases"
      (is (= "COMPLETED" (str (.getStatus proc))))
      (is (= ["abrir" "promover"] (mapv #(.getActionName %) (.getHistory proc)))))

    (testing "só o estado atual fica visível — o anterior foi escondido"
      (is (identical? analise (st/current proc)))
      (is (= [analise] (st/states proc))))

    (testing "a transição emitiu StateTransitionEvent, com o estado anterior"
      (let [ts (filterv #(= :state-transition (:event %)) @eventos)]
        (is (= 2 (count ts)))
        (is (= [nil triagem] (mapv :previous-state ts)))
        (is (= [triagem analise] (mapv :new-state ts)))))))

(deftest enter-recusa-quem-nao-e-estado
  (let [bb (InMemoryBlackboard. "t")]
    (is (thrown-with-msg? AssertionError #"não é um state type"
                          (st/enter! bb (->SemEstado 1))))))

(deftest hide-e-cirurgico-diferente-de-clear
  (let [proc (ec/run! (plataforma)
                      (ec/agent {:name "y" :description "…"
                                 :goals   [{:name "g" :pre ["feito"]}]
                                 :actions [{:name "a" :post ["feito"]
                                            :fn (fn [{:keys [pc]}]
                                                  (bb/put! pc :mantido "fica")
                                                  (bb/put! pc :sumido "some")
                                                  (bb/hide! pc "some")
                                                  (bb/set-condition! pc "feito" true))}]}))]
    (is (= "COMPLETED" (str (.getStatus proc))))
    (testing "o escondido some da leitura, mas o resto do blackboard fica de pé"
      (is (= "fica" (bb/fetch proc :mantido)))
      (is (nil? (bb/fetch proc :sumido)))
      (is (true? (bb/condition? proc "feito"))))))

;; --- as duas armadilhas do defrecord ---------------------------------------

(defrecord RecTriagem [caso])
(defrecord RecAnalise [caso])
(defrecord Produto [nome])
(defrecord Fatura [numero])

(deftest defrecord-parece-um-map-para-o-java
  (testing "`=` do Clojure distingue os tipos; `.equals` do Java NÃO"
    (let [t (->RecTriagem "c") a (->RecAnalise "c")]
      (is (false? (= t a))       "= do Clojure inclui o tipo")
      (is (true?  (.equals t a)) ".equals de defrecord é igualdade de MAPA, sem tipo")
      (is (= (.hashCode t) (.hashCode a)))))
  (testing "e como o blackboard guarda os escondidos num Set (equals + hashCode),
            esconder um record esconde outro record de OUTRO tipo com os mesmos
            campos — e também qualquer igual adicionado depois"
    (let [bb (InMemoryBlackboard. "x")
          t  (->RecTriagem "c")
          a  (->RecAnalise "c")]
      (.bind bb "it" t)
      (bb/hide! bb t)
      (.bind bb "it" a)
      (is (= [] (vec (.getObjects bb)))
          "o RecAnalise nasceu invisível porque .equals disse que já estava escondido")))
  (testing "com deftype (identidade), nada disso acontece"
    (let [bb (InMemoryBlackboard. "x")]
      (.bind bb "it" triagem)
      (bb/hide! bb triagem)
      (.bind bb "it" analise)
      (is (= [analise] (vec (.getObjects bb)))))))

(deftest defrecord-anula-a-camada-tipada
  (testing "o determinador de world state dá por satisfeita QUALQUER condição
            `it:Tipo` quando o valor ligado é um Map — sem checar o tipo
            (`BlackboardWorldStateDeterminer.determineCondition`, onde está
            escrito 'TODO may want to add type checking here'). Como todo
            defrecord É um java.util.Map, um goal que pede Fatura fecha com um
            Produto no blackboard."
    (let [corre (fn [v]
                  (str (.getStatus
                        (ec/run! (plataforma)
                                 (ec/agent {:name "t" :description "…"
                                            :goals   [{:name "g" :inputs [Fatura]}]
                                            :actions [{:name "poe" :outputs [Fatura]
                                                       :fn (fn [{:keys [pc]}]
                                                             (bb/put! pc "it" v))}]})))))]
      (is (= "COMPLETED" (corre (->Produto "x")))
          "defrecord: goal tipado em Fatura fecha com um Produto — falso positivo")
      (is (= "STUCK" (corre (->Triagem "x")))
          "deftype: o planner distingue os tipos e corretamente NÃO fecha"))))
