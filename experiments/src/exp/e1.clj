(ns exp.e1
  "E1 · Real resume — the experiment that decides which category the thesis is in.

   Hypothesis: with a blackboard made of values only, an interrupted process can
   be rehydrated in another JVM from the log and finish where it left off.

   Two real operating-system processes:

     clojure -M:e1-crash    runs and calls System/exit IN THE MIDDLE of action 3
     clojure -M:e1-resume   boots another JVM, reads the log from disk, finishes

   If this works the argument changes category: it stops being observability and
   becomes DURABLE EXECUTION — which is what Embabel's own `core/hitl` package
   needs and does not have today (the repository is in-memory with a window of
   1000)."
  (:require [embabel-clj.blackboard :as bb]
            [embabel-clj.core :as ec]
            [embabel-clj.platform :as platform]
            [embabel-clj.process-store :as ps]
            [exp.common :as c])
  (:import [com.embabel.agent.core AgentPlatform AgentProcess])
  (:gen-class))

(def log-file "target/e1-processes.edn")

;; --- phase 1: run and die ---------------------------------------------------

(defn crash! []
  (c/delete-files! log-file)
  (c/section "E1 · phase 1 — run and KILL the JVM in the middle of action 3 (pack)")
  (let [repo (ps/edn-repository {:file log-file})
        sys  (c/boot! {:repo repo})
        ag   (ec/agent (c/order-agent
                        {"pack" (fn [_]
                                  (println "  >>> System/exit 9 RIGHT HERE. No finally, no shutdown hook.")
                                  (flush)
                                  (System/exit 9))}))]
    (println "  running...")
    (c/run-agent! (:platform sys) ag {:order "P-4711" :customer "ana"})
    (println "  NEVER REACHED")
    (platform/stop! sys)))

;; --- phase 2: rehydrate and finish ------------------------------------------

(defn- restore!
  "Rebuilds the interrupted process's world from the last log record:
   conditions become setCondition, everything else becomes a slot binding.

   This is where the thesis lives or dies — if state the log does not carry is
   missing, the planner replans wrongly or the process never closes."
  [^AgentPlatform plat ag record]
  (let [p     (:process record)
        conds (:conditions p)
        slots (apply dissoc (:bindings p) (keys conds))
        ^AgentProcess proc (.createAgentProcess plat ag (ec/process-options nil) slots)]
    (doseq [[k v] conds] (bb/set-condition! proc k v))
    {:proc proc :slots slots :conds conds}))

(defn resume! []
  (c/section "E1 · phase 2 — another JVM, reading the log from disk")
  (let [log      (ps/read-log log-file)
        id       (get-in (last log) [:process :id])
        last-rec (last (ps/timeline log id))
        before   (mapv :action (get-in last-rec [:process :history]))
        _ (println "  interrupted process:" id)
        _ (println "  actions already run:" before)
        _ (println "  status in the log:" (get-in last-rec [:process :status]))

        repo (ps/edn-repository {:file log-file})
        sys  (c/boot! {:repo repo})
        plat (:platform sys)
        ag   (ec/agent (c/order-agent))         ; without the crash hook

        ;; (a) resume from the log
        {:keys [proc slots conds]} (restore! plat ag last-rec)
        resumed  (.join (.start plat proc))
        resumed-actions (c/actions-run resumed)

        ;; (b) control: a clean run from scratch, to compare the path
        control  (c/run-agent! plat ag {:order "P-CONTROL" :customer "ana"})
        control-actions (c/actions-run control)

        ;; (c) tombstones: how much of the blackboard was NOT a value?
        tombstones (count (filter #(and (map? %) (:embabel-clj/type %))
                                  (vals (get-in last-rec [:process :bindings]))))

        completed? (= "COMPLETED" (str (.getStatus resumed)))
        only-remaining? (= resumed-actions
                           (vec (drop (count before) c/full-order)))]
    (platform/stop! sys)

    (c/verdict
     {:experiment :e1
      :hypothesis-confirmed? (and completed? only-remaining? (zero? tombstones))

      :interrupted-process    id
      :actions-before-crash   before
      :status-in-the-log      (get-in last-rec [:process :status])
      :restored-slots         slots
      :restored-conditions    conds
      :tombstones-in-blackboard tombstones

      :actions-on-resume      resumed-actions
      :actions-on-a-clean-run control-actions
      :final-status-of-resume (str (.getStatus resumed))
      :tracking-produced      (bb/fetch resumed :tracking)

      :action-calls-saved     (- (count control-actions) (count resumed-actions))

      :finding
      (if (and completed? only-remaining?)
        (str "The process was rebuilt in another JVM from the log and completed by running "
             "ONLY " (pr-str resumed-actions) " — the planner saw the restored world and did "
             "not repeat " (pr-str before) ". "
             "Thesis B is not merely 'durable history': with a blackboard of values it is "
             "RESUMABLE EXECUTION. Honest caveat: the resumed process is a NEW process "
             "(new id, history starting from zero) — the continuity is of the WORLD, not of "
             "the identity. Stitching the two timelines via :parent-id, or via a "
             ":resumed-from field, is the next step.")
        (str "The resume did NOT reproduce the expected behaviour. "
             "actions-on-resume=" (pr-str resumed-actions)
             " expected=" (pr-str (vec (drop (count before) c/full-order)))
             " status=" (str (.getStatus resumed))
             ". Thesis B is reduced to 'durable history' until this is solved."))})))

(defn -main [& [mode]]
  (case mode
    "crash"  (crash!)
    "resume" (do (resume!) (shutdown-agents))
    (println "usage: -m exp.e1 crash|resume")))
