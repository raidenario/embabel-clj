(ns embabel-clj.reconcile-test
  "Testes das FUNCOES PURAS do reconciliador (PRD §6.2 ponto 2):
   `classify-error` e `snapshot->conditions`. Sem Spring/Embabel rodando — so
   precisam das classes no classpath (o :import da ns reconcile resolve via deps).

   Rodar:
     clojure -M:test
   (ou via REPL: (require 'embabel-clj.reconcile-test :reload)
                 (clojure.test/run-tests 'embabel-clj.reconcile-test))"
  (:require [clojure.test :refer [deftest is testing run-tests use-fixtures]]
            [embabel-clj.reconcile :as r]))

;; O playbook-registry e um defonce atom global (hot-load persiste entre runs).
;; Nos testes isolamos cada deftest com um registry limpo.
(use-fixtures :each (fn [t]
                      (reset! r/playbook-registry {})
                      (try (t) (finally (reset! r/playbook-registry {})))))

;; ---------------------------------------------------------------------------
;; classify-error  (PURO)
;; ---------------------------------------------------------------------------
(deftest classify-error-test
  (testing "400 microsoft com categoria -> :category/missing"
    (is (= :category/missing
           (r/classify-error {:status 400 :provider "microsoft"
                              :message "categoria 'Sync · Roxo' ausente"})))
    (is (= :category/missing
           (r/classify-error {:status 400 :provider :microsoft
                              :message "category not found"}))))

  (testing "400 microsoft SEM mencao a categoria -> :unknown (nao casa)"
    (is (= :unknown
           (r/classify-error {:status 400 :provider "microsoft"
                              :message "bad request generico"}))))

  (testing "400 google com categoria -> :unknown (so microsoft tem categoria)"
    (is (= :unknown
           (r/classify-error {:status 400 :provider "google"
                              :message "categoria ausente"}))))

  (testing "401 e 403 -> :account/unauthorized (qualquer provider)"
    (is (= :account/unauthorized (r/classify-error {:status 401})))
    (is (= :account/unauthorized (r/classify-error {:status 403 :provider "google"})))
    (is (= :account/unauthorized (r/classify-error {:status 403 :provider "microsoft"}))))

  (testing "429 -> :provider/throttled"
    (is (= :provider/throttled (r/classify-error {:status 429 :provider "google"}))))

  (testing "sem status, mensagem fixa de autorizacao (sync.clj:93 / I7) -> :account/unauthorized"
    (is (= :account/unauthorized
           (r/classify-error {:status nil :message "conta de destino nao autorizada"}))))

  (testing "desconhecidos -> :unknown"
    (is (= :unknown (r/classify-error {:status 500})))
    (is (= :unknown (r/classify-error {:status 503 :provider "microsoft"})))
    (is (= :unknown (r/classify-error {})))
    (is (= :unknown (r/classify-error {:status nil :message "timeout de rede"}))))

  (testing "shape LEGADO do state.edn (C-2): :error string sem :status/:provider"
    ;; status parseado de 'clj-http: status NNN'; :error como fallback de :message
    (is (= :account/unauthorized
           (r/classify-error {:op "update" :error "clj-http: status 403" :at 1})))
    (is (= :provider/throttled
           (r/classify-error {:error "clj-http: status 429"})))
    ;; 400 legado sem provider/detalhe NAO da p/ saber que e categoria -> :unknown
    (is (= :unknown
           (r/classify-error {:op "create" :error "clj-http: status 400" :at 1})))))

;; ---------------------------------------------------------------------------
;; snapshot->conditions  (PURO) — so polos POSITIVOS (revisao C3/Q4)
;; ---------------------------------------------------------------------------
(deftest snapshot->conditions-pending-test
  (testing "creates + updates pendentes, sem erros -> polos OK true, consistent false"
    (let [c (r/snapshot->conditions {:errors []}
                                    [{:op :create} {:op :update}])]
      (is (true?  (get c r/c-pending-creates)))
      (is (true?  (get c r/c-pending-updates)))
      (is (false? (get c r/c-orphans)))
      (is (true?  (get c r/c-category-ok)))
      (is (false? (get c r/c-category-needs)))
      (is (true?  (get c r/c-conn-authorized)))
      (is (false? (get c r/c-conn-needs-reauth)))
      (is (false? (get c r/c-throttled)))
      (is (true?  (get c r/c-unblocked)))   ; pendente mas sem erro -> mirrors liberadas
      (is (false? (get c r/c-consistent)))))

  (testing ":op pode vir como STRING (Jackson) e e normalizado"
    (let [c (r/snapshot->conditions {:errors []} [{:op "create"} {:op "delete"}])]
      (is (true? (get c r/c-pending-creates)))
      (is (true? (get c r/c-orphans)))
      (is (false? (get c r/c-pending-updates))))))

(deftest snapshot->conditions-consistent-test
  (testing "sem pendencias e sem erros -> graph/consistent? + graph/unblocked? TRUE"
    (let [c (r/snapshot->conditions {:errors []} [])]
      (is (false? (get c r/c-pending-creates)))
      (is (false? (get c r/c-pending-updates)))
      (is (false? (get c r/c-orphans)))
      (is (true?  (get c r/c-unblocked)))
      (is (true?  (get c r/c-consistent)))))

  (testing "sem pendencias MAS com erro bloqueante -> NAO consistente NEM unblocked"
    (let [c (r/snapshot->conditions
             {:errors [{:status 403 :provider "google"}]} [])]
      (is (false? (get c r/c-consistent)))
      (is (false? (get c r/c-unblocked)))
      (is (false? (get c r/c-conn-authorized)))
      (is (true?  (get c r/c-conn-needs-reauth))))))

(deftest snapshot->conditions-error-poles-test
  (testing "erro :category/missing derruba category/ok? e liga category/needs-fix?"
    (let [c (r/snapshot->conditions
             {:errors [{:status 400 :provider "microsoft"
                        :message "categoria ausente"}]}
             [{:op "create"}])]
      (is (false? (get c r/c-category-ok)))
      (is (true?  (get c r/c-category-needs)))
      (is (false? (get c r/c-consistent)))
      ;; auth nao foi tocado por esse erro
      (is (true?  (get c r/c-conn-authorized)))))

  (testing "erro 429 -> provider/throttled? true e nao consistente"
    (let [c (r/snapshot->conditions
             {:errors [{:status 429 :provider "google"}]} [])]
      (is (true?  (get c r/c-throttled)))
      (is (false? (get c r/c-consistent)))))

  (testing "cenario do trace §6.4: 400 categoria (msft) + 403 (google)"
    (let [c (r/snapshot->conditions
             {:errors [{:status 400 :provider "microsoft" :message "categoria ausente"}
                       {:status 403 :provider "google"}]}
             [{:op "create"} {:op "update"}])]
      (is (true?  (get c r/c-pending-creates)))
      (is (true?  (get c r/c-pending-updates)))
      (is (false? (get c r/c-category-ok)))
      (is (true?  (get c r/c-category-needs)))
      (is (false? (get c r/c-conn-authorized)))
      (is (true?  (get c r/c-conn-needs-reauth)))
      (is (false? (get c r/c-consistent))))))

;; ===========================================================================
;; SLICE 2 — PLAYBOOK EDN HOT-LOAD (PRD §6.5)  — funcoes PURAS
;; ===========================================================================

(def ^:private valid-pb-edn
  "{:id :svc-unavailable-backoff
    :matches {:status 503 :provider :google}
    :classify-as :provider/throttled
    :remedy-action \"retry-throttled\"
    :backoff-millis 3000}")

(deftest parse-playbook-test
  (testing "EDN simples -> mapa normalizado"
    (let [pb (r/parse-playbook valid-pb-edn)]
      (is (= :svc-unavailable-backoff (:id pb)))
      (is (= 503 (get-in pb [:matches :status])))
      (is (= :google (get-in pb [:matches :provider])))
      (is (= :provider/throttled (:classify-as pb)))
      (is (= "retry-throttled" (:remedy-action pb)))))

  (testing "tira cercas markdown ```edn ... ```"
    (let [pb (r/parse-playbook (str "```edn\n" valid-pb-edn "\n```"))]
      (is (= :provider/throttled (:classify-as pb)))))

  (testing "DESCARTA chaves extras do LLM (no topo e em :matches) antes de validar"
    (let [pb (r/parse-playbook
              "{:id :x :reason \"alucinacao\" :score 0.9
                :matches {:status 503 :provider :google :foo \"bar\"}
                :classify-as :provider/throttled :remedy-action \"retry-throttled\"}")]
      (is (nil? (:reason pb)))
      (is (nil? (:score pb)))
      (is (nil? (get-in pb [:matches :foo])))
      (is (some? (r/validate-playbook pb)) "playbook limpo passa na validacao :closed")))

  (testing "coage tipos string (status, provider, id, classify-as)"
    (let [pb (r/parse-playbook
              "{:id \"strc\" :matches {:status \"503\" :provider \"google\"}
                :classify-as \"provider/throttled\" :remedy-action \"retry-throttled\"}")]
      (is (= :strc (:id pb)))
      (is (= 503 (get-in pb [:matches :status])))
      (is (= :google (get-in pb [:matches :provider])))
      (is (= :provider/throttled (:classify-as pb)))
      (is (some? (r/validate-playbook pb)))))

  (testing "tolera chave/valor SEM ':' (LLM real omitiu o colon do :id) -> simbolo"
    ;; saida observada do gpt-4o-mini: `{id :x ...}` (id como simbolo, nao keyword)
    (let [pb (r/parse-playbook
              "{id :svc-unavailable-backoff
                :matches {:status 503 :provider :google :message-pattern \"Service Unavailable\"}
                :classify-as :provider/throttled :remedy-action \"retry-throttled\"}")]
      (is (= :svc-unavailable-backoff (:id pb)))
      (is (= :provider/throttled (:classify-as pb)))
      (is (some? (r/validate-playbook pb)) "playbook com chave-simbolo normaliza e valida"))
    ;; classify-as como SIMBOLO (sem ':') tambem coage
    (let [pb (r/parse-playbook
              "{:id :x :matches {:status 503 :provider :google}
                :classify-as provider/throttled :remedy-action \"retry-throttled\"}")]
      (is (= :provider/throttled (:classify-as pb)))
      (is (some? (r/validate-playbook pb)))))

  (testing "lixo / nao-mapa -> nil"
    (is (nil? (r/parse-playbook "isto nao e edn {{{")))
    (is (nil? (r/parse-playbook "[:not :a :map]")))
    (is (nil? (r/parse-playbook nil)))))

(deftest validate-playbook-test
  (testing "playbook valido passa"
    (is (some? (r/validate-playbook (r/parse-playbook valid-pb-edn)))))

  (testing "REJEITA remedy-action fora do enum (§12 done-when)"
    (is (nil? (r/validate-playbook
               (r/parse-playbook
                "{:id :evil :matches {:status 503 :provider :google}
                  :classify-as :provider/throttled :remedy-action \"rm -rf /\"}")))))

  (testing "REJEITA classify-as inutil (:unknown nao limpa o bloqueio)"
    (is (nil? (r/validate-playbook
               (r/parse-playbook
                "{:id :u :matches {:status 500 :provider :google}
                  :classify-as :unknown :remedy-action \"retry-throttled\"}")))))

  (testing "REJEITA status fora do enum de status"
    (is (nil? (r/validate-playbook
               (r/parse-playbook
                "{:id :s :matches {:status 418 :provider :google}
                  :classify-as :provider/throttled :remedy-action \"retry-throttled\"}")))))

  (testing "REJEITA provider fora do enum"
    (is (nil? (r/validate-playbook
               (r/parse-playbook
                "{:id :p :matches {:status 503 :provider :yahoo}
                  :classify-as :provider/throttled :remedy-action \"retry-throttled\"}")))))

  (testing "REJEITA chave extra no topo passada DIRETO (sem o strip do parse)"
    ;; validate sozinho e estrito (:closed); o strip vive no parse-playbook
    (is (nil? (r/validate-playbook
               {:id :x :matches {:status 503 :provider :google}
                :classify-as :provider/throttled :remedy-action "retry-throttled"
                :reason "extra"})))))

(deftest playbook-match?-test
  (let [pb (r/parse-playbook valid-pb-edn)]
    (testing "status + provider batem (provider como string no erro)"
      (is (true? (r/playbook-match? pb {:status 503 :provider "google"})))
      (is (true? (r/playbook-match? pb {:status 503 :provider :google}))))
    (testing "provider diferente nao casa"
      (is (false? (r/playbook-match? pb {:status 503 :provider "microsoft"}))))
    (testing "status diferente nao casa"
      (is (false? (r/playbook-match? pb {:status 500 :provider "google"}))))
    (testing "status legado embutido na string e parseado"
      (is (true? (r/playbook-match? pb {:provider "google"
                                        :error "clj-http: status 503"})))))

  (testing "message-pattern (case-insensitive) filtra"
    (let [pb (r/parse-playbook
              "{:id :q :matches {:status 403 :provider :google :message-pattern \"quota\"}
                :classify-as :scope/insufficient :remedy-action \"reauth-account\"}")]
      (is (true?  (r/playbook-match? pb {:status 403 :provider "google"
                                         :message "Daily Quota Exceeded"})))
      (is (false? (r/playbook-match? pb {:status 403 :provider "google"
                                         :message "permissao negada"})))))

  (testing "chars especiais no message-pattern sao SUBSTRING literal (sem ReDoS)"
    ;; '(.*a){25}' seria catastrofico como regex; como substring nao explode
    (let [pb (r/parse-playbook
              "{:id :badrx :matches {:status 503 :provider :google :message-pattern \"(.*a){25}\"}
                :classify-as :provider/throttled :remedy-action \"retry-throttled\"}")]
      (is (true?  (r/playbook-match? pb {:status 503 :provider "google"
                                         :message "literal (.*a){25} aqui"})))
      (is (false? (r/playbook-match? pb {:status 503 :provider "google"
                                         :message "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}))))))

(deftest classify-error*-test
  (testing "registry vazio: 503 google -> :unknown (estatico nao conhece)"
    (is (= :unknown (r/classify-error* {} {:status 503 :provider "google"}))))

  (testing "ESTATICO sempre vence; playbook so resolve :unknown"
    ;; um playbook que (erroneamente) tentaria reclassificar um 403 e IGNORADO
    (let [reg {:bad {:id :bad :matches {:status 403 :provider :google}
                     :classify-as :provider/throttled
                     :remedy-action "retry-throttled"}}]
      (is (= :account/unauthorized
             (r/classify-error* reg {:status 403 :provider "google"})))))

  (testing "playbook resolve um :unknown para classe acionavel"
    (let [reg {:svc {:id :svc :matches {:status 503 :provider :google}
                     :classify-as :provider/throttled
                     :remedy-action "retry-throttled"}}]
      (is (= :provider/throttled
             (r/classify-error* reg {:status 503 :provider "google"})))
      ;; provider diferente continua :unknown
      (is (= :unknown
             (r/classify-error* reg {:status 503 :provider "microsoft"}))))))

(deftest learn-from-text-hot-load-test
  (testing "texto valido do LLM -> swap! no registry + devolve o playbook"
    (is (empty? @r/playbook-registry))
    (let [pb (r/learn-from-text valid-pb-edn)]
      (is (some? pb))
      (is (= :provider/throttled (:classify-as pb)))
      (is (= pb (get @r/playbook-registry :svc-unavailable-backoff)))
      ;; e a classificacao efetiva passa a reconhecer o 503 SEM restart
      (is (= :provider/throttled
             (r/classify-error* @r/playbook-registry
                                {:status 503 :provider "google"})))))

  (testing "texto invalido (remedy fora do enum) -> nil e registry NAO muta"
    (reset! r/playbook-registry {})            ; isola deste mesmo deftest
    (is (nil? (r/learn-from-text
               "{:id :evil :matches {:status 503 :provider :google}
                 :classify-as :provider/throttled :remedy-action \"DROP TABLE\"}")))
    (is (empty? @r/playbook-registry) "playbook rejeitado nao entra no registry")))

(deftest snapshot->conditions-playbook-aware-test
  (testing "503 google: ANTES de aprender -> error/unknown? true, NAO consistente"
    (let [classify #(r/classify-error* @r/playbook-registry %)
          c (r/snapshot->conditions
             {:errors [{:status 503 :provider "google"}]}
             [{:op :create}]
             classify)]
      (is (true?  (get c r/c-unknown)))
      (is (false? (get c r/c-throttled)))
      (is (false? (get c r/c-unblocked)))   ; erro desconhecido bloqueia mirrors
      (is (false? (get c r/c-consistent)))))

  (testing "503 google: DEPOIS de aprender o playbook -> reclassifica p/ throttled"
    (r/learn-from-text valid-pb-edn)
    (let [classify #(r/classify-error* @r/playbook-registry %)
          c (r/snapshot->conditions
             {:errors [{:status 503 :provider "google"}]}
             []
             classify)]
      (is (false? (get c r/c-unknown)))
      (is (true?  (get c r/c-throttled)))
      ;; ainda bloqueado/nao consistente: throttled bloqueia ate retry-throttled rodar
      (is (false? (get c r/c-unblocked)))
      (is (false? (get c r/c-consistent))))))

(defn -main [& _]
  (let [{:keys [fail error] :as summary} (run-tests 'embabel-clj.reconcile-test)]
    (println summary)
    (System/exit (if (zero? (+ (or fail 0) (or error 0))) 0 1))))
