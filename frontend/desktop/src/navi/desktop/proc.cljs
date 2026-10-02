(ns navi.desktop.proc
  "Child-process helpers over Deno.Command.")

(defn exec
  "Runs `cmd` with `args` and resolves to {:code :success? :stdout :stderr}."
  [cmd args]
  (let [command (js/Deno.Command. cmd #js {:args (into-array args)
                                           :stdin "null"
                                           :stdout "piped"
                                           :stderr "piped"})
        decoder (js/TextDecoder.)]
    (-> (.output command)
        (.then (fn [^js out]
                 {:code (.-code out)
                  :success? (.-success out)
                  :stdout (.decode decoder (.-stdout out))
                  :stderr (.decode decoder (.-stderr out))})))))

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
  (let [child (.spawn (js/Deno.Command. cmd #js {:args (into-array args)
                                                 :stdin "null"
                                                 :stdout "null"
                                                 :stderr "null"}))]
    (.unref child)
    child))
