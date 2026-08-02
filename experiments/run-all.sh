#!/usr/bin/env bash
# Runs the whole bench. E1 requires two operating-system processes: phase 1
# calls System/exit in the middle of an action, which is the point of it.
set -u
cd "$(dirname "$0")"

noise() { grep -v "WARN \|INFO \|Console Code\|Unicode\|BeanPostProcessor"; }

echo "=== E1 · real resume (2 JVMs) ==="
clojure -M:e1-crash  2>&1 | noise
clojure -M:e1-resume 2>&1 | noise | sed -n '/RESULT/,$p'

for e in e2 e4 e5; do
  echo; echo "=== ${e^^} ==="
  clojure -M:$e 2>&1 | noise | sed -n '/RESULT/,$p'
done

echo; echo "=== E3 · agent as EDN (2 JVMs) ==="
clojure -M:e3-export 2>&1 | noise | tail -3
clojure -M:e3-import 2>&1 | noise | sed -n '/RESULT/,$p'

echo; echo "=== E6/E7 · chronicle scale ==="
echo "cd ../../../dice-chronicle/experiments && clojure -M:e6 && clojure -M:e7"
