(ns exp.e3
  "E3 · The agent crosses the boundary.

   Hypothesis: an agent defined as EDN can be serialized, transmitted and
   executed in another process with no shared code — thesis A, and the direct
   evidence about the `embabel-agent-spec` module, which the hub describes as
   *\"serializable action and goal definitions… defined in YML or otherwise
   persisted in a serialized format\"* and which does not exist in the repo.

     clojure -M:e3-export   writes target/e3-agent.edn (pure data)
     clojure -M:e3-import   another JVM: reads it, runs it, and edits the agent
                            IN THE DATA

   The metric is not yes/no — it is WHAT FRACTION of the surface survives the
   round-trip. The expected result (\"the graph is data, the bodies are code\")
   refutes nothing: a YML from `embabel-agent-spec` would hit the same limit.
   The point is to measure it."
  (:require [clojure.edn :as edn]
            [clojure.walk :as walk]
            [embabel-clj.blackboard :as bb]
            [embabel-clj.core :as ec]
            [embabel-clj.platform :as platform]
            [exp.common :as c])
  (:gen-class))

(def spec-file "target/e3-agent.edn")

;; --- the registry: the BODIES, which must exist on both sides ---------------
;;
;; Exactly what `embabel-agent-spec` would also need: a YML can name the action,
;; it cannot carry the body of it.

(def registry
  {:body/check-stock (fn [{:keys [pc]}] (bb/put! pc :stock 9)
                       (bb/set-condition! pc "checked" true))
   :body/pick        (fn [{:keys [pc]}] (bb/put! pc :boxes (quot (bb/fetch pc :stock 1) 3))
                       (bb/set-condition! pc "picked" true))
   :body/pack        (fn [{:keys [pc]}] (bb/put! pc :seal "S-77")
                       (bb/set-condition! pc "packed" true))
   :body/ship        (fn [{:keys [pc]}] (bb/put! pc :tracking "BR-0001")
                       (bb/set-condition! pc "delivered" true))})

;; --- the agent AS DATA: zero fns, everything literal ------------------------

(def spec
  {:name        "order"
   :description "Checks stock, picks, packs and ships an order"
   :goals       [{:name "shipped" :description "order on its way" :pre ["delivered"]}]
   :actions     [{:name "check-stock" :description "checks the stock"
                  :post ["checked"] :cost 0.1 :body :body/check-stock}
                 {:name "pick" :description "picks into boxes"
                  :pre ["checked"] :post ["picked"] :cost 0.2 :body :body/pick}
                 {:name "pack" :description "seals the box"
                  :pre ["picked"] :post ["packed"] :cost 0.2 :body :body/pack}
                 {:name "ship" :description "hands over to the carrier"
                  :pre ["packed"] :post ["delivered"] :cost 0.5 :body :body/ship}]})

(defn spec->agent
  "Resolves `:body` (a keyword) against the registry and produces the `:fn` the
   library expects. It is the ONLY translation needed between the data and the
   live agent."
  [s reg]
  (update s :actions
          (fn [as] (mapv (fn [a] (-> a
                                     (assoc :fn (or (get reg (:body a))
                                                    (throw (ex-info "body missing from registry"
                                                                    {:action (:name a)}))))
                                     (dissoc :body)))
                         as))))

;; --- the measurement --------------------------------------------------------

(defn- leaves
  "Every scalar leaf of the map, to measure how much is data."
  [m]
  (let [acc (atom [])]
    (walk/postwalk (fn [x] (when-not (coll? x) (swap! acc conj x)) x) m)
    @acc))

(defn export! []
  (c/section "E3 · export — the agent written as pure EDN")
  (let [text  (pr-str spec)
        back  (edn/read-string text)]
    (c/delete-files! spec-file)
    (spit (doto (java.io.File. spec-file) (-> .getParentFile .mkdirs)) text)
    (println "  written:" spec-file (str "(" (count text) " bytes)"))
    (println "  pr-str/read-string round-trip identical?" (= spec back))
    {:bytes (count text) :round-trip? (= spec back)}))

(defn import! []
  (c/section "E3 · import — another JVM: reads the EDN from disk and runs it")
  (let [s     (edn/read-string (slurp spec-file))
        sys   (c/boot! {})
        plat  (:platform sys)

        ;; (a) the agent coming from the data
        ag    (ec/agent (spec->agent s registry))
        proc  (c/run-agent! plat ag {:order "P-EDN"})
        acts  (c/actions-run proc)

        ;; (b) measurement: how much of the surface is data?
        ls      (leaves s)
        bodies  (count (filter #(= "body" (namespace %)) (filter keyword? ls)))
        data    (- (count ls) bodies)

        ;; (c) the agent EDITED IN THE DATA, without touching code:
        ;;     removing `pack` should make the goal unreachable
        no-pack (update s :actions (fn [as] (filterv #(not= "pack" (:name %)) as)))
        proc2   (c/run-agent! plat (ec/agent (spec->agent no-pack registry)) {:order "P-BROKEN"})
        status2 (str (.getStatus proc2))

        ;; (d) and edited to chain differently: ship straight from picked
        shortcut (update s :actions
                         (fn [as] (mapv #(if (= "ship" (:name %))
                                           (assoc % :pre ["picked"]) %) as)))
        proc3   (c/run-agent! plat (ec/agent (spec->agent shortcut registry)) {:order "P-SHORTCUT"})
        acts3   (c/actions-run proc3)]
    (platform/stop! sys)

    (c/verdict
     {:experiment :e3
      :hypothesis-confirmed? (and (= "COMPLETED" (str (.getStatus proc)))
                                  (= c/full-order acts)
                                  (not= "COMPLETED" status2)
                                  (not= acts acts3))

      :spec-bytes          (count (slurp spec-file))
      :total-leaves        (count ls)
      :leaves-that-are-data   data
      :leaves-that-are-bodies bodies
      :data-fraction       (c/round-to (/ (double data) (count ls)) 3)

      :survive-as-data     [:name :description :goals :pre :post :cost :actions]
      :needs-code          [:fn]
      :registry-size       (count registry)

      :ran-from-the-edn    acts
      :status-from-the-edn (str (.getStatus proc))
      :tracking            (bb/fetch proc :tracking)

      :editing-only-the-data
      {:without-the-pack-action {:status status2 :actions (c/actions-run proc2)}
       :ship-shortcut           {:actions acts3}}

      :finding
      (str "Of " (count ls) " leaves in the agent, " data " are pure data and " bodies
           " are references to a function body — " (c/round-to (* 100 (/ (double data) (count ls))) 1)
           "% of the surface crosses the boundary as EDN. What survives: name, description, "
           "goals, pre/post-conditions, costs and the ENTIRE topology of the graph. The only "
           "thing that does not cross is the action body, resolved by name through the "
           "registry — which is exactly the same limit a YML from `embabel-agent-spec` would "
           "hit. Proof that the graph really does come from the data: deleting the `pack` "
           "action in the EDN alone makes the goal unreachable (status " status2 "), and "
           "changing one pre-condition in the EDN makes the planner run " (pr-str acts3)
           " — zero lines of code touched.")})))

(defn -main [& [mode]]
  (case mode
    "export" (export!)
    "import" (do (import!) (shutdown-agents))
    (println "usage: -m exp.e3 export|import")))
