(ns embabel-clj.blackboard
  "Helpers data-friendly sobre o Blackboard do Embabel.

   Todas as funções aceitam como `src` qualquer um de:
     - o mapa ctx `{:pc ProcessContext, :oc OperationContext?}` que a
       biblioteca passa às suas actions/conditions;
     - um `Blackboard` (o `AgentProcess` É um Blackboard — leia slots do
       processo retornado por `run!` direto com estas fns);
     - um `ProcessContext` ou `OperationContext`.

   Chaves podem ser strings ou keywords; keywords namespaced viram o formato
   canônico do projeto: `:graph/consistent?` -> \"graph/consistent?\".
   (Nomes de CONDIÇÃO nunca podem conter `:` — o determiner do Embabel 0.4.x
   desviaria para o ramo de data-binding; a conversão de keyword nunca produz
   `:`, e o schema da lib rejeita strings com `:`.)

   Gotcha herdado do Embabel: `Blackboard.set` NPEia com null. `put!` guarda a
   sentinela `none` no lugar de nil e `fetch` a converte de volta para nil."
  (:import [com.embabel.agent.core Blackboard ProcessContext]
           [com.embabel.agent.api.common OperationContext]))

(def none
  "Sentinela para \"nil guardado\" (o Blackboard NPEia com null)."
  :embabel-clj/none)

(defn key->str
  "Chave de slot/condição como o Embabel a vê: string. Keywords preservam o
   namespace (`:co/domains` -> \"co/domains\")."
  ^String [k]
  (cond
    (string? k)  k
    (keyword? k) (if-let [ns* (namespace k)] (str ns* "/" (name k)) (name k))
    :else        (str k)))

(defn ->blackboard
  "Extrai o Blackboard de qualquer representação aceita (ver docstring da ns)."
  ^Blackboard [src]
  (cond
    (instance? Blackboard src)       src
    (instance? ProcessContext src)   (.getBlackboard ^ProcessContext src)
    (instance? OperationContext src) (.getBlackboard
                                      (.getProcessContext ^OperationContext src))
    (map? src)                       (->blackboard (or (:pc src) (:oc src)
                                                       (:blackboard src)))
    :else (throw (ex-info "Não sei extrair um Blackboard deste valor"
                          {:value src :type (type src)}))))

(defn fetch
  "Lê um slot de dados. `none` guardado volta como nil; ausente vira `default`."
  ([src k] (fetch src k nil))
  ([src k default]
   (let [v (.get (->blackboard src) (key->str k))]
     (cond
       (nil? v)   default
       (= v none) nil
       :else      v))))

(defn put!
  "Escreve um slot de dados. nil vira a sentinela `none` (o .set NPEia com
   null). Devolve `v`."
  [src k v]
  (.set (->blackboard src) (key->str k) (if (nil? v) none v))
  v)

(defn set-condition!
  "Seta UMA condição booleana nomeada (o que o planner GOAP enxerga)."
  [src k b]
  (.setCondition (->blackboard src) (key->str k) (boolean b))
  (boolean b))

(defn set-conditions!
  "Seta várias condições de uma vez: `(set-conditions! ctx {:graph/ok? true})`."
  [src m]
  (doseq [[k v] m] (set-condition! src k v))
  m)

(defn condition?
  "Lê uma condição booleana. Ausente = `default` (default false — espelha o
   determiner do Embabel, que coage ausente -> FALSE)."
  ([src k] (condition? src k false))
  ([src k default]
   (let [b (.getCondition (->blackboard src) (key->str k))]
     (if (nil? b) default (boolean b)))))

(defn conditions
  "Lê várias condições -> mapa {chave boolean} (chaves como você as passou)."
  [src ks]
  (into {} (map (fn [k] [k (condition? src k)])) ks))

(defn objects
  "Todos os objetos do blackboard (a visão \"tipada\" do Embabel)."
  [src]
  (vec (.getObjects (->blackboard src))))

;; Aliases curtos, continuidade com os helpers dos projetos agendas /
;; beautiful-linkedin (g / s! / c!).
(def g  "Alias de `fetch`."           fetch)
(def s! "Alias de `put!`."            put!)
(def c! "Alias de `set-conditions!`." set-conditions!)
