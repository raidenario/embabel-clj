(ns nature.main
  "AGENTE DE NATUREZA sobre a API pública do embabel-clj.

   Compare com examples/app-caminho-b: lá o mesmo agente precisava de
   App.java, pom Spring Boot próprio e um arquivo inteiro de interop
   (agents.clj com proxy/reify). Aqui: UM namespace, só dados e funções.

   Rodar (com OPENROUTER_APIKEY no ambiente):
     cd examples/nature && clojure -M:run ../../samples/nature.jpg"
  (:require [embabel-clj.blackboard :as bb]
            [embabel-clj.core :as ec]
            [embabel-clj.platform :as platform]
            [embabel-clj.schema :as schema])
  (:import [com.embabel.agent.api.common AgentImage]
           [java.io File]))

;; ---------------------------------------------------------------------------
;; O contrato com o LLM é um schema malli — o MESMO valor gera o prompt,
;; valida a resposta e coage os tipos (o "data class + Jackson" data-driven).
;; ---------------------------------------------------------------------------

(def Insights
  [:map
   [:resumo            {:description "Resumo em 2-3 frases do que a foto mostra."} :string]
   [:bioma             {:description "Bioma identificado (ex.: Cerrado, Mata Atlântica, Deserto)."} :string]
   [:tipo-vegetacao    {:optional true :description "Vegetação predominante."} :string]
   [:arvores-provaveis {:optional true :description "Árvores/plantas prováveis (nome comum e/ou científico)."} [:sequential :string]]
   [:local-provavel    {:optional true :description "Região/país provável, com raciocínio curto."} :string]
   [:clima             {:optional true :description "Clima provável (ex.: árido quente, boreal)."} :string]
   [:estacao-estimada  {:optional true :description "Estação do ano estimada, com a pista."} :string]
   [:pistas-visuais    {:optional true :description "Pistas visuais que sustentam a análise."} [:sequential :string]]
   [:fauna-possivel    {:optional true :description "Fauna possível para o bioma/região."} [:sequential :string]]
   [:confianca         {:optional true :description "Confiança geral, 0.0 a 1.0."} [:double {:min 0.0 :max 1.0}]]])

(def vision-model "openai/gpt-4o")

;; ---------------------------------------------------------------------------
;; A action, como no artigo: um defn com a tag :action/* na metadata.
;; ---------------------------------------------------------------------------

(defn analyze-nature
  "Analisa a foto no slot :image e escreve os insights validados em :insights."
  {:action/post [:nature/insights-ready?] :action/cost 1.0 :action/llm true}
  [ctx]
  (let [insights (schema/create-edn!
                  ctx
                  {:schema     Insights
                   :llm        vision-model
                   :image      (bb/fetch ctx :image)
                   ;; o OpenRouter pré-autoriza max_tokens contra o saldo;
                   ;; sem isto vale o output máximo do modelo (16k no gpt-4o)
                   :max-tokens 1200
                   :retries    1
                   :prompt  (schema/edn-prompt
                             Insights
                             {:preamble (str "Você é um naturalista de campo, botânico e "
                                             "biogeógrafo experiente. Analise com atenção a "
                                             "FOTOGRAFIA DE NATUREZA anexada e infira o máximo "
                                             "possível a partir da vegetação, do terreno, da luz "
                                             "e de marcos visíveis. Responda em português do "
                                             "Brasil. Seja específico, mas honesto sobre a incerteza.")})})]
    (bb/put! ctx :insights insights)
    (bb/set-condition! ctx :nature/insights-ready? true)))

(def nature-agent
  (delay
    (ec/agent-from-ns 'nature.main
                      {:name        "nature-agent"
                       :description "Analisa uma foto de natureza e gera insights estruturados"
                       :goals       [{:name "nature-insights"
                                      :description "Gerar insights sobre uma foto de natureza"
                                      :pre   [:nature/insights-ready?]
                                      :value 1.0}]})))

;; ---------------------------------------------------------------------------
;; Boot + execução — o Spring/AgentPlatform sobe DENTRO da lib.
;; ---------------------------------------------------------------------------

(defn start-system! []
  (platform/start!
   ;; base-url SEM /v1: o Spring AI acrescenta /v1/chat/completions sozinho
   ;; (com /v1 na base vira /api/v1/v1/... -> 404 em HTML do site)
   {:properties {:embabel.agent.platform.models.openai.base-url
                 "https://openrouter.ai/api"
                 :embabel.agent.platform.models.openai.api-key
                 (or (System/getenv "OPENROUTER_APIKEY")
                     (throw (ex-info "exporte OPENROUTER_APIKEY" {})))
                 :embabel.models.default-llm "openai/gpt-4o-mini"
                 :embabel.agent.platform.models.openai.max-attempts 2
                 :embabel.agent.platform.models.options.http-headers.X-Title
                 "embabel-clj nature example"
                 :logging.level.root "WARN"
                 :logging.level.com.embabel "INFO"}}))

(defn analyze-image!
  "Roda o agente sobre um arquivo de imagem; devolve o resultado como dados."
  [platform path]
  (let [img  (AgentImage/fromFile (File. ^String path))
        proc (ec/run! platform @nature-agent
                      {:bindings {:image img}
                       :options  {:budget {:cost 0.50}}})]
    (ec/result proc {:slots      [:insights]
                     :conditions [:nature/insights-ready?]})))

(defn -main [& [path]]
  (let [{:keys [platform] :as sys} (start-system!)
        _    (ec/deploy! platform @nature-agent)
        r    (analyze-image! platform (or path "../../samples/nature.jpg"))]
    (println)
    (println "----- INSIGHTS -----")
    (doseq [[k v] (get-in r [:slots :insights])]
      (println " " k "->" (pr-str v)))
    (println "--------------------")
    (println "status:" (:status r) "| condições:" (:conditions r))
    (platform/stop! sys)
    (System/exit 0)))
