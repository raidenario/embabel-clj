(ns embabel-clj.dev
  "Conveniências de desenvolvimento. Nada aqui é dependência dura da lib —
   o nREPL só é exigido se você chamar start-nrepl! (adicione nrepl/nrepl às
   suas deps de dev).")

(defonce ^:private nrepl-server (atom nil))

(defn start-nrepl!
  "Sobe um nREPL DENTRO do processo que hospeda o AgentPlatform — o fluxo
   REPL-driven dos projetos agendas/beautiful-linkedin: redefinir actions
   (defn) e re-deployar agentes ao vivo, sem restart."
  ([] (start-nrepl! {}))
  ([{:keys [port bind] :or {port 7888 bind "127.0.0.1"}}]
   (if @nrepl-server
     @nrepl-server
     (let [start (try (requiring-resolve 'nrepl.server/start-server)
                      (catch Exception _
                        (throw (ex-info (str "embabel-clj.dev/start-nrepl! precisa de "
                                             "nrepl/nrepl no classpath (dep de dev).")
                                        {:missing 'nrepl/nrepl}))))
           srv   (start :port port :bind bind)]
       (reset! nrepl-server srv)
       (println (format "[embabel-clj] nREPL ouvindo em %s:%d" bind port))
       srv))))

(defn stop-nrepl! []
  (when-let [srv @nrepl-server]
    ((requiring-resolve 'nrepl.server/stop-server) srv)
    (reset! nrepl-server nil)
    :stopped))
