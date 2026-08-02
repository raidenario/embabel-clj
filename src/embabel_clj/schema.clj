(ns embabel-clj.schema
  "malli na fronteira com o LLM: saída estruturada em EDN, validada e coagida
   pelo MESMO schema que gera as instruções do prompt.

   É o espelho data-driven do createObject<T>() do Embabel: lá o contrato é
   um data class + Jackson; aqui o contrato é um schema malli — um VALOR,
   serializável, redefinível no REPL, que também gera JSON Schema.

   O caminho feliz:

     (def Insights
       [:map
        [:resumo {:description \"2-3 frases\"} :string]
        [:confianca {:optional true :description \"0.0 a 1.0\"}
         [:double {:min 0.0 :max 1.0}]]])

     ;; dentro de uma action {:llm? true}:
     (schema/create-edn! ctx {:schema  Insights
                              :llm     \"openai/gpt-4o-mini\"
                              :prompt  (schema/edn-prompt Insights
                                        {:preamble \"Você é um naturalista.\"})})

   `create-edn!` re-pergunta ao modelo com os ERROS HUMANIZADOS do malli
   quando a resposta não valida (o loop de auto-cura do estilo instructor)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [embabel-clj.guardrails :as gr]
            [embabel-clj.interceptors :as ic]
            [malli.core :as m]
            [malli.error :as me]
            [malli.json-schema :as mjs]
            [malli.transform :as mt])
  (:import [com.embabel.agent.api.tool.callback
            ToolCallInspector ToolLoopInspector ToolLoopTransformer]
           [com.embabel.agent.api.validation.guardrails GuardRail]
           [com.embabel.common.ai.model Thinking]
           [com.embabel.common.ai.prompt PromptContributor]
           [com.embabel.agent.api.common.streaming StreamingPromptRunner]
           [kotlin.jvm.functions Function0]))

(def ^:dynamic *max-response-chars*
  "Cap de tamanho da resposta parseada (EDN fundo demais estoura a pilha do
   reader — e StackOverflowError é Error, não Exception)."
  20000)

;; ---------------------------------------------------------------------------
;; Prompt a partir do schema
;; ---------------------------------------------------------------------------

(defn prompt-fields
  "Lista de campos (bullet list) derivada de um schema [:map ...], usando as
   `:description` das propriedades — a MESMA fonte de verdade que valida a
   resposta. Campos aninhados aparecem pelo nome; descreva-os na propriedade."
  ^String [schema]
  (let [sch (m/schema schema)]
    (when-not (= :map (m/type sch))
      (throw (ex-info "prompt-fields espera um schema [:map ...]"
                      {:type (m/type sch)})))
    (->> (m/children sch)
         (map (fn [[k props _child]]
                (str "  - " (name k)
                     (when (:optional props) " (opcional)")
                     (when-let [d (:description props)] (str ": " d)))))
         (str/join "\n"))))

(defn edn-prompt
  "Prompt completo pedindo UM mapa EDN no formato do schema.
   `:preamble` abre o prompt (persona/tarefa); `:extra` fecha (regras suas)."
  ^String [schema {:keys [preamble extra]}]
  (str (when preamble (str preamble " "))
       "IMPORTANTE: responda SOMENTE com UM mapa EDN do Clojure (chaves como "
       "keywords, ex.: {:campo \"valor\"}), sem markdown, sem ```, sem texto "
       "fora do mapa. Campos esperados:\n"
       (prompt-fields schema)
       "\nStrings entre aspas duplas; listas como [\"a\" \"b\"]; números sem aspas."
       (when extra (str "\n" extra))))

;; ---------------------------------------------------------------------------
;; Parse + validação da resposta
;; ---------------------------------------------------------------------------

(defn clean-fences
  "Remove cercas ```edn/```clojure que os modelos adoram pôr mesmo proibidos."
  ^String [s]
  (-> (str s)
      str/trim
      (str/replace #"(?s)^```(?:edn|clojure)?\s*" "")
      (str/replace #"(?s)\s*```$" "")))

(defn- ->kw
  "Normaliza chaves: símbolo (LLM esqueceu o ':') vira keyword PRESERVANDO o
   namespace; string vira keyword; keyword passa."
  [x]
  (cond
    (keyword? x) x
    (symbol? x)  (keyword (namespace x) (name x))
    (string? x)  (keyword x)
    :else        x))

(defn- keywordize-deep [v]
  (walk/postwalk
   (fn [x]
     (if (map? x)
       (into {} (map (fn [[k v*]] [(->kw k) v*])) x)
       x))
   v))

(def ^:private decoder-for
  "Decoder malli memoizado por schema (schemas são valores: memoize funciona)."
  (memoize
   (fn [schema]
     (m/decoder schema (mt/transformer mt/string-transformer
                                       mt/json-transformer)))))

(defn parse-edn
  "Resposta crua do LLM -> mapa EDN normalizado (ou nil se não parseia).
   Defensivo: limpa cercas, cap de tamanho, `edn/read-string` (NUNCA `read` —
   EDN não executa), catch de Throwable (reader fundo = StackOverflowError)."
  [s]
  (let [s* (str s)]
    (when (<= (count s*) *max-response-chars*)
      (try
        (let [v (edn/read-string (clean-fences s*))]
          (when (map? v) (keywordize-deep v)))
        (catch Throwable _ nil)))))

(defn parse
  "parse-edn + coerção (string/json transformer) + validação pelo schema.
   Devolve {:value <mapa válido>} ou {:errors <humanizado>, :raw s}."
  [schema s]
  (if-let [parsed (parse-edn s)]
    (let [decoded ((decoder-for schema) parsed)]
      (if (m/validate schema decoded)
        {:value decoded}
        {:errors (me/humanize (m/explain schema decoded)) :raw (str s)}))
    {:errors ["resposta não é um mapa EDN parseável"] :raw (str s)}))

(defn json-schema
  "JSON Schema derivado do schema malli (para tools/MCP/documentação)."
  [schema]
  (mjs/transform schema))

;; ---------------------------------------------------------------------------
;; Chamadas ao LLM
;; ---------------------------------------------------------------------------

(defn- ->oc [ctx]
  (or (and (map? ctx) (:oc ctx)) ctx))

(defn- name-str ^String [k]
  (if (keyword? k) (name k) (str k)))

(def ^:private llm-by-name
  ;; O método do companion que cria LlmOptions por slug MUDOU de nome:
  ;;   0.4.0/0.5.0 → fromModel(String)   1.0.0 → withModel(String)
  ;; Resolvido UMA vez por reflexão — a lib funciona nas duas famílias
  ;; (mesmo padrão do qos-ctor em interop.clj).
  (delay
    (let [c (class com.embabel.common.ai.model.LlmOptions/Companion)]
      (or (try (.getMethod c "withModel" (into-array Class [String]))
               (catch NoSuchMethodException _ nil))
          (.getMethod c "fromModel" (into-array Class [String]))))))

(defn ->prompt-contributor
  "PromptContributor a partir de dado. Aceita:
     - string            -> contribuição fixa
     - fn de ZERO args   -> contribuição recalculada a cada chamada
     - PromptContributor -> passa direto

   Não precisa de `reify` da interface: o framework já expõe as fábricas
   `PromptContributor/fixed` e `/dynamic`, e a segunda recebe um
   `Function0<String>` do Kotlin — que em Clojure é um reify de um método só.
   (Os outros dois membros, `role` e `promptContributionLocation`, são DEFAULT
   na interface `PromptElement`, então não há o que implementar.)"
  ^PromptContributor [x]
  (cond
    (instance? PromptContributor x) x
    (string? x) (PromptContributor/fixed x)
    (ifn? x)    (PromptContributor/dynamic (reify Function0 (invoke [_] (str (x)))))
    :else (throw (ex-info "embabel-clj: prompt-contributor deve ser string, fn de 0 args ou PromptContributor"
                          {:value x :type (type x)}))))

(defn- ->thinking
  "Thinking a partir de dado: `:extraction`, um inteiro (orçamento de tokens),
   ou um `Thinking` pronto."
  ^Thinking [x]
  (cond
    (instance? Thinking x) x
    (= :extraction x)      (Thinking/withExtraction)
    (integer? x)           (Thinking/withTokenBudget (int x))
    :else (throw (ex-info "embabel-clj: :thinking deve ser :extraction, um inteiro ou um Thinking"
                          {:value x}))))

(defn- llm-options
  "LlmOptions do Embabel a partir de :llm/:max-tokens/:temperature/:timeout-s/
   :thinking. Só é construído quando algum ajuste além do slug é pedido."
  [{:keys [llm max-tokens temperature timeout-s thinking]}]
  (let [companion com.embabel.common.ai.model.LlmOptions/Companion
        base      (if llm
                    (.invoke ^java.lang.reflect.Method @llm-by-name companion
                             (object-array [llm]))
                    (.withDefaultLlm companion))]
    (cond-> base
      max-tokens  (.withMaxTokens (int max-tokens))
      temperature (.withTemperature (Double/valueOf (double temperature)))
      timeout-s   (.withTimeout (java.time.Duration/ofSeconds (long timeout-s)))
      thinking    (.withThinking (->thinking thinking)))))

(defn ask
  "Texto livre do LLM, a partir do ctx de uma action `{:llm? true}`.

   Opções: :llm (slug registrado por nome), :image (AgentImage),
   :max-tokens (IMPORTANTE em providers que pré-autorizam o teto contra o
   saldo, como o OpenRouter — sem ele vale o output máximo do modelo),
   :temperature, :timeout-s (timeout por chamada, em segundos — evita a
   tempestade de retries quando um modelo trava/enfileira), :tools (seq de
   embabel-clj.tools/tool — o modelo pode chamá-las), :tool-groups (roles de
   ToolGroups da plataforma, ex. [\"web\"] — tools de MCP chegam por aqui),
   :tool-loop-inspectors / :tool-loop-transformers / :tool-call-inspectors
   (mapas de fns — ver embabel-clj.interceptors), :guardrails (guardas de
   entrada/saída — ver embabel-clj.guardrails; `:critical` ABORTA a chamada
   com GuardRailViolationException)."
  ^String [ctx {:keys [llm image prompt max-tokens temperature timeout-s tools
                       tool-groups tool-loop-inspectors tool-loop-transformers
                       tool-call-inspectors guardrails prompt-contributors] :as opts}]
  (let [oc (->oc ctx)
        _  (when (nil? oc)
             (throw (ex-info "ask: ctx sem :oc — a action precisa de {:llm? true}"
                             {:ctx ctx})))
        tune? (or max-tokens temperature timeout-s (:thinking opts))
        runner (cond-> (.ai oc)
                 tune?                    (.withLlm (llm-options opts))
                 (and llm (not tune?))    (.withLlm ^String llm)
                 image (.withImage image)
                 (seq tool-groups) (.withToolGroups
                                    ^java.util.Set (set (map name-str tool-groups)))
                 (seq tools) (.withTools ^java.util.List (vec tools))
                 ;; os três with* são VARARGS no Kotlin => um array no JVM
                 (seq tool-loop-inspectors)
                 (.withToolLoopInspectors
                  (into-array ToolLoopInspector
                              (map ic/->tool-loop-inspector tool-loop-inspectors)))
                 (seq tool-loop-transformers)
                 (.withToolLoopTransformers
                  (into-array ToolLoopTransformer
                              (map ic/->tool-loop-transformer tool-loop-transformers)))
                 (seq tool-call-inspectors)
                 (.withToolCallInspectors
                  (into-array ToolCallInspector
                              (map ic/->tool-call-inspector tool-call-inspectors)))
                 (seq prompt-contributors)
                 (.withPromptContributors
                  ^java.util.List (mapv ->prompt-contributor prompt-contributors))
                 (seq guardrails)
                 (.withGuardRails
                  (into-array GuardRail (map gr/->guardrail guardrails))))]
    (.generateText runner prompt)))

(defn stream
  "Texto do LLM em STREAMING: devolve um reactor.core.publisher.Flux<String>.

   Achado que motiva esta fn: o `ask` monta o runner a partir de `(.ai oc)`, e
   `OperationContextAi` NÃO é um `StreamingPromptRunner`. Quem é streaming é
   `(.promptRunner oc)`, que devolve um `DelegatingStreamingPromptRunner` —
   medido: promptRunner-streaming? true, ai-streaming? false. Ou seja: o
   streaming nunca esteve ausente do framework nem exigia wrapper novo; a lib
   é que estava no seam errado.

   Opções: :prompt (obrigatório), :llm, :max-tokens, :temperature, :timeout-s,
   :thinking, :prompt-contributors.

     (->> (schema/stream ctx {:prompt \"conte uma história\"})
          schema/stream-seq
          (run! print))"
  [ctx {:keys [llm prompt prompt-contributors] :as opts}]
  (let [oc (->oc ctx)
        ;; checa o TIPO, não só nil: `->oc` devolve o próprio ctx quando não há
        ;; `:oc`, então um `(nil? oc)` deixava passar um mapa e a falha só
        ;; aparecia como "No matching field found: promptRunner".
        _  (when-not (instance? com.embabel.agent.api.common.OperationContext oc)
             (throw (ex-info "stream: ctx sem :oc — a action precisa de {:llm? true}"
                             {:ctx ctx :resolvido (type oc)})))
        tune? (or (:max-tokens opts) (:temperature opts) (:timeout-s opts) (:thinking opts))
        runner (cond-> (.promptRunner oc)
                 tune?                 (.withLlm (llm-options opts))
                 (and llm (not tune?)) (.withLlm ^String llm)
                 (seq prompt-contributors)
                 (.withPromptContributors
                  ^java.util.List (mapv ->prompt-contributor prompt-contributors)))]
    (when-not (instance? StreamingPromptRunner runner)
      (throw (ex-info (str "stream: o runner deixou de ser StreamingPromptRunner após "
                           "a configuração — reporte a combinação de opções")
                      {:class (.getName (class runner)) :opts (keys opts)})))
    ;; O `.streaming()` do framework lança UnsupportedOperationException quando
    ;; o modelo por baixo não faz streaming (e manda checar `supportsStreaming`
    ;; antes). Checar aqui troca um erro de runtime do Kotlin por um erro de
    ;; dado com o nome do modelo.
    (when-not (.supportsStreaming ^StreamingPromptRunner runner)
      (throw (ex-info "stream: o modelo configurado não suporta streaming"
                      {:llm llm :runner (.getName (class runner))})))
    (-> ^StreamingPromptRunner runner
        .streaming
        (.withPrompt prompt)
        .generateStream)))

(defn stream-seq
  "Flux -> seq preguiçosa e BLOQUEANTE de pedaços. Para não obrigar quem usa a
   lib a importar Reactor só para consumir o stream."
  [^reactor.core.publisher.Flux flux]
  (iterator-seq (.iterator (.toIterable flux))))

(defn create-edn!
  "Pede ao LLM um mapa EDN no formato do `:schema`, valida com malli e
   RE-PERGUNTA com os erros humanizados quando a resposta não valida
   (`:retries` re-tentativas, default 1). Devolve o mapa validado/coagido ou
   lança ex-info {:raw ... :errors ...}.

   Opções: :schema (obrig.), :prompt (obrig. — use edn-prompt), :llm, :image,
   :max-tokens, :temperature, :retries, :ask-fn (fn [prompt] -> raw; injete
   para testar sem LLM ou para usar outro transporte)."
  [ctx {:keys [schema prompt retries ask-fn] :as opts
        :or   {retries 1}}]
  (let [ask* (or ask-fn
                 (fn [p] (ask ctx (assoc (dissoc opts :schema :retries :ask-fn)
                                         :prompt p))))]
    (loop [attempt 0
           p       prompt]
      (let [raw    (ask* p)
            {:keys [value errors]} (parse schema raw)]
        (cond
          value
          value

          (< attempt retries)
          (recur (inc attempt)
                 (str prompt
                      "\n\nATENÇÃO: sua resposta anterior foi inválida. Erros: "
                      (pr-str errors)
                      "\nResponda NOVAMENTE, corrigindo os erros, SOMENTE com o mapa EDN."))

          :else
          (throw (ex-info "create-edn!: resposta do LLM não validou contra o schema"
                          {:type ::invalid-llm-output
                           :raw raw :errors errors :attempts (inc attempt)})))))))
