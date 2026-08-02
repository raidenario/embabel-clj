(ns store.demo
  "Tese B, ponta a ponta: o objeto do processo é efêmero — a história não precisa ser.

   Três rodadas de um agente GOAP real na plataforma real (sem LLM: as actions
   são código puro, a chave do provider é dummy). O AgentProcessRepository é o
   `embabel-clj.process-store`, plugado como bean @Primary. No fim o contexto
   Spring é FECHADO — que é o mais perto de 'a JVM morreu' que dá para fazer
   num processo só — e a história é lida de volta do DISCO, como dado."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [embabel-clj.blackboard :as bb]
            [embabel-clj.core :as ec]
            [embabel-clj.platform :as platform]
            [embabel-clj.process-store :as ps])
  (:gen-class))

(def log-file "target/processes.edn")

;; --- o agente: três actions encadeadas por condições, zero LLM --------------

(defn- conferir [{:keys [pc]}]
  (bb/put! pc :estoque (+ 3 (rand-int 5)))
  (bb/set-condition! pc "conferido" true))

(defn- separar [{:keys [pc]}]
  (bb/put! pc :caixas (max 1 (quot (bb/fetch pc :estoque 1) 3)))
  (bb/set-condition! pc "separado" true))

(defn- despachar [{:keys [pc]}]
  (bb/put! pc :rastreio (format "BR%04d" (rand-int 9999)))
  (bb/set-condition! pc "entregue" true))

(def pedido
  {:name        "pedido"
   :description "Confere estoque, separa e despacha um pedido"
   :goals       [{:name "despachado" :description "pedido a caminho" :pre ["entregue"]}]
   :actions     [{:name "conferir-estoque" :post ["conferido"] :fn conferir}
                 {:name "separar"  :pre ["conferido"] :post ["separado"] :fn separar}
                 {:name "despachar" :pre ["separado"] :post ["entregue"] :fn despachar}]})

;; --- a demo -----------------------------------------------------------------

(defn -main [& _]
  (io/delete-file log-file true)

  (let [repo (ps/edn-repository {:file log-file})
        sys  (platform/start!
              {:properties   {:embabel.agent.platform.models.openai.api-key "dummy-nao-usada"
                              :logging.level.root                           "WARN"}
               :initializers [(ps/as-primary-bean repo)]})
        plat (:platform sys)]

    (println "\n=== 3 rodadas na plataforma real ===")
    (ec/deploy! plat (ec/agent pedido))
    (dotimes [i 3]
      (let [proc (ec/run! plat (ec/agent pedido) {:bindings {:cliente (str "c-" i)}})]
        (println " " (ec/result proc {:slots [:rastreio]}))))

    (println "\n=== o repositório em uso É o nosso (resolução por tipo, @Primary) ===")
    (println "  bean:" (.getSimpleName
                        (class (.getBean (:context sys)
                                         com.embabel.agent.core.AgentProcessRepository))))

    (println "\n=== fechando o contexto Spring — o equivalente a matar a JVM ===")
    (platform/stop! sys))

  ;; A partir daqui não existe mais plataforma, processo, blackboard nem Spring.
  ;; Só um arquivo EDN e funções puras.
  (let [log (ps/read-log log-file)]
    (println "\n=== a história, lida do DISCO, com tudo morto ===")
    (println "\nsummary:")
    (pp/pprint (ps/summary log))
    (println "\nruns:")
    (pp/print-table [:id :agent :goal :status :ticks :actions] (ps/runs log))
    (let [id (:id (first (ps/runs log)))]
      (println "\ntimeline do primeiro processo (" id "):")
      (doseq [r (ps/timeline log id)]
        (println " " (:seq r) (:event r)
                "status=" (get-in r [:process :status])
                "actions=" (mapv :action (get-in r [:process :history]))
                "slots=" (get-in r [:process :bindings]))))
    (println "\nas-of no meio do log (o que o sistema sabia então):")
    (pp/print-table [:id :status :ticks] (ps/as-of log (:at (nth log (quot (count log) 2))))))

  (shutdown-agents))
