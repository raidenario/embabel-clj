(ns embabel-clj.rag-test
  "RagService como mapa de fns, contra as classes REAIS do módulo
   embabel-agent-rag-pipeline (opt-in — ver o alias :rag no deps.edn)."
  (:require [clojure.test :refer [deftest is testing]]
            [embabel-clj.rag :as rag])
  (:import [com.embabel.agent.rag.model Chunk Retrievable]
           [com.embabel.agent.rag.service RagRequest RagResponse RagService]))

(def acervo
  "Um 'acervo' qualquer — aqui um vetor, mas seria o Datalevin do chronicle."
  [{:id "f-1" :texto "a fatura 900 foi paga por Ana em março"}
   {:id "f-2" :texto "a fatura 901 está em aberto"}
   {:id "f-3" :texto "Ana mudou de endereço em abril"}])

(defn- busca-boba [{:keys [query top-k]}]
  (->> acervo
       (filter #(re-find (re-pattern (str "(?i)" query)) (:texto %)))
       (take top-k)
       (mapv (fn [{:keys [id texto]}] {:id id :text texto :score 0.9}))))

(def servico
  (rag/rag-service {:name        "acervo-teste"
                    :description "um vetor fingindo ser um índice"
                    :search      busca-boba}))

(deftest reify-de-duas-linhas-e-um-ragservice-de-verdade
  (testing "o objeto satisfaz a interface do framework"
    (is (instance? RagService servico))
    (is (= "acervo-teste" (.getName ^RagService servico)))
    (is (= "um vetor fingindo ser um índice" (.getDescription ^RagService servico))))
  (testing "e responde um RagResponse legítimo quando chamado pelo framework"
    (let [req  (rag/->request "Ana" {:top-k 5})
          resp (.search ^RagService servico req)]
      (is (instance? RagResponse resp))
      (is (= 2 (count (.getResults resp))))
      (is (every? #(instance? Retrievable (.getMatch %)) (.getResults resp)))
      (is (= "acervo-teste" (.getService resp))))))

(deftest a-fn-de-busca-so-ve-dados
  (let [visto (atom nil)
        svc   (rag/rag-service {:name "espiao"
                                :search (fn [m] (reset! visto m) [])})]
    (.search ^RagService svc (rag/->request "pergunta" {:top-k 3 :threshold 0.42}))
    (testing "a busca recebe MAPA, não RagRequest"
      (is (= "pergunta" (:query @visto)))
      (is (= 3 (:top-k @visto)))
      (is (= 0.42 (:threshold @visto)))
      (is (vector? (:hints @visto))))
    (testing "e o objeto original continua acessível"
      (is (instance? RagRequest (:raw @visto))))))

(deftest resultado-aceita-tres-formas
  (let [ret (rag/->chunk "x" "texto")
        svc (fn [rs] (rag/rag-service {:name "f" :search (constantly rs)}))]
    (testing "mapa"
      (is (= 1 (count (:results (rag/search (svc [{:id "a" :text "t" :score 0.5}]) "q"))))))
    (testing "par [retrievable score]"
      (let [r (first (:results (rag/search (svc [[ret 0.33]]) "q")))]
        (is (= 0.33 (:score r)))
        (is (= "texto" (:text r)))))
    (testing "SimilarityResult já pronto"
      (let [pronto (com.embabel.common.core.types.SimpleSimilaritySearchResult. ret 0.77)]
        (is (= 0.77 (:score (first (:results (rag/search (svc [pronto]) "q"))))))))
    (testing "nil vale como nenhum resultado"
      (is (= [] (:results (rag/search (svc nil) "q")))))
    (testing "forma desconhecida falha com erro nomeado"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"forma desconhecida"
                            (rag/search (svc [42]) "q"))))))

(deftest search-devolve-dados
  (let [r (rag/search servico "fatura" {:top-k 1})]
    (is (= "acervo-teste" (:service r)))
    (is (= "fatura" (:query r)))
    (is (= 1 (count (:results r))))
    (is (= "f-1" (:id (first (:results r)))))
    (is (= 0.9 (:score (first (:results r)))))))

(deftest ->chunk-usa-a-fabrica-publica
  (testing "ChunkImpl é package-private; Chunk/create é o caminho"
    (let [c (rag/->chunk "id-1" "conteúdo")]
      (is (instance? Chunk c))
      (is (instance? Retrievable c))
      (is (= "conteúdo" (.getText c))))))

(deftest defaults-do-kotlin-pela-mascara
  (testing "->request passa query/threshold/topK e deixa o Kotlin preencher o
            resto — passar nil explodiria com 'Parameter specified as non-null'"
    (let [r (rag/->request "q" nil)]
      (is (= 5 (.getTopK r)))
      (is (= 0.7 (.getSimilarityThreshold r)))
      (is (some? (.getContentElementSearch r)))
      (is (some? (.getTimestamp r))))))
