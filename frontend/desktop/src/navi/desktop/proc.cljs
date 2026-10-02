(ns navi.desktop.proc
  "Child-process helpers over Node.js child_process."
  (:require ["node:child_process" :as child-process]
            [navi.desktop.log :as log]))

(defn exec
  "Runs `cmd` with `args` and resolves to {:code :success? :stdout :stderr}."
  [cmd args]
  (js/Promise.
   (fn [resolve reject]
     (let [child (.spawn child-process cmd (clj->js args)
                         #js {:stdio #js ["ignore" "pipe" "pipe"]})
           stdout (atom "")
           stderr (atom "")]
       (.on (.-stdout child) "data" #(swap! stdout str %))
       (.on (.-stderr child) "data" #(swap! stderr str %))
       (.on child "error" reject)
       (.on child "close"
            (fn [code]
              (let [code (or code -1)]
                (resolve {:code code
                          :success? (zero? code)
                          :stdout @stdout
                          :stderr @stderr}))))))))

(defn exec!
  "Like `exec`, but rejects when the process exits unsuccessfully."
  [cmd args]
  (-> (exec cmd args)
      (.then (fn [{:keys [success? code stderr] :as result}]
               (if success?
                 result
                 (throw (js/Error. (str cmd " exited with code " code
                                        (when (seq stderr) (str ": " stderr))))))))))

(defn spawn-detached!
  "Starts `cmd` without waiting for it or keeping this process alive."
  [cmd args]
  (let [child (.spawn child-process cmd (clj->js args)
                      #js {:detached true :stdio "ignore"})]
    (.unref child)
    child))

(defn spawn-detached-checked!
  "Resolves only after Node confirms the detached child spawned; rejects launch errors."
  [cmd args]
  (js/Promise.
   (fn [resolve reject]
     (let [child (.spawn child-process cmd (clj->js args)
                         #js {:detached true :stdio "ignore"})]
       (.once child "error" reject)
       (.once child "spawn" (fn [] (.unref child) (resolve child)))))))

(defn- stop-helper! [^js child]
  (try (.kill child)
       (catch :default e
         (log/error "Could not stop update helper:" e))))

(defn spawn-update-helper!
  "Waits for the detached SEA updater's IPC ready message before allowing exit."
  [cmd args]
  (js/Promise.
   (fn [resolve reject]
     (let [child (.spawn child-process cmd (clj->js args)
                         #js {:detached true :stdio #js ["ignore" "ignore" "ignore" "ipc"]})
           settled? (atom false)
           timer (atom nil)
           finish! (fn [error]
                     (when-not @settled?
                       (reset! settled? true)
                       (js/clearTimeout @timer)
                       (if error
                         (do (stop-helper! child) (reject error))
                         (do (.unref child) (resolve child)))))]
       (.once child "error" finish!)
       (.once child "exit"
              (fn [code signal]
                (finish! (js/Error. (str "Update helper exited before readiness: " code " " signal)))))
       (.once child "disconnect"
              #(finish! (js/Error. "Update helper disconnected before readiness.")))
       (.on child "message"
            (fn [message]
              (finish! (when-not (= "ready" message)
                         (js/Error. (str "Update helper failed: " (js/JSON.stringify message)))))))
       (reset! timer (js/setTimeout #(finish! (js/Error. "Timed out waiting for update helper readiness."))
                                   15000))))))
