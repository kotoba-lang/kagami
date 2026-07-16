(ns fleet.db
  "fleet-db — the Phase 0 read model (ADR-2607160005).

  The db value is the map produced by fleet.west/parse:
    {:fleet/header .. :fleet/footer .. :fleet/remotes [..] :fleet/repos [entity ..]}
  persisted as one EDN file. west.yml is the *projection* of this value
  (fleet.west/emit); in Phase 0 the direction of truth is still west.yml ->
  fleet-db (import), and flips in Phase 1.

  Alongside the db file sits an append-only ledger (one EDN map per line,
  monotonic :event/seq — same shape as canvas-ledger.edn). Phase 0 only
  records events; admission-gate enforcement is Phase 1."
  (:require [clojure.string :as str]
            [fleet.west :as west]))

(defn schema-datoms
  "The repo-entity schema as Datomic-style transaction maps, for consumers
  that want to transact the db into DataScript/kotobase instead of reading
  the EDN map directly."
  []
  [{:db/ident :repo/name :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :repo/remote :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :repo/revision :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :repo/path :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :repo/clone-depth :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :repo/groups :db/valueType :db.type/string :db/cardinality :db.cardinality/many}
   {:db/ident :repo/submodules? :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}])

(defn repo-datoms
  "Repo entities as transactable maps (userdata flattened to :repo/datalad?)."
  [db]
  (mapv (fn [e]
          (cond-> (dissoc e :repo/userdata)
            (get-in e [:repo/userdata :datalad]) (assoc :repo/datalad? true)))
        (:fleet/repos db)))

;; ---------------------------------------------------------------------------
;; queries (plain fns in Phase 0; datalog once fleet-db lives in kotobase)

(defn by-org [db org]
  (filterv #(str/starts-with? (:repo/path %) (str "orgs/" org "/")) (:fleet/repos db)))

(defn by-group [db group]
  (filterv #(some #{group} (:repo/groups %)) (:fleet/repos db)))

(defn heavy [db]      (filterv :repo/clone-depth (:fleet/repos db)))
(defn datalad [db]    (filterv #(get-in % [:repo/userdata :datalad]) (:fleet/repos db)))
(defn archived [db]   (by-group db "archived"))

(defn stats [db]
  {:repos    (count (:fleet/repos db))
   :orgs     (count (:fleet/remotes db))
   :heavy    (count (heavy db))
   :datalad  (count (datalad db))
   :archived (count (archived db))})

;; ---------------------------------------------------------------------------
;; ledger (append-only, one EDN map per line)

(defn next-seq [ledger-events]
  (inc (reduce max 0 (map :event/seq ledger-events))))

(defn pin-advance-event
  "Phase 0 read-model event for a pin advance. `at` is an ISO-8601 string
  supplied by the caller (nbb side owns the clock). Enforcement of the three
  admission invariants is Phase 1; Phase 0 records intent + evidence."
  [ledger-events {:keys [repo old-rev new-rev actor at]}]
  {:event/seq  (next-seq ledger-events)
   :event/type :pin/advance
   :event/at   at
   :event/actor actor
   :repo/name  repo
   :pin/old    old-rev
   :pin/new    new-rev})

(defn apply-pin-advance
  "Apply a pin advance event to the db value (pure)."
  [db event]
  (let [repo-name (:repo/name event)
        new-rev   (:pin/new event)]
    (when-not (west/find-repo db repo-name)
      (throw (ex-info "pin-advance for unknown repo" {:repo repo-name})))
    (update db :fleet/repos
            (fn [repos]
              (mapv #(if (= (:repo/name %) repo-name)
                       (assoc % :repo/revision new-rev)
                       %)
                    repos)))))
