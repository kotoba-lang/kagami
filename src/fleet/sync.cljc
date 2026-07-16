(ns fleet.sync
  "Pure sync planner (ADR-2607160005 Plane 5, Phase 0).

  Given a repo entity + observed workspace state, produce the git argv steps
  that materialize the checkout at the pinned revision. Pure: no IO here —
  the nbb CLI executes plans through an injected runner (same pattern as
  kotoba/git_adapter.cljc). Because every plan targets the pin SHA directly
  (`fetch --depth 1 origin <sha>`), the shallow-vs-pin mismatch class and
  local ancestry judgments do not exist in this planner: there is nothing to
  judge, only a SHA to materialize.

  west semantics kept: a dirty checkout is skipped, never overwritten."
  (:require [clojure.string :as str]
            [fleet.west :as west]))

(defn plan
  "entity + state -> {:action .. :steps [[cmd & args] ..]}.

  `state` describes what the CLI observed at the target dir:
    {:exists? bool :dirty? bool :head sha-or-nil}
  `dir` is the absolute checkout path inside the workspace."
  [db entity {:keys [exists? dirty? head]} dir]
  (let [rev   (:repo/revision entity)
        url   (west/remote-url db entity)
        depth (or (:repo/clone-depth entity) 1)]  ;; shallow default (CLAUDE.md)
    (cond
      (and exists? dirty?)
      {:action :skip-dirty :steps []}

      (and exists? (= head rev))
      {:action :noop :steps []}

      exists?
      {:action :advance
       :steps [["git" "-C" dir "fetch" "--depth" (str depth) "origin" rev]
               ["git" "-C" dir "checkout" "--detach" rev]]}

      :else
      {:action :materialize
       :steps [["git" "init" "-q" dir]
               ["git" "-C" dir "remote" "add" "origin" url]
               ["git" "-C" dir "fetch" "--depth" (str depth) "origin" rev]
               ["git" "-C" dir "checkout" "--detach" "FETCH_HEAD"]]})))

(defn working-set
  "Resolve a working-set spec against the db (Phase 0 selector; becomes a
  datalog query once fleet-db lives in kotobase).
    {:names [..]} | {:org ..} | {:group ..} — filters compose (AND)."
  [db {:keys [names org group]}]
  (cond->> (:fleet/repos db)
    names (filterv (comp (set names) :repo/name))
    org   (filterv #(str/starts-with? (:repo/path %) (str "orgs/" org "/")))
    group (filterv #(some #{group} (:repo/groups %)))))

(defn summarize [results]
  (reduce (fn [acc {:keys [action ok?]}]
            (update acc (if (false? ok?) :failed action) (fnil inc 0)))
          {} results))
