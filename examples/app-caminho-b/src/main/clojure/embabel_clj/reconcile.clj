(ns embabel-clj.reconcile
  "Slice 1 — Ideia 1: RECONCILIADOR AUTO-CURAVEL do grafo de espelhos (PRD §6),
   SEM playbook hot-load (isso e o Slice 2).

   Modelo (PRD §6.1, revisao C3): o planner GOAP do Embabel 0.4.0 so enxerga
   CONDICOES BOOLEANAS NOMEADAS (strings). O determiner
   (BlackboardWorldStateDeterminer) trata uma condicao DECLARADA mas NUNCA
   setada como FALSE (nao UNKNOWN) — verificado no fonte 0.4.0: o ramo `else`
   faz `ConditionDetermination(getCondition).asTrueOrFalse()`, e getCondition
   devolve nil quando ausente, virando FALSE. (Resolve a Q4 do PRD.)

   CONSEQUENCIA (revisao C3/Q4): NAO existe 'pre = NAO X'. Modelamos sempre o
   COMPLEMENTO POSITIVO. Em vez de 'create-mirror exige NAO category/missing?',
   declaramos `category/ok?` e a setamos TRUE quando nao ha erro de categoria;
   create-mirror exige `category/ok?` TRUE. Idem `conn/authorized?`.

   Dois niveis:
     - condicoes booleanas  -> o que o planner roteia (.setCondition / .getCondition)
     - slots de dados       -> o que as funcoes das actions leem/escrevem (.set / .get)
       slot-world (snapshot rico cru), slot-plan (vetor de actions OPACAS de
       /cap/plan), slot-callback, slot-replans (contador), slot-executed,
       slot-classifications, slot-result.

   IMPORTANTE (cuidado de runtime, verificado em InMemoryBlackboard):
   `(.set bb k v)` NPEia se v for nil (ConcurrentHashMap). Guardar nils.
   `clear()` apaga condicoes nas transicoes de estado; este agente e de estado
   unico (sem domain-type transitions), entao as condicoes persistem entre
   actions e o determiner as re-le a cada tick do OODA loop."
  (:require [embabel-clj.agents :as a]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [malli.core :as m])
  (:import [com.embabel.agent.core Agent ProcessContext]))

;; =========================================================================
;; CONDICOES BOOLEANAS (strings; SEM ':' no nome — ':' dispara o ramo de
;; data-binding do determiner. Usamos '/' e '?', que sao seguros).
;; =========================================================================
(def c-pending-creates   "graph/has-pending-creates?")
(def c-pending-updates   "graph/has-pending-updates?")
(def c-orphans           "graph/has-orphans?")
(def c-consistent        "graph/consistent?")
(def c-reported-blocking "graph/reported-blocking?")

(def c-category-ok       "category/ok?")        ; polo positivo (sem erro de categoria)
(def c-category-needs    "category/needs-fix?") ; flag positiva: classify viu :category/missing
(def c-conn-authorized   "conn/authorized?")    ; polo positivo (sem erro de auth)
(def c-conn-needs-reauth "conn/needs-reauth?")  ; flag positiva: classify viu :account/unauthorized
(def c-throttled         "provider/throttled?")
(def c-needs-human       "needs-human?")        ; teto/erro que exige humano
(def c-unknown           "error/unknown?")      ; erro nao classificado (estatico+playbooks) -> learn-playbook (Slice 2)
(def c-unblocked         "graph/unblocked?")    ; polo positivo: NENHUM erro bloqueante -> GATE das mirror-actions
                                                ; (= not(category-missing|unauth|throttled|unknown|needs-human)).
                                                ; Sem este gate o A* escolhe create-mirror em vez de
                                                ; learn/retry/reauth qd ha erro + pendencia (revisao adversarial Slice 2).

;; =========================================================================
;; SLOTS DE DADOS (.set/.get; dados ricos, o planner NAO os ve)
;; =========================================================================
(def slot-world           "world")             ; snapshot rico cru
(def slot-plan            "plan")              ; vetor de actions OPACAS de /cap/plan
(def slot-source-account  "source-account")    ; filtro opcional do /cap/plan
(def slot-replans         "replans")           ; contador (guarda anti-loop)
(def slot-executed        "executed")          ; vetor de [op target id] executados
(def slot-classifications "classifications")    ; vetor de keywords de erro classificadas
(def slot-result          "reconcile/result")
(def slot-playbooks       "playbooks-loaded")    ; ids (string) de playbooks aprendidos nesta run (Slice 2)

(def max-replans 8)

;; =========================================================================
;; FUNCOES PURAS (testaveis sem Spring) — PRD §6.2 ponto 2
;; =========================================================================

