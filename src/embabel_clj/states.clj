(ns embabel-clj.states
  "States: fatiar um fluxo em fases, escondendo o que não pertence à fase atual.

   No modelo ANOTADO do Embabel, uma classe marcada com `@State` que é devolvida
   por uma action faz o framework **esconder os outros objetos de estado** do
   blackboard — escopo sem limpar tudo — e emitir um `StateTransitionEvent`.

   Duas descobertas que definem esta ns:

   1. **A anotação funciona num `defrecord`**, via metadata no nome do tipo —
      a mesma técnica da boot class da lib:

        (defrecord ^{State true} Triagem [caso])
        (.isAnnotationPresent Triagem State)  ; => true

      Ou seja: um record Clojure é um state type legítimo aos olhos do
      `isStateType` do próprio framework, sem casca Java.

   2. **Mas o comportamento não vem de graça aqui.** O esconder-os-outros vive
      no `MultiTransformationAction`, a Action do modelo anotado. O
      `embabel-clj` constrói a própria `AbstractAction`, então nenhuma action
      desta lib passa por lá. A transição é EXPLÍCITA:

        (defrecord ^{State true} Triagem [caso])
        (defrecord ^{State true} Analise  [caso])

        (defn abrir [{:keys [pc]}]
          (states/enter! pc (->Triagem \"c-1\")))

   Isso é mais Clojure do que mágica de anotação — a transição de estado é uma
   chamada de função, não um efeito colateral de um valor de retorno — e é
   compatível com estados escritos em Kotlin/Java pelo usuário, porque o teste
   de \"é estado?\" é o mesmo do framework.

   ## ⚠️ `hide` esconde por VALOR, não por identidade

   Medido (`states-test/hide-esconde-por-valor-nao-por-identidade`): o
   `InMemoryBlackboard` guarda os escondidos num `Set`, então a checagem é por
   `equals`. Como `defrecord` tem igualdade por valor, **esconder um estado
   esconde qualquer estado IGUAL — inclusive um adicionado depois**:

     (bb/hide! bb (->Triagem \"c-1\"))
     (bb/put! ...)                      ; um novo (->Triagem \"c-1\")
     (states/current bb)                ; => nil, o novo já nasceu invisível

   Não é peculiaridade de Clojure: uma `data class` do Kotlin tem o mesmo
   comportamento. Duas consequências práticas:

   - **Dê identidade aos seus estados** (um id, um instante) se a mesma fase
     puder ser reentrada com os mesmos dados.
   - **Não declare como `:inputs` tipado um estado que você vai esconder.**
     Esconder o input reabre a pré-condição, o planner replaneja para
     reproduzi-lo e o fluxo entra em ciclo. Gate a action seguinte por
     CONDIÇÃO (`:pre`) e leia o estado com `current`."
  (:require [embabel-clj.blackboard :as bb])
  (:import [com.embabel.agent.api.annotation State]
           [com.embabel.agent.api.event StateTransitionEvent]
           [com.embabel.agent.core Blackboard ProcessContext]))

(defn state-type?
  "A classe é um state type? Espelha o `isStateType` do framework (que é
   `internal` no Kotlin e não dá para chamar): a própria classe, qualquer
   superclasse ou qualquer interface implementada carregando `@State`."
  [^Class c]
  (boolean
   (when (and c (not= Object c))
     (or (.isAnnotationPresent c State)
         (some state-type? (seq (.getInterfaces c)))
         (state-type? (.getSuperclass c))))))

(defn state?
  "O objeto é um estado?"
  [x]
  (and (some? x) (state-type? (class x))))

(defn states
  "Os objetos de estado VISÍVEIS no blackboard, na ordem em que entraram.
   Estados escondidos por uma transição não aparecem — é essa a definição de
   escopo aqui."
  [src]
  (filterv state? (.getObjects (bb/->blackboard src))))

(defn current
  "O estado atual (o último visível), ou nil."
  [src]
  (last (states src)))

(defn enter!
  "Transiciona para `state`: esconde os demais estados visíveis, põe o novo no
   blackboard e emite `StateTransitionEvent`. Devolve `state`.

   Esconder (`hide`) não é apagar: o objeto continua no blackboard e no log, só
   deixa de ser visto pelo planner e pelas leituras — a diferença exata entre
   escopo de estado e `:clear-blackboard? true`, que zera tudo.

   `src` é o ctx da action, o ProcessContext ou o AgentProcess. O evento só é
   emitido quando dá para chegar num ProcessContext (é dele que sai o
   `onProcessEvent`); com um blackboard cru, a transição acontece em silêncio.

   O estado entra por BINDING NOMEADO (`:binding`, default `\"it\"`), não por
   `addObject`. Medido: `addObject` só anexa em `_entries`, e a determinação de
   world state do planner lê o binding — um estado só adicionado é invisível
   para `:inputs`/`:outputs` tipados, e a action seguinte roda duas vezes. É o
   mesmo caminho que o `MultiTransformationAction` usa para a saída de uma
   action (`agentProcess[outputVarName] = output`)."
  ([src state] (enter! src state nil))
  ([src state {:keys [binding] :or {binding "it"}}]
  (assert (state? state)
          (str "embabel-clj/states: " (some-> state class .getName)
               " não é um state type — anote o defrecord: (defrecord ^{State true} X [...])"))
  (let [^Blackboard bb  (bb/->blackboard src)
        ;; `not=` e não `identical?`: como o `hide` compara por equals, esconder
        ;; um estado IGUAL ao que está entrando tornaria o novo invisível na
        ;; hora. Reentrar a mesma fase com os mesmos dados vira no-op de escopo.
        anteriores      (remove #(= % state) (states bb))
        previous        (last anteriores)]
    (doseq [s anteriores] (.hide bb s))
    (bb/put! bb binding state)
    (when-let [^ProcessContext pc (or (when (instance? ProcessContext src) src)
                                      (:pc src))]
      (.onProcessEvent pc (StateTransitionEvent. (.getAgentProcess pc) state previous)))
    state)))
