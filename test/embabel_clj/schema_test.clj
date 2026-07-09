(ns embabel-clj.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [embabel-clj.schema :as schema]))

(def Insights
  [:map
   [:resumo {:description "2-3 frases"} :string]
   [:bioma :string]
   [:confianca {:optional true} [:double {:min 0.0 :max 1.0}]]])

(deftest parse-edn-defensivo
  (testing "cercas de markdown são limpas"
    (is (= {:a 1} (schema/parse-edn "```edn\n{:a 1}\n```"))))
  (testing "símbolos-chave (LLM esqueceu o ':') viram keywords PRESERVANDO namespace"
    (is (= {:co/dominio "x.com"} (schema/parse-edn "{co/dominio \"x.com\"}"))))
  (testing "não-mapa e lixo viram nil, nunca exceção"
    (is (nil? (schema/parse-edn "[1 2 3]")))
    (is (nil? (schema/parse-edn "isso não é EDN {"))))
  (testing "cap de tamanho"
    (binding [schema/*max-response-chars* 10]
      (is (nil? (schema/parse-edn "{:a \"muito longo mesmo\"}"))))))

(deftest parse-valida-e-coage
  (testing "resposta válida (com coerção de string->double)"
    (let [{:keys [value errors]}
          (schema/parse Insights "{:resumo \"ok\" :bioma \"Cerrado\" :confianca \"0.8\"}")]
      (is (nil? errors))
      (is (= "Cerrado" (:bioma value)))
      (is (= 0.8 (:confianca value)))))
  (testing "resposta inválida devolve erros humanizados + raw"
    (let [{:keys [value errors raw]} (schema/parse Insights "{:resumo \"só resumo\"}")]
      (is (nil? value))
      (is (contains? errors :bioma))
      (is (string? raw)))))

(deftest edn-prompt-deriva-do-schema
  (let [p (schema/edn-prompt Insights {:preamble "Você é um naturalista."})]
    (is (.contains p "resumo"))
    (is (.contains p "2-3 frases"))
    (is (.contains p "confianca (opcional)"))
    (is (.contains p "SOMENTE com UM mapa EDN"))))

(deftest create-edn!-auto-cura
  (testing "1ª resposta inválida -> re-pergunta com os erros -> 2ª válida"
    (let [prompts (atom [])
          answers (atom ["{:resumo \"faltou bioma\"}"
                         "{:resumo \"ok\" :bioma \"Caatinga\"}"])
          ask-fn  (fn [p]
                    (swap! prompts conj p)
                    (let [[a & rest*] @answers] (reset! answers rest*) a))
          value   (schema/create-edn! {} {:schema Insights
                                          :prompt "análise"
                                          :ask-fn ask-fn
                                          :retries 1})]
      (is (= "Caatinga" (:bioma value)))
      (is (= 2 (count @prompts)))
      (is (.contains ^String (second @prompts) "resposta anterior foi inválida"))))
  (testing "esgotou retries -> ex-info com :raw e :errors"
    (let [e (try (schema/create-edn! {} {:schema Insights :prompt "x"
                                         :ask-fn (constantly "lixo") :retries 1})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (= "lixo" (:raw (ex-data e))))
      (is (= 2 (:attempts (ex-data e)))))))

(deftest json-schema-do-mesmo-schema
  (let [js (schema/json-schema Insights)]
    (is (= "object" (:type js)))
    (is (contains? (:properties js) :resumo))))
