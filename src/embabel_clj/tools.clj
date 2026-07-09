(ns embabel-clj.tools
  "Fn Clojure como TOOL do LLM — sem anotação @Tool, sem classe.

   O Embabel 0.4.0 tem uma API funcional de tools (com.embabel.agent.api.tool
   .Tool/create + Handler) que dispensa o modelo anotado. Esta ns fecha o
   circuito com malli: o MESMO schema descreve os argumentos (vira o JSON
   Schema que o modelo enxerga), valida/coage o que o modelo mandou, e o seu
   fn recebe um mapa Clojure.

     (def soma
       (tools/tool
        {:name        \"soma\"
         :description \"Soma dois números.\"
         :schema      [:map
                       [:a {:description \"primeira parcela\"} :double]
                       [:b {:description \"segunda parcela\"} :double]]
         :fn          (fn [{:keys [a b]}] (+ a b))}))

     ;; numa action {:llm? true}:
     (schema/ask ctx {:llm \"openai/gpt-4o-mini\"
                      :tools [soma]
                      :prompt \"Quanto é 2.5 + 4.25? Use a tool soma.\"})

   O retorno do fn vira o resultado da tool: string vai como texto; qualquer
   outro valor é serializado em JSON. Exceção vira Tool.Result/error (o
   modelo vê o erro e pode se corrigir)."
  (:require [clojure.walk :as walk]
            [malli.core :as m]
            [malli.error :as me]
            [malli.json-schema :as mjs]
            [malli.transform :as mt])
  (:import [com.embabel.agent.api.tool Tool Tool$Handler Tool$InputSchema
            Tool$Result]
           [com.fasterxml.jackson.databind ObjectMapper]))

;; Jackson já está no classpath (Spring/Embabel) — sem dep nova de JSON.
(def ^:private ^ObjectMapper mapper (ObjectMapper.))

(defn- ->jsonable [x]
  (walk/postwalk #(if (keyword? %)
                    (if-let [ns* (namespace %)] (str ns* "/" (name %)) (name %))
                    %)
                 x))

(defn ->json
  "Valor Clojure -> string JSON (chaves/valores keyword viram strings)."
  ^String [x]
  (.writeValueAsString mapper (->jsonable x)))

(defn- java->clj
  "Jackson devolve LinkedHashMap/ArrayList; clojure.walk NÃO desce em coleções
   Java — conversão recursiva explícita, chaves keywordizadas."
  [x]
  (cond
    (instance? java.util.Map x)
    (into {} (map (fn [[k v]] [(keyword (str k)) (java->clj v)])) x)

    (instance? java.util.List x)
    (mapv java->clj x)

    :else x))

(defn parse-json
  "String JSON -> dados Clojure com chaves keywordizadas (ou nil)."
  [^String s]
  (try
    (some-> (.readValue mapper s ^Class Object) java->clj)
    (catch Exception _ nil)))

(def ^:private decoder-for
  (memoize
   (fn [schema]
     (m/decoder schema (mt/transformer mt/string-transformer
                                       mt/json-transformer)))))

(defn input-schema
  "Tool$InputSchema derivado de um schema malli [:map ...]: o toJsonSchema é
   o mjs/transform do MESMO schema que valida os argumentos em runtime."
  ^Tool$InputSchema [malli-schema]
  (let [json (->json (mjs/transform malli-schema))]
    (reify Tool$InputSchema
      (toJsonSchema [_] json)
      (getParameters [_] []))))

(defn tool
  "Constrói um com.embabel.agent.api.tool.Tool a partir de um mapa:

   :name / :description  — o que o modelo vê
   :schema               — malli [:map ...] dos argumentos (gera o JSON
                           Schema E valida/coage a chamada)
   :fn                   — (fn [args-map] resultado); recebe o mapa já
                           validado/coagido, com chaves keyword

   Use com schema/ask ou schema/create-edn! via `:tools [t ...]`, ou direto
   num PromptRunner via `.withTool`."
  ^Tool [{:keys [name description schema] f :fn}]
  (Tool/create
   ^String name
   ^String (or description name)
   (input-schema schema)
   (reify Tool$Handler
     (handle [_ json-args]
       (try
         (let [raw     (or (parse-json json-args) {})
               decoded ((decoder-for schema) raw)]
           (if (m/validate schema decoded)
             (let [r (f decoded)]
               (Tool$Result/text (if (string? r) r (->json r))))
             (Tool$Result/error
              (str "argumentos inválidos: "
                   (pr-str (me/humanize (m/explain schema decoded)))))))
         (catch Throwable t
           (Tool$Result/error (str (.getMessage t)) t)))))))
