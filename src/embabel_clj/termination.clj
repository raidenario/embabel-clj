(ns embabel-clj.termination
  "Quando o processo PARA — as duas interfaces como fns.

     EarlyTerminationPolicy   avaliada pela plataforma a cada tick: \"chega\"
     StuckHandler             chamada quando o planner não acha caminho para
                              goal nenhum: \"consegue destravar?\"

   ## O budget JÁ É uma EarlyTerminationPolicy

   O reenquadramento que importa: o `:budget {:cost :actions :tokens}` que a lib
   expõe desde sempre **não é uma alternativa** às policies — ele É três delas,
   compostas por `firstOf` (`ProcessOptions.kt`, `Budget.earlyTerminationPolicy()`):

     firstOf(maxActions(actions), maxTokens(tokens), hardBudgetLimit(cost))

   Ou seja, três das quatro embutidas já estavam expostas. O que faltava era
   poder SOMAR uma sua — e o seam é um método só,
   `ProcessOptions.withAdditionalEarlyTerminationPolicy`, que compõe com o que
   já existe em vez de substituir. É o `:early-termination` do run options:

     (ec/run! platform ag
       {:options {:budget {:cost 0.10}
                  :early-termination [(fn [proc] (when (fim? proc) \"chega\"))]}})

   A quarta embutida é `on-stuck` — a que o próprio framework usa com planner
   de utilidade.

   ## O StuckHandler resolve por EFEITO COLATERAL

   Ele recebe o AgentProcess e devolve só \"vale a pena replanejar?\". A
   resolução de verdade é escrever no blackboard (setar a condição que faltava,
   preencher o slot) — e como o `AgentProcess` É um `Blackboard`, as fns de
   `embabel-clj.blackboard` funcionam direto nele:

     {:stuck-handler (fn [proc]
                       (bb/set-condition! proc :fallback/ok? true)
                       \"liberei o caminho alternativo\")}   ; => REPLAN

   Por isso as fns daqui recebem o **AgentProcess cru**, não um mapa projetado:
   ele é um handle vivo que se consulta e se escreve, não um valor.

   Verificado por javap em 0.4.0, 0.5.0-SNAPSHOT e 1.0.0: as duas interfaces,
   as quatro embutidas, o `firstOf` e o
   `withAdditionalEarlyTerminationPolicy` existem nas TRÊS versões."
  (:require [embabel-clj.specs :as specs])
  (:import [com.embabel.agent.api.common StuckHandler StuckHandlerResult
            StuckHandlingResultCode]
           [com.embabel.agent.api.termination Termination]
           [com.embabel.agent.api.tool TerminateActionException
            TerminateAgentException TerminationException]
           [com.embabel.agent.core EarlyTermination EarlyTerminationPolicy
            ProcessContext]))

;; ---------------------------------------------------------------------------
;; EarlyTerminationPolicy
;; ---------------------------------------------------------------------------

(defn policy
  "EarlyTerminationPolicy a partir de uma fn ou de `{:name ... :terminate? ...}`.

   `:terminate?` recebe o AgentProcess e devolve:

     nil / false                    -> continua
     true                           -> termina (error? true, reason genérica)
     \"motivo\"                       -> termina com esse motivo (error? true)
     {:reason \"...\" :error? false}   -> controle total

   `:error?` é o que separa \"parei porque quis\" de \"parei porque estourou\".
   A única embutida com `error? false` é a `on-stuck`, e a razão está no
   javadoc dela: com planner de utilidade, não conseguir progredir pode ser um
   fim legítimo em vez de uma falha."
  ^EarlyTerminationPolicy [f-or-map]
  (let [m (if (map? f-or-map) f-or-map {:terminate? f-or-map})
        _ (specs/validate! specs/PolicyDef m "early-termination policy")
        {:keys [name terminate?]} m
        nm (str (or name "embabel-clj/policy"))]
    (reify EarlyTerminationPolicy
      (getName [_] nm)
      (shouldTerminate [this proc]
        ;; null = "não termina": é o contrato do framework (retorno nullable)
        (let [r (terminate? proc)]
          (when (and (some? r) (not (false? r)))
            (let [{:keys [reason error?] :or {error? true}}
                  (cond
                    (map? r)    r
                    (string? r) {:reason r}
                    :else       {})]
              (EarlyTermination. proc
                                 (boolean error?)
                                 (str (or reason nm))
                                 this))))))))

(defn ->policy
  "Coerção usada pelo `:early-termination` do run options."
  ^EarlyTerminationPolicy [x]
  (if (instance? EarlyTerminationPolicy x) x (policy x)))

(defn first-of
  "Compõe policies: termina na PRIMEIRA que disser sim, na ordem dada."
  ^EarlyTerminationPolicy [policies]
  (EarlyTerminationPolicy/firstOf
   (into-array EarlyTerminationPolicy (map ->policy policies))))

;; --- as quatro embutidas ----------------------------------------------------
;;
;; PEGADINHA: ON_STUCK é `@JvmStatic val` de companion object — vira o MÉTODO
;; estático `getON_STUCK()`, NÃO um campo. `EarlyTerminationPolicy/ON_STUCK` dá
;; "no matches found for static method". Mesma família do `ValidationResult/VALID`
;; (que pede `.getVALID` no Companion): `@JvmStatic` não é `@JvmField`.

(defn on-stuck
  "A embutida que termina quando o planner trava — sem marcar erro."
  ^EarlyTerminationPolicy [] (EarlyTerminationPolicy/getON_STUCK))

(defn max-actions
  "Embutida: teto de actions executadas. (O `:budget {:actions n}` já a monta.)"
  ^EarlyTerminationPolicy [n] (EarlyTerminationPolicy/maxActions (int n)))

(defn max-tokens
  "Embutida: teto de tokens. (O `:budget {:tokens n}` já a monta.)"
  ^EarlyTerminationPolicy [n] (EarlyTerminationPolicy/maxTokens (int n)))

(defn hard-budget-limit
  "Embutida: teto de custo em dólares. (O `:budget {:cost d}` já a monta.)"
  ^EarlyTerminationPolicy [d] (EarlyTerminationPolicy/hardBudgetLimit (double d)))

;; ---------------------------------------------------------------------------
;; StuckHandler
;; ---------------------------------------------------------------------------

(defn stuck-handler
  "StuckHandler a partir de uma fn ou de `{:name ... :handle ...}`.

   `:handle` recebe o AgentProcess (que é um Blackboard — escreva nele para
   destravar) e devolve:

     nil / false / :no-resolution   -> NO_RESOLUTION (desisto)
     true / :replan                 -> REPLAN
     \"mensagem\"                     -> REPLAN com essa mensagem
     {:code :replan :message \"...\"}  -> controle total"
  ^StuckHandler [f-or-map]
  (let [m (if (map? f-or-map) f-or-map {:handle f-or-map})
        _ (specs/validate! specs/StuckHandlerDef m "stuck handler")
        {:keys [name handle]} m
        nm (str (or name "embabel-clj/stuck-handler"))]
    (reify StuckHandler
      (handleStuck [this proc]
        (let [r (handle proc)
              {:keys [code message]}
              (cond
                (map? r)     r
                (string? r)  {:code :replan :message r}
                (true? r)    {:code :replan}
                (keyword? r) {:code r}
                :else        {:code :no-resolution})
              code (if (true? code) :replan (or code :no-resolution))]
          (StuckHandlerResult.
           (str (or message (str nm ": " (clojure.core/name code))))
           this
           (if (= :replan code)
             StuckHandlingResultCode/REPLAN
             StuckHandlingResultCode/NO_RESOLUTION)
           proc))))))

(defn ->stuck-handler
  "Coerção usada pelo `:stuck-handler` do AgentDef."
  ^StuckHandler [x]
  (if (instance? StuckHandler x) x (stuck-handler x)))

;; ---------------------------------------------------------------------------
;; Terminação COOPERATIVA — a de dentro
;;
;; As duas de cima são avaliadas pela plataforma, de FORA, a cada tick. Esta é
;; disparada de DENTRO de uma action (ou de uma tool): o próprio corpo decide
;; que acabou. Duas variantes × dois escopos:
;;
;;              | graceful (sinal)      | imediata (exceção)
;;   -----------+-----------------------+---------------------------
;;   agente     | terminate-agent!      | terminate-agent-now!
;;   action     | terminate-action!     | terminate-action-now!
;;
;; Por baixo são extension functions do Kotlin (`Termination.terminateAgent`,
;; métodos ESTÁTICOS que recebem o ProcessContext) e duas RuntimeExceptions.
;; O ponto de existirem aqui é o mesmo da lib inteira: o seu projeto não
;; importa nada de `com.embabel`.
;; ---------------------------------------------------------------------------

(defn- ->process-context
  "Extrai o ProcessContext do ctx que a lib passa às actions (`{:pc ...}`),
   ou aceita um ProcessContext direto."
  ^ProcessContext [ctx]
  (cond
    (instance? ProcessContext ctx) ctx
    (and (map? ctx) (:pc ctx))     (:pc ctx)
    :else (throw (ex-info (str "terminação cooperativa: esperava o ctx da action "
                               "(com :pc) ou um ProcessContext")
                          {:type ::no-process-context :value ctx}))))

(defn terminate-agent!
  "Sinaliza (graceful) que o AGENTE inteiro terminou. O processo encerra
   limpo depois da volta atual — nada é abortado no meio."
  [ctx reason]
  (Termination/terminateAgent (->process-context ctx) (str reason))
  nil)

(defn terminate-action!
  "Sinaliza (graceful) que a ACTION atual terminou.

   ATENÇÃO, e isto está na página do hub: a variante graceful de ACTION só
   funciona em action de LLM COM TOOL LOOP — numa action de transformação
   simples ela não tem onde ser observada, e o que serve é a exceção
   (`terminate-action-now!`). E para a action poder ser retomada depois, ela
   precisa de `:rerun? true`."
  [ctx reason]
  (Termination/terminateAction (->process-context ctx) (str reason))
  nil)

(defn terminate-agent-now!
  "Aborta o AGENTE na hora, lançando TerminateAgentException. Não precisa do
   ctx — funciona de dentro de uma tool, fundo na pilha."
  [reason]
  (throw (TerminateAgentException. (str reason))))

(defn terminate-action-now!
  "Aborta a ACTION na hora, lançando TerminateActionException."
  [reason]
  (throw (TerminateActionException. (str reason))))

(defn termination-reason
  "A `reason` de uma TerminationException (nil para qualquer outro throwable).
   Deixa `(catch Throwable t ...)` legível sem importar as classes do Embabel."
  [t]
  (when (instance? TerminationException t)
    (.getReason ^TerminationException t)))

(defn termination-scope
  "`:agent`, `:action` ou nil — qual escopo aquela exceção pedia para encerrar."
  [t]
  (condp instance? t
    TerminateAgentException  :agent
    TerminateActionException :action
    nil))
