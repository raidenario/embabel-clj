(ns embabel-clj.guardrails
  "Guardrails como FNS — e o veredito na forma que o malli já devolve.

   O Embabel tem duas guardas em volta do LLM, ambas subinterfaces de
   `ContentValidator<String>`:

     UserInputGuardRail        valida o que VAI para o modelo (antes da chamada)
     AssistantMessageGuardRail valida o que VOLTA dele (inclusive o thinking)

   Em Kotlin cada uma é uma classe com `name`, `description` e `validate`. Aqui:

     (require '[embabel-clj.guardrails :as gr])

     (def sem-segredo
       (gr/user-input
        {:name        \"sem-segredo\"
         :description \"barra chave de API vazando no prompt\"
         :fn (fn [{:keys [content]}]
               (when (re-find #\"sk-[A-Za-z0-9]{20,}\" content)
                 \"o prompt contém o que parece ser uma chave de API\"))}))

     (schema/ask ctx {:prompt \"...\" :guardrails [sem-segredo]})

   E porque um schema malli JÁ é um validador que produz mensagens legíveis,
   ele serve de guarda direto — sem escrever `:fn` nenhuma:

     (gr/assistant-message {:name \"tamanho\" :schema [:string {:max 4000}]})

   ## O veredito (o que a sua fn devolve)

     nil, true, [], \"\"        -> passou
     \"mensagem\"               -> UM erro com essa mensagem
     [\"m1\" \"m2\"]              -> vários erros
     {:message \"...\" :severity :warning :code \"c\"}   -> um erro detalhado
     {:valid? false :errors [...]}                    -> controle total
     um ValidationResult                              -> passa direto

   `:severity` default do mapa inteiro = **`:critical`** — ou seja, guarda que
   dispara BLOQUEIA. Os quatro níveis são `:info :warning :error :critical`.

   ## Pegadinha que muda o comportamento (verificada no fonte)

   Quem decide o que acontece é `llmOperationGuardRails.kt/handleValidationResult`,
   e ele olha a **maior severidade entre os ERROS** — o booleano `isValid` do
   `ValidationResult` é ignorado nesse caminho. Consequências reais:

     - só `:critical` LANÇA (`GuardRailViolationException`, abortando a chamada);
       `:error`/`:warning`/`:info` apenas logam e a execução segue;
     - devolver \"inválido, sem erros\" (`{:valid? false :errors []}`) não faz
       NADA — nem log. Por isso esta ns nunca produz um resultado inválido sem
       ao menos um erro: `{:valid? false}` sozinho vira um erro `:critical`.

   Verificado por javap nos jars 0.4.0, 0.5.0-SNAPSHOT e 1.0.0: as duas
   interfaces, `PromptRunner.withGuardRails` e as classes
   `com.embabel.common.core.validation.*` (que vêm dentro do próprio
   embabel-agent-api) existem nas TRÊS versões."
  (:require [embabel-clj.blackboard :as bb]
            [embabel-clj.specs :as specs]
            [malli.core :as m]
            [malli.error :as me])
  (:import [com.embabel.agent.api.validation.guardrails
            AssistantMessageGuardRail GuardRail UserInputGuardRail]
           [com.embabel.agent.core Blackboard]
           [com.embabel.common.core.thinking ThinkingResponse]
           [com.embabel.common.core.validation
            ValidationError ValidationResult ValidationSeverity]))

;; ---------------------------------------------------------------------------
;; Veredito Clojure -> ValidationResult
;; ---------------------------------------------------------------------------

(def ^:private severities
  {:info     ValidationSeverity/INFO
   :warning  ValidationSeverity/WARNING
   :error    ValidationSeverity/ERROR
   :critical ValidationSeverity/CRITICAL})

(def valid
  "O ValidationResult \"passou\" do framework."
  (.getVALID ValidationResult/Companion))

(defn- ->severity ^ValidationSeverity [k default]
  (or (severities k)
      (severities default)
      ValidationSeverity/CRITICAL))

(defn- ->error ^ValidationError [x {:keys [name severity]}]
  (if (instance? ValidationError x)
    x
    (let [m (if (map? x) x {:message (str x)})]
      (ValidationError. (str (:code m name))
                        (str (:message m))
                        (->severity (:severity m) severity)))))

(defn- errors-of
  "Normaliza o veredito numa seq de coisas-que-viram-erro (ou nil = passou)."
  [v]
  (cond
    (nil? v)     nil
    (true? v)    nil
    (false? v)   ["guardrail reprovou o conteúdo"]
    (string? v)  (when (seq v) [v])
    (map? v)     (if (or (contains? v :errors) (contains? v :valid?))
                   (let [es (seq (:errors v))]
                     ;; "inválido sem erros" não faria NADA no framework
                     (cond es                     es
                           (false? (:valid? v))   ["guardrail reprovou o conteúdo"]
                           :else                  nil))
                   [v])
    (coll? v)    (seq v)
    :else        [(str v)]))

(defn ->validation-result
  "Veredito Clojure -> ValidationResult do Embabel. `opts` traz `:name` (vira o
   `code` default do erro) e `:severity` (a severidade default)."
  ^ValidationResult [v opts]
  (if (instance? ValidationResult v)
    v
    (if-let [es (errors-of v)]
      (ValidationResult. false (mapv #(->error % opts) es))
      valid)))

(defn- humanized->messages
  "Saída do `me/humanize` -> mensagens legíveis. Para schema não-mapa o malli
   devolve um vetor de strings; para mapa, um mapa por campo."
  [h]
  (cond
    (string? h) [h]
    (and (sequential? h) (every? string? h)) (vec h)
    :else [(pr-str h)]))

(defn- schema-check
  "Fecha um schema malli como veredito: nil quando valida, mensagens quando não."
  [schema]
  (fn [{:keys [content]}]
    (when-let [expl (m/explain schema content)]
      (humanized->messages (me/humanize expl)))))

;; ---------------------------------------------------------------------------
;; As duas guardas
;; ---------------------------------------------------------------------------

(defn- check-fn
  "A fn de veredito efetiva: `:fn` explícita, ou a derivada do `:schema`
   (se vierem as duas, ambas rodam e os erros se somam)."
  [{:keys [schema] f :fn}]
  (let [sf (when schema (schema-check schema))]
    (cond
      (and f sf) (fn [ctx] (concat (errors-of (f ctx)) (errors-of (sf ctx))))
      f          f
      :else      sf)))

(defn user-input
  "UserInputGuardRail: valida o que VAI para o modelo.

   `:fn` recebe `{:content <String> :bb <Blackboard> :raw <o objeto original>}`.
   Os overloads do framework (lista de UserMessages, conteúdo multimodal) caem
   nos defaults dele, que combinam/extraem o texto e chamam esta mesma fn."
  ^UserInputGuardRail [m]
  (specs/validate! specs/GuardRailDef m "user-input guardrail")
  (let [{:keys [name description severity] :or {severity :critical}} m
        nm    (bb/key->str name)
        check (check-fn m)
        opts  {:name nm :severity severity}]
    (reify UserInputGuardRail
      (getName [_] nm)
      (getDescription [_] (or description nm))
      (^ValidationResult validate [_ ^Object content ^Blackboard bb]
        (-> (check {:content (str content) :bb bb :raw content})
            (->validation-result opts))))))

(defn assistant-message
  "AssistantMessageGuardRail: valida o que VOLTA do modelo.

   `:fn` recebe `{:content <String> :bb <Blackboard> :raw <o objeto original>}`.
   Respostas com THINKING passam por `:thinking-fn`, que recebe
   `{:thinking <String> :result <obj> :blocks [...] :bb ... :raw <ThinkingResponse>}`;
   sem ela, o default é rodar a MESMA `:fn` sobre o texto do thinking — o
   raciocínio interno vaza tão bem quanto a resposta final."
  ^AssistantMessageGuardRail [m]
  (specs/validate! specs/GuardRailDef m "assistant-message guardrail")
  (let [{:keys [name description severity thinking-fn] :or {severity :critical}} m
        nm    (bb/key->str name)
        check (check-fn m)
        opts  {:name nm :severity severity}]
    (reify AssistantMessageGuardRail
      (getName [_] nm)
      (getDescription [_] (or description nm))

      (^ValidationResult validate [_ ^Object content ^Blackboard bb]
        (-> (check {:content (str content) :bb bb :raw content})
            (->validation-result opts)))

      (^ValidationResult validate [_ ^ThinkingResponse response ^Blackboard bb]
        (let [thinking (.getThinkingContent response)
              ctx      {:thinking thinking
                        :result   (.getResult response)
                        :blocks   (vec (.getThinkingBlocks response))
                        :content  (str thinking)
                        :bb       bb
                        :raw      response}]
          (-> (cond
                thinking-fn        (thinking-fn ctx)
                (seq (str thinking)) (check ctx)
                :else              nil)
              (->validation-result opts)))))))

(defn guardrail
  "Guarda a partir de um mapa com `:on :user-input` ou `:on :assistant-message`
   — a forma 100% dado, para quem monta a lista de guardas como EDN."
  ^GuardRail [{:keys [on] :as m}]
  (case on
    :user-input        (user-input (dissoc m :on))
    :assistant-message (assistant-message (dissoc m :on))
    (throw (ex-info (str "guardrail: :on precisa ser :user-input ou "
                         ":assistant-message (veio " (pr-str on) ")")
                    {:type ::invalid-guardrail :value m}))))

(defn ->guardrail
  "Coerção usada pelo `:guardrails` do schema/ask."
  ^GuardRail [x]
  (if (instance? GuardRail x) x (guardrail x)))
