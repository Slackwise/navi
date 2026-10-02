(ns bootstrap
  "Idempotent dev-environment bootstrap, run by bootstrap.sh via nbb once Node.js exists.
   Installs/upgrades Deno (latest) and a JDK 21 (build-time only, for shadow-cljs),
   installs VS Code extensions, then runs npm install for the repo.
   Usage: bootstrap.cljs [--skip-extensions] [--skip-npm]"
  (:require ["child_process" :as cp]
            ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            [clojure.string :as str]
            [nbb.core :refer [*file*]]))

(def windows? (= "win32" js/process.platform))
(def macos? (= "darwin" js/process.platform))

(def repo-root (path/resolve (path/dirname *file*) ".."))

(def args (set *command-line-args*))

(def vscode-extensions ["betterthantomorrow.calva" "denoland.vscode-deno"])

;; winget exit codes that mean "nothing to do"
(def winget-ok-codes #{0
                       -1978335189   ; 0x8A15002B UPDATE_NOT_APPLICABLE
                       -1978335135}) ; 0x8A150061 PACKAGE_ALREADY_INSTALLED

(defn log [& xs] (println "==>" (str/join " " xs)))
(defn warn [& xs] (binding [*print-fn* *print-err-fn*] (println "!! " (str/join " " xs))))

(defn die [& xs]
  (apply warn xs)
  (js/process.exit 1))

(def ^:private cmd-shims
  "Commands that are .cmd shims on Windows and must be launched through a shell."
  #{"npm" "npx" "code"})

(defn run
  "Runs a command synchronously and returns {:code :out}. Output (stdout + stderr) is captured
   when :capture? is set, otherwise streamed to the terminal."
  ([cmd cmd-args] (run cmd cmd-args {}))
  ([cmd cmd-args {:keys [capture? cwd]}]
   (let [res (cp/spawnSync cmd (clj->js cmd-args)
                           #js {:stdio (if capture? "pipe" "inherit")
                                :encoding "utf8"
                                :shell (and windows? (contains? cmd-shims cmd))
                                :cwd (or cwd repo-root)})]
     {:code (bit-or (or (.-status res) -1) 0)
      :out (str (.-stdout res) (.-stderr res))})))

(defn ok? [cmd cmd-args]
  (zero? (:code (run cmd cmd-args {:capture? true}))))

(defn sh! [cmd cmd-args & [opts]]
  (let [{:keys [code]} (run cmd cmd-args opts)]
    (when-not (zero? code)
      (die cmd (str/join " " cmd-args) "failed with exit code" code))))

(defn command-exists? [cmd]
  (if windows?
    (ok? "where.exe" [cmd])
    (ok? "sh" ["-c" (str "command -v " cmd)])))

(defn first-line [s]
  (first (str/split-lines (str/trim s))))

(defn version-of [cmd & flags]
  (first-line (:out (run cmd (or (seq flags) ["--version"]) {:capture? true}))))

(defn prepend-path! [dir]
  (set! (.-PATH js/process.env) (str dir path/delimiter (.-PATH js/process.env))))

;; WINDOWS PATH REFRESH -------------------------------------------------------

(def ^:private windows-path-keys
  ["HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment"
   "HKCU\\Environment"])

