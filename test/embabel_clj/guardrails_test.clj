(ns embabel-clj.guardrails-test
  "Guardrails como fns: chama as interfaces REAIS do Embabel (inclusive os
   caminhos DEFAULT do framework — lista de UserMessages, AssistantMessage,
   ThinkingResponse) sem Spring e sem LLM."
  (:require [clojure.test :refer [deftest is testing]]
            [embabel-clj.guardrails :as gr]
            [embabel-clj.schema :as schema])
  (:import [com.embabel.agent.api.common PromptRunner]
           [com.embabel.agent.api.validation.guardrails
            AssistantMessageGuardRail GuardRail UserInputGuardRail]
           [com.embabel.agent.core Blackboard]
           [com.embabel.chat AssistantMessage UserMessage]
           [com.embabel.common.core.thinking ThinkingResponse]
           [com.embabel.common.core.validation
            ValidationError ValidationResult ValidationSeverity]))

;; Blackboard stub: os métodos DEFAULT do framework têm checagem de não-nulo do
;; Kotlin no parâmetro, então passar nil estoura NPE antes de chegar na guarda.
(def ^:private bb (reify Blackboard))

(defn- msg-user ^UserMessage [s] (UserMessage. ^String s nil (java.time.Instant/now)))

(defn- verdict
  "[valid? [[code message severity] ...]] de um ValidationResult."
  [^ValidationResult r]
  [(.isValid r)
   (mapv (fn [^ValidationError e]
           [(.getCode e) (.getMessage e) (str (.getSeverity e))])
         (.getErrors r))])

;; --- UserInputGuardRail ------------------------------------------------------

