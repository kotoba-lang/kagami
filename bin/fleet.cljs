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
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [fleet.db :as db]
            [fleet.pin :as pin]
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

;; ---------------------------------------------------------------------------
;; ed25519 via node:crypto (Phase 1; ids are ed25519:<pubkey-hex>)

(def ^:private spki-prefix "302a300506032b6570032100") ;; DER SPKI header for raw ed25519 pubkey

(defn pubkey-object [pubkey-hex]
  (crypto/createPublicKey
   #js {:key (js/Buffer.from (str spki-prefix pubkey-hex) "hex")
        :format "der" :type "spki"}))

(defn node-verify [pubkey-hex payload sig-hex]
  (try
    (crypto/verify nil (js/Buffer.from payload "utf8")
                   (pubkey-object pubkey-hex)
                   (js/Buffer.from sig-hex "hex"))
    (catch :default _ false)))

(defn node-sign [privkey-pem payload]
  (-> (crypto/sign nil (js/Buffer.from payload "utf8")
                   (crypto/createPrivateKey privkey-pem))
      (.toString "hex")))

(defn node-sha256 [s]
  (-> (crypto/createHash "sha256") (.update s "utf8") (.digest "hex")))

(defn pubkey-hex-of-priv [privkey-pem]
  (let [pub (crypto/createPublicKey (crypto/createPrivateKey privkey-pem))
        der (.export pub #js {:format "der" :type "spki"})]
    (-> der (.subarray (- (.-length der) 32)) (.toString "hex"))))

(defn cmd-keygen [{:keys [out]}]
  (when-not out (die "keygen needs --out <privkey.pem>"))
  (let [kp (crypto/generateKeyPairSync
            "ed25519" #js {:privateKeyEncoding #js {:format "pem" :type "pkcs8"}
                           :publicKeyEncoding  #js {:format "der" :type "spki"}})
        pem (.-privateKey kp)
        raw (-> (.-publicKey kp) (.subarray (- (.-length (.-publicKey kp)) 32)) (.toString "hex"))]
    (fs/writeFileSync out pem #js {:mode 0600})
    (println "private key:" out "(mode 0600 — keep out of git; see secrets-location-map)")
    (println "signer id  : " (str "ed25519:" raw))))

(defn gh-reachable?
  "Server-side reachability: pin must be identical|behind vs default branch.
  404/API failure -> :unknown (fail-open, same as verify-west-pins)."
  [org-repo sha]
  (p/let [r (sh ["gh" "api" (str "repos/" org-repo "/compare/HEAD..." sha)
                 "--jq" ".status"])]
    (if-not (:ok? r)
      :unknown
      (contains? #{"identical" "behind"} (str/trim (:out r))))))

(defn org-repo-of [d entity]
  (let [base (some #(when (= (:remote/name %) (:repo/remote entity)) (:remote/url-base %))
                   (:fleet/remotes d))
        org  (last (str/split base #"[:/]"))]
    (str org "/" (or (:repo/repo-path entity) (:repo/name entity)))))

(defn load-ledger [f]
  (if (fs/existsSync f)
    (mapv reader/read-string (remove str/blank? (str/split (slurp* f) #"\n")))
    []))

(defn last-signed [ledger repo]
  (->> ledger
       (filter #(and (= :pin/advance-signed (:event/type %))
                     (= repo (get-in % [:pin/record :pin/name]))))
       last))

(defn cmd-pin-advance-signed
  "Signed pin advance (Phase 1): sign -> admission gate -> ledger + db +
  west.yml projection splice. Rejection exits 1 with reasons."
  [{:keys [db repo new key keys west] :as opts}]
  (when-not (and db repo new key keys)
    (die "pin-advance needs --db --repo --new --key <privkey.pem> --keys <fleet-keys.edn>"))
  (let [d       (load-db db)
        entity  (or (west/find-repo d repo) (die (str "unknown repo " repo)))
        keyring (reader/read-string (slurp* keys))
        pem     (slurp* key)
        signer  (str "ed25519:" (pubkey-hex-of-priv pem))
        ledgerf (str/replace db #"\.edn$" ".ledger.edn")
        ledger  (load-ledger ledgerf)
        cur-ev  (last-signed ledger repo)
        current (when cur-ev {:record (:pin/record cur-ev)
                              :signature (:pin/signature cur-ev)})
        record  (pin/make-record
                 {:repo repo :value new
                  :sequence (inc (get-in current [:record :pin/sequence] 0))
                  :parent (when current
                            (pin/record-hash node-sha256 (:record current)
                                             (:signature current)))})
        sig     (node-sign pem (pin/canonical-str record))]
    (p/let [reach (gh-reachable? (org-repo-of d entity) new)]
      (let [proposal {:record record :signature sig :signer signer}
            verdict  (pin/admit proposal
                                {:repo-path (:repo/path entity)
                                 :current current
                                 :keyring keyring
                                 :verify-fn node-verify
                                 :hash-fn node-sha256
                                 :reachable? reach})]
        (if (= :reject (:verdict verdict))
          (do (js/console.error "REJECTED:" (pr-str (:reasons verdict)))
              (js/process.exit 1))
          (let [ev (pin/accepted-event proposal verdict (db/next-seq ledger)
                                       (.toISOString (js/Date.)))
                d' (db/apply-pin-advance d {:repo/name repo :pin/new new})]
            (fs/appendFileSync ledgerf (str (pr-str ev) "\n"))
            (spit* db (pr-str d'))
            (when west
              (let [w (west/parse (slurp* west))
                    w' (update w :fleet/repos
                               (fn [rs] (mapv #(if (= (:repo/name %) repo)
                                                 (assoc % :repo/revision new) %)
                                              rs)))]
                (spit* west (west/emit w'))))
            (println (name (:verdict verdict)) "— seq" (:pin/sequence record)
                     "signer" signer)
            (when (seq (:reasons verdict)) (println "  note:" (pr-str (:reasons verdict))))
            (println "ledger:" ledgerf (if west (str "/ projected: " west) ""))))))))

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
   "list" cmd-list "sync" cmd-sync
   "keygen" cmd-keygen
   "pin-advance" cmd-pin-advance-signed
   "pin-advance-unsigned" cmd-pin-advance})

(let [[cmd & rest-args] *command-line-args*
      f (get commands cmd)]
  (if f
    (f (parse-args rest-args))
    (die (str "usage: fleet {" (str/join "|" (keys commands)) "} ..."))))