(defn- reg-path [reg-key]
  (->> (:out (run "reg.exe" ["query" reg-key "/v" "Path"] {:capture? true}))
       str/split-lines
       (some #(second (re-find #"^\s+Path\s+REG_\w+\s+(.*)$" %)))))

(defn- expand-env [s]
  (str/replace s #"%([^%]+)%" (fn [[m v]] (or (aget js/process.env v) m))))

(defn refresh-path!
  "Re-reads the machine and user PATH from the registry so tools installed by winget
   during this run are visible without restarting the terminal."
  []
  (when windows?
    (let [fresh (->> windows-path-keys
                     (keep reg-path)
                     (mapcat #(str/split (expand-env %) #";")))
          current (str/split (or (.-PATH js/process.env) "") #";")]
      (set! (.-PATH js/process.env)
            (->> (concat fresh current) (remove str/blank?) distinct (str/join ";"))))))

;; JAVA -----------------------------------------------------------------------

(def jdk-major 21)

(defn java-major []
  (when (command-exists? "java")
    (some-> (re-find #"version \"(\d+)" (:out (run "java" ["-version"] {:capture? true})))
            second
            js/parseInt)))

(defn java-ok? []
  (when-let [v (java-major)]
    (>= v jdk-major)))

;; WINDOWS (WINGET) -----------------------------------------------------------

(def windows-packages
  [{:id "DenoLand.Deno" :name "Deno" :present? #(command-exists? "deno")}
   {:id "Microsoft.OpenJDK.21" :name "Microsoft OpenJDK 21" :present? java-ok?}])

(defn winget-installed? [id]
  (ok? "winget" ["list" "--id" id "--exact" "--accept-source-agreements" "--disable-interactivity"]))

(defn winget! [verb id]
  (let [{:keys [code]} (run "winget" [verb "--id" id "--exact" "--silent"
                                      "--accept-source-agreements" "--accept-package-agreements"
                                      "--disable-interactivity"])]
    (when-not (contains? winget-ok-codes code)
      (die "winget" verb id "failed with exit code" code))))

(defn ensure-winget-package! [{:keys [id name present?]}]
  (cond
    (winget-installed? id) (do (log "Upgrading" name "via winget if a newer version exists...")
                               (winget! "upgrade" id))
    (present?) (log name "already installed outside winget; leaving it alone.")
    :else (do (log "Installing" name "via winget (a UAC prompt may appear)...")
              (winget! "install" id))))

(defn install-windows! []
  (when-not (command-exists? "winget")
    (die "winget not found. Install \"App Installer\" from the Microsoft Store and re-run."))
  (run! ensure-winget-package! windows-packages))

;; MACOS / LINUX --------------------------------------------------------------

(defn brew? []
  (and macos? (command-exists? "brew")))

(defn brew-has? [formula]
  (ok? "brew" ["list" formula]))

(def deno-home-bin (path/join (os/homedir) ".deno" "bin"))

(defn ensure-deno-unix! []
  (prepend-path! deno-home-bin)
  (cond
    (and (brew?) (brew-has? "deno")) (do (log "Upgrading Deno via Homebrew if a newer version exists...")
                                         (run "brew" ["upgrade" "deno"]))
    (fs/existsSync (path/join deno-home-bin "deno")) (do (log "Upgrading Deno to the latest version...")
                                                         (sh! "deno" ["upgrade"]))
    (command-exists? "deno") (log "Deno already installed by another package manager; leaving it alone.")
    (brew?) (do (log "Installing Deno via Homebrew...")
                (sh! "brew" ["install" "deno"]))
    :else (do (log "Installing Deno via the official install script...")
              (sh! "sh" ["-c" "curl -fsSL https://deno.land/install.sh | sh -s -- -y"]))))

(def linux-jdk-installers
  [[#{"debian" "ubuntu"} "apt-get update && apt-get install -y openjdk-21-jdk-headless"]
   [#{"arch"} "pacman -S --needed --noconfirm jdk21-openjdk"]
   [#{"fedora" "rhel"} "dnf install -y java-21-openjdk-devel"]])

(defn linux-distro-ids
  "ID and ID_LIKE values from /etc/os-release, e.g. #{\"cachyos\" \"arch\"}."
  []
  (let [f "/etc/os-release"]
    (if (fs/existsSync f)
      (->> (str/split-lines (fs/readFileSync f "utf8"))
           (keep #(second (re-find #"^(?:ID|ID_LIKE)=\"?([^\"]*)\"?$" %)))
           (mapcat #(str/split % #"\s+"))
           set)
      #{})))

(defn install-linux-jdk! []
  (let [ids (linux-distro-ids)
        cmd (some (fn [[family c]] (when (some family ids) c)) linux-jdk-installers)]
    (if cmd
      (do (log "Installing OpenJDK" jdk-major "(sudo)...")
          (sh! "sudo" ["sh" "-c" cmd]))
      (warn "Unrecognized Linux distro" (pr-str ids) "- install a JDK" (str jdk-major "+") "manually."))))

(defn ensure-jdk-unix! []
  (cond
    (java-ok?) (log "Java" (java-major) "already installed.")
    (brew?) (do (log "Installing Temurin JDK" jdk-major "via Homebrew...")
                (sh! "brew" ["install" "--cask" (str "temurin@" jdk-major)]))
    macos? (warn "Homebrew not found; install a JDK" (str jdk-major "+") "manually.")
    :else (install-linux-jdk!)))

(defn install-unix! []
  (ensure-deno-unix!)
  (ensure-jdk-unix!))

;; VS CODE + NPM --------------------------------------------------------------

(defn ensure-extensions! []
  (if-not (command-exists? "code")
    (warn "VS Code 'code' CLI not on PATH; skipping extension install.")
    (let [installed (->> (:out (run "code" ["--list-extensions"] {:capture? true}))
                         str/split-lines
                         (map (comp str/lower-case str/trim))
                         set)]
      (doseq [ext vscode-extensions]
        (if (installed ext)
          (log "VS Code extension" ext "already installed.")
          (do (log "Installing VS Code extension" ext "...")
              (sh! "code" ["--install-extension" ext])))))))

(defn npm-install! []
  (log "npm install (root workspace + backend)...")
  (sh! "npm" ["install"]))

(defn print-versions! []
  (log "Versions:")
  (doseq [[cmd & flags] [["node"] ["npm"] ["deno"] ["java" "-version"]]]
    (println (str "    " cmd ": " (apply version-of cmd flags)))))

;; MAIN -----------------------------------------------------------------------

(defn main []
  (log "Repo:" repo-root)
  (if windows? (install-windows!) (install-unix!))
  (refresh-path!)
  (doseq [cmd ["deno" "java"]]
    (when-not (command-exists? cmd)
      (die cmd "is still not on PATH. Open a new terminal and re-run.")))
  (when-not (args "--skip-extensions") (ensure-extensions!))
  (when-not (args "--skip-npm") (npm-install!))
  (print-versions!)
  (log "Bootstrap complete. If tools were newly installed, restart VS Code so its terminals pick up the new PATH."))

(main)
