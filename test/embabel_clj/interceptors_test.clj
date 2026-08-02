(ns embabel-clj.interceptors-test
  "Interceptors do tool loop como fns: monta os contextos REAIS do Embabel e
   chama as interfaces de verdade (sem Spring, sem LLM). O que este arquivo
   protege é o contrato — assinatura, projeção e tipo de retorno."
  (:require [clojure.test :refer [deftest is testing]]
            [embabel-clj.interceptors :as ic]
            [embabel-clj.schema :as schema]
            [embabel-clj.tools :as tools])
  (:import [com.embabel.agent.api.common PromptRunner]
           [com.embabel.agent.api.tool Tool$Result]
           [com.embabel.agent.api.tool.callback
            AfterIterationContext AfterLlmCallContext AfterToolCallContext
            AfterToolResultContext BeforeLlmCallContext BeforeToolCallContext
            ToolCallInspector ToolLoopInspector ToolLoopTransformer]
           [com.embabel.chat ToolCall UserMessage]))

;; --- fixtures: os contextos que o framework passaria -------------------------

(defn- msg ^UserMessage [s] (UserMessage. ^String s nil (java.time.Instant/now)))

(def ^:private historia [(msg "oi") (msg "tudo bem?")])

(defn- eco-tool []
  (tools/tool {:name "eco" :description "ecoa"
               :schema [:map [:msg :string]]
               :fn :msg}))

(defn- before-llm ^BeforeLlmCallContext []
  (BeforeLlmCallContext. historia (int 1) [(eco-tool)] (Integer/valueOf 42)))

(defn- after-llm ^AfterLlmCallContext []
  (AfterLlmCallContext. historia (int 2) (msg "resposta") nil))

(defn- tool-call ^ToolCall [] (ToolCall. "tc-1" "eco" "{\"msg\":\"oi\"}"))

(defn- after-tool-result ^AfterToolResultContext []
  (AfterToolResultContext. historia (int 3) (tool-call)
                           (Tool$Result/text "resultado longo") "resultado longo"))

(defn- after-iteration ^AfterIterationContext []
  (AfterIterationContext. historia (int 4) [(tool-call)]))

;; --- ToolLoopInspector -------------------------------------------------------

