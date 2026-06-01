(ns embabel-clj.agents
  (:import [com.embabel.agent.core.support AbstractAction]
           [com.embabel.agent.core ProcessContext ActionStatus ActionStatusCode
                                    ActionQos Agent Goal Export]
           [com.embabel.agent.api.common TransformationActionContext]
           [java.time Duration]))

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

(defn action
  ^AbstractAction [name pre post f]
  (proxy [AbstractAction]
         [name
          name
          (vec pre)
          (vec post)
          (const-fn 0.0)
          (const-fn 0.0)
          #{}
          #{}
          #{}
          false
          false
          false
          (default-qos)]
    (execute [^ProcessContext pc]
      (f pc)
      (ActionStatus. (Duration/ofMillis 0) ActionStatusCode/SUCCEEDED))
    (referencedInputProperties [_variable]
      #{})))

(defn llm-action
  ^AbstractAction [name pre post f]
  (proxy [AbstractAction]
         [name name (vec pre) (vec post)
          (const-fn 0.0) (const-fn 0.0)
          #{} #{} #{}
          false false false
          (default-qos)]
    (execute [^ProcessContext pc]
      (let [oc (TransformationActionContext. nil pc this Object Object)]
        (f oc))
      (ActionStatus. (Duration/ofMillis 0) ActionStatusCode/SUCCEEDED))
    (referencedInputProperties [_variable]
      #{})))

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
