#!/usr/bin/env nbb
(ns fleet.cli
  "kotoba-fleet CLI (Phase 0, ADR-2607160005).

  Commands:
    import  --west <west.yml> --out <fleet-db.edn>
    check   --west <west.yml> [--db <fleet-db.edn>]     ;; byte-exact round-trip
    stats   --db <fleet-db.edn>
    list    --db <fleet-db.edn> [--org O] [--group G]
    sync    --db <fleet-db.edn> --workspace <dir>
            [--names a,b,c] [--org O] [--group G] [--jobs N] [--dry-run]
    pin-advance --db <fleet-db.edn> --repo <name> --new <sha> [--actor A]
            ;; Phase 0 two-phase: ledger event + db update, then delegates
            ;; west.yml reflection to the existing verified path
            ;; (nbb scripts/gen-west-manifest.cljs --entry <name>)."
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [fleet.db :as db]
            [fleet.sync :as sync]
            [fleet.west :as west]
            [promesa.core :as p]))

;; ---------------------------------------------------------------------------
;; args

(defn parse-args [argv]
  (loop [opts {} [a & more] argv]
    (cond
      (nil? a) opts
      (str/starts-with? a "--")
      (let [k (keyword (subs a 2))]
        (if (or (nil? (first more)) (str/starts-with? (first more) "--"))
          (recur (assoc opts k true) more)
          (recur (assoc opts k (first more)) (rest more))))
      :else (recur (update opts :_ (fnil conj []) a) more))))

(defn die [msg]
  (js/console.error (str "fleet: " msg))
  (js/process.exit 1))

;; ---------------------------------------------------------------------------
;; io

(defn slurp* [f] (fs/readFileSync f "utf8"))
(defn spit* [f s] (fs/writeFileSync f s))
(defn load-db [f] (reader/read-string (slurp* f)))

