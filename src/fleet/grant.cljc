(ns fleet.grant
  "Delegation-chain grants + Governor quorum policy (Phase 2, ADR-2607160005).

  Chains carry authority from an owner root did:key to agent did:keys with
  attenuation — same semantics as cacao-clj's verify-chain (root-first /
  leaf-last, child.resources ⊆ parent, child.iss == parent.aud, expiry),
  in a fleet-native canonical encoding that runs on nbb. Aligning the wire
  format with real CAIP-122 CACAO (cacao-clj) is a tracked follow-up; the
  *rules* are identical so the swap is an encoding change, not a semantics
  change.

  Quorum policy (Radicle crefs transplanted as data):
    {:canonical {:allow #{did ...} :threshold 2}}
  Canonical advances need >= threshold distinct valid signatures from the
  allow set — defense-in-depth against single-key compromise, not
  distributed decision-making."
  (:require [clojure.string :as str]
            [fleet.did :as did]
            [fleet.pin :as pin]))

;; ---------------------------------------------------------------------------
;; links

(defn link-canonical
  "Signing payload for one delegation link. Field order is protocol."
  [{:grant/keys [iss aud resources exp]}]
  (pr-str ["fleet-grant/v1" iss aud (vec (sort resources)) exp]))

(defn verify-chain
  "chain: [link ...] root-first, each {:grant/iss :grant/aud :grant/resources
  :grant/exp :grant/sig}. ctx: {:roots #{did} :verify-fn f :now iso-str}.
  -> {:ok? bool :holder did :resources #{..} :reasons [..]}"
  [chain {:keys [roots verify-fn now]}]
  (if (empty? chain)
    {:ok? false :reasons [:empty-chain]}
    (let [reasons
          (reduce
           (fn [acc [i {:grant/keys [iss aud resources exp sig] :as link}]]
             (let [parent (when (pos? i) (nth chain (dec i)))]
               (cond-> acc
                 (and (zero? i) (not (contains? roots iss)))
                 (conj :root-not-trusted)

                 (and parent (not= iss (:grant/aud parent)))
                 (conj :broken-linkage)

                 (and parent
                      (not (every? (fn [r] (some #(pin/covers? % r)
                                                 (:grant/resources parent)))
                                   resources)))
                 (conj :attenuation-violation)

                 (and exp now (neg? (compare exp now)))
                 (conj :expired)

                 (not (try (verify-fn (did/did->pubkey-hex iss)
                                      (link-canonical link) sig)
                           (catch #?(:clj Exception :cljs :default) _ false)))
                 (conj :bad-link-signature))))
           [] (map-indexed vector chain))]
      (if (seq reasons)
        {:ok? false :reasons (vec (distinct reasons))}
        (let [leaf (last chain)]
          {:ok? true :holder (:grant/aud leaf)
           :resources (set (:grant/resources leaf)) :reasons []})))))

(defn holds?
  "Does a verified chain grant `resource` (trailing-* wildcard)?"
  [{:keys [ok? resources]} resource]
  (boolean (and ok? (some #(pin/covers? % resource) resources))))

;; ---------------------------------------------------------------------------
;; quorum admission for canonical advances

(defn admit-quorum
  "Canonical pin advance under quorum policy.

  proposal: {:record r :signatures [{:signer did :signature hex} ...]}
  ctx: {:policy {:allow #{did} :threshold n}
        :current, :verify-fn, :hash-fn, :reachable?, :value-advance?
        — same meanings as fleet.pin/admit}

  Structural invariants (sequence, parent, reachability, value-advance) are
  identical to the single-key gate; authority is >= threshold distinct valid
  signatures from the allow set over the same canonical payload."
  [{:keys [record signatures]}
   {:keys [policy current verify-fn hash-fn reachable? value-advance?]
    :or {value-advance? true}}]
  (let [{:keys [allow threshold]} policy
        payload (pin/canonical-str record)
        cur-rec (:record current)
        valid-signers
        (into #{}
              (keep (fn [{:keys [signer signature]}]
                      (when (and (contains? allow signer)
                                 (try (verify-fn (did/did->pubkey-hex signer)
                                                 payload signature)
                                      (catch #?(:clj Exception :cljs :default) _ false)))
                        signer)))
              signatures)
        reasons
        (cond-> []
          (< (count valid-signers) (or threshold 1))
          (conj :quorum-not-met)

          (and cur-rec (<= (:pin/sequence record) (:pin/sequence cur-rec)))
          (conj :sequence-rollback)

          (and (nil? cur-rec) (not= 1 (:pin/sequence record)))
          (conj :genesis-sequence-not-1)

          (and cur-rec
               (not= (:pin/parent record)
                     (pin/record-hash hash-fn cur-rec (:signature current))))
          (conj :parent-mismatch)

          (and (nil? cur-rec) (some? (:pin/parent record)))
          (conj :genesis-has-parent)

          (false? reachable?)
          (conj :unreachable-from-upstream-default-branch)

          (and cur-rec (false? value-advance?))
          (conj :value-regression))]
    (cond
      (seq reasons) {:verdict :reject :reasons reasons
                     :valid-signers valid-signers}
      (or (= :unknown reachable?) (= :unknown value-advance?))
      {:verdict :warn-accept :valid-signers valid-signers
       :reasons (cond-> []
                  (= :unknown reachable?) (conj :reachability-unverifiable-fail-open)
                  (= :unknown value-advance?) (conj :value-advance-unverifiable-fail-open))}
      :else {:verdict :accept :reasons [] :valid-signers valid-signers})))
