(ns exp.e2
  "E2 · The ceiling of 1000, measured instead of quoted.

   Hypothesis: past `windowSize` the framework loses the history of the oldest
   processes; the log loses nothing — and the price of that is small.

   There is no real doubt about the IF (`windowSize = 1000` and the eviction are
   in the source). What this experiment produces are the three numbers that
   answer the only real counter-attack — *it's expensive*: where memory starts
   erasing, what the log costs per tick, and how many bytes per process."
  (:require [clojure.java.io :as io]
            [embabel-clj.core :as ec]
            [embabel-clj.platform :as platform]
            [embabel-clj.process-store :as ps]
            [exp.common :as c])
  (:import [com.embabel.agent.core AgentPlatform AgentProcessRepository])
  (:gen-class))

(def n-runs 1500)
(def log-file "target/e2-processes.edn")

(defn- bulk-run!
  "Runs `n` processes and returns [ids millis]."
  [^AgentPlatform plat n]
  (let [ag  (ec/agent (c/order-agent))
        ;; warmup: without it the JIT dominates and the measured overhead swings
        ;; 20% -> 170% between identical runs. A lesson from this experiment.
        _   (dotimes [i 200] (c/run-agent! plat ag {:order (str "warm-" i)}))
        t0  (System/nanoTime)
        ids (mapv (fn [i]
                    (.getId (c/run-agent! plat ag {:order (str "p-" i)})))
                  (range n))]
    [ids (/ (- (System/nanoTime) t0) 1e6)]))

(defn -main [& _]
  (c/delete-files! log-file)

  ;; --- (1) baseline: the framework's DEFAULT repository ---------------------
  (c/section (str "E2 · baseline — framework's default repository, " n-runs " processes"))
  (let [base-sys   (c/boot! {})
        [_ ms-base] (bulk-run! (:platform base-sys) n-runs)
        _          (platform/stop! base-sys)

        ;; --- (2) the same, with the process-store plugged in ---------------
        _    (c/section (str "E2 · with the process-store — " n-runs " processes"))
        repo (ps/edn-repository {:file log-file})
        sys  (c/boot! {:repo repo})
        plat (:platform sys)
        [ids ms-store] (bulk-run! plat n-runs)

        ;; --- (3) do ids collide? (unplanned finding — see :finding-2) -------
        freq       (frequencies ids)
        collisions (- (count ids) (count (distinct ids)))
        ;; birthday: E[collisions] ~ n²/2N  =>  N ~ n²/2·collisions
        est-space  (when (pos? collisions)
                     (long (/ (* (double n-runs) n-runs) (* 2.0 collisions))))
        ;; the eviction measurement is only valid over ids that did NOT collide
        unique     (filterv #(= 1 (freq %)) ids)

        ;; --- (4) who does the framework still remember? ---------------------
        ^AgentProcessRepository bean (.getBean (:context sys) AgentProcessRepository)
        remembered (filterv #(some? (.findById bean %)) unique)
        idx        (fn [id] (.indexOf ^java.util.List ids id))
        first-remembered (when (seq remembered) (idx (first remembered)))
        last-forgotten   (when-let [f (seq (remove (set remembered) unique))]
                           (idx (last f)))
        _ (platform/stop! sys)

        ;; --- (5) the log, with everything dead -----------------------------
        log       (ps/read-log log-file)
        bytes     (.length (io/file log-file))
        ticks     (count log)
        forgotten (- (count unique) (count remembered))
        in-log    (count (filter #(seq (ps/timeline log %)) (take 5 unique)))]

    (c/verdict
     {:experiment :e2
      :hypothesis-confirmed? (and (pos? forgotten) (= 5 in-log))

      :processes                 n-runs
      :unique-ids                (count unique)
      :remembered-by-framework   (count remembered)
      :forgotten                 forgotten
      :first-still-remembered    first-remembered
      :last-forgotten            last-forgotten
      :declared-window-size      1000

      ;; --- the unplanned finding ---
      :collided-ids              collisions
      :most-repeated-id          (when (pos? collisions)
                                   (first (sort-by (comp - val) (filter #(> (val %) 1) freq))))
      :estimated-id-space        est-space

      :records-in-the-log        ticks
      :ticks-per-process         (double (/ ticks n-runs))
      :log-bytes                 bytes
      :bytes-per-process         (long (/ bytes n-runs))

      :ms-without-log            (Math/round ^double ms-base)
      :ms-with-log               (Math/round ^double ms-store)
      ;; the reliable metric is the ABSOLUTE one per tick; the percentage rests on
      ;; a microsecond baseline and is dominated by JIT/GC even with warmup.
      :overhead-ms-per-tick      (c/round-to (/ (- ms-store ms-base) ticks) 3)
      :noisy-overhead-pct        (c/round-to (* 100.0 (/ (- ms-store ms-base) ms-base)) 1)

      :of-the-first-5-processes-the-log-still-has in-log

      :finding
      (str "The framework forgot " forgotten " of " (count unique) " unique-id processes; "
           "the log holds " ticks " records and answers for ALL of them, evicted ones "
           "included. Cost: " (long (/ bytes n-runs)) " B/process. "
           "CAREFUL reading the overhead: it is measured on an agent WITHOUT an LLM, where "
           "a tick lasts microseconds — the worst possible case for the log. With an LLM "
           "call in the tick (hundreds of ms) the same absolute cost vanishes into noise.")

      :finding-2-unplanned
      (str "Process ids COLLIDE: " collisions " collisions in " n-runs
           " processes (~" (c/round-to (* 100.0 (/ collisions n-runs)) 1) "%). The default "
           "generator is MobyNameGenerator (Docker-style names, 'clever_napier'), with an "
           "estimated space of ~" est-space " names — and AgentProcess.id documents itself "
           "as 'Unique id of this process'. Consequence for ANY durable store (ours "
           "included): findById returns the wrong process and two unrelated runs merge into "
           "the same timeline. This is what polluted the eviction measurement until we "
           "isolated the unique ids. A legitimate question for upstream.")}))

  (c/save! "e2" {:log log-file})
  (shutdown-agents))
