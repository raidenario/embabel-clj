(ns embabel-clj.register
  (:require [embabel-clj.agents :as agents]
            [embabel-clj.nature :as nature]
            [embabel-clj.reconcile :as reconcile]
            [clojure.string :as str]
            [cheshire.core :as json]
            [nrepl.server :as nrepl])
  (:import [com.embabel.agent.core AgentPlatform ProcessOptions]
           [com.embabel.agent.api.common AgentImage]
           [java.io File]
           [java.net URL]))

(defonce ^AgentPlatform platform (atom nil))
(defonce hello-ref (atom nil))
(defonce nature-ref (atom nil))
(defonce reconcile-ref (atom nil))
(defonce nrepl-server (atom nil))

(def nrepl-port 7888)

(defn run-once
  []
  (let [proc (.runAgentFrom @platform @hello-ref (ProcessOptions.) {})]
    {:status   (str (.getStatus proc))
     :greeting (.get proc agents/slot-greeting)}))

;; -------------------------------------------------------------------------
;; Slice 1: API de execucao do reconcile-agent auto-curavel (loop B).
;; Chamada pelo @RestController AgentController via Clojure.var(...).invoke(...).
;; Roda o planner GOAP; o snapshot e o plano sao lidos do agendas DENTRO da
;; action load-plan (http calls). Devolve String JSON (PRD §5.1).
;;
;; EXCLUSAO MUTUA (revisao I3): POST /cap/daemon {on? false} ANTES de
;; runAgentFrom e {on? true} DEPOIS (try/finally). O daemon de sync e o
;; reconciliador escrevem o mesmo state.edn; desligar o daemon evita
;; double-create / corrupcao enquanto o reconciliador opera.
;; -------------------------------------------------------------------------

(defn- daemon!
  "Liga/desliga o daemon de sync no agendas via /cap/daemon. Tolera falha de
   transporte (loga e segue) — a exclusao mutua e best-effort no Slice 1."
  [on?]
  (try
    (agents/http-post (str agents/agendas-base-url "/api/cap/daemon") {:on? on?})
    (catch Exception e
      (println "[embabel-clj] /cap/daemon" on? "falhou:" (.getMessage e))
      nil)))

(defn- daemon-on?
  "Le o estado atual do daemon via /cap/health (p/ restaurar depois — I-2)."
  []
  (try
    (boolean (:daemon-on (:body (agents/http-get
                                 (str agents/agendas-base-url "/api/cap/health")))))
    (catch Exception _ false)))

(defn- final-conditions
  "Le do blackboard o estado final das condicoes que o planner roteou (PRD §5.1
   'conditions-finais'). .getCondition devolve Boolean ou nil (UNKNOWN/nunca-set
   = tratado como FALSE pelo determiner)."
  [proc]
  (let [bb (.getBlackboard proc)]
    (into {}
          (for [k [reconcile/c-pending-creates reconcile/c-pending-updates
                   reconcile/c-orphans reconcile/c-consistent
                   reconcile/c-reported-blocking reconcile/c-category-ok
                   reconcile/c-category-needs reconcile/c-conn-authorized
                   reconcile/c-conn-needs-reauth reconcile/c-throttled
                   reconcile/c-unknown reconcile/c-needs-human]]
            [k (boolean (.getCondition bb k))]))))

(defn run-reconcile
  "PRD §5.1. intent/world/callback aceitos por contrato; o agente carrega o
   snapshot+plano frescos via Capabilities API. Retorna STRING JSON com
   {status, plan-inicial, acoes-executadas, classificacoes, blocking?,
   conditions-finais} (nao map Clojure: maps implementam IFn->Callable e o
   Spring MVC os despacharia como Callable assincrono -> ArityException)."
  ([] (run-reconcile "reconcile-graph" nil nil))
  ([intent world callback]
   ;; pre-bind do filtro opcional de source-account, se vier no `world`.
   (let [src (when (map? world) (or (get world "source-account")
                                    (get world :source-account)))
         bindings (if src {reconcile/slot-source-account src} {})
         was-on? (daemon-on?)]            ; I-2: lembra p/ restaurar (nao ligar incondicional)
     (daemon! false)                       ; exclusao mutua: desliga o daemon
     (try
       (let [proc   (.runAgentFrom @platform @reconcile-ref (ProcessOptions.) bindings)
             status (str (.getStatus proc))
             result (.get proc reconcile/slot-result)]
         (json/generate-string
          {:status status
           :result {:intent             intent
                    :plan-inicial       (.get proc reconcile/slot-plan)
                    :acoes-executadas   (or (.get proc reconcile/slot-executed) [])
                    :classificacoes     (or (.get proc reconcile/slot-classifications) [])
                    :playbooks-loaded   (or (.get proc reconcile/slot-playbooks) [])
                    :blocking?          (boolean (:blocking? result))
                    :conditions-finais  (final-conditions proc)
                    :replans            (.get proc reconcile/slot-replans)
                    :max-replans        reconcile/max-replans}}))
       (finally
         (when was-on? (daemon! true)))))))   ; I-2: religa só se estava ligado

