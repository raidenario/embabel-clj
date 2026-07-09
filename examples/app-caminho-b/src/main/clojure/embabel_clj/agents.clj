(ns embabel-clj.agents
  (:require [cheshire.core :as json])
  (:import [com.embabel.agent.core.support AbstractAction]
           [com.embabel.agent.core ProcessContext ActionStatus ActionStatusCode
                                    ActionQos Agent Goal Export]
           [com.embabel.agent.api.common TransformationActionContext]
           [java.time Duration]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers]))

(def fact-greeted "greeted")
(def slot-greeting "greeting")

(defn greeting
  []
  "Hello, world! -- v1, action data-oriented em Clojure rodando pelo planner GOAP")

(defn greet-impl
  [^ProcessContext pc]
  (let [bb  (.getBlackboard pc)
        msg (greeting)]
    (.set bb slot-greeting msg)
    (.setCondition bb fact-greeted true)
    msg))

(defn- ^kotlin.jvm.functions.Function1 const-fn
  [v]
  (reify kotlin.jvm.functions.Function1
    (invoke [_ _world-state] (Double/valueOf (double v)))))

(defn- default-qos
  ^ActionQos []
  (ActionQos. (int 5) (long 10000) (double 5.0) (long 60000) false))

;; -------------------------------------------------------------------------
;; Helpers de action (Nivel 3, data-oriented).
;;
;; CORRECAO C4 (PRD secao 9): antes `cost` e `value` eram hardcoded em
;; (const-fn 0.0) -> a "otimizacao por custo" do A* do planner era ficticia.
;; Agora `action`/`llm-action` ACEITAM um `cost` e o repassam como
;; (const-fn cost), distinguindo planos por custo. value segue 0.0 (nao usado
;; por actions; goals tem value).
;;
;; pre/post sao List<String> = condicoes booleanas nomeadas (NAO predicados).
;; O planner so roteia por estas; dados ricos vivem em slots (.set/.get no bb).
;; -------------------------------------------------------------------------