(defn sh
  "Run argv, resolve {:ok? bool :out str :err str}. Never rejects."
  [[cmd & args]]
  (p/create
   (fn [resolve _]
     (let [ps (cp/spawn cmd (clj->js args) #js {:stdio #js ["ignore" "pipe" "pipe"]})
           out (atom "") err (atom "")]
       (.on (.-stdout ps) "data" #(swap! out str %))
       (.on (.-stderr ps) "data" #(swap! err str %))
       (.on ps "close" #(resolve {:ok? (zero? %) :out @out :err @err}))
       (.on ps "error" #(resolve {:ok? false :out @out :err (str %)}))))))

(defn pmap-pool
  "Run (f item) -> promise over items with at most n in flight."
  [n f items]
  (let [results (atom (vec (repeat (count items) nil)))
        queue   (atom (map-indexed vector items))]
    (p/create
     (fn [resolve _]
       (let [active (atom 0)
             step (fn step []
                    (if-let [[i item] (first @queue)]
                      (do (swap! queue rest)
                          (swap! active inc)
                          (-> (f item)
                              (p/then (fn [r]
                                        (swap! results assoc i r)
                                        (swap! active dec)
                                        (step)))))
                      (when (zero? @active) (resolve @results))))]
         (if (empty? items)
           (resolve [])
           (dotimes [_ (min n (count items))] (step))))))))

;; ---------------------------------------------------------------------------
;; commands

(defn cmd-import [{:keys [west out]}]
  (when-not (and west out) (die "import needs --west and --out"))
  (let [d (west/parse (slurp* west))]
    (spit* out (pr-str d))
    (println "imported" (count (:fleet/repos d)) "repos ->" out)))

(defn cmd-check [{:keys [west db]}]
  (when-not west (die "check needs --west"))
  (let [text (slurp* west)
        d    (if db (load-db db) (west/parse text))
        out  (west/emit d)]
    (if (= out text)
      (println "OK: projection is byte-identical to" west
               (str "(" (count (:fleet/repos d)) " repos)"))
      (let [a (str/split text #"\n") b (str/split out #"\n")
            i (or (first (keep-indexed #(when (not= %2 (get b %1)) %1) a))
                  (min (count a) (count b)))]
        (println "STALE: first divergence at line" (inc i))
        (println "  west.yml :" (pr-str (get a i)))
        (println "  fleet-db :" (pr-str (get b i)))
        (js/process.exit 1)))))

(defn cmd-stats [{:keys [db]}]
  (when-not db (die "stats needs --db"))
  (prn (db/stats (load-db db))))

(defn cmd-list [{:keys [db org group]}]
  (when-not db (die "list needs --db"))
  (let [d (load-db db)]
    (doseq [e (sync/working-set d {:org org :group group})]
      (println (:repo/name e) (:repo/revision e) (:repo/path e)))))

(defn observe-state [dir]
  (if-not (fs/existsSync dir)
    (p/resolved {:exists? false})
    (p/let [dirty (sh ["git" "-C" dir "status" "--porcelain"])
            head  (sh ["git" "-C" dir "rev-parse" "HEAD"])]
      {:exists? true
       :dirty?  (not (str/blank? (:out dirty)))
       :head    (when (:ok? head) (str/trim (:out head)))})))

(defn run-plan [entity {:keys [action steps]} dir dry-run?]
  (if (or dry-run? (empty? steps))
    (p/resolved {:repo (:repo/name entity) :action action :ok? true :dry-run? dry-run?})
    (p/loop [[s & more] steps]
      (if (nil? s)
        {:repo (:repo/name entity) :action action :ok? true}
        (p/let [r (sh s)]
          (if (:ok? r)
            (p/recur more)
            {:repo (:repo/name entity) :action action :ok? false
             :step s :err (str/trim (:err r))}))))))

(defn cmd-sync [{:keys [db workspace names org group jobs dry-run]}]
  (when-not (and db workspace) (die "sync needs --db and --workspace"))
  (let [d    (load-db db)
        ws   (sync/working-set d {:names (when names (str/split names #","))
                                  :org org :group group})
        n    (js/parseInt (or jobs "8"))
        t0   (js/Date.now)]
    (when (empty? ws) (die "working set is empty"))
    (println "sync:" (count ws) "repos, jobs =" n (if dry-run "(dry-run)" ""))
    (-> (pmap-pool
         n
         (fn [entity]
           (let [dir (path/join workspace (:repo/path entity))]
             (p/let [st   (observe-state dir)
                     plan (p/resolved (sync/plan d entity st dir))
                     res  (run-plan entity plan dir (boolean dry-run))]
               (when-not (:ok? res)
                 (js/console.error "  FAIL" (:repo res) (pr-str (:step res)) (:err res)))
               res)))
         ws)
        (p/then (fn [results]
                  (let [sum (sync/summarize results)
                        dt  (/ (- (js/Date.now) t0) 1000.0)]
                    (println "done in" (.toFixed dt 1) "s:" (pr-str sum))
                    (when (pos? (:failed sum 0)) (js/process.exit 1))))))))

(defn cmd-pin-advance [{:keys [db repo new actor]}]
  (when-not (and db repo new) (die "pin-advance needs --db --repo --new"))
  (let [dbf    db
        d      (load-db dbf)
        entity (or (west/find-repo d repo) (die (str "unknown repo " repo)))
        ledgerf (str/replace dbf #"\.edn$" ".ledger.edn")
        events (if (fs/existsSync ledgerf)
                 (mapv reader/read-string
                       (remove str/blank? (str/split (slurp* ledgerf) #"\n")))
                 [])
        ev     (db/pin-advance-event
                events {:repo repo :old-rev (:repo/revision entity) :new-rev new
                        :actor (or actor "fleet-cli")
                        :at (.toISOString (js/Date.))})]
    (fs/appendFileSync ledgerf (str (pr-str ev) "\n"))
    (spit* dbf (pr-str (db/apply-pin-advance d ev)))
    (println "ledger:" (pr-str ev))
    (println "fleet-db updated. Reflect to west.yml via the existing verified path:")
    (println "  nbb scripts/gen-west-manifest.cljs --entry" repo)))

(def commands
  {"import" cmd-import "check" cmd-check "stats" cmd-stats
   "list" cmd-list "sync" cmd-sync "pin-advance" cmd-pin-advance})

(let [[cmd & rest-args] *command-line-args*
      f (get commands cmd)]
  (if f
    (f (parse-args rest-args))
    (die (str "usage: fleet {" (str/join "|" (keys commands)) "} ..."))))
