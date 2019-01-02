(ns lein-monolith.task.fingerprint
  (:require
    [clojure.data]
    [clojure.edn :as edn]
    [clojure.java.io :as jio]
    [clojure.set :as set]
    [clojure.string :as str]
    [leiningen.core.main :as lein]
    [leiningen.core.project :as project]
    [lein-monolith.dependency :as dep]
    [lein-monolith.target :as target]
    [lein-monolith.task.util :as u]
    [multihash.core :as mhash]
    [multihash.digest :as digest]
    [puget.color.ansi :as ansi]
    [puget.printer :as puget])
  (:import
    (java.io
      File
      PushbackInputStream)))


;; ## Hashing projects' inputs

(def ^:private ->multihash
  "Globally storing the algorithm we use to generate each multihash."
  digest/sha1)


(defn- aggregate-hashes
  "Takes a collection of multihashes, and aggregates them together into a unified hash."
  [mhashes]
  ;; TODO: is there a better way to do this?
  (if (= 1 (count mhashes))
    (first mhashes)
    (->> mhashes
         (map mhash/base58)
         (sort)
         (apply str)
         (->multihash))))


(defn- list-all-files
  [^File file]
  (if (.isFile file)
    [file]
    (mapcat list-all-files (.listFiles file))))


(defn- hash-file
  "Takes a File object, and returns a multihash that uniquely identifies the
  content of this file and the location of the file."
  [^File file]
  ;; TODO: only prefix the file location relative to the subproject root?
  (let [prefix (.getBytes (str (.getAbsolutePath file) "\n"))]
    (with-open [in (PushbackInputStream. (jio/input-stream file) (count prefix))]
      (.unread in prefix)
      (->multihash in))))


(defn- hash-sources
  [project paths-key]
  (->> (paths-key project)
       (map (fn absolute-file
              [dir-str]
              ;; Monolith subprojects don't have absolute paths
              (if (str/starts-with? dir-str (:root project))
                (jio/file dir-str)
                (jio/file (:root project) dir-str))))
       (mapcat list-all-files)
       (map hash-file)
       (aggregate-hashes)))


(defn- hash-dependencies
  [project]
  (-> (:dependencies project)
      (pr-str)
      (->multihash)))


(declare hash-inputs)