(defn classify-error
  "PURO. Mapeia UM mapa de erro do agendas para uma keyword de classe.

   Entrada: {:status <int|nil> :provider <str|kw|nil> :message <str|nil> ...}
   (formato do corpo de erro de /cap/exec-action — PRD §4.3 — ou de um item de
   :errors do snapshot rico — PRD §4.1).

   Regras (PRD §6.2):
     - 400 microsoft com 'categ' na mensagem -> :category/missing
     - 401 ou 403                            -> :account/unauthorized
     - 429                                   -> :provider/throttled
     - qualquer outro                        -> :unknown

   Exemplos:
     (classify-error {:status 400 :provider \"microsoft\"
                      :message \"categoria 'Sync · Roxo' ausente\"})
       => :category/missing
     (classify-error {:status 403 :provider \"google\"})  => :account/unauthorized
     (classify-error {:status 401})                       => :account/unauthorized
     (classify-error {:status 429 :provider \"google\"})  => :provider/throttled
     (classify-error {:status 500})                       => :unknown
     (classify-error {:status nil :message \"conta nao autorizada\"})
       => :account/unauthorized   ; caso sem status (sync.clj:93 / I7)
     (classify-error {})                                  => :unknown"
  [{:keys [status provider message error]}]
  (let [prov  (some-> provider name str/lower-case)
        ;; tolera shape legado: :error (string) no lugar de :message, e status
        ;; embutido na string "clj-http: status NNN" quando :status e nil (C-2).
        raw   (or message error)
        status (or status
                   (when raw (some-> (re-find #"status (\d{3})" raw) second parse-long)))
        msg   (some-> raw str/lower-case)
        categ? (and msg (or (str/includes? msg "categ")     ; categoria/category
                            (str/includes? msg "category")))]
    (cond
      (and (= status 400) (= prov "microsoft") categ?) :category/missing
      ;; sem status mas a mensagem fixa do agendas indica autorizacao (I7)
      (and (nil? status) msg (str/includes? msg "autoriz")) :account/unauthorized
      (#{401 403} status)                              :account/unauthorized
      (= status 429)                                   :provider/throttled
      :else                                            :unknown)))

;; =========================================================================
;; SLICE 2 — PLAYBOOK EDN HOT-LOAD (PRD §6.5)
;;
;; Quando o classificador ESTATICO (classify-error) devolve :unknown, o
;; reconciliador pede ao LLM (OpenRouter) UM playbook EDN que (a) classifica o
;; erro numa classe JA conhecida do catalogo e (b) escolhe uma remedy-action JA
;; existente — NUNCA uma funcao/simbolo arbitrario (correcao de seguranca I9 do
;; PRD: a allowlist e de CLASSES e ACTIONS de enums fechados, nao de codigo).
;; O playbook valido e validado por Malli e hot-loaded num atom (sem restart).
;; O catalogo de classificacao efetivo = estatico + playbooks (classify-error*).
;; =========================================================================

(def playbook-model
  "Slug OpenRouter registrado em models/openai-models.yml (verificado respondendo)."
  "openai/gpt-4o-mini")

(def useful-classes
  "Classes acionaveis: o playbook so ajuda se mapear para uma condicao que tem
   action corretiva (ensure-category / reauth-account / retry-throttled). :unknown
   nao ajuda (nao limpa o bloqueio) -> rejeitado em validate-playbook."
  #{:category/missing :account/unauthorized :scope/insufficient :provider/throttled})

(def Playbook
  "Schema Malli (DADO). :closed -> nenhuma chave extra no topo. Os enums fecham
   o espaco: classify-as so vira condicao conhecida; remedy-action so nomeia uma
   action do catalogo §6.2. Sem :fn/:capability/simbolo (I9)."
  [:map {:closed true}
   [:id :keyword]
   [:matches [:map
              [:status [:enum 400 403 404 409 429 500 503]]
              [:provider [:enum :google :microsoft]]
              [:message-pattern {:optional true} :string]]]
   [:classify-as [:enum :category/missing :account/unauthorized
                  :scope/insufficient :provider/throttled :unknown]]
   [:remedy-action [:enum "reauth-account" "ensure-category" "retry-throttled"
                    "create-mirror" "update-mirror" "delete-orphan"]]
   [:backoff-millis {:optional true} pos-int?]
   [:max-retries {:optional true} :int]])

(defonce ^{:doc "Registro hot-loaded de playbooks: {id -> playbook}. Persiste
   entre runs (auto-cura aprendida sem restart). Reset nos testes."}
  playbook-registry
  (atom {}))

(defn- clean-fences
  "Tira cercas markdown ```edn / ``` que o LLM as vezes inclui (igual nature.clj)."
  [s]
  (-> (str s) str/trim
      (str/replace #"(?s)^```(?:edn|clojure)?\s*" "")
      (str/replace #"(?s)\s*```$" "")))

(defn- ->kw
  "string|symbol -> keyword PRESERVANDO namespace; senao inalterado. O LLM as
   vezes omite o ':' (ex.: `id :x` em vez de `:id :x`, ou `:classify-as
   provider/throttled`), e o reader EDN devolve isso como SIMBOLO. Cuidado:
   (keyword (name 'provider/throttled)) PERDERIA o ns -> :throttled; por isso
   usamos a aridade-2 / parse de string que mantem 'provider/throttled'."
  [x] (cond
        (keyword? x) x
        (symbol? x)  (keyword (namespace x) (name x))
        (string? x)  (keyword x)
        :else        x))

(defn- deep-keywordize-keys
  "Como walk/keywordize-keys, mas converte chaves STRING **e SYMBOL** -> keyword
   (recursivo). Necessario porque o LLM pode emitir chaves sem ':'."
  [m]
  (walk/postwalk
   (fn [x] (if (map? x)
             (into {} (map (fn [[k v]] [(->kw k) v]) x))
             x))
   m))

(defn parse-playbook
  "PURO. Texto cru do LLM -> mapa playbook normalizado (ou nil). Le EDN,
   keywordiza, SELECIONA so as chaves conhecidas (descarta extras do LLM ANTES da
   validacao :closed, em vez de rejeitar) e coage tipos basicos (status string ->
   long; provider/id/classify-as string -> keyword). NAO valida (ver
   validate-playbook). Tolerante: qualquer parse-fail -> nil.
   Cap de tamanho + catch Throwable: EDN profundo demais lanca StackOverflowError
   (um Error, NAO Exception) no reader; o cap + catch amplo evitam que escape."
  [s]
  (when (and s (<= (count (str s)) 20000))
    (try
      (let [v (edn/read-string (clean-fences s))]
        (when (map? v)
          (let [v   (deep-keywordize-keys v)
                mt  (some-> (:matches v)
                            (select-keys [:status :provider :message-pattern])
                            (as-> m (cond-> m
                                      (string? (:status m)) (update :status #(or (parse-long %) %))
                                      (:provider m)         (update :provider ->kw))))
                pb  (cond-> (select-keys v [:id :matches :classify-as :remedy-action
                                            :backoff-millis :max-retries])
                      mt (assoc :matches mt))]
            (cond-> pb
              (:id pb)          (update :id ->kw)
              (:classify-as pb) (update :classify-as ->kw)))))
      (catch Throwable _ nil))))

(defn validate-playbook
  "PURO. Devolve o playbook se valido pelo schema Malli E com :classify-as
   ACIONAVEL (em useful-classes; :unknown nao ajuda); senao nil. Rejeita
   remedy-action fora do enum (Malli :closed/:enum) e classify-as inutil."
  [pb]
  (when (and (map? pb)
             (m/validate Playbook pb)
             (contains? useful-classes (:classify-as pb)))
    pb))

(defn playbook-match?
  "PURO. Um playbook casa um erro se status e provider batem e (se houver) o
   message-pattern aparece na mensagem. message-pattern e SUBSTRING
   case-insensitive — NAO regex: um playbook vem de saida NAO confiavel do LLM,
   e um regex hostil (ex.: (.*a){30}) causaria ReDoS numa thread do planner.
   Substring e suficiente p/ casar trechos de mensagem de erro (ex.: 'quota').
   Tolera shape legado (status embutido em 'clj-http: status NNN')."
  [pb err]
  (let [mt   (:matches pb)
        {:keys [status provider message error]} err
        raw  (or message error)
        st   (or status
                 (when raw (some-> (re-find #"status (\d{3})" raw) second parse-long)))
        prov (some-> provider name str/lower-case keyword)
        pat  (:message-pattern mt)]
    (boolean
     (and (= (:status mt) st)
          (= (:provider mt) prov)
          (or (nil? pat)
              (and raw (str/includes? (str/lower-case raw)
                                      (str/lower-case pat))))))))

(defn classify-error*
  "PURO (dado um `registry`). Como classify-error, mas se a regra ESTATICA
   devolver :unknown, consulta os playbooks registrados; o 1o que casar fornece
   :classify-as ACIONAVEL. Devolve :unknown se nenhum casar. Os playbooks NUNCA
   sobrepoem uma classificacao estatica ja conhecida (so resolvem :unknown)."
  [registry err]
  (let [base (classify-error err)]
    (if (not= base :unknown)
      base
      (or (some (fn [pb]
                  (when (playbook-match? pb err)
                    (let [c (:classify-as pb)]
                      (when (contains? useful-classes c) c))))
                (vals registry))
          :unknown))))

(defn learn-from-text
  "Texto cru do LLM -> parse + valida + swap! no playbook-registry. Devolve o
   playbook ACEITO ou nil (fallback). UNICO ponto que MUTA o registry (testavel
   sem Spring/OpenRouter — alimentar com strings)."
  [raw]
  (let [pb (some-> raw parse-playbook validate-playbook)]
    (when pb (swap! playbook-registry assoc (:id pb) pb))
    pb))

(defn playbook-prompt
  "Prompt p/ o LLM gerar UM playbook EDN p/ um erro desconhecido. Lista os enums
   fechados e exige coerencia classify-as<->remedy-action."
  [err]
  (str "Voce e um SRE que classifica erros de API de calendario (Google Calendar / "
       "Microsoft Graph) para um motor de auto-cura GOAP. Recebe UM erro que o "
       "classificador estatico marcou como DESCONHECIDO. Decida (a) em qual CLASSE "
       "conhecida ele se encaixa e (b) qual ACAO corretiva ja existente aplicar.\n\n"
       "ERRO:\n"
       "  status:   " (pr-str (:status err)) "\n"
       "  provider: " (pr-str (:provider err)) "\n"
       "  mensagem: " (pr-str (or (:message err) (:error err))) "\n\n"
       "Responda SOMENTE com UM mapa EDN do Clojure (sem markdown, sem ```), com "
       "EXATAMENTE estas chaves:\n"
       "  :id            -> keyword curta e descritiva (ex.: :svc-unavailable-backoff)\n"
       "  :matches       -> {:status <int> :provider :google ou :microsoft "
       ":message-pattern \"<substring opcional da mensagem>\"}\n"
       "  :classify-as   -> UMA de: :category/missing :account/unauthorized "
       ":scope/insufficient :provider/throttled\n"
       "  :remedy-action -> UMA de: \"ensure-category\" \"reauth-account\" \"retry-throttled\"\n"
       "Regras: :classify-as e :remedy-action DEVEM ser coerentes "
       "(:provider/throttled<->\"retry-throttled\"; :category/missing<->\"ensure-category\"; "
       ":account/unauthorized ou :scope/insufficient<->\"reauth-account\"). "
       ":matches.:status DEVE ser EXATAMENTE " (pr-str (:status err))
       " e :matches.:provider o provider acima. "
       "Se nenhuma acao corretiva for claramente segura, escolha "
       ":provider/throttled + \"retry-throttled\" (backoff + nova tentativa). "
       "NUNCA invente outras chaves ou valores fora dos enums. "
       "TODAS as chaves sao keywords (comecam com ':'). Responda EXATAMENTE neste formato:\n"
       "{:id :exemplo-id :matches {:status 429 :provider :google} "
       ":classify-as :provider/throttled :remedy-action \"retry-throttled\"}"))

(defn on-unknown-error
  "I/O: chama o LLM (OpenRouter) p/ UM erro desconhecido (via `oc`) e delega a
   learn-from-text. Tolera falha de transporte do LLM (-> nil -> fallback). `oc` =
   TransformationActionContext (tem `.ai`)."
  [oc err]
  (learn-from-text
   (try (-> (.ai oc) (.withLlm playbook-model) (.generateText (playbook-prompt err)))
        (catch Exception _ nil))))

(defn snapshot->conditions
  "PURO. Deriva o mapa {condicao-string -> bool} a partir do snapshot rico e do
   vetor de actions de /cap/plan. So polos POSITIVOS (revisao C3/Q4).

   `snapshot` = corpo de GET /cap/snapshot (PRD §4.1): {:accounts :rules
   :mappings :errors :last-sync :daemon-on :mirror-label}.
   `plan` = vetor de actions de POST /cap/plan (cada uma com :op :create|:update
   |:delete).

   Tolerante a chaves vindas como string (Jackson/cheshire keywordiza, mas o
   :op pode vir 'create' string vs :create kw). Normaliza ambos.

   Exemplos:
     (snapshot->conditions {:errors []}
                           [{:op :create} {:op :update}])
       => {\"graph/has-pending-creates?\" true
           \"graph/has-pending-updates?\" true
           \"graph/has-orphans?\" false
           \"category/ok?\" true \"category/needs-fix?\" false
           \"conn/authorized?\" true \"conn/needs-reauth?\" false
           \"provider/throttled?\" false
           \"graph/consistent?\" false}

     (snapshot->conditions {:errors [{:status 400 :provider \"microsoft\"
                                      :message \"categoria ausente\"}]}
                           [{:op \"create\"}])
       => category/ok? false, category/needs-fix? true, ...consistent? false

     (snapshot->conditions {:errors []} [])
       => tudo pending false; category/ok? true; conn/authorized? true;
          graph/consistent? TRUE"
  ([snapshot plan] (snapshot->conditions snapshot plan classify-error))
  ([snapshot plan classify-fn]
   (let [ops          (->> plan (keep :op) (map #(keyword (name %))) set)
         creates?     (contains? ops :create)
         updates?     (contains? ops :update)
         orphans?     (contains? ops :delete)
         errors       (:errors snapshot)
         classes      (set (map classify-fn errors))
         cat-missing? (contains? classes :category/missing)
         ;; scope/insufficient (so via playbook) e tratado como auth (mesma remedy)
         unauth?      (or (contains? classes :account/unauthorized)
                          (contains? classes :scope/insufficient))
         throttled?   (contains? classes :provider/throttled)
         unknown?     (contains? classes :unknown)
         ;; erro bloqueante = qualquer classe que impeca consistencia.
         blocking?    (or cat-missing? unauth? throttled? unknown?)]
     {c-pending-creates creates?
      c-pending-updates updates?
      c-orphans         orphans?
      c-category-ok     (not cat-missing?)
      c-category-needs  cat-missing?
      c-conn-authorized (not unauth?)
      c-conn-needs-reauth unauth?
      c-throttled       throttled?
      c-unknown         unknown?
      c-unblocked       (not blocking?)      ; gate das mirror-actions
      c-consistent      (and (not creates?) (not updates?) (not orphans?)
                             (not blocking?))})))

;; =========================================================================
;; HELPERS DE BLACKBOARD
;; =========================================================================

(defn- bb [^ProcessContext pc] (.getBlackboard pc))

(defn- set-conditions!
  "Aplica um mapa {cond-string -> bool} via .setCondition."
  [^ProcessContext pc cmap]
  (let [b (bb pc)]
    (doseq [[k v] cmap] (.setCondition b k (boolean v)))))

(defn- gset
  "get com default (evita nil em .set)."
  [^ProcessContext pc k default]
  (let [v (.get (bb pc) k)] (if (nil? v) default v)))

(defn- bump-replans!
  "Incrementa o contador de replans; se exceder o teto, seta needs-human? TRUE
   (guarda anti-loop, PRD §6.2 ponto 4 / I4). Devolve o novo valor."
  [^ProcessContext pc]
  (let [n (inc (long (gset pc slot-replans 0)))]
    (.set (bb pc) slot-replans (long n))
    (when (> n max-replans)
      (.setCondition (bb pc) c-needs-human true))
    n))

(defn- record-class!
  "Anexa uma keyword de classe de erro ao slot de classificacoes."
  [^ProcessContext pc klass]
  (let [v (conj (vec (gset pc slot-classifications [])) (str klass))]
    (.set (bb pc) slot-classifications v)))

(defn- record-executed!
  [^ProcessContext pc result]
  (let [v (conj (vec (gset pc slot-executed [])) (vec result))]
    (.set (bb pc) slot-executed v)))

(defn- apply-error-pole!
  "Dado um mapa de erro, classifica (estatico + playbooks ja aprendidos) e seta o
   POLO POSITIVO de erro correto (revisao C3). Tambem registra a classe.
   (Slice 2) Erro :unknown -> error/unknown? (-> learn-playbook tenta aprender um
   playbook), NAO mais needs-human? direto."
  [^ProcessContext pc err]
  (let [klass (classify-error* @playbook-registry err)]
    (record-class! pc klass)
    (case klass
      :category/missing     (do (.setCondition (bb pc) c-category-ok false)
                                (.setCondition (bb pc) c-category-needs true))
      (:account/unauthorized :scope/insufficient)
                            (do (.setCondition (bb pc) c-conn-authorized false)
                                (.setCondition (bb pc) c-conn-needs-reauth true))
      :provider/throttled   (.setCondition (bb pc) c-throttled true)
      :unknown              (.setCondition (bb pc) c-unknown true))
    ;; qualquer erro classificado BLOQUEIA as mirror-actions ate a remedy rodar.
    (.setCondition (bb pc) c-unblocked false)
    klass))

(defn- take-op
  "Remove e devolve [action resto] a 1a action com :op `op` do slot-plan.
   Devolve [nil plan] se nao houver."
  [^ProcessContext pc op]
  (let [plan (vec (gset pc slot-plan []))
        idx  (first (keep-indexed
                     (fn [i a] (when (= (keyword (name (:op a))) op) i))
                     plan))]
    (if idx
      [(nth plan idx) (into (subvec plan 0 idx) (subvec plan (inc idx)))]
      [nil plan])))

(defn- blocking?*
  "PURO sobre o blackboard. TRUE se existe QUALQUER erro bloqueante atual (le os
   polos via .getCondition; nil/never-set conta como FALSE)."
  [^ProcessContext pc]
  (or (false? (.getCondition (bb pc) c-category-ok))
      (false? (.getCondition (bb pc) c-conn-authorized))
      (true?  (.getCondition (bb pc) c-throttled))
      (true?  (.getCondition (bb pc) c-unknown))
      (true?  (.getCondition (bb pc) c-needs-human))))

(defn- refresh-derived!
  "Re-deriva os DOIS polos derivados — c-unblocked e c-consistent — a partir do
   plan restante (slot-plan) + dos polos de erro ATUAIS no blackboard.
   - c-unblocked  = nao ha erro bloqueante  (GATE das mirror-actions)
   - c-consistent = sem pendencia E sem bloqueio
   Chamado por TODA action que muda o slot-plan ou limpa/seta um bloqueio, para
   que o gate e o goal reflitam o estado real a cada tick (o A* so encadeia pelos
   posts DECLARADOS; o valor REAL e este)."
  [^ProcessContext pc]
  (let [ops (->> (gset pc slot-plan []) (keep :op) (map #(keyword (name %))) set)
        b?  (blocking?* pc)]
    (.setCondition (bb pc) c-unblocked  (not b?))
    (.setCondition (bb pc) c-consistent (and (empty? ops) (not b?)))))

(defn- rederive-pending!
  "Apos remover uma action do slot-plan, re-deriva as 3 condicoes de pendencia a
   partir do plan restante + os derivados (unblocked/consistent via refresh-derived!)."
  [^ProcessContext pc plan-restante]
  (let [ops (->> plan-restante (keep :op) (map #(keyword (name %))) set)]
    (.set (bb pc) slot-plan (vec plan-restante))
    (.setCondition (bb pc) c-pending-creates (contains? ops :create))
    (.setCondition (bb pc) c-pending-updates (contains? ops :update))
    (.setCondition (bb pc) c-orphans         (contains? ops :delete))
    (refresh-derived! pc)))

;; =========================================================================
;; ACTIONS — PRD §6.2. Construidas com agents/action (controle fino dos dois
;; ramos: sucesso vs erro de dominio). http-get/http-post mandam X-Cap-Token.
;; =========================================================================

(defn- exec-one-mirror!
  "Comum a create/update/delete: pega UMA action :op do slot-plan, POST
   /cap/exec-action (action OPACO inteiro). Sucesso -> remove do slot-plan +
   re-deriva pendencias + registra executado. Erro de dominio -> classifica
   e seta o polo de erro; NAO toca as pos -> replan. bump-replans no inicio."
  [^ProcessContext pc op]
  (bump-replans! pc)
  (let [[action resto] (take-op pc op)]
    (when action
      (let [{:keys [status body]}
            (a/http-post (str a/agendas-base-url "/api/cap/exec-action") action)
            dom-err (:error body)]
        (if (and (>= status 200) (< status 300) (nil? dom-err))
          (do (record-executed! pc (:result body))
              (rederive-pending! pc resto))
          ;; erro de dominio (corpo {:error {:status ...}} OU status http >=400)
          (apply-error-pole! pc (or dom-err
                                    {:status (when (>= status 400) status)
                                     :provider (:target-account action)
                                     :message (str body)})))))))

(defn load-plan
  "Action: GET /cap/snapshot + POST /cap/plan. Guarda snapshot em slot-world e o
   vetor de actions em slot-plan; deriva e seta TODAS as condicoes via
   snapshot->conditions. pre []. cost 1."
  ^com.embabel.agent.core.support.AbstractAction []
  (a/action
   "load-plan" [] [c-pending-creates c-pending-updates c-orphans
                   c-category-ok c-conn-authorized c-unblocked c-consistent]
   0.1
   (fn [^ProcessContext pc]
     (bump-replans! pc)
     (let [snap-resp (a/http-get (str a/agendas-base-url "/api/cap/snapshot"))
           snapshot  (or (:body snap-resp) {})   ; nil body -> NPE no .set (ConcurrentHashMap)
           src       (gset pc slot-source-account nil)
           plan-resp (a/http-post (str a/agendas-base-url "/api/cap/plan")
                                  (if src {:source-account src} {}))
           plan      (vec (:actions (:body plan-resp)))]
       (.set (bb pc) slot-world snapshot)
       (.set (bb pc) slot-plan plan)
       ;; deriva e seta TODOS os polos (incl. flags 'needs' e error/unknown?) a
       ;; partir do snapshot+plan, classificando com estatico + playbooks ja
       ;; aprendidos (Slice 2). O 3-arity ja cobre category/needs, conn/needs,
       ;; throttled e unknown -> nao ha mais doseq separado (e :unknown agora vai
       ;; p/ error/unknown?, nao needs-human?, p/ habilitar learn-playbook).
       (set-conditions! pc (snapshot->conditions
                            snapshot plan
                            #(classify-error* @playbook-registry %)))))))

;; NOTA DE MODELAGEM GOAP (correcao C-1 + planner real):
;; - rerun? = true: o OODA loop executa 1 action por tick e re-planeja; com
;;   canRerun=false o Embabel injeta hasRun_<name>=FALSE e a action so rodaria
;;   1x -> nao converge com >1 pendencia.
;; - post = [c-consistent]: o A* so encadeia uma action ate o goal se ela
;;   DECLARAR um efeito que alcança o goal. Sem isso (post []) o planner nunca
;;   escolhe create/update/delete. O efeito declarado e OTIMISTA (uma execucao
;;   "tende a" consistencia); o valor REAL de c-consistent e re-derivado em
;;   runtime por rederive-pending! e so fica TRUE quando o slot-plan zera.
;; - cost baixo (0.1): mantem netValue (= goal.value - sum(cost)) POSITIVO ao
;;   longo de varias execucoes (revisao I-4).

;; GATE c-unblocked (revisao adversarial Slice 2): as 3 mirror-actions exigem
;; graph/unblocked? = TRUE (= nenhum erro bloqueante). Sem isso o A* escolhe
;; create-mirror em vez de ensure/reauth/retry/learn quando ha erro + pendencia,
;; refaz a operacao condenada em loop e estoura maxActions sem atingir goal. Cada
;; remedy (ensure-category/retry-throttled/learn-playbook) declara c-unblocked no
;; post (otimista) p/ o A* poder encadear remedy -> mirror -> consistent.
;; (c-unblocked SUBSUME category/ok? e conn/authorized? — gate unico e mais forte.)
(defn create-mirror
  "pre [has-pending-creates? graph/unblocked?]. Pega UM :create do slot-plan e
   executa. post [c-consistent] (otimista), rerun? true, cost 0.1."
  ^com.embabel.agent.core.support.AbstractAction []
  (a/action
   "create-mirror"
   [c-pending-creates c-unblocked]
   [c-consistent]
   0.1 true
   (fn [^ProcessContext pc] (exec-one-mirror! pc :create))))

(defn update-mirror
  "pre [has-pending-updates? graph/unblocked?]. post [c-consistent], rerun? true, cost 0.1."
  ^com.embabel.agent.core.support.AbstractAction []
  (a/action
   "update-mirror"
   [c-pending-updates c-unblocked]
   [c-consistent]
   0.1 true
   (fn [^ProcessContext pc] (exec-one-mirror! pc :update))))

(defn delete-orphan
  "pre [has-orphans? graph/unblocked?]. post [c-consistent], rerun? true, cost 0.1."
  ^com.embabel.agent.core.support.AbstractAction []
  (a/action
   "delete-orphan"
   [c-orphans c-unblocked]
   [c-consistent]
   0.1 true
   (fn [^ProcessContext pc] (exec-one-mirror! pc :delete))))

(defn ensure-category
  "pre [category/needs-fix?]. POST /cap/ensure-category. Sucesso -> category/ok?
   TRUE e limpa category/needs-fix?. cost 1.

   (revisao C3: a action e logicamente habilitada quando category/ok? e FALSE,
   mas como so modelamos polos positivos, a PRE e a flag positiva
   `category/needs-fix?`, setada por classify-error.)"
  ^com.embabel.agent.core.support.AbstractAction []
  (a/action
   "ensure-category"
   [c-category-needs]
   [c-category-ok c-unblocked]            ; c-unblocked otimista p/ encadear -> create-mirror
   0.1
   (fn [^ProcessContext pc]
     (bump-replans! pc)
     ;; I-5: o erro de categoria nao carrega :preset/:name. A categoria a criar
     ;; vem da COR do evento que falhou — pega o :color do :mirror da 1a action
     ;; de espelho no slot-plan p/ aquele destino; o agendas deriva preset+nome
     ;; da cor (nearest-color + preset-label). Fallback: target do erro.
     (let [snap   (gset pc slot-world {})
           cat-e  (first (filter #(= :category/missing
                                     (classify-error* @playbook-registry %))
                                 (:errors snap)))
           dest   (or (:target cat-e) (:account cat-e))
           plan   (vec (gset pc slot-plan []))
           mirror (some (fn [a] (when (= (:target-account a) dest) (:mirror a))) plan)
           color  (or (:color mirror) (:color cat-e))
           body   {:account dest :color color
                   :preset  (:preset cat-e)   ; geralmente nil -> agendas usa :color
                   :name    (:label cat-e)}
           {:keys [status body]}
           (a/http-post (str a/agendas-base-url "/api/cap/ensure-category") body)
           dom-err (:error body)]
       (if (and (>= status 200) (< status 300) (nil? dom-err))
         (do (.setCondition (bb pc) c-category-ok true)
             (.setCondition (bb pc) c-category-needs false)
             (refresh-derived! pc))         ; destrava + (se nada pendente) consistente
         (apply-error-pole! pc (or dom-err {:status status :message (str body)})))))))

(defn reauth-account
  "pre [conn/needs-reauth?]. POST /cap/reauth. cost 8.

   No Slice 1 reauth EXIGE humano: a chamada abre o navegador e provavelmente
   NAO resolve sozinha no mesmo ciclo. Caminho realista: setar needs-human?
   (-> report-blocking). So marca authorized? TRUE se o agendas devolver que a
   conexao ficou autorizada (improvavel sincronamente)."
  ^com.embabel.agent.core.support.AbstractAction []
  (a/action
   "reauth-account"
   [c-conn-needs-reauth]
   [c-needs-human]                       ; declara que leva a needs-human (-> report-blocking)
   0.1
   (fn [^ProcessContext pc]
     (bump-replans! pc)
     (let [snap  (gset pc slot-world {})
           auth-e (first (filter #(= :account/unauthorized (classify-error %))
                                 (:errors snap)))
           body  {:conn-id (or (:connection auth-e) (:target auth-e))}
           {:keys [status body]}
           (a/http-post (str a/agendas-base-url "/api/cap/reauth") body)
           authorized? (boolean (:authorized body))]
       (if (and (>= status 200) (< status 300) authorized?)
         (do (.setCondition (bb pc) c-conn-authorized true)
             (.setCondition (bb pc) c-conn-needs-reauth false))
         ;; exige humano: marca needs-human? p/ report-blocking terminar o loop
         (.setCondition (bb pc) c-needs-human true))))))

(defn retry-throttled
  "pre [provider/throttled?]. POST /cap/clear-errors. Limpa throttled?. cost 2."
  ^com.embabel.agent.core.support.AbstractAction []
  (a/action
   "retry-throttled"
   [c-throttled]
   [c-unblocked c-consistent]             ; destrava (-> mirror) e otimista consistente
   0.1
   (fn [^ProcessContext pc]
     (bump-replans! pc)
     (let [{:keys [status]}
           (a/http-post (str a/agendas-base-url "/api/cap/clear-errors") {})]
       (if (and (>= status 200) (< status 300))
         (do (.setCondition (bb pc) c-throttled false)
             (refresh-derived! pc))         ; destrava + (se nada pendente) consistente
         (.setCondition (bb pc) c-needs-human true))))))

(defn learn-playbook
  "(Slice 2 — playbook EDN hot-load, PRD §6.5) pre [error/unknown?]. Para CADA
   erro que o classificador (estatico + playbooks ja aprendidos) ainda marca como
   :unknown, pede ao LLM um playbook EDN, valida via Malli e hot-load no
   playbook-registry (learn-from-text). Depois RE-CLASSIFICA os erros com o
   registry atualizado e seta os polos das classes RESOLVIDAS, habilitando a
   action corretiva ja existente (ensure-category / reauth-account /
   retry-throttled) no proximo replan — tudo sem restart.

   SEMPRE limpa error/unknown? (sucesso -> polo conhecido; fracasso/parcial ->
   needs-human?) para NAO reentrar (a pre nao volta a valer -> sem loop).
   post [c-consistent] (otimista, como as mirror-actions); o valor real e
   re-derivado em runtime. cost 0.1, rerun? true."
  ^com.embabel.agent.core.support.AbstractAction []
  (a/llm-action*
   "learn-playbook"
   [c-unknown]
   [c-unblocked c-consistent]             ; otimista; valor real re-derivado abaixo
   0.1 true
   (fn [oc ^ProcessContext pc]
     (bump-replans! pc)
     (let [snap    (gset pc slot-world {})
           reg0    @playbook-registry
           unknown (vec (filter #(= :unknown (classify-error* reg0 %))
                                (:errors snap)))]
       (if (empty? unknown)
         ;; corrida: nada desconhecido sobrou (resolvido por playbook anterior)
         (.setCondition (bb pc) c-unknown false)
         (let [learned (doall (keep #(on-unknown-error oc %) unknown))
               reg     @playbook-registry
               poles   (mapv #(classify-error* reg %) unknown)]
           ;; registra ids aprendidos p/ o relatorio (slot-playbooks)
           (when (seq learned)
             (let [ids (mapv (comp str :id) learned)]
               (.set (bb pc) slot-playbooks
                     (vec (distinct (concat (vec (gset pc slot-playbooks [])) ids))))))
           ;; aplica o polo de cada classe RESOLVIDA + registra a classe
           (doseq [k poles]
             (when (not= k :unknown) (record-class! pc k))
             (case k
               :category/missing (do (.setCondition (bb pc) c-category-ok false)
                                     (.setCondition (bb pc) c-category-needs true))
               (:account/unauthorized :scope/insufficient)
                                 (do (.setCondition (bb pc) c-conn-authorized false)
                                     (.setCondition (bb pc) c-conn-needs-reauth true))
               :provider/throttled (.setCondition (bb pc) c-throttled true)
               :unknown nil))
           ;; limpa error/unknown? SEMPRE; se sobrou desconhecido sem playbook,
           ;; cai p/ needs-human? (-> report-blocking encerra sem loop).
           (.setCondition (bb pc) c-unknown false)
           (when (some #(= :unknown %) poles)
             (record-class! pc :unknown)
             (.setCondition (bb pc) c-needs-human true))))
       ;; re-deriva unblocked + consistent dos polos resultantes (ambos os ramos):
       ;; resolveu p/ throttled/categoria/auth -> ainda bloqueado (remedy proxima);
       ;; falhou -> needs-human bloqueia; corrida vazia -> pode destravar.
       (refresh-derived! pc)))))

(defn report-blocking
  "pre [needs-human?]. post [reported-blocking?]. cost 0. Desistencia controlada
   (PRD §6.3): satisfaz o GOAL secundario quando ha causa que exige humano
   (reauth interativo, erro desconhecido) OU o teto de replans estourou."
  ^com.embabel.agent.core.support.AbstractAction []
  (a/action
   "report-blocking"
   [c-needs-human]
   [c-reported-blocking]
   0.0
   (fn [^ProcessContext pc]
     (.set (bb pc) slot-result
           {:blocking?        true
            :classifications  (vec (gset pc slot-classifications []))
            :executed         (vec (gset pc slot-executed []))
            :playbooks        (vec (gset pc slot-playbooks []))
            :replans          (long (gset pc slot-replans 0))})
     (.setCondition (bb pc) c-reported-blocking true))))

;; =========================================================================
;; GOALS — PRD §6.3
;; =========================================================================

(defn reconcile-agent
  ^Agent []
  (let [actions [(load-plan) (create-mirror) (update-mirror) (delete-orphan)
                 (ensure-category) (reauth-account) (retry-throttled)
                 (learn-playbook) (report-blocking)]
        consistent (a/goal "graph-consistent"
                           "Grafo de espelhos consistente (sem pendencias nem erro bloqueante)"
                           [c-consistent] 1.0)
        blocking   (a/goal "reported-blocking"
                           "Desistencia controlada: reportou bloqueio que exige humano"
                           [c-reported-blocking] 0.3)]
    (Agent. "reconcile-agent"
            "embabel-clj"
            "0.1.0"
            "Slice 1: reconciliador auto-curavel do grafo de espelhos (Ideia 1, sem playbook)"
            #{consistent blocking}
            (vec actions))))
