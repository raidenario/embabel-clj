(ns embabel-clj.hitl
  "Human-in-the-loop: o processo PARA, pede algo a um humano, e retoma.

   O Embabel modela isso com `Awaitable` — um pedido que a action joga como
   exceção de controle de fluxo (`AwaitableResponseException`). O framework
   não trata como erro: põe o awaitable no blackboard e estaciona o processo
   em WAITING. Depois alguém entrega a resposta e roda o processo de novo.

   O ciclo, inteiro:

     ;; 1. DENTRO da action — pede e para
     (defn revisar [{:keys [pc]}]
       (let [texto (bb/fetch pc :rascunho)]
         (hitl/confirm! texto \"Pode publicar?\")))   ; <- não retorna

     ;; 2. FORA — o processo voltou de `run!` em WAITING
     (hitl/waiting? proc)          ; => true
     (hitl/pending proc)           ; => {:kind :confirmation :message \"Pode publicar?\" ...}

     ;; 3. respondendo e retomando (pode ser noutro request HTTP, noutro dia)
     (hitl/answer! proc {:accept? true})
     (hitl/resume! proc)           ; => AgentProcess COMPLETED

   ## Três formas de pedir

   | fn | pede | ao responder |
   |---|---|---|
   | `confirm!`  | um sim/não sobre um objeto | se sim, o objeto entra no blackboard |
   | `ask!`      | um valor de um TIPO        | o valor entra no blackboard |
   | `wait-for!` | qualquer `Awaitable`       | o que o seu `:on-response` fizer |

   `awaitable` constrói o seu próprio a partir de um mapa de fns — o mesmo
   padrão de `events`/`interceptors`/`guardrails`: **interface pequena do
   Kotlin = mapa de fns no Clojure**.

   ## Duas condições que NÃO são opcionais

   1. **O processo tem que sobreviver entre o pedido e a resposta.** O
      repositório padrão do framework é in-memory com janela de 1000 e despejo
      do mais antigo — um processo que espera três dias pode simplesmente
      sumir. Use `embabel-clj.process-store` (e leia a doc dele).
   2. **Processo efêmero não espera.** O `AbstractAgentProcessRepository`
      recusa persistir `processOptions.ephemeral` com a mensagem
      \"Ephemeral processes are not persisted and do not support wait states\".

   ## Nota de retomada

   `resume!` roda a MESMA instância de processo — id e history preservados. É o
   contrato que o próprio framework fixa em teste (`AgentProcessResumeEventContractTest`:
   retomar não re-emite `AgentProcessCreationEvent`). Isso é diferente da
   retomada por log do `process-store`, que reconstrói o MUNDO num processo novo:
   uma continua a identidade, a outra continua o estado."
  (:require [embabel-clj.interop :as interop])
  (:import [com.embabel.agent.core AgentProcess AgentProcessStatusCode]
           [com.embabel.agent.core.hitl AbstractAwaitable Awaitable
            AwaitableResponse AwaitableResponseException ConfirmationRequest
            ConfirmationResponse FormBindingRequest ResponseImpact TypeRequest
            TypeResponse]
           [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Pedir (chamado DE DENTRO de uma action)
;; ---------------------------------------------------------------------------

(defn wait-for!
  "Estaciona o processo esperando `awaitable`. NÃO retorna: lança o
   `AwaitableResponseException`, que o `ActionRunner` do framework trata como
   sinal de controle (põe o awaitable no blackboard, status WAITING) e não
   como erro."
  [^Awaitable awaitable]
  (throw (AwaitableResponseException. awaitable)))

(defn confirm!
  "Pede confirmação humana de `payload`. Se a resposta for `{:accept? true}`,
   o próprio framework promove `payload` ao blackboard; se for false, o fluxo
   segue sem ele (e provavelmente sem alcançar o goal — que é o ponto)."
  [payload ^String message]
  (wait-for! (ConfirmationRequest. payload message false)))

(defn ask!
  "Pede um valor do tipo `t` (uma Class — um `defrecord` serve). O valor da
   resposta entra no blackboard, então uma action/goal com `:inputs [t]`
   encadeia por tipo depois disso.

   Opções: `:message` (o que mostrar) e `:hint` (valor pré-preenchido)."
  ([^Class t] (ask! t nil))
  ([^Class t {:keys [message hint]}]
   (wait-for! (TypeRequest. t message hint false))))

;; ---------------------------------------------------------------------------
;; Awaitable próprio: mapa de fns
;; ---------------------------------------------------------------------------

(defprotocol ^:private HasValue
  (-value [this] "O valor que `answer!` embrulhou para um awaitable próprio."))

(deftype ^:private CljResponse [id awaitable-id value ^Instant ts]
  AwaitableResponse
  (getId [_] id)
  (getAwaitableId [_] awaitable-id)
  (getTimestamp [_] ts)
  (persistent [_] false)
  HasValue
  (-value [_] value))

(defn awaitable
  "Awaitable a partir de um mapa:

     {:payload      o que vai junto do pedido (obrigatório)
      :on-response  (fn [valor ^AgentProcess proc] ...) — obrigatório
      :id           id estável (default: uuid)
      :persistent?  default false}

   O retorno de `:on-response` diz se o mundo mudou: `:unchanged` (ou false)
   vira `ResponseImpact/UNCHANGED`; qualquer outra coisa vira `UPDATED`.
   Escrever no blackboard é responsabilidade sua — `proc` É um Blackboard
   (`AgentProcess` estende `Blackboard`), então `bb/put!` funciona nele."
  ^Awaitable
  [{:keys [payload on-response id persistent?]}]
  (assert (some? payload) "hitl/awaitable: :payload é obrigatório")
  (assert (ifn? on-response) "hitl/awaitable: :on-response é obrigatório")
  (proxy [AbstractAwaitable] [payload
                              (or id (str (random-uuid)))
                              (Instant/now)
                              (boolean persistent?)]
    (onResponse [resp ^AgentProcess proc]
      (let [v (if (instance? CljResponse resp) (-value ^CljResponse resp) resp)]
        (if (#{:unchanged false} (on-response v proc))
          ResponseImpact/UNCHANGED
          ResponseImpact/UPDATED)))))

;; ---------------------------------------------------------------------------
;; Ler o que o processo está esperando
;; ---------------------------------------------------------------------------

(defn waiting?
  "O processo parou esperando alguém?"
  [^AgentProcess proc]
  (= AgentProcessStatusCode/WAITING (.getStatus proc)))

(defn- kind-of [x]
  (condp instance? x
    ConfirmationRequest :confirmation
    TypeRequest         :type-request
    FormBindingRequest  :form
    :custom))

(defn pending
  "O pedido pendente do processo, como mapa — ou nil se não há nenhum.

   Varre os objetos do blackboard de trás para frente em vez de olhar só o
   `lastResult`: o awaitable é o último objeto no caminho normal, mas uma
   action `:after` que escreva algo depois mudaria isso.

   As chaves vêm da projeção genérica de `interop/props` (o objeto original
   fica em `:raw`), mais `:kind` — porque é por ele que `answer!` decide a
   forma da resposta."
  [^AgentProcess proc]
  (when-let [aw (->> (.getObjects proc) reverse (filter #(instance? Awaitable %)) first)]
    (assoc (interop/props aw #{:class})
           :kind (kind-of aw)
           :raw  aw)))

;; ---------------------------------------------------------------------------
;; Responder e retomar
;; ---------------------------------------------------------------------------

(defn- ->response
  "Constrói a resposta na forma que ESTE awaitable espera."
  ^AwaitableResponse [aw {:keys [accept? value response]}]
  (or response
      (condp instance? aw
        ConfirmationRequest (ConfirmationResponse. (str (random-uuid))
                                                   (.getId ^ConfirmationRequest aw)
                                                   (boolean accept?)
                                                   false
                                                   (Instant/now))
        TypeRequest         (TypeResponse. value
                                           (.getId ^TypeRequest aw)
                                           (str (random-uuid))
                                           (Instant/now)
                                           false)
        (CljResponse. (str (random-uuid)) (.getId ^Awaitable aw) value (Instant/now)))))

(defn answer!
  "Entrega a resposta ao pedido pendente. Devolve `:updated` ou `:unchanged`
   (o `ResponseImpact` do framework, como keyword).

     (answer! proc {:accept? true})     ; confirmação
     (answer! proc {:value produto})    ; ask! / form / awaitable próprio
     (answer! proc {:response r})       ; um AwaitableResponse que você montou

   NÃO retoma o processo — `resume!` faz isso. Separado de propósito: entre
   responder e retomar costuma haver uma fronteira de transação, de fila ou de
   request HTTP.

   `FormBindingRequest` é um caso à parte e usa o `.bind` público do próprio
   framework: o caminho normal dele processa um `FormSubmission` vindo de uma
   UI, e daqui o valor já vem tipado — não há formulário a processar."
  [^AgentProcess proc {:keys [value] :as opts}]
  (let [{:keys [raw kind]} (or (pending proc)
                               (throw (ex-info "embabel-clj/hitl: nada pendente neste processo"
                                               {:process-id (.getId proc)
                                                :status     (str (.getStatus proc))})))
        impact (if (= :form kind)
                 (.bind ^FormBindingRequest raw value proc)
                 (.onResponse ^Awaitable raw (->response raw opts) proc))]
    (if (= ResponseImpact/UNCHANGED impact) :unchanged :updated)))

(defn resume!
  "Retoma o processo na MESMA instância (id e history preservados) e devolve o
   AgentProcess já rodado. Se ainda houver pedido pendente ele para de novo —
   um fluxo com dois `confirm!` volta duas vezes."
  ^AgentProcess [^AgentProcess proc]
  (.run proc))

(defn answer-and-resume!
  "`answer!` seguido de `resume!`, para quando não há fronteira entre os dois."
  ^AgentProcess [^AgentProcess proc opts]
  (answer! proc opts)
  (resume! proc))