(defn action
  "Cria uma AbstractAction. `cost` (double) alimenta o A* do planner.
   `f` recebe o ProcessContext e executa o efeito (escreve slots,
   setCondition das `post` no sucesso).

   `rerun?` (CORRECAO C-1, verificado no fonte 0.4.0 AbstractAction.kt/
   AbstractAgentProcess.kt:603): com canRerun=false o Embabel injeta a pre
   `hasRun_<name>=FALSE` e seta hasRun=TRUE apos cada execucao -> a action so
   roda 1x por processo. Actions que processam UM item por tick e sao
   re-executadas pelo OODA loop (create/update/delete-mirror) PRECISAM
   rerun?=true; senao o grafo nunca converge com >1 pendencia."
  (^AbstractAction [name pre post f] (action name pre post 1.0 false f))
  (^AbstractAction [name pre post cost f] (action name pre post cost false f))
  (^AbstractAction [name pre post cost rerun? f]
   (proxy [AbstractAction]
          [name
           name
           (vec pre)
           (vec post)
           (const-fn (double cost))
           (const-fn 0.0)
           #{}
           #{}
           #{}
           (boolean rerun?)   ; canRerun (posicao 10 do ctor) — C-1
           false
           false
           (default-qos)]
     (execute [^ProcessContext pc]
       (f pc)
       (ActionStatus. (Duration/ofMillis 0) ActionStatusCode/SUCCEEDED))
     (referencedInputProperties [_variable]
       #{}))))

(defn llm-action
  "Como `action`, mas `f` recebe um TransformationActionContext (acesso a `.ai`
   para chamadas LLM). Tambem aceita `cost`."
  (^AbstractAction [name pre post f] (llm-action name pre post 1.0 f))
  (^AbstractAction [name pre post cost f]
   (proxy [AbstractAction]
          [name name (vec pre) (vec post)
           (const-fn (double cost)) (const-fn 0.0)
           #{} #{} #{}
           false false false
           (default-qos)]
     (execute [^ProcessContext pc]
       (let [oc (TransformationActionContext. nil pc this Object Object)]
         (f oc))
       (ActionStatus. (Duration/ofMillis 0) ActionStatusCode/SUCCEEDED))
     (referencedInputProperties [_variable]
       #{}))))

(defn llm-action*
  "Como `llm-action`, mas `f` recebe DOIS args: `[oc pc]` — o
   TransformationActionContext (acesso a `.ai` p/ chamar o LLM) E o
   ProcessContext (acesso a `.getBlackboard`, p/ REUSAR os helpers de
   condicao/slot ja existentes do reconcile). Tambem aceita `cost` e `rerun?`.

   Motivacao (Slice 2 / learn-playbook): a action precisa (a) chamar o LLM
   (so disponivel via `.ai` no TransformationActionContext) E (b) manipular o
   blackboard com os mesmos helpers pc-based das demais actions do reconcile.
   `llm-action` so entrega `oc`; `action` so entrega `pc`. Este entrega ambos."
  (^AbstractAction [name pre post cost rerun? f]
   (proxy [AbstractAction]
          [name name (vec pre) (vec post)
           (const-fn (double cost)) (const-fn 0.0)
           #{} #{} #{}
           (boolean rerun?) false false
           (default-qos)]
     (execute [^ProcessContext pc]
       (let [oc (TransformationActionContext. nil pc this Object Object)]
         (f oc pc))
       (ActionStatus. (Duration/ofMillis 0) ActionStatusCode/SUCCEEDED))
     (referencedInputProperties [_variable]
       #{}))))

;; -------------------------------------------------------------------------
;; HTTP client para a Capabilities API do agendas (chamada de volta, loop B).
;;
;; Manda o header X-Cap-Token (env SYNC_CAP_TOKEN, default dev "dev-cap-token"
;; — IGUAL ao default do lado agendas em web.clj). Timeout curto: erro de
;; TRANSPORTE (agendas down/timeout) lanca excecao (a action aborta); erro de
;; DOMINIO (status >= 400 no corpo) e tratado pela funcao da action.
;; -------------------------------------------------------------------------

(def cap-token
  (or (System/getenv "SYNC_CAP_TOKEN") "dev-cap-token"))

(def agendas-base-url
  (or (System/getenv "AGENDAS_BASE_URL") "http://127.0.0.1:8888"))

(defn- ^HttpClient http-client []
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 5))
      (.build)))

(defn http-get
  "GET `url` com X-Cap-Token; devolve {:status :body} com body ja parseado de JSON
   (keywordized). Lanca em erro de transporte."
  [url]
  (let [req  (-> (HttpRequest/newBuilder (URI/create url))
                 (.timeout (Duration/ofSeconds 15))
                 (.header "X-Cap-Token" cap-token)
                 (.GET)
                 (.build))
        resp (.send (http-client) req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :body   (json/parse-string (.body resp) true)}))

(defn http-post
  "POST `url` com `body` (map -> JSON) e X-Cap-Token; devolve {:status :body}.
   Lanca em erro de transporte."
  [url body]
  (let [req  (-> (HttpRequest/newBuilder (URI/create url))
                 (.timeout (Duration/ofSeconds 30))
                 (.header "X-Cap-Token" cap-token)
                 (.header "Content-Type" "application/json")
                 (.POST (HttpRequest$BodyPublishers/ofString
                         (json/generate-string (or body {}))))
                 (.build))
        resp (.send (http-client) req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :body   (json/parse-string (.body resp) true)}))

(defn http-action
  "Action que chama a Capabilities API do agendas e roteia o planner por condicao.

   - `method` :get | :post
   - `path`   ex. \"/api/cap/snapshot\" (concatenado a AGENDAS_BASE_URL)
   - `result-slot` nome do slot onde o body cru e guardado (.set no bb)
   - `on-ok`  (fn [pc body]) chamada quando status 2xx: deve setCondition das
              pos-condicoes de sucesso (e escrever slots derivados se quiser).
              Erro de DOMINIO (status >= 400) NAO chama on-ok -> as pos nao sao
              setadas -> o planner replaneja.
   - `on-error` (fn [pc body status]) OPCIONAL, chamada quando status >= 400:
              deve classificar o erro e setCondition do POLO de erro correto
              (revisao C3: so polos positivos, ex. setCondition category/ok?
              false / conn/needs-reauth? true). NUNCA seta as pos de sucesso.
              Se nil, o comportamento e o do Slice 0 (so guarda o erro no slot).

   `cost` opcional alimenta o A*. `body-fn` opcional (fn [pc]) monta o corpo do
   POST a partir do blackboard.

   Erro de DOMINIO = status HTTP do corpo >= 400 OU corpo com chave :error
   (o agendas devolve erros de exec-action como 200 + {:error {:status ...}};
   ver web.clj cap-exec-action / PRD §4.3) -> tratado como falha logica."
  (^AbstractAction [name pre post method path result-slot on-ok]
   (http-action name pre post 1.0 method path result-slot on-ok nil nil))
  (^AbstractAction [name pre post cost method path result-slot on-ok body-fn]
   (http-action name pre post cost method path result-slot on-ok body-fn nil))
  (^AbstractAction [name pre post cost method path result-slot on-ok body-fn on-error]
   (action
    name pre post cost
    (fn [^ProcessContext pc]
      (let [bb   (.getBlackboard pc)
            url  (str agendas-base-url path)
            {:keys [status body]} (case method
                                    :get  (http-get url)
                                    :post (http-post url (when body-fn (body-fn pc))))
            ;; o agendas embrulha erro de dominio do exec-action em 200 + :error
            ;; (status no corpo). Trata os dois casos como falha logica.
            dom-err (get body :error)
            ok?     (and (>= status 200) (< status 300) (nil? dom-err))]
        (.set bb result-slot body)
        (.set bb (str result-slot "/status") (int status))
        (if ok?
          (on-ok pc body)
          (let [eff-status (or (:status dom-err) status)]
            ;; erro de dominio: deixa as pos NAO setadas -> replan
            (.set bb (str result-slot "/error") (or dom-err body))
            (when on-error
              (on-error pc (or dom-err body) eff-status)))))))))

(defn goal
  ^Goal [name description pre value]
  (Goal. name description (set pre) #{} nil (const-fn value) #{} #{}
         (Export. nil false true #{})))

(defn hello-agent
  ^Agent []
  (let [greet (action "greet" [] [fact-greeted] #'greet-impl)
        deliver (goal "deliver-greeting"
                      "Entregar uma saudacao ao usuario"
                      [fact-greeted]
                      1.0)]
    (Agent. "hello-agent"
            "embabel-clj"
            "0.1.0"
            "Agente hello-world data-oriented em Clojure"
            #{deliver}
            [greet])))