(deftest user-input-passa-e-bloqueia
  (let [^UserInputGuardRail g
        (gr/user-input {:name        :sem-segredo
                        :description "barra chave de API no prompt"
                        :fn (fn [{:keys [content]}]
                              (when (re-find #"sk-[A-Za-z0-9]{6,}" content)
                                "o prompt contém o que parece uma chave de API"))})]
    (testing "name/description são o que o framework loga"
      (is (= "sem-segredo" (.getName g)))
      (is (= "barra chave de API no prompt" (.getDescription g))))
    (testing "conteúdo limpo passa"
      (is (= [true []] (verdict (.validate g "resuma este texto" bb)))))
    (testing "conteúdo sujo vira UM erro CRITICAL, com o nome da guarda como code"
      (let [[valid? errs] (verdict (.validate g "a chave é sk-ABCDEFGH" bb))]
        (is (false? valid?))
        (is (= 1 (count errs)))
        (is (= "sem-segredo" (ffirst errs)))
        (is (= "CRITICAL" (last (first errs))))))))

(deftest user-input-usa-os-defaults-do-framework
  ;; O framework tem overloads default (lista de UserMessages, multimodal) que
  ;; combinam o texto e chamam a MESMA fn — este teste prova que a nossa reify
  ;; é quem eles acabam invocando.
  (let [vistos (atom [])
        ^UserInputGuardRail g
        (gr/user-input {:name "eco" :fn (fn [{:keys [content]}]
                                          (swap! vistos conj content) nil)})]
    (.validate g [(msg-user "primeira") (msg-user "segunda")] bb)
    (is (= ["primeira\nsegunda"] @vistos)
        "combineMessages junta com \\n e delega para validate(String)")))

;; --- as formas de veredito ---------------------------------------------------

(defn- veredito-de [v]
  (verdict (.validate ^UserInputGuardRail
                      (gr/user-input {:name "g" :fn (constantly v)})
                      "x" bb)))

(deftest formas-de-veredito
  (testing "passou"
    (is (= [true []] (veredito-de nil)))
    (is (= [true []] (veredito-de true)))
    (is (= [true []] (veredito-de "")))
    (is (= [true []] (veredito-de []))))

  (testing "string vira um erro; vetor de strings vira vários"
    (is (= [false [["g" "ruim" "CRITICAL"]]] (veredito-de "ruim")))
    (is (= 2 (count (second (veredito-de ["a" "b"]))))))

  (testing "false vira um erro crítico com mensagem genérica"
    (let [[valid? errs] (veredito-de false)]
      (is (false? valid?))
      (is (= 1 (count errs)))
      (is (= "CRITICAL" (last (first errs))))))

  (testing "mapa de erro detalhado: code e severity próprios"
    (is (= [false [["meu-code" "cuidado" "WARNING"]]]
           (veredito-de {:code "meu-code" :message "cuidado" :severity :warning}))))

  (testing "envelope {:valid? :errors} dá controle total"
    (is (= [false [["g" "e1" "ERROR"]]]
           (veredito-de {:valid? false :errors [{:message "e1" :severity :error}]}))))

  (testing "ValidationResult pronto passa direto"
    (let [r (ValidationResult. false [(ValidationError. "c" "m" ValidationSeverity/INFO)])]
      (is (= [false [["c" "m" "INFO"]]] (veredito-de r)))))

  (testing "PEGADINHA do framework: inválido SEM erros não faria nada — a lib
            materializa um erro crítico em vez de deixar passar em silêncio"
    (let [[valid? errs] (veredito-de {:valid? false :errors []})]
      (is (false? valid?))
      (is (= 1 (count errs)))
      (is (= "CRITICAL" (last (first errs)))))))

(deftest severidade-default-do-mapa
  (testing ":severity define a severidade de todos os erros da guarda"
    (let [^UserInputGuardRail g (gr/user-input {:name "g" :severity :warning
                                                :fn (constantly "atenção")})]
      (is (= [false [["g" "atenção" "WARNING"]]] (verdict (.validate g "x" bb))))))
  (testing "a severidade do erro individual vence a do mapa"
    (let [^UserInputGuardRail g (gr/user-input {:name "g" :severity :info
                                                :fn (constantly {:message "m"
                                                                 :severity :critical})})]
      (is (= "CRITICAL" (last (first (second (verdict (.validate g "x" bb))))))))))

;; --- schema malli como guarda ------------------------------------------------

(deftest schema-malli-e-guarda
  (let [^UserInputGuardRail g (gr/user-input {:name "tamanho" :schema [:string {:max 10}]})]
    (is (= [true []] (verdict (.validate g "curto" bb))))
    (let [[valid? errs] (verdict (.validate g "isto aqui é longo demais" bb))]
      (is (false? valid?))
      (is (re-find #"at most 10" (second (first errs))))))

  (testing ":fn e :schema juntos somam os erros"
    (let [^UserInputGuardRail g (gr/user-input
                                 {:name "ambos"
                                  :schema [:string {:max 3}]
                                  :fn (fn [{:keys [content]}]
                                        (when (re-find #"xxx" content) "tem xxx"))})]
      (is (= 2 (count (second (verdict (.validate g "xxxyyy" bb))))))
      (is (= 1 (count (second (verdict (.validate g "abcdef" bb)))))))))

;; --- AssistantMessageGuardRail ----------------------------------------------

(deftest assistant-message-nos-tres-caminhos
  (let [vistos (atom [])
        ^AssistantMessageGuardRail g
        (gr/assistant-message {:name "sem-pii"
                               :fn (fn [{:keys [content]}]
                                     (swap! vistos conj content)
                                     (when (re-find #"CPF" content) "vazou PII"))})]
    (testing "string crua"
      (is (= [true []] (verdict (.validate g "tudo certo" bb))))
      (is (false? (first (verdict (.validate g "o CPF dele é..." bb))))))

    (testing "AssistantMessage cai no default do framework -> nossa fn"
      (reset! vistos [])
      (.validate g (AssistantMessage. "resposta do modelo") bb)
      (is (= ["resposta do modelo"] @vistos)))

    (testing "ThinkingResponse: por default a MESMA fn roda sobre o thinking"
      (reset! vistos [])
      (let [tr (ThinkingResponse. "resultado" [] nil)]
        ;; sem thinking nenhum não há o que checar
        (is (= [true []] (verdict (.validate g tr bb))))
        (is (= [] @vistos))))))

(deftest assistant-message-thinking-fn
  (let [^AssistantMessageGuardRail g
        (gr/assistant-message {:name "raciocinio"
                               :fn (constantly nil)
                               :thinking-fn (fn [{:keys [result blocks]}]
                                              (str "result=" result
                                                   " blocos=" (count blocks)))})
        [valid? errs] (verdict (.validate g (ThinkingResponse. "R" [] nil) bb))]
    (is (false? valid?))
    (is (= "result=R blocos=0" (second (first errs))))))

;; --- forma 100% EDN e validação da definição --------------------------------

(deftest guardrail-a-partir-de-on
  (is (instance? UserInputGuardRail
                 (gr/guardrail {:on :user-input :name "a" :fn (constantly nil)})))
  (is (instance? AssistantMessageGuardRail
                 (gr/guardrail {:on :assistant-message :name "b" :fn (constantly nil)})))
  (testing ":on ausente ou errado falha com mensagem explícita"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":user-input ou :assistant-message"
                          (gr/guardrail {:name "c" :fn (constantly nil)})))))

(deftest definicao-invalida-falha-na-construcao
  (testing "typo em chave (:closed)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"disallowed key"
                          (gr/user-input {:name "g" :severidade :critical
                                          :fn (constantly nil)}))))
  (testing "sem :fn nem :schema não há o que validar"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":fn e/ou :schema"
                          (gr/user-input {:name "g"}))))
  (testing "severidade inexistente"
    (is (thrown? clojure.lang.ExceptionInfo
                 (gr/user-input {:name "g" :severity :fatal :fn (constantly nil)})))))

;; --- o caminho de verdade: schema/ask -> PromptRunner.withGuardRails ---------

(defprotocol FakeOc (ai [this]))

(deftest ask-instala-as-guardas-no-prompt-runner
  (let [visto  (atom nil)
        runner (reify PromptRunner
                 (withGuardRails [this xs] (reset! visto (vec xs)) this)
                 (^String generateText [_ ^String _p] "ok"))
        oc     (reify FakeOc (ai [_] runner))
        r      (schema/ask {:oc oc}
                           {:prompt "oi"
                            :guardrails [{:on :user-input :name "a" :fn (constantly nil)}
                                         (gr/assistant-message {:name "b" :schema :string})]})]
    (is (= "ok" r))
    (is (= 2 (count @visto)))
    (is (every? #(instance? GuardRail %) @visto))
    (is (= ["a" "b"] (mapv #(.getName ^GuardRail %) @visto)))))
