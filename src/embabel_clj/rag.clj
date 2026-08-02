(ns embabel-clj.rag
  "RAG como dado: qualquer coisa que responda uma busca vira um `RagService`
   do Embabel — em duas linhas.

   O `RagService` tem exatamente **dois membros próprios**:

     interface RagService : Described, HasInfoString {
         val name: String
         fun search(ragRequest: RagRequest): RagResponse
     }

   Ou seja, é o mesmo padrão de `events`/`interceptors`/`guardrails`:
   **interface pequena do Kotlin = mapa de fns no Clojure**. A sua fn recebe a
   busca como MAPA e devolve uma sequência de resultados como MAPAS; esta ns
   monta os objetos do framework.

     (require '[embabel-clj.rag :as rag])

     (def chronicle-rag
       (rag/rag-service
        {:name        \"chronicle\"
         :description \"a história do DICE, consultada por texto\"
         :search      (fn [{:keys [query top-k threshold]}]
                        (->> (minha-busca query top-k)
                             (map (fn [{:keys [id texto score]}]
                                    {:id id :text texto :score score}))))}))

     (rag/search chronicle-rag \"quem pagou a fatura?\" {:top-k 3})

   O ponto não é \"implementar RAG\" — é que o acervo que você **já tem** (um
   índice Datalevin, o dice-chronicle, um Postgres, um mapa em memória) passa a
   ser fonte de RAG de qualquer agente Embabel sem escrever uma classe.

   ## Dependência opt-in

   Esta ns importa `com.embabel.agent.rag.*`, que **não vem no
   embabel-agent-starter**. Adicione ao seu projeto:

     com.embabel.agent/embabel-agent-rag-pipeline {:mvn/version \"1.0.0\"}

   A lib não a declara como dep dura (decisão travada: deps mínimas) — a ns só
   carrega se você trouxe o módulo.

   ## Duas pegadinhas de interop verificadas aqui

   1. **`ChunkImpl` é package-private.** O javap mostra o construtor público,
      mas a CLASSE não é — chamar `(ChunkImpl. ...)` do Clojure dá
      IllegalAccessError. A fábrica pública é `Chunk/create`.
   2. **`RagRequest`/`RagResponse` têm parâmetros não-nulos COM default do
      Kotlin.** Passar `nil` explode (\"Parameter specified as non-null is
      null\"). O jeito é o construtor sintético com MÁSCARA de bits, onde o bit
      N liga \"use o default do parâmetro N\" — ver `->request` e `->response`."
  (:import [com.embabel.agent.rag.model Chunk Retrievable]
           [com.embabel.agent.rag.service RagRequest RagResponse RagService]
           [com.embabel.common.core.types SimpleSimilaritySearchResult]
           [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Construção: mapas -> objetos do framework
;; ---------------------------------------------------------------------------

(defn ->request
  "RagRequest a partir de dados. `:top-k` e `:threshold` opcionais.

   Máscara de defaults do Kotlin: hints(3)=8, contentElementSearch(4)=16,
   entitySearch(5)=32, timestamp(6)=64 → 120. Os três primeiros parâmetros
   (query, threshold, topK) são sempre passados."
  ^RagRequest [query {:keys [top-k threshold] :or {top-k 5 threshold 0.7}}]
  (RagRequest. query (double threshold) (int top-k) nil nil nil nil (int 120) nil))

(defn ->chunk
  "Um `Retrievable` (Chunk) a partir de dados.

   `Chunk/create` é a fábrica pública — `ChunkImpl` é package-private. E a
   ordem dos parâmetros dela é `(text, parentId, metadata, id, urtext)`, NÃO
   `(id, text)`: chamar errado não dá erro, só põe o id dentro do texto — o
   tipo de bug que passa no compilador e falha no teste. Aqui a ordem é a
   Clojure (id primeiro) e a tradução acontece dentro."
  (^Chunk [id text] (->chunk id text nil))
  (^Chunk [id text metadata]
   (Chunk/create text "" (or metadata {}) (or id (str (random-uuid))))))

(defn- ->result
  "Um resultado: aceita o mapa {:id :text :score :metadata}, ou um par
   [retrievable score], ou um SimilarityResult já pronto."
  [x]
  (cond
    (instance? com.embabel.common.core.types.SimilarityResult x) x
    (map? x)    (SimpleSimilaritySearchResult.
                 (if (instance? Retrievable (:match x))
                   (:match x)
                   (->chunk (:id x) (:text x) (:metadata x)))
                 (double (or (:score x) 1.0)))
    (vector? x) (SimpleSimilaritySearchResult. (first x) (double (second x)))
    :else       (throw (ex-info "embabel-clj/rag: resultado em forma desconhecida"
                                {:value x :type (type x)}))))

(defn ->response
  "RagResponse a partir dos resultados da sua fn de busca.

   Máscara de defaults: enhancement(3)=8, qualityMetrics(4)=16,
   timestamp(5)=32 → 56."
  ^RagResponse [^RagRequest request service-name results]
  (RagResponse. request service-name (mapv ->result results)
                nil nil nil (int 56) nil))

;; ---------------------------------------------------------------------------
;; Leitura: objetos do framework -> mapas
;; ---------------------------------------------------------------------------

(defn request->map
  "RagRequest -> mapa, para a sua fn de busca não tocar em interop."
  [^RagRequest r]
  {:query     (.getQuery r)
   :top-k     (.getTopK r)
   :threshold (.getSimilarityThreshold r)
   :hints     (vec (.getHints r))
   :raw       r})

(defn response->map
  "RagResponse -> mapa. `:results` são {:text :score :id :match}."
  [^RagResponse resp]
  {:service (.getService resp)
   :query   (.getQuery (.getRequest resp))
   :results (mapv (fn [r]
                    (let [m (.getMatch r)]
                      {:score (.getScore r)
                       :id    (try (.getId m) (catch Throwable _ nil))
                       :text  (try (.getText m) (catch Throwable _ (str m)))
                       :match m}))
                  (.getResults resp))
   :raw     resp})

;; ---------------------------------------------------------------------------
;; O serviço
;; ---------------------------------------------------------------------------

(defn rag-service
  "RagService a partir de um mapa:

     {:name        \"chronicle\"            ; obrigatório, único na aplicação
      :description \"…\"                    ; opcional (default: o name)
      :search      (fn [req-map] results)}  ; obrigatório

   `req-map` é o de `request->map`. O retorno pode ser qualquer sequência de:
   mapas `{:id :text :score :metadata}`, pares `[retrievable score]`, ou
   `SimilarityResult` prontos. Devolver nil vale como nenhum resultado."
  ^RagService
  [{:keys [name description search]}]
  (assert (string? name)  "embabel-clj/rag: :name é obrigatório")
  (assert (ifn? search)   "embabel-clj/rag: :search é obrigatório")
  (reify RagService
    (getName [_] name)
    (getDescription [_] (or description name))
    (search [_ req]
      (->response req name (or (search (request->map req)) [])))
    (infoString [_ _verbose _indent]
      (str "RagService(" name ")"))))

(defn search
  "Chama um RagService com dados e devolve o mapa da resposta.

     (rag/search svc \"quem pagou?\" {:top-k 3 :threshold 0.8})"
  ([^RagService svc query] (search svc query nil))
  ([^RagService svc query opts]
   (response->map (.search svc (->request query (or opts {}))))))