(defn- load-image
  ^AgentImage [path-or-url]
  (if (re-find #"(?i)^https?://" path-or-url)
    (let [conn (doto (.openConnection (URL. path-or-url))
                 (.setRequestProperty "User-Agent" "embabel-clj/0.1"))
          bytes (with-open [in (.getInputStream conn)]
                  (.readAllBytes in))
          ct (or (.getContentType conn) "image/jpeg")]
      (AgentImage/create ct bytes))
    (AgentImage/fromFile (File. ^String path-or-url))))

(defn analyze-image
  [path-or-url]
  (let [img  (load-image path-or-url)
        proc (.runAgentFrom @platform @nature-ref (ProcessOptions.) {nature/slot-image img})]
    {:status   (str (.getStatus proc))
     :insights (.get proc nature/slot-insights)
     :raw      (.get proc nature/slot-insights-raw)}))

(defn- start-nrepl! []
  (when-not @nrepl-server
    (reset! nrepl-server (nrepl/start-server :port nrepl-port :bind "127.0.0.1"))
    (println (format "[embabel-clj] nREPL ouvindo em 127.0.0.1:%d" nrepl-port))))

;; =========================================================================
;; start! — caminho SERVLET (revisao I1):
;; deploya os agentes, sobe o nREPL e RETORNA. NAO chama keep-alive!,
;; interactive! nem System/exit — o Tomcat (web-application-type=servlet)
;; segura a JVM viva. O CommandLineRunner do App.java chama (start! p) e o
;; proprio runner retorna sem bloquear.
;; =========================================================================
(defn start!
  [^AgentPlatform p]
  (reset! platform p)
  (let [hello     (agents/hello-agent)
        nature    (nature/nature-agent)
        recon     (reconcile/reconcile-agent)]
    (reset! hello-ref hello)
    (reset! nature-ref nature)
    (reset! reconcile-ref recon)
    (.deploy p hello)
    (.deploy p nature)
    (.deploy p recon))
  (println "[embabel-clj] agentes deployados:"
           (mapv #(.getName %) (.agents p)))
  (start-nrepl!)
  (println "[embabel-clj] start! concluido; Tomcat segura a JVM (porta servlet).")
  :ok)

;; =========================================================================
;; CLI opt-in (analise de imagem interativa). NAO usado pelo caminho servlet;
;; chamavel via `embabel-clj.register/-main-cli` ou nREPL.
;; =========================================================================

(def ^:private ^java.io.PrintWriter utf8-out
  (java.io.PrintWriter.
   (java.io.OutputStreamWriter. System/out java.nio.charset.StandardCharsets/UTF_8)
   true))

(defn- pp-map
  [m]
  (if (map? m)
    (do (println "{")
        (doseq [[k v] m]
          (println " " (pr-str k) (pr-str v)))
        (println "}"))
    (prn m)))

(defn- print-result
  [{:keys [status insights]}]
  (binding [*out* utf8-out]
    (let [m (if (map? insights) insights {})]
      (println)
      (println "----- INSIGHTS -----")
      (println (or (:resumo m) (:raw m) insights))
      (println)
      (println "----- MAPA CLOJURE (dados da imagem) -----")
      (pp-map insights)
      (println "------------------------------------------")
      (when (and (map? insights) (not (contains? insights :raw)))
        (if-let [problems (nature/explain-insights insights)]
          (do (println "schema Malli: PROBLEMAS na saida:")
              (doseq [[k msgs] problems]
                (println "   -" (name k) "->" (str/join "; " msgs))))
          (println "schema Malli: OK (saida valida)")))
      (println "status do processo:" status)
      (println)
      (flush))))

(defn analyze-and-print
  [path-or-url]
  (try
    (print-result (analyze-image path-or-url))
    (catch Exception e
      (binding [*out* utf8-out]
        (println "  [erro]" (.getMessage e))
        (flush)))))

(defn interactive!
  []
  (binding [*out* utf8-out]
    (println)
    (println "==================================================================")
    (println " AGENTE DE NATUREZA pronto. Cole o caminho de uma imagem (ou URL)")
    (println " e tecle Enter. Vazio, ':q' ou Ctrl+Z+Enter encerra.")
    (println "==================================================================")
    (loop []
      (print "\nimagem> ")
      (flush)
      (let [line (read-line)
            in   (some-> line str/trim)]
        (cond
          (nil? in)
          (println "\n[sem stdin interativo] nREPL segue em 127.0.0.1:7888 p/ hot-reload.")

          (#{"" ":q" ":quit" "quit" "exit"} in)
          (do (println "\nate mais.") (flush) (System/exit 0))

          :else
          (do (analyze-and-print in)
              (recur)))))))

(defn -main-cli
  "Opt-in: CLI interativa/one-shot de analise de imagem (requer @platform ja iniciado)."
  [& args]
  (let [img (first (seq args))]
    (if img
      (do (println "[embabel-clj] one-shot, imagem:" img)
          (analyze-and-print img)
          (flush)
          (System/exit 0))
      (interactive!))))
