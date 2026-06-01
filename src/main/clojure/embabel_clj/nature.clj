(ns embabel-clj.nature
  (:require [embabel-clj.agents :as a]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.transform :as mt]
            [malli.error :as me]
            [malli.json-schema :as mjs])
  (:import [com.embabel.agent.core Agent]
           [com.embabel.agent.api.common AgentImage]))

(def vision-model "gemini-2.5-flash")

(def fact-insights "nature/insights-ready")
(def slot-image "image")
(def slot-insights "insights")
(def slot-insights-raw "insights-raw")

(def insights-schema
  [:map
   [:resumo            {:description "Resumo em 2-3 frases (insights em prosa)."} :string]
   [:bioma             {:description "Bioma identificado (ex.: Floresta Boreal/Taiga, Cerrado, Deserto)."} :string]
   [:tipo-vegetacao    {:optional true :description "Tipo de vegetacao predominante."} :string]
   [:arvores-provaveis {:optional true :description "Arvores/plantas provaveis (nome comum e/ou cientifico)."} [:sequential :string]]
   [:local-provavel    {:optional true :description "Regiao/pais provavel, com raciocinio curto."} :string]
   [:clima             {:optional true :description "Clima provavel (ex.: arido quente, boreal)."} :string]
   [:estacao-estimada  {:optional true :description "Estacao do ano estimada."} :string]
   [:pistas-visuais    {:optional true :description "Pistas visuais que sustentam a analise."} [:sequential :string]]
   [:fauna-possivel    {:optional true :description "Fauna possivel para o bioma/regiao."} [:sequential :string]]
   [:confianca         {:optional true :description "Confianca geral da analise, numero de 0.0 a 1.0."} [:double {:min 0.0 :max 1.0}]]])

(def ^:private decode-insights
  (m/decoder insights-schema (mt/transformer mt/string-transformer mt/json-transformer)))

(defn explain-insights
  [m]
  (some-> (m/explain insights-schema m) (me/humanize)))

(defn valid-insights?
  [m]
  (m/validate insights-schema m))

(defn- schema->prompt-fields
  [schema]
  (->> (m/children (m/schema schema))
       (map (fn [[k props _]]
              (str "  - " (name k)
                   (when (:optional props) " (opcional)")
                   (when-let [d (:description props)] (str ": " d)))))
       (str/join "\n")))

(def insights-json-schema (mjs/transform insights-schema))

(defn analysis-prompt
  []
  (str "Voce e um naturalista de campo, botanico e biogeografo experiente. "
       "Analise com atencao a FOTOGRAFIA DE NATUREZA anexada e infira o maximo possivel. "
       "Responda em portugues do Brasil. "
       "IMPORTANTE: responda SOMENTE com UM mapa EDN do Clojure (chaves com keywords), "
       "sem markdown, sem ```, sem texto fora do mapa. Campos esperados:\n"
       (schema->prompt-fields insights-schema)
       "\nStrings entre aspas duplas; arrays como [\"a\" \"b\"]; :confianca e um numero de 0 a 1. "
       "Baseie os palpites na vegetacao, no terreno, na luz e em marcos visiveis. "
       "Seja especifico, mas honesto sobre a incerteza."))

(defn parse-insights
  [^String s]
  (let [cleaned (-> (str s)
                    str/trim
                    (str/replace #"(?s)^```(?:edn|clojure)?\s*" "")
                    (str/replace #"(?s)\s*```$" ""))]
    (try
      (let [v (edn/read-string cleaned)]
        (if (map? v)
          (decode-insights (walk/keywordize-keys v))
          {:raw (str s)}))
      (catch Exception _ {:raw (str s)}))))

(defn analyze-fn
  [oc]
  (let [^AgentImage img (.get oc slot-image)
        prompt-runner (-> (.ai oc)
                          (.withLlm vision-model)
                          (.withImage img))
        raw   (.generateText prompt-runner (analysis-prompt))
        dados (parse-insights raw)]
    (.set oc slot-insights dados)
    (.set oc slot-insights-raw raw)
    (.setCondition oc fact-insights true)
    dados))

(defn nature-agent
  ^Agent []
  (let [analyze (a/llm-action "analyze-nature" [] [fact-insights] #'analyze-fn)
        deliver (a/goal "nature-insights"
                        "Gerar insights sobre uma foto de natureza"
                        [fact-insights]
                        1.0)]
    (Agent. "nature-agent"
            "embabel-clj"
            "0.1.0"
            "Analisa uma foto de natureza e gera insights (bioma, vegetacao, arvores, local)"
            #{deliver}
            [analyze])))
