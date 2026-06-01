(ns embabel-clj.register
  (:require [embabel-clj.agents :as agents]
            [embabel-clj.nature :as nature]
            [clojure.string :as str]
            [nrepl.server :as nrepl])
  (:import [com.embabel.agent.core AgentPlatform ProcessOptions]
           [com.embabel.agent.api.common AgentImage]
           [java.io File]
           [java.net URL]
           [java.util.concurrent CountDownLatch]))

(defonce ^AgentPlatform platform (atom nil))
(defonce hello-ref (atom nil))
(defonce nature-ref (atom nil))
(defonce nrepl-server (atom nil))

(def nrepl-port 7888)

(defn run-once
  []
  (let [proc (.runAgentFrom @platform @hello-ref (ProcessOptions.) {})]
    {:status   (str (.getStatus proc))
     :greeting (.get proc agents/slot-greeting)}))

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

(defn- start-nrepl! []
  (when-not @nrepl-server
    (reset! nrepl-server (nrepl/start-server :port nrepl-port :bind "127.0.0.1"))
    (println (format "[embabel-clj] nREPL ouvindo em 127.0.0.1:%d" nrepl-port))))

(defn- keep-alive!
  []
  (doto (Thread. ^Runnable (fn [] (try (.await (CountDownLatch. 1))
                                       (catch InterruptedException _ nil)))
                 "embabel-clj-keepalive")
    (.setDaemon false)
    (.start)))

(defn- deploy!
  [^AgentPlatform p]
  (reset! platform p)
  (let [hello  (agents/hello-agent)
        nature (nature/nature-agent)]
    (reset! hello-ref hello)
    (reset! nature-ref nature)
    (.deploy p hello)
    (.deploy p nature))
  (println "[embabel-clj] agentes deployados:"
           (mapv #(.getName %) (.agents p)))
  (start-nrepl!)
  (println "[embabel-clj] run de startup (hello) =>" (run-once)))

(defn boot
  [^AgentPlatform p args]
  (deploy! p)
  (keep-alive!)
  (let [img (first (seq args))]
    (if img
      (do (println "[embabel-clj] one-shot, imagem:" img)
          (analyze-and-print img)
          (flush)
          (System/exit 0))
      (interactive!)))
  :ok)

(defn register
  [^AgentPlatform p]
  (boot p nil))
