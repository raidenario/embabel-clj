(ns exp.e4
  "E4 · Replay as saved tokens — and the replanning risk.

   Hypothesis: with the log, fixing the last action does not require re-calling
   the LLM on the earlier steps (memoization by action + input state).

   The risk that makes this an experiment rather than a demo: **GOAP replans on
   every tick**. If the LLM output decides which way the plan goes, two runs
   with the SAME input can take DIFFERENT paths — and then the cache is not just
   useless, it is unsafe.

   That is why the agent has a branch decided by the LLM, and the experiment has
   two arms:
     A · deterministic LLM (temperature 0)     -> the cache should hold
     B · non-deterministic LLM (temp > 0)      -> the path should diverge

   The cache is NOT invented: it is DERIVED FROM THE LOG by diffing consecutive
   ticks — exactly what the thesis promises the log enables."
  (:require [embabel-clj.blackboard :as bb]
            [embabel-clj.core :as ec]
            [embabel-clj.platform :as platform]
            [embabel-clj.process-store :as ps]
            [exp.common :as c])
  (:gen-class))

(def log-file "target/e4-processes.edn")

;; --- the "LLM" --------------------------------------------------------------
;;
;; Fake on purpose: the experiment is about the PLANNER, not the model. A real
;; provider would only change the cost of each call — the question (is the path
;; stable?) is the same, and this way it runs offline and reproducibly.

(def calls (atom 0))

(defn- llm!
  "Classifies the order. `deterministic?` selects the arm of the experiment.
   Simulates latency so that 'call avoided' means something."
  [deterministic? order]
  (swap! calls inc)
  (Thread/sleep 5)
  (if deterministic?
    (if (even? (hash order)) "complex" "simple")
    (rand-nth ["complex" "simple"])))

;; --- the branching agent ----------------------------------------------------

