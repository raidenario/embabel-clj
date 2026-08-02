(ns embabel-clj.interceptors
  "Os interceptors do tool loop como MAPAS DE FNS.

   Quando o modelo tem tools, o Embabel roda um laço: manda a história ao LLM,
   recebe pedidos de tool, executa, junta os resultados, repete. O framework
   abre três pontos de extensão nesse laço — e os três são interfaces de
   métodos pequenos, que em Kotlin viram classe e aqui viram um mapa:

     ToolLoopInspector    observa (log, métrica, debug) — não altera nada
     ToolLoopTransformer  altera o que passa (compressão, janela, resumo)
     ToolCallInspector    observa cada tool call (vale também em streaming)

   Uso — os três entram por `schema/ask` e `schema/create-edn!`, que os
   repassam ao PromptRunner:

     (schema/ask ctx
       {:prompt \"...\"
        :tools  [minha-tool]
        :tool-loop-inspectors  [{:after-llm-call (fn [c] (log/info (:usage c)))}]
        :tool-loop-transformers [{:after-tool-result
                                  (fn [c] (subs (:result-as-string c) 0 500))}]
        :tool-call-inspectors  [{:after-tool-call
                                 (fn [c] (metrics! (:duration-ms c)))}]})

   Cada fn recebe o contexto do framework projetado como MAPA (as propriedades
   por reflexão + `:raw` com o objeto original). As chaves seguem os campos do
   Embabel em kebab-case:

     :before-llm-call     {:history :iteration :tools :token-estimate :raw}
     :after-llm-call      {:history :iteration :response :usage :raw}
     :after-tool-result   {:history :iteration :tool-call :result
                           :result-as-string :raw}
     :after-iteration     {:history :iteration :tool-calls-in-iteration :raw}
     :before-tool-call    {:tool-call :raw}
     :after-tool-call     {:tool-call :result :result-as-string :duration-ms :raw}

   INSPECTORS ignoram o retorno. TRANSFORMERS usam o retorno — e cada hook tem
   um tipo de retorno próprio (ver `tool-loop-transformer`). Hook ausente, ou
   que devolva nil, cai no default do framework (identidade).

   Verificado por javap nos jars 0.4.0, 0.5.0-SNAPSHOT e 1.0.0 GA: as três
   interfaces e os três `PromptRunner.withTool*` existem nas TRÊS versões —
   isto não é superfície nova do 1.0."
  (:require [embabel-clj.interop :as interop]
            [embabel-clj.specs :as specs])
  (:import [com.embabel.agent.api.tool.callback
            ToolCallInspector ToolLoopInspector ToolLoopTransformer]))

(defn ctx->map
  "Contexto de callback do Embabel -> mapa Clojure (props + :raw)."
  [c]
  (assoc (interop/props c #{:class}) :raw c))

(defn tool-loop-inspector
  "ToolLoopInspector (observador read-only do laço de tools) a partir de
   `{:before-llm-call f :after-llm-call f :after-tool-result f
     :after-iteration f}`. Todas as chaves são opcionais; o retorno das fns
   é ignorado."
  ^ToolLoopInspector
  [{:keys [before-llm-call after-llm-call after-tool-result after-iteration]
    :as m}]
  (specs/validate! specs/ToolLoopInspectorDef m "tool-loop-inspector")
  (reify ToolLoopInspector
    (beforeLlmCall   [_ c] (when before-llm-call   (before-llm-call   (ctx->map c))))
    (afterLlmCall    [_ c] (when after-llm-call    (after-llm-call    (ctx->map c))))
    (afterToolResult [_ c] (when after-tool-result (after-tool-result (ctx->map c))))
    (afterIteration  [_ c] (when after-iteration   (after-iteration   (ctx->map c))))))

(defn tool-loop-transformer
  "ToolLoopTransformer (altera o que atravessa o laço) a partir de um mapa de
   fns. O RETORNO de cada uma substitui o valor original:

     :before-llm-call    -> seq de com.embabel.chat.Message (default :history)
     :after-llm-call     -> um com.embabel.chat.Message      (default :response)
     :after-tool-result  -> String                           (default
                                                              :result-as-string)
     :after-iteration    -> seq de Message                   (default :history)

   Devolver nil = manter o default. Um vetor Clojure já É um java.util.List,
   então `(filterv ... (:history c))` serve direto no retorno.

   Cuidado: aqui você está reescrevendo o que o modelo vê. Truncar resultado de
   tool é barato e seguro; mexer na história é onde se perde contexto sem
   perceber."
  ^ToolLoopTransformer
  [{:keys [before-llm-call after-llm-call after-tool-result after-iteration]
    :as m}]
  (specs/validate! specs/ToolLoopTransformerDef m "tool-loop-transformer")
  (reify ToolLoopTransformer
    (transformBeforeLlmCall [_ c]
      (or (when before-llm-call (before-llm-call (ctx->map c)))
          (.getHistory c)))
    (transformAfterLlmCall [_ c]
      (or (when after-llm-call (after-llm-call (ctx->map c)))
          (.getResponse c)))
    (transformAfterToolResult [_ c]
      (if-let [v (when after-tool-result (after-tool-result (ctx->map c)))]
        (str v)
        (.getResultAsString c)))
    (transformAfterIteration [_ c]
      (or (when after-iteration (after-iteration (ctx->map c)))
          (.getHistory c)))))

(defn tool-call-inspector
  "ToolCallInspector (observador de cada tool call, leve — sem história nem
   iteração, e por isso o único que também vale em modo streaming) a partir de
   `{:before-tool-call f :after-tool-call f}`."
  ^ToolCallInspector
  [{:keys [before-tool-call after-tool-call] :as m}]
  (specs/validate! specs/ToolCallInspectorDef m "tool-call-inspector")
  (reify ToolCallInspector
    (beforeToolCall [_ c] (when before-tool-call (before-tool-call (ctx->map c))))
    (afterToolCall  [_ c] (when after-tool-call  (after-tool-call  (ctx->map c))))))

;; --- coerções usadas por schema/ask ----------------------------------------

(defn ->tool-loop-inspector ^ToolLoopInspector [x]
  (if (instance? ToolLoopInspector x) x (tool-loop-inspector x)))

(defn ->tool-loop-transformer ^ToolLoopTransformer [x]
  (if (instance? ToolLoopTransformer x) x (tool-loop-transformer x)))

(defn ->tool-call-inspector ^ToolCallInspector [x]
  (if (instance? ToolCallInspector x) x (tool-call-inspector x)))

(defn recording-inspector
  "Devolve `[inspector log]` — um ToolLoopInspector que acumula
   `{:hook :after-llm-call :ctx {...}}` num atom. O `events/recording-listener`
   do laço de tools: útil em teste e para ver o laço sem instrumentar nada."
  []
  (let [log (atom [])
        rec (fn [hook] (fn [c] (swap! log conj {:hook hook :ctx c})))]
    [(tool-loop-inspector {:before-llm-call   (rec :before-llm-call)
                           :after-llm-call    (rec :after-llm-call)
                           :after-tool-result (rec :after-tool-result)
                           :after-iteration   (rec :after-iteration)})
     log]))
