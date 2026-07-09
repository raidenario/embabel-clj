(ns embabel-clj.platform
  "Sobe (e derruba) o AgentPlatform do Embabel a partir de Clojure puro —
   nenhuma classe Java DE NINGUÉM (nem da biblioteca), nenhum pom Spring Boot.

   Por baixo: uma gen-class vazia anotada @SpringBootApplication via metadata
   (embabel-clj.boot-class), compilada SOB DEMANDA em runtime num diretório
   temporário e definida no DynamicClassLoader do Clojure — técnica provada no
   projeto fabulista. O autoconfigure do embabel-agent-starter materializa o
   bean AgentPlatform. Sem src-java, sem javac, sem passo de prep.

     (def sys (platform/start!
               {:properties {:embabel.agent.platform.models.openai.base-url
                             \"https://openrouter.ai/api/v1\"
                             :embabel.agent.platform.models.openai.api-key
                             (System/getenv \"OPENROUTER_APIKEY\")
                             :embabel.models.default-llm \"openai/gpt-4o-mini\"}}))
     (:platform sys) ; => AgentPlatform
     (platform/stop! sys)

   Notas de campo (herdadas dos projetos agendas/beautiful-linkedin):
   - Um provider LLM (ex.: embabel-agent-starter-openai) precisa estar no
     classpath E com api-key configurada, senão o boot falha reclamando da
     chave. Modelos não-OpenAI exigem override do models/openai-models.yml
     no SEU classpath (resources/models/openai-models.yml).
   - `:web :none` (default) não sobe Tomcat: a JVM segue viva enquanto o SEU
     processo viver (REPL, -main bloqueado). Empacotou como fat-jar e o main
     retorna? Use `await!`.
   - Sob fat-jar Spring Boot, faça require EAGER das suas ns na thread main
     (gotcha do TCCL); em classpath normal (deps.edn/REPL) isso não morde."
  (:require [clojure.java.io :as io])
  (:import [com.embabel.agent.core AgentPlatform]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent CountDownLatch]
           [org.springframework.boot WebApplicationType]
           [org.springframework.boot.builder SpringApplicationBuilder]
           [org.springframework.context ConfigurableApplicationContext]))

(defonce ^:private default-system (atom nil))
(defonce ^:private keep-alive-latch (atom nil))
(defonce ^:private boot-class* (atom nil))

(def ^:private boot-class-name "embabel_clj.EmbabelBoot")

(defn- compile-boot-class!
  "Compila embabel-clj.boot-class (a gen-class anotada) num diretório
   TEMPORÁRIO e define os bytes direto no DynamicClassLoader do Clojure —
   cujo cache estático torna a classe visível ao baseLoader/TCCL. Nada
   precisa estar pré-compilado nem no classpath do consumidor."
  ^Class []
  (let [tmp (str (Files/createTempDirectory
                  "embabel-clj-boot" (make-array FileAttribute 0)))]
    (binding [*compile-path* tmp]
      (compile 'embabel-clj.boot-class))
    (let [f     (io/file tmp "embabel_clj" "EmbabelBoot.class")
          bytes (Files/readAllBytes (.toPath f))
          dcl   (clojure.lang.DynamicClassLoader. (clojure.lang.RT/baseLoader))]
      (.defineClass dcl boot-class-name bytes nil))))

(defn boot-class
  "A classe @SpringBootApplication da biblioteca, compilada sob demanda na
   primeira chamada (gen-class anotada via metadata — sem Java, sem javac).
   Idempotente; pública só para introspecção/testes."
  ^Class []
  (or @boot-class*
      (locking boot-class*
        (or @boot-class*
            (reset! boot-class*
                    (or (try (Class/forName boot-class-name)
                             (catch ClassNotFoundException _ nil))
                        (compile-boot-class!)))))))

(defn- prop-key ^String [k]
  (if (keyword? k)
    (if-let [ns* (namespace k)] (str ns* "/" (name k)) (name k))
    (str k)))

(def ^:private web-types
  {:none    WebApplicationType/NONE
   :servlet WebApplicationType/SERVLET
   :reactive WebApplicationType/REACTIVE})

(defn start!
  "Sobe o contexto Spring + AgentPlatform. Devolve o sistema
   {:context ConfigurableApplicationContext :platform AgentPlatform}
   (e o guarda como default para as arities-zero de platform/stop!).

   Opções:
   :properties  mapa de propriedades Spring/Embabel (chaves keyword ou string;
                keywords com pontos funcionam: :embabel.models.default-llm)
   :web         :none (default) | :servlet | :reactive
   :banner?     mostra o banner do Spring (default false)
   :sources     Classes @Configuration/@Component adicionais suas
   :args        args de linha de comando repassados ao Spring"
  ([] (start! {}))
  ([{:keys [properties web banner? sources args]
     :or   {web :none banner? false}}]
   ;; TCCL -> classloader do Clojure ANTES do run (lição do fabulista): a boot
   ;; class vive no DynamicClassLoader, e threads de pool do Spring herdam o
   ;; TCCL do chamador.
   (.setContextClassLoader (Thread/currentThread) (clojure.lang.RT/baseLoader))
   (let [props     (cond-> {}
                     (not banner?) (assoc "spring.main.banner-mode" "off")
                     true          (into (map (fn [[k v]] [(prop-key k) (str v)])
                                              (or properties {}))))
         classes   (into-array Class (cons (boot-class) (or sources [])))
         builder   (doto (SpringApplicationBuilder. classes)
                     (.web ^WebApplicationType (web-types web)))
         ;; Propriedades do usuário entram como ARGS (--k=v): precedência
         ;; MÁXIMA no Environment. builder.properties() seria defaultProperties
         ;; (precedência mínima) e perderia para defaults embutidos do
         ;; framework (ex.: embabel.models.default-llm).
         all-args  (concat (map (fn [[k v]] (str "--" k "=" v)) props)
                           (map str (or args [])))
         ctx       (.run builder (into-array String all-args))
         sys     {:context  ctx
                  :platform (.getBean ^ConfigurableApplicationContext ctx
                                      AgentPlatform)}]
     (reset! default-system sys)
     sys)))

(defn agent-platform
  "O AgentPlatform do sistema (ou do default, sem args)."
  (^AgentPlatform [] (agent-platform @default-system))
  (^AgentPlatform [sys]
   (or (:platform sys)
       (throw (ex-info "embabel-clj: nenhuma plataforma ativa — chame platform/start! antes."
                       {})))))

(defn stop!
  "Fecha o contexto Spring (e libera await!, se armado)."
  ([] (when-let [sys @default-system] (stop! sys)))
  ([sys]
   (when-let [^ConfigurableApplicationContext ctx (:context sys)]
     (.close ctx))
   (when-let [^CountDownLatch l @keep-alive-latch]
     (.countDown l)
     (reset! keep-alive-latch nil))
   (when (identical? sys @default-system)
     (reset! default-system nil))
   :stopped))

(defn await!
  "Bloqueia a thread atual até stop! (latch non-daemon manual). Só é preciso
   quando NADA mais segura a JVM (ex.: -main de fat-jar com :web :none)."
  []
  (let [l (or @keep-alive-latch
              (let [l (CountDownLatch. 1)] (reset! keep-alive-latch l) l))]
    (.await ^CountDownLatch l)))