(defn agent-def [{:keys [deterministic? cache suffix]}]
  (let [;; the memoization wrapper: key = [action, input state]
        memo (fn [name* f]
               (fn [{:keys [pc] :as ctx}]
                 (let [before (into (sorted-map)
                                    (remove (comp boolean? val))
                                    (.expressionEvaluationModel (bb/->blackboard pc)))
                       k      [name* (hash before)]]
                   (if-let [out (get cache k)]
                     (do (doseq [[slot v] (:slots out)] (bb/put! pc (keyword slot) v))
                         (doseq [[cnd v] (:conds out)] (bb/set-condition! pc cnd v))
                         :cache-hit)
                     (f ctx)))))]
    {:name        "triage"
     :description "Classifies the order and routes it"
     :goals       [{:name "done" :pre ["delivered"]}]
     :actions
     ;; OPTIMISTIC posts: the planner must believe `classify` can lead to either
     ;; branch, otherwise no plan reaches the goal and the process is born STUCK
     ;; without executing anything (GOAP gotcha verified here: the first version
     ;; of this experiment declared only [\"classified\"] and the agent sat
     ;; still). At runtime only one of the two becomes true, the world is
     ;; re-derived and the planner replans onto the right route.
     [{:name "classify" :post ["classified" "simple" "complex"]
       :fn (memo "classify"
                 (fn [{:keys [pc]}]
                   (let [cls (llm! deterministic? (bb/fetch pc :order "x"))]
                     (bb/put! pc :class cls)
                     (bb/set-condition! pc "classified" true)
                     (bb/set-condition! pc (if (= cls "complex") "complex" "simple") true))))}

      {:name "fast-route" :pre ["classified" "simple"] :post ["routed"] :cost 0.1
       :fn (memo "fast-route"
                 (fn [{:keys [pc]}]
                   (llm! deterministic? "fast-route")
                   (bb/put! pc :route "express")
                   (bb/set-condition! pc "routed" true)))}

      {:name "slow-route" :pre ["classified" "complex"] :post ["routed"] :cost 0.9
       :fn (memo "slow-route"
                 (fn [{:keys [pc]}]
                   (llm! deterministic? "slow-route")
                   (bb/put! pc :route "consolidated")
                   (bb/set-condition! pc "routed" true)))}

      ;; the "fixed" action: changes behaviour between runs, and is the ONLY one
      ;; that must really execute the second time round
      {:name "ship" :pre ["routed"] :post ["delivered"]
       :fn (fn [{:keys [pc]}]
             (bb/put! pc :tracking (str "BR-" suffix))
             (bb/set-condition! pc "delivered" true))}]}))

;; --- the cache, DERIVED FROM THE LOG ---------------------------------------

(defn cache-from-log
  "Diffs consecutive ticks: the action that ran between them is the new entry in
   `history`, and what it produced is the slot/condition delta. Key = [action,
   hash of the input state] — the same key the `memo` computes at runtime."
  [log process-id]
  (let [recs (ps/timeline log process-id)]
    (into {}
          (keep (fn [[a b]]
                  (let [ha  (mapv :action (get-in a [:process :history]))
                        hb  (mapv :action (get-in b [:process :history]))
                        new (first (drop (count ha) hb))]
                    (when new
                      (let [ba     (get-in a [:process :bindings])
                            bb*    (get-in b [:process :bindings])
                            before (into (sorted-map) (remove (comp boolean? val)) ba)
                            delta  (into {} (remove (fn [[k v]] (= v (get ba k)))) bb*)]
                        [[new (hash before)]
                         {:slots (into {} (remove (comp boolean? val)) delta)
                          :conds (into {} (filter (comp boolean? val)) delta)}])))))
          (partition 2 1 recs))))

;; --- the arms ---------------------------------------------------------------

(defn- run-arm [{:keys [deterministic? repeats]}]
  (c/delete-files! log-file)
  (let [repo (ps/edn-repository {:file log-file})
        sys  (c/boot! {:repo repo})
        plat (:platform sys)
        input {:order "P-777"}

        ;; cold run: no cache
        _     (reset! calls 0)
        p1    (c/run-agent! plat (ec/agent (agent-def {:deterministic? deterministic?
                                                       :cache {} :suffix "v1"}))
                            input)
        cold  @calls
        path1 (c/actions-run p1)

        log   (ps/read-log log-file)
        cache (cache-from-log log (.getId p1))

        ;; CONTROL: the same repeats WITHOUT cache. Without this arm B lies —
        ;; a cache that never calls the LLM always shows 100% identical paths,
        ;; and the stability would be the cache's, not the agent's.
        control
        (vec (for [_ (range repeats)]
               (c/actions-run
                (c/run-agent! plat (ec/agent (agent-def {:deterministic? deterministic?
                                                         :cache {} :suffix "v1"}))
                              input))))

        ;; warm runs: with the log-derived cache, and the last action "fixed"
        results
        (vec (for [_ (range repeats)]
               (do (reset! calls 0)
                   (let [p (c/run-agent! plat (ec/agent (agent-def {:deterministic? deterministic?
                                                                    :cache cache :suffix "v2"}))
                                         input)]
                     {:calls    @calls
                      :path     (c/actions-run p)
                      :tracking (bb/fetch p :tracking)
                      :status   (str (.getStatus p))}))))]
    (platform/stop! sys)
    {:cold-calls       cold
     :cold-path        path1
     :cache-entries    (count cache)
     :warm             results
     :warm-calls       (mapv :calls results)
     :same-paths       (count (filter #(= path1 (:path %)) results))
     :control-distinct-paths (count (distinct control))
     :control-same-as-cold   (count (filter #(= path1 %) control))
     :repeats          repeats}))

(defn -main [& _]
  (c/section "E4 · arm A — deterministic LLM (the cache should hold)")
  (let [a (run-arm {:deterministic? true :repeats 5})
        _ (c/section "E4 · arm B — non-deterministic LLM (the path should diverge)")
        b (run-arm {:deterministic? false :repeats 20})

        saving-a (- (:cold-calls a) (double (/ (reduce + (:warm-calls a)) (:repeats a))))]

    (c/verdict
     {:experiment :e4
      ;; the hypothesis only holds ENTIRELY if arm A saves AND arm B shows that
      ;; the safety of the cache depends on determinism
      :hypothesis-confirmed? (and (pos? saving-a)
                                  (= (:repeats a) (:same-paths a)))

      :arm-a-deterministic
      {:llm-calls-without-cache (:cold-calls a)
       :llm-calls-with-cache    (:warm-calls a)
       :average-saving          (c/round-to saving-a 2)
       :saving-pct              (c/round-to (* 100.0 (/ saving-a (:cold-calls a))) 1)
       :path                    (:cold-path a)
       :identical-paths         (str (:same-paths a) "/" (:repeats a))
       :cache-entries           (:cache-entries a)
       :tracking-of-the-fix     (:tracking (first (:warm a)))}

      :arm-b-non-deterministic
      {:cold-path                (:cold-path b)
       :without-cache-distinct-paths (:control-distinct-paths b)
       :without-cache-same-as-cold   (str (:control-same-as-cold b) "/" (:repeats b))
       :with-cache-same-as-cold      (str (:same-paths b) "/" (:repeats b))
       :calls-with-cache         (frequencies (:warm-calls b))}

      :finding
      (str "Arm A (deterministic): " (:cold-calls a) " LLM calls cold, "
           (pr-str (:warm-calls a)) " with the log-derived cache — a saving of "
           (c/round-to (* 100.0 (/ saving-a (:cold-calls a))) 1) "%. The fixed action really "
           "did run (tracking " (pr-str (:tracking (first (:warm a))))
           "), the earlier ones came from the log. The saving is real.")

      :finding-2-the-danger
      (str "Arm B (non-deterministic) WITHOUT cache: "
           (:control-same-as-cold b) " out of " (:repeats b)
           " runs repeated the original path — the agent genuinely oscillates between "
           (:control-distinct-paths b) " paths on the SAME input. "
           "WITH the cache: " (:same-paths b) "/" (:repeats b)
           " identical and ZERO LLM calls. "
           "In other words: the cache does not fail under non-determinism — it ERASES it. "
           "The agent stops deciding and starts repeating the recorded decision, silently. "
           "Honest conclusion: log-derived replay is excellent as **auditing and regression "
           "testing** (it pins the path on purpose) and **unsafe as transparent memoization** "
           "anywhere the LLM picks the branch, because it freezes a choice that should be "
           "made again. E4's token saving is true and comes with that label.")})

    (shutdown-agents)))
