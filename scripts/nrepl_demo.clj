;; Driver da prova de HOT-RELOAD.
;; Conecta um nREPL externo ao processo Spring Boot/Embabel em execucao (porta 7888)
;; e, SEM restart e SEM redeploy do agente:
;;   1. roda o agente deployado            -> saudacao v1
;;   2. redefine a fn `greeting` ao vivo    -> via (intern ...) na ns do nucleo
;;   3. roda o MESMO agente deployado       -> saudacao v2 (mudou ao vivo)
;;
;; Uso (a partir da raiz do projeto):
;;   clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.1"}}}' -M scripts/nrepl_demo.clj
(require '[nrepl.core :as nrepl])

(def new-greeting
  "Bonjour, le monde! -- v2 HOT-RELOADED via nREPL (sem restart, sem redeploy)")

(with-open [conn (nrepl/connect :host "127.0.0.1" :port 7888)]
  (let [client  (nrepl/client conn 15000)
        session (nrepl/client-session client)
        ev      (fn [code]
                  (-> (nrepl/message session {:op "eval" :code code})
                      nrepl/response-values
                      first))]
    (println "\n========== PROVA DE HOT-RELOAD (nREPL -> processo vivo) ==========")
    (println "[1] run do agente DEPLOYADO (antes):")
    (println "    =>" (ev "(embabel-clj.register/run-once)"))

    (println "\n[2] redefinindo embabel-clj.agents/greeting ao vivo (intern, sem restart)...")
    (println "    =>" (ev (str "(intern 'embabel-clj.agents 'greeting (constantly "
                               (pr-str new-greeting) "))")))

    (println "\n[3] run do MESMO agente DEPLOYADO (depois):")
    (println "    =>" (ev "(embabel-clj.register/run-once)"))
    (println "==================================================================\n")
    (flush)))
