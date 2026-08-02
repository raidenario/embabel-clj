# Experiments — E1 to E5

> The rule these were written under: **an experiment is something that can come
> out negative.** If a hypothesis can only be confirmed, it is a demo — and a demo
> teaches nothing to someone who already agrees.

All five run offline: no LLM key, no Docker, no Neo4j.

```bash
cd experiments && ./run-all.sh
```

Three companion experiments about DICE and indexed history (E6–E8) live in
[dice-chronicle](https://github.com/raidenario/dice-chronicle/blob/main/docs/experiments.md).
The reasoning all eight test is in
[agent-as-data.md](https://github.com/raidenario/dice-chronicle/blob/main/docs/agent-as-data.md).

| # | Experiment | Verdict |
|---|---|---|
| E1 | Real resume | confirmed |
| E2 | The 1000 ceiling | confirmed |
| E3 | The agent crosses the boundary | confirmed |
| E4 | Replay as saved tokens | **confirmed, with a label** |
| E5 | The log as an evaluation dataset | confirmed |

---

## E1 · Real resume

The decisive one. Phase 1 runs a four-action agent and calls `System/exit 9`
**inside the third action** — a real JVM death: no `finally`, no shutdown hook,
no cooperative teardown. The log is left with 3 records. Phase 2 is a different
operating-system process.

| | |
|---|---|
| interrupted process | `nifty_knuth` |
| actions before the crash | `["check" "pick"]` |
| restored slots | `{customer "ana", stock 9, boxes 3, order "P-4711"}` |
| restored conditions | `{checked, picked, hasRun_check, hasRun_pick}` |
| tombstones on the blackboard | **0** |
| **actions on resume** | **`["pack" "ship"]`** |
| actions in a clean run | `["check" "pick" "pack" "ship"]` |
| final status | `COMPLETED`, tracking `BR-0001` |

**With a blackboard made of values, this is not just durable history — it is
resumable execution.** The planner saw the restored world and repeated nothing.

> **Caveat the experiment imposed on itself.** The resumed process is a *new*
> process — new id, `history` starting from zero. Continuity belongs to the
> **world**, not to the identity. Stitching the two timelines together
> (`:parent-id`, or a `:resumed-from`) is the next step, and it is small.

## E2 · The 1000 ceiling, measured

1500 runs, with and without the process store.

- the framework remembered **964** of 1416 unique ids; **452 forgotten**
- eviction is exactly **FIFO**: last forgotten `#477`, first remembered `#478`
- log: 9000 records, **4,765 B/process**, **~0.5–0.8 ms/tick** of overhead

Overhead as a *percentage* is useless here (it swung 20%→172% between identical
runs before warmup) because the LLM-free baseline is measured in microseconds. The
honest metric is absolute time per tick — and in a tick containing an LLM call
(hundreds of ms) it disappears into the noise.

### E2.2 · Unplanned finding: process ids collide

**42 collisions in 1500 processes (~2.8%).** The default generator is
`MobyNameGenerator` (`clever_napier`), a birthday-bound space of roughly **26,785
names** — while `AgentProcess.id` documents itself as *"Unique id of this
process"*.

The consequence applies to **any** durable store, this one included: `findById`
can return the wrong process, and two unrelated runs can merge into a single
timeline. It is what polluted the eviction measurement until unique ids were
isolated — with collisions present, "first still remembered" came out as `#12`,
which is meaningless.

## E3 · The agent crosses the boundary

An agent written as pure EDN (671 bytes), read in another JVM, resolved against a
registry of four function bodies.

- **58 leaves: 54 data, 4 references to code → 93.1% data**
- what survives: name, description, goals, pre/post-conditions, costs, and **the
  entire topology of the graph**
- ran from the EDN: `["check" "pick" "pack" "ship"]`, `COMPLETED`

And the proof that the graph really does come from the data — editing only the
file:

| edit, in the EDN only | effect |
|---|---|
| delete the `pack` action | goal unreachable → `STUCK` |
| change `ship`'s precondition to `picked` | `["check" "pick" "ship"]` |

Zero lines of code touched. This is the measured answer for a serializable agent
spec: *the graph is data, the bodies are code* — and a YAML format would have
exactly the same limit.

## E4 · Replay as saved tokens — with a label

An agent whose branch is chosen by the "LLM"; the cache is **derived from the
log** by diffing ticks.

- **Arm A (deterministic):** 2 cold calls → **0** with cache, **100% saved**,
  across 5/5 runs with an identical path. The corrected action really did run
  (`BR-v2`).
- **Arm B (non-deterministic), with a control:** **without** cache, 12/20 runs
  repeated the original path — the agent genuinely oscillates between 2 paths on
  the same input. **With** cache: **20/20 identical and zero calls.**

> **The cache does not fail under non-determinism — it erases it.** The agent
> stops deciding and starts replaying the recorded decision, silently. So
> log-derived replay is excellent as **audit and regression testing** (it pins the
> path on purpose) and **unsafe as transparent memoisation** anywhere the LLM
> picks the branch. The saving is real and comes with that label.

Without the control arm the experiment would have lied: 20/20 identical paths
would have looked like agent stability, when it was the cache pinning the route.

## E5 · The log as an evaluation dataset

40 runs, half carrying a planted routing bug (always routes "simple", ignoring its
own classification). The bug is **invisible in the result** — all 40 orders were
delivered, all `COMPLETED` — and shows up only in the **path**.

- bugs detectable in the trace: **8** · caught by the judge: **8** · false
  positives: **0**
- recall **1.0**, precision **1.0**

A methodological note the experiment forced: of the 20 buggy agents only 8 are
detectable, because when the agent classifies "simple" the bug and the correct
behaviour coincide. **Evaluation measures what the trace exposes, not intent.**

### E5.2 · Unplanned finding: the two listener seams are not equivalent

| channel | events | types |
|---|---|---|
| `:listeners` on `ProcessOptions` | 120 | **1** (`:object-bound`) |
| listener as a platform **bean** | 760 | **8** |

Only the bean sees `:action-execution-start`, `:action-execution-result`,
`:agent-process-creation`, `:agent-process-plan-formulated`,
`:agent-process-ready-to-plan`, `:goal-achieved` and `:agent-process-completed`.

The reason is in the source: `ProcessContext` composes `processOptions.listeners +
platformServices.eventListener`, but `AbstractAgentProcess` emits
`AgentProcessCompleted/Failed/Waiting/Paused` **directly** on
`platformServices.eventListener`, bypassing the composite.

**This corrected this library's own documentation**, which presented `:listeners`
as the "no Spring bean needed" path. For durable tracing the correct seam is the
**bean**.

### E5.3 · The gap that was already predicted

`LlmInvocation` stores `llmMetadata`, `usage`, `timestamp`, `runningTime` — it does
**not** store the prompt or the response. That content lives only in
`LlmRequestEvent.messages` and `LlmResponseEvent.response`, which are ephemeral
events. An LLM judge of **content** requires stitching the store to those events;
the seam is built, and what is missing is a real provider so there is something to
record.

---

## Open questions for the upstream

Born from reading the source, offered as questions:

1. `AgentProcess.id` documents itself as unique, but the default
   `MobyNameGenerator` collides ~2.8% in 1500 processes. Is the integrator
   expected to swap the `NameGenerator` before persisting?
2. `ProcessOptions.listeners` does not receive process lifecycle events
   (Completed/Failed/Waiting/Paused), which go straight to the platform listener.
   Design, or accidental asymmetry?
3. `windowSize = 1000` with silent eviction, given that `core/hitl` wait states
   depend on the repository — what is the intended path for a process that waits
   three days?
