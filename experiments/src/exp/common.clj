(ns exp.common
  "Shared bench infrastructure: boot the platform offline, the guinea-pig agent,
   and result reporting (every experiment returns a map — the result is data,
   like everything else here)."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [embabel-clj.blackboard :as bb]
            [embabel-clj.core :as ec]
            [embabel-clj.platform :as platform]
            [embabel-clj.process-store :as ps])
  (:import [com.embabel.agent.core AgentPlatform AgentProcess]))

;; --- platform ---------------------------------------------------------------

(defn boot!
  "Boots the platform with (or without) the process-store plugged in.
   `:repo` nil = the framework's default repository (in-memory, window of
   1000) — the baseline."
  [{:keys [repo beans quiet?] :or {quiet? true}}]
  (platform/start!
   (cond-> {:properties (cond-> {:embabel.agent.platform.models.openai.api-key "dummy-offline"}
                          quiet? (assoc :logging.level.root "WARN"))}
     repo  (assoc :initializers [(ps/as-primary-bean repo)])
     beans (assoc :beans beans))))

;; --- the guinea-pig agent ---------------------------------------------------
;;
;; Four actions chained by conditions, no LLM. `pack` takes a hook so E1 can
;; kill the JVM in the middle of it.

(defn- step
  "Body of an action: write a slot and turn on the outgoing condition."
  [slot value-fn out-condition]
  (fn [{:keys [pc]}]
    (bb/put! pc slot (value-fn pc))
    (bb/set-condition! pc out-condition true)))

(defn order-agent
  "The agent. `hooks` is {action-name fn} — called BEFORE the body, so E1 can
   inject the crash without changing the agent."
  ([] (order-agent {}))
  ([hooks]
   (let [wrap (fn [name* f]
                (fn [ctx]
                  (when-let [h (get hooks name*)] (h ctx))
                  (f ctx)))]
     {:name        "order"
      :description "Checks stock, picks, packs and ships an order"
      :goals       [{:name "shipped" :description "order on its way" :pre ["delivered"]}]
      :actions
      [{:name "check-stock" :post ["checked"]
        :fn   (wrap "check-stock" (step :stock (constantly 9) "checked"))}
       {:name "pick" :pre ["checked"] :post ["picked"]
        :fn   (wrap "pick" (step :boxes #(quot (bb/fetch % :stock 1) 3) "picked"))}
       {:name "pack" :pre ["picked"] :post ["packed"]
        :fn   (wrap "pack" (step :seal (constantly "S-77") "packed"))}
       {:name "ship" :pre ["packed"] :post ["delivered"]
        :fn   (wrap "ship" (step :tracking (constantly "BR-0001") "delivered"))}]})))

(def full-order ["check-stock" "pick" "pack" "ship"])

;; --- reading the process ----------------------------------------------------

(defn actions-run
  "The actions THIS process executed, in order."
  [^AgentProcess proc]
  (mapv #(.getActionName %) (.getHistory proc)))

(defn run-agent!
  "Runs the agent and returns the AgentProcess."
  ^AgentProcess [^AgentPlatform plat ag bindings]
  (ec/run! plat ag {:bindings bindings}))

;; --- reporting --------------------------------------------------------------

(defn round-to
  "Rounds to `places` decimals. Exists because `format \"%.2f\"` uses the default
   locale (pt-BR -> comma) and parsing it back blows up."
  ([x] (round-to x 2))
  ([x places]
   (let [m (Math/pow 10 places)]
     (/ (Math/round (* (double x) m)) m))))

(defn section [title]
  (println (str "\n" (apply str (repeat 78 "-"))
                "\n" title
                "\n" (apply str (repeat 78 "-")))))

(defn verdict
  "Prints and returns the experiment result. `:hypothesis-confirmed?` is what
   matters — including when it is false."
  [m]
  (println "\nRESULT:")
  (pp/pprint m)
  (println (if (:hypothesis-confirmed? m)
             "\n>>> HYPOTHESIS CONFIRMED"
             "\n>>> HYPOTHESIS REFUTED (or partial) — read :finding"))
  m)

(defn save! [name* m]
  (let [f (io/file "target" (str name* ".edn"))]
    (io/make-parents f)
    (spit f (with-out-str (pp/pprint m)))
    f))

(defn delete-files! [& paths]
  (doseq [p paths] (io/delete-file p true)))
