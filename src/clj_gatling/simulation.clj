(ns clj-gatling.simulation
  (:require [clj-gatling.httpkit :as http]
            [clj-gatling.simulation-runners :refer :all]
            [clj-gatling.schema :as schema]
            [clj-gatling.simulation-util :refer [weighted-scenarios
                                                 choose-runner
                                                 log-exception]]
            [schema.core :refer [check validate]]
            [clj-time.local :as local-time]
            [clojure.set :refer [rename-keys]]
            [clojure.core.async :as async :refer [go go-loop close! put! <!! alts! <! >!]]
            [clojure.stacktrace :as stacktrace]))

(set! *warn-on-reflection* true)

(defn- now [] (System/currentTimeMillis))

(defn asynchronize [f ctx]
  (let [parse-response (fn [result]
                         (if (vector? result)
                           {:result (first result) :end-time (now) :context (second result)}
                           {:result result :end-time (now) :context ctx}))]
    (go
      (try
        (let [result (f ctx)]
          (if (instance? clojure.core.async.impl.channels.ManyToManyChannel result)
            (parse-response (<! result))
            (parse-response result)))
        (catch Exception e
          (log-exception "/tmp/test.log" e)
          {:result false :end-time (now) :context ctx})))))

(defn async-function-with-timeout [step timeout sent-requests user-id original-context]
  (swap! sent-requests inc)
  (go
    (when-let [sleep-before (:sleep-before step)]
    (<! (async/timeout (sleep-before original-context))))
    (let [original-context-with-user (assoc original-context :user-id user-id)
          start (now)
          return {:name (:name step)
                  :id user-id
                  :start start
                  :context-before original-context-with-user}
          response (asynchronize (:request step) original-context-with-user)
          [{:keys [result end-time context]} c] (alts! [response (async/timeout timeout)])]
      (if (= c response)
        [(assoc return :end end-time
                       :result result
                       :context-after context) context]
        [(assoc return :end (now)
                       :return false
                       :context-after original-context-with-user)
         original-context-with-user]))))

(defn- response->result [scenario result]
  {:name (:name scenario)
   :id (:id (first result))
   :start (:start (first result))
   :end (:end (last result))
   :requests result})

(defn- run-scenario-once [{:keys [runner simulation-start] :as options} scenario user-id]
  (let [timeout (:timeout-in-ms options)
        sent-requests (:sent-requests options)
        result-channel (async/chan)
        skip-next-after-failure? (if (nil? (:skip-next-after-failure? scenario))
                                   true
                                   (:skip-next-after-failure? scenario))
        should-terminate? #(and (:allow-early-termination? scenario)
                                (not (continue-run? runner @sent-requests simulation-start)))
        request-failed? #(not (:result %))]
    (go-loop [steps (:steps scenario)
              context (or (merge (:context options) (:context scenario)) {})
              results []]
             (let [[result new-ctx] (<! (async-function-with-timeout (first steps)
                                                                     timeout
                                                                     sent-requests
                                                                     user-id
                                                                     context))]
               (if (or (should-terminate?)
                       (empty? (rest steps))
                       (and skip-next-after-failure?
                            (request-failed? result)))
                 (>! result-channel (conj results result))
                 (recur (rest steps) new-ctx (conj results result)))))
    result-channel))

(defn- run-scenario-constantly [options scenario user-id]
  (let [c (async/chan)
        runner (:runner options)
        simulation-start (:simulation-start options)
        sent-requests (:sent-requests options)]
    (go-loop []
             (let [result (<! (run-scenario-once options scenario user-id))]
               (>! c result)
               (if (continue-run? runner @sent-requests simulation-start)
                 (recur)
                 (close! c))))
    c))

(defn- print-scenario-info [scenario]
  (println "Running scenario" (:name scenario)
           "with concurrency" (count (:users scenario))))

(defn- convert-legacy-fn [request]
  (let [f (if-let [url (:http request)]
            (partial http/async-http-request url)
            (:fn request))
        c (async/chan)]
    (fn [ctx]
      (f (fn [result & [new-ctx]]
           (if new-ctx
             (put! c [result new-ctx])
             (put! c [result ctx])))
         ctx)
      c)))

(defn- convert-from-legacy [scenarios]
  (let [request->step (fn [request]
                        (-> request
                            (assoc :request (convert-legacy-fn request))
                            (dissoc :fn :http)))]
    (map (fn [scenario]
           (-> scenario
               (update :requests #(map request->step %))
               (rename-keys {:requests :steps})
               (dissoc :concurrency :weight)))
         scenarios)))

(defn- run-scenario [options scenario]
  (print-scenario-info scenario)
  (let [responses (async/merge (map #(run-scenario-constantly options scenario %)
                                    (:users scenario)))
        results (async/chan)]
    (go-loop []
             (if-let [result (<! responses)]
               (do
                 (>! results (response->result scenario result))
                 (recur))
               (close! results)))
    results))

(defn run-scenarios [options scenarios convert-from-legacy?]
  (println "Running simulation with" (runner-info (:runner options)))
  (let [simulation-start (local-time/local-now)
        sent-requests (atom 0)
        runnable-scenarios (validate [schema/RunnableScenario] (if convert-from-legacy?
                                                                 (convert-from-legacy scenarios)
                                                                 scenarios))
        run-scenario-with-opts (partial run-scenario
                                        (assoc options
                                               :simulation-start simulation-start
                                               :sent-requests sent-requests))
        responses (async/merge (map run-scenario-with-opts runnable-scenarios))
        results (async/chan)]
    (go-loop []
             (if-let [result (<! responses)]
               (do
                 (>! results result)
                 (recur))
               (close! results)))
    results))

(defn run [{:keys [scenarios] :as simulation}
           {:keys [concurrency users] :as options}]
  (let [user-ids (or users (range concurrency))]
    (validate schema/Simulation simulation)
    (run-scenarios (assoc options :runner (choose-runner scenarios
                                                         (count user-ids)
                                                         options))
                   (weighted-scenarios user-ids scenarios)
                   false)))
