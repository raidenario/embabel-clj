;; Driver da demo do AGENTE DE NATUREZA.
;; Conecta no nREPL do processo vivo (7888) e roda o nature-agent sobre uma imagem,
;; que o planner encaminha para a action de visao -> Gemini multimodal -> insights.
;;
;; Uso:
;;   clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.1"}}}' -M scripts/nature_demo.clj [caminho-ou-url]
(require '[nrepl.core :as nrepl])

(def image
  (or (first *command-line-args*)
      "C:/Users/jpedr/IdeaProjects/embabel-clj/samples/nature.jpg"))

(with-open [conn (nrepl/connect :host "127.0.0.1" :port 7888)]
  (let [client  (nrepl/client conn 120000)            ; 2 min: chamada de LLM com imagem
        session (nrepl/client-session client)
        ev      (fn [code]
                  (-> (nrepl/message session {:op "eval" :code code})
                      nrepl/response-values
                      first))]
    (println "\n========== AGENTE DE NATUREZA (nREPL -> Gemini visao) ==========")
    (println "imagem:" image)
    (let [r (ev (str "(embabel-clj.register/analyze-image " (pr-str image) ")"))]
      (println "status:" (:status r))
      (println "\n----- INSIGHTS -----")
      (println (:insights r))
      (println "--------------------"))
    (println "================================================================\n")
    (flush)))