(defn- hash-upstream-projects
  [project dep-map subprojects cache]
  (->> (dep-map (dep/project-name project))
       (keep subprojects)
       (map #(::final (hash-inputs % dep-map subprojects cache)))
       (aggregate-hashes)))


(defn- cache-result!
  [cache project m]
  (get (swap! cache assoc (dep/project-name project) m) (dep/project-name project)))


(defn- hash-inputs
  "Hashes each of a project's inputs, and returns a map containing each individual
  result, so it's easier to explain what aspect of a project caused its overall
  fingerprint to change.

  Returns a map of `{::xyz <mhash>}`

  Keeps a cache of hashes computed so far, for efficiency."
  [project dep-map subprojects cache]
  (or (@cache (dep/project-name project))
      (let [prints
            {::sources (hash-sources project :source-paths)
             ::tests (hash-sources project :test-paths)
             ::resources (hash-sources project :resource-paths)
             ::deps (hash-dependencies project)
             ::upstream (hash-upstream-projects project dep-map subprojects cache)}

            prints
            (assoc prints
                   ::final (aggregate-hashes (vals prints))
                   ::time (System/currentTimeMillis))]
        (cache-result! cache project prints))))


;; ## Storing fingerprints

;; The .lein-monolith-fingerprints file at the metaproject root stores the
;; detailed fingerprint map for each project and marker type.

(comment
  ;; Example .lein-monolith-fingerprints
  {:build {foo/bar {::sources "multihash abcde"
                    ::tests "multihash fghij"
                    ,,,
                    ::final "multihash vwxyz"}
           ,,,}
   ,,,})


(defn- fingerprints-file
  ^File
  [monolith]
  (jio/file (:root monolith) ".lein-monolith-fingerprints"))


(defn- read-fingerprints-file
  [monolith]
  (let [f (fingerprints-file monolith)]
    (when (.exists f)
      (edn/read-string
        {:readers {'data/hash mhash/decode}}
        (slurp f)))))


(defn- write-fingerprints-file!
  [monolith fingerprints]
  (let [f (fingerprints-file monolith)]
    (spit f (puget/pprint-str
              fingerprints
              {:print-handlers
               {multihash.core.Multihash
                (puget/tagged-handler 'data/hash mhash/base58)}}))))


(let [lock (Object.)]
  (defn update-fingerprints-file!
    [monolith f & args]
    (locking lock
      (write-fingerprints-file!
        monolith
        (apply f (read-fingerprints-file monolith) args)))))


;; ## Generating and comparing fingerprints

(defn context
  "Create a stateful context to use for fingerprinting operations."
  [monolith subprojects]
  (let [dep-map (dep/dependency-map subprojects)
        initial (read-fingerprints-file monolith)
        cache (atom {})]
    {:monolith monolith
     :subprojects subprojects
     :dependencies dep-map
     :initial initial
     :cache cache}))


(defn- fingerprints
  "Returns a map of fingerpints associated with a project, including the ::final
  one. Can be compared with a previous fingerprint file."
  [ctx project-name]
  (let [{:keys [subprojects dependencies cache]} ctx]
    (hash-inputs (subprojects project-name) dependencies subprojects cache)))


(defn changed?
  "Determines if a project has changed since the last fingerprint saved under the
  given marker."
  [ctx marker project-name]
  (let [{:keys [initial]} ctx
        current (fingerprints ctx project-name)
        past (get-in initial [marker project-name])]
    (not= (::final past) (::final current))))


(defn- explain-kw
  [ctx marker project-name]
  (let [{:keys [initial]} ctx
        current (fingerprints ctx project-name)
        past (get-in initial [marker project-name])]
    (if (= (::final past) (::final current))
      ::up-to-date
      (or (some
            (fn [ftype]
              (when (not= (ftype past) (ftype current))
                ftype))
            [::sources ::tests ::resources ::deps ::upstream])
          ::no-keyword))))


(defn explain-str
  [ctx marker project-name]
  (case (explain-kw ctx marker project-name)
    ::up-to-date (ansi/sgr "up-to-date" :green)
    ::sources (ansi/sgr "sources changed" :red)
    ::tests (ansi/sgr "tests changed" :red)
    ::resources (ansi/sgr "resources changed" :red)
    ::deps (ansi/sgr "external dependency changed" :yellow)
    ::upstream (ansi/sgr "downstream of affected project" :yellow)
    ::unknown (ansi/sgr "new project" :red)))


(defn save!
  "Save the fingerprints for a project with the specified marker."
  [ctx marker project-name]
  (let [current (fingerprints ctx project-name)]
    (update-fingerprints-file!
      (:monolith ctx)
      assoc-in [marker project-name] current)))


;; TODO: remove
(defn- changed-projects
  "Takes two detailed fingerprint maps, and returns a set of project names that
  have a current fingerprint but changed."
  [past current marker]
  (into #{}
        (keep
          (fn compare-fingerprints
            [[project-name current-info]]
            (let [past-info (get-in past [marker project-name])]
              (when (not= (::final past-info) (::final current-info))
                project-name))))
        current))


(defn- explain
  [past current marker project-name]
  (let [past-info (get-in past [marker project-name])
        current-info (get current project-name)]
    (if (= (::final past-info) (::final current-info))
      ::up-to-date
      (or (some
            (fn [ftype]
              (when (not= (ftype past-info) (ftype current-info))
                ftype))
            [::sources ::tests ::resources ::deps ::upstream])
          ::unknown))))


(defn- list-projects
  [project-names color]
  (->> project-names
       (map #(ansi/sgr % color))
       (str/join ", ")))


(defn info
  [project opts & [marker]]
  (let [[monolith subprojects] (u/load-monolith! project)
        dep-map (dep/dependency-map subprojects)
        project-name (dep/project-name project)
        targets (target/select monolith subprojects opts)
        cache (atom {})
        current (->> targets
                     (keep
                       (fn [project-name]
                         (when-let [subproject (subprojects project-name)]
                           [project-name
                            (hash-inputs
                              subproject dep-map subprojects cache)])))
                     (into {}))
        past (read-fingerprints-file monolith)
        markers (if marker
                  [marker]
                  (keys past))]
    (if (empty? markers)
      (lein/info "No saved fingerprint markers")
      (doseq [marker markers
              :let [changed (changed-projects past current marker)
                    pct-changed (if (seq current)
                                  (* 100.0 (/ (count changed) (count current)))
                                  0.0)]]
        (lein/info (ansi/sgr (format "%.2f%%" pct-changed)
                             (cond
                               (== 0.0 pct-changed) :green
                               (< pct-changed 50) :yellow
                               :else :red))
                   "out of"
                   (count current)
                   "projects have out-of-date"
                   (ansi/sgr marker :bold)
                   "fingerprints:\n")
        (let [reasons (group-by (partial explain past current marker)
                                (keys current))]
          (when-let [projs (seq (concat (::sources reasons)
                                        (::tests reasons)
                                        (::resources reasons)))]
            (lein/info "*" (ansi/sgr (count projs) :red)
                       "updated sources:"
                       (list-projects projs :red)))
          (when-let [projs (seq (::deps reasons))]
            (lein/info "*" (ansi/sgr (count projs) :yellow)
                       "updated external dependencies:"
                       (list-projects projs :yellow)))
          (when-let [projs (seq (::upstream reasons))]
            (lein/info "*" (ansi/sgr (count projs) :yellow)
                       "are downstream of affected projects"))
          (when-let [projs (seq (::up-to-date reasons))]
            (lein/info "*" (ansi/sgr (count projs) :green)
                       "up-to-date")))))))


(defn mark
  [project opts args]
  ;; TODO: validate markers
  (let [markers (str/split (first args) #",")
        [monolith subprojects] (u/load-monolith! project)
        dep-map (dep/dependency-map subprojects)
        targets (target/select monolith subprojects opts)
        cache (atom {})
        current (->> targets
                     (keep
                       (fn [project-name]
                         (when-let [subproject (subprojects project-name)]
                           [project-name
                            (hash-inputs
                              subproject dep-map subprojects cache)])))
                     (into {}))
        state (read-fingerprints-file monolith)
        state' (reduce
                 (fn [state marker]
                   (update state marker merge current))
                 state
                 markers)]
    (write-fingerprints-file! monolith state')
    (lein/info (format "Set %s markers for %s projects"
                       (ansi/sgr (count markers) :bold)
                       (ansi/sgr (count current) :bold)))))