(deftest inspector-recebe-contexto-como-mapa
  (let [visto (atom {})
        ^ToolLoopInspector i
        (ic/tool-loop-inspector
         {:before-llm-call   #(swap! visto assoc :before %)
          :after-llm-call    #(swap! visto assoc :after-llm %)
          :after-tool-result #(swap! visto assoc :after-result %)
          :after-iteration   #(swap! visto assoc :after-iter %)})]
    (.beforeLlmCall   i (before-llm))
    (.afterLlmCall    i (after-llm))
    (.afterToolResult i (after-tool-result))
    (.afterIteration  i (after-iteration))

    (testing "before-llm-call: história, iteração, tools e estimativa de tokens"
      (let [c (:before @visto)]
        (is (= 2 (count (:history c))))
        (is (= 1 (:iteration c)))
        (is (= ["eco"] (mapv #(-> % .getDefinition .getName) (:tools c))))
        (is (= 42 (:token-estimate c)))
        (is (instance? BeforeLlmCallContext (:raw c)))))

    (testing "after-llm-call: a resposta e o usage (nil quando o provider não manda)"
      (let [c (:after-llm @visto)]
        (is (= 2 (:iteration c)))
        (is (instance? UserMessage (:response c)))
        (is (nil? (:usage c)))))

    (testing "after-tool-result: tool call + resultado tipado e como string"
      (let [c (:after-result @visto)]
        (is (= "eco" (.getName ^ToolCall (:tool-call c))))
        (is (= "resultado longo" (:result-as-string c)))
        (is (some? (:result c)))))

    (testing "after-iteration: as tool calls daquela volta"
      (is (= 1 (count (:tool-calls-in-iteration (:after-iter @visto))))))))

(deftest inspector-com-hooks-parciais
  (testing "hook ausente é no-op (o default do framework)"
    (let [n (atom 0)
          ^ToolLoopInspector i (ic/tool-loop-inspector {:after-iteration (fn [_] (swap! n inc))})]
      (.beforeLlmCall i (before-llm))
      (.afterLlmCall  i (after-llm))
      (is (= 0 @n))
      (.afterIteration i (after-iteration))
      (is (= 1 @n)))))

(deftest inspector-valida-typo
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"disallowed key"
                        (ic/tool-loop-inspector {:after-tool-results (fn [_])}))))

(deftest recording-inspector-acumula
  (let [[i log] (ic/recording-inspector)]
    (.beforeLlmCall ^ToolLoopInspector i (before-llm))
    (.afterIteration ^ToolLoopInspector i (after-iteration))
    (is (= [:before-llm-call :after-iteration] (mapv :hook @log)))
    (is (= 1 (-> @log first :ctx :iteration)))))

;; --- ToolLoopTransformer -----------------------------------------------------

(deftest transformer-vazio-e-identidade
  (testing "sem hooks, cada método devolve o default documentado pelo framework"
    (let [^ToolLoopTransformer t (ic/tool-loop-transformer {})]
      (is (= historia (vec (.transformBeforeLlmCall t (before-llm)))))
      (is (= historia (vec (.transformAfterIteration t (after-iteration)))))
      (is (= "resultado longo" (.transformAfterToolResult t (after-tool-result))))
      (is (instance? UserMessage (.transformAfterLlmCall t (after-llm)))))))

(deftest transformer-substitui-pelo-retorno
  (let [^ToolLoopTransformer t
        (ic/tool-loop-transformer
         {:before-llm-call   (fn [c] (vec (take 1 (:history c))))
          :after-tool-result (fn [c] (subs (:result-as-string c) 0 9))
          :after-llm-call    (fn [_] (msg "substituída"))})]
    (testing "história truncada — vetor Clojure JÁ é java.util.List"
      (let [h (.transformBeforeLlmCall t (before-llm))]
        (is (instance? java.util.List h))
        (is (= 1 (count h)))))
    (testing "resultado de tool truncado (o caso mais comum e mais barato)"
      (is (= "resultado" (.transformAfterToolResult t (after-tool-result)))))
    (testing "resposta do LLM substituída"
      (is (= "substituída" (.getContent ^UserMessage (.transformAfterLlmCall t (after-llm))))))
    (testing "hook ausente continua caindo no default"
      (is (= historia (vec (.transformAfterIteration t (after-iteration))))))))

(deftest transformer-nil-cai-no-default
  (testing "devolver nil = manter o valor original (não apagar a história)"
    (let [^ToolLoopTransformer t (ic/tool-loop-transformer
                                  {:before-llm-call   (constantly nil)
                                   :after-tool-result (constantly nil)})]
      (is (= historia (vec (.transformBeforeLlmCall t (before-llm)))))
      (is (= "resultado longo" (.transformAfterToolResult t (after-tool-result)))))))

;; --- ToolCallInspector -------------------------------------------------------

(deftest tool-call-inspector-recebe-duracao
  (let [visto (atom {})
        ^ToolCallInspector i (ic/tool-call-inspector
                              {:before-tool-call #(swap! visto assoc :before %)
                               :after-tool-call  #(swap! visto assoc :after %)})]
    (.beforeToolCall i (BeforeToolCallContext. (tool-call)))
    (.afterToolCall  i (AfterToolCallContext. (tool-call) (Tool$Result/text "ok")
                                              "ok" (long 137)))
    (is (= "eco" (.getName ^ToolCall (:tool-call (:before @visto)))))
    (let [c (:after @visto)]
      (is (= 137 (:duration-ms c)))
      (is (= "ok" (:result-as-string c)))
      (testing "este contexto NÃO tem história nem iteração (é o leve, de streaming)"
        (is (not (contains? c :history)))
        (is (not (contains? c :iteration)))))))

(deftest tool-call-inspector-valida-typo
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"disallowed key"
                        (ic/tool-call-inspector {:after-toolcall (fn [_])}))))

;; --- coerções usadas pelo schema/ask ----------------------------------------

(deftest coercoes-aceitam-mapa-ou-instancia
  (let [pronto (ic/tool-loop-inspector {})]
    (is (identical? pronto (ic/->tool-loop-inspector pronto)))
    (is (instance? ToolLoopInspector (ic/->tool-loop-inspector {:after-iteration (fn [_])})))
    (is (instance? ToolLoopTransformer (ic/->tool-loop-transformer {})))
    (is (instance? ToolCallInspector (ic/->tool-call-inspector {})))))

;; --- o caminho de verdade: schema/ask -> PromptRunner ------------------------

(defprotocol FakeOc
  "O `ask` chama `(.ai oc)` por REFLEXÃO — basta um objeto com método `ai()`,
   e o interface gerado por defprotocol dá exatamente isso."
  (ai [this]))

(deftest ask-instala-os-interceptors-no-prompt-runner
  ;; Este é o teste que protege o cond-> do schema/ask: os três `with*` são
  ;; VARARGS no Kotlin (= um array no JVM) e as chamadas ali são reflexivas —
  ;; um nome de método errado só apareceria com um LLM vivo.
  (let [visto  (atom {})
        runner (reify PromptRunner
                 (withToolLoopInspectors   [this xs] (swap! visto assoc :insp (vec xs)) this)
                 (withToolLoopTransformers [this xs] (swap! visto assoc :tr   (vec xs)) this)
                 (withToolCallInspectors   [this xs] (swap! visto assoc :tc   (vec xs)) this)
                 (^String generateText [_ ^String p] (swap! visto assoc :prompt p) "resposta-fake"))
        oc     (reify FakeOc (ai [_] runner))
        r      (schema/ask {:oc oc}
                           {:prompt "oi"
                            :tool-loop-inspectors   [{:after-llm-call (fn [_])}
                                                     (ic/tool-loop-inspector {})]
                            :tool-loop-transformers [{:after-tool-result (fn [_] "x")}]
                            :tool-call-inspectors   [{} {}]})]
    (is (= "resposta-fake" r))
    (is (= "oi" (:prompt @visto)))
    (testing "mapas e instâncias prontas chegam como o TIPO que o framework espera"
      (is (= 2 (count (:insp @visto))))
      (is (every? #(instance? ToolLoopInspector %) (:insp @visto)))
      (is (= 1 (count (:tr @visto))))
      (is (every? #(instance? ToolLoopTransformer %) (:tr @visto)))
      (is (= 2 (count (:tc @visto))))
      (is (every? #(instance? ToolCallInspector %) (:tc @visto))))))

(deftest ask-sem-interceptors-nao-toca-no-runner
  (let [tocou  (atom false)
        runner (reify PromptRunner
                 (withToolLoopInspectors [this _] (reset! tocou true) this)
                 (^String generateText [_ ^String _p] "ok"))
        oc     (reify FakeOc (ai [_] runner))]
    (is (= "ok" (schema/ask {:oc oc} {:prompt "oi"})))
    (is (false? @tocou))))
