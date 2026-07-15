(ns specialized.store
  "SSoT for the specialized-construction-coordination actor, behind a
  `Store` protocol so the backend is a swap, not a rewrite -- the same
  seam every prior `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/specialized/store_contract_test.clj), which is the whole point:
  the actor, the Specialized Trade Governor and the audit ledger never
  know which SSoT they run on.

  `DatomicStore` uses `langchain-store.core` (ADR-2607141600) for the
  EDN-blob codec, `:db.unique/identity` schema and the seq-keyed
  event-log read/append pattern, instead of hand-rolling `enc`/`dec*`.

  This actor has FOUR coordination-proposal ops, each with its OWN
  append-only history collection and jurisdiction-scoped sequence
  counter: `:log-site-record` (site-record-log), `:schedule-specialized-
  operation` (schedule-proposal), `:flag-safety-concern` (safety-
  concern-flag) and `:order-supplies` (supply-order-proposal). None of
  these is a one-time double-actuation-guarded real-world event -- every
  op may recur any number of times for the same site (a site gets logged
  repeatedly over its project lifecycle, may be rescheduled across
  trades, may accumulate multiple safety-concern flags, may place
  multiple supply orders) BECAUSE this actor never actually dispatches a
  trade crew, authorizes trade-equipment use, or signs off on structural
  completion -- it only ever proposes, logs and schedules (`:effect
  :propose` unconditionally, see `specialized.governor` ns docstring).
  The site's own `:site-verified?` / `:scaffold-inspection-completed?` /
  `:safety-concern-unresolved?` / `:vibration-level-measured` /
  `:vibration-mitigation-installed?` ground-truth fields are what the
  Specialized Trade Governor independently re-checks before a `:schedule-
  specialized-operation` proposal may ever commit (see `specialized.
  governor`).

  The ledger stays append-only on every backend: 'which site was logged,
  which specialized-operation schedule was proposed, which safety concern
  was flagged and notified, which supply order was proposed, on what
  jurisdictional basis, approved by whom' is always a query over an
  immutable log."
  (:require [specialized.registry :as registry]
            [langchain-store.core :as ls]
            [langchain.db :as d]))

(defprotocol Store
  (site [s id])
  (all-sites [s])
  (ledger [s])
  (site-record-log-history [s] "the append-only site-record-log history (specialized.registry drafts)")
  (schedule-proposal-history [s] "the append-only specialized-operation schedule-proposal history")
  (safety-concern-flag-history [s] "the append-only safety-concern-flag history")
  (supply-order-proposal-history [s] "the append-only supply-order-proposal history")
  (next-site-record-sequence [s jurisdiction])
  (next-schedule-sequence [s jurisdiction])
  (next-safety-concern-sequence [s jurisdiction])
  (next-supply-order-sequence [s jurisdiction])
  (commit-record! [s record] "apply a committed op's PROPOSAL record to the SSoT -- see ns docstring, never a real-world actuation")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-sites [s sites] "replace/seed the site directory (map id->site)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained site set covering the happy path (site-1, JPN,
  scaffold-erection with no pile-driving vibration involved), the
  uncovered-jurisdiction / not-verified / scaffold-inspection-incomplete
  / vibration-noncompliant / unresolved-safety-concern failure modes
  (site-2..site-6), a cross-jurisdiction (USA) happy path where the
  jurisdiction is honestly `:qualitative` for vibration (site-7), and a
  second cross-jurisdiction (DEU) happy path where the jurisdiction is
  `:quantitative` (DIN 4150-3 PPV) but the site's own recorded vibration
  reading is comfortably below the trigger (site-8) -- so the actor +
  tests run offline."
  []
  {:sites
   {"site-1" {:id "site-1" :name "Sakura Heights Apartments -- Exterior Scaffold Erection (Repaint Prep)"
              :jurisdiction "JPN" :trades [:scaffold-erection]
              :site-verified? true :scaffold-inspection-completed? true
              :vibration-level-measured nil :vibration-mitigation-installed? nil
              :safety-concern-unresolved? false
              :safety-contacts [{:name "Tanaka (site supervisor)" :email "tanaka@example.com" :phone "+819000000001"}
                                {:name "Suzuki (safety officer)" :email "suzuki@example.com" :phone "+819000000002"}]
              :status :ready-to-schedule}
    "site-2" {:id "site-2" :name "Atlantis Waterfront Tower -- Sheet-Pile Cofferdam"
              :jurisdiction "ATL" :trades [:pile-driving]
              :site-verified? true :scaffold-inspection-completed? true
              :vibration-level-measured 50 :vibration-mitigation-installed? true
              :safety-concern-unresolved? false
              :safety-contacts []
              :status :intake}
    "site-3" {:id "site-3" :name "鈴木ビル改修 -- 外壁改修足場仮設工事"
              :jurisdiction "JPN" :trades [:scaffold-erection]
              :site-verified? false :scaffold-inspection-completed? false
              :vibration-level-measured nil :vibration-mitigation-installed? nil
              :safety-concern-unresolved? false
              :safety-contacts [{:name "Sato (site supervisor)" :email "sato@example.com" :phone "+819000000003"}]
              :status :unverified}
    "site-4" {:id "site-4" :name "田中団地 -- 防水改修足場設置工事"
              :jurisdiction "JPN" :trades [:scaffold-erection :waterproofing]
              :site-verified? true :scaffold-inspection-completed? false
              :vibration-level-measured nil :vibration-mitigation-installed? nil
              :safety-concern-unresolved? false
              :safety-contacts [{:name "Ito (site supervisor)" :email "ito@example.com" :phone "+819000000004"}]
              :status :inspection-pending}
    "site-5" {:id "site-5" :name "ことぶき小学校 -- 基礎補強杭打ち工事"
              :jurisdiction "JPN" :trades [:pile-driving]
              :site-verified? true :scaffold-inspection-completed? true
              :vibration-level-measured 82 :vibration-mitigation-installed? false
              :safety-concern-unresolved? false
              :safety-contacts [{:name "Watanabe (site supervisor)" :email "watanabe@example.com" :phone "+819000000005"}]
              :status :vibration-pending}
    "site-6" {:id "site-6" :name "山田マンション -- 基礎杭増設工事"
              :jurisdiction "JPN" :trades [:pile-driving]
              :site-verified? true :scaffold-inspection-completed? true
              :vibration-level-measured nil :vibration-mitigation-installed? nil
              :safety-concern-unresolved? true
              :safety-contacts [{:name "Yamamoto (site supervisor)" :email "yamamoto@example.com" :phone "+819000000006"}
                                {:name "Kobayashi (safety officer)" :email "kobayashi@example.com" :phone "+819000000007"}]
              :status :concern-open}
    "site-7" {:id "site-7" :name "Riverside Plaza -- Pile-Driving Foundation Reinforcement"
              :jurisdiction "USA" :trades [:pile-driving :scaffold-erection]
              :site-verified? true :scaffold-inspection-completed? true
              :vibration-level-measured nil :vibration-mitigation-installed? true
              :safety-concern-unresolved? false
              :safety-contacts [{:name "Casey (site supervisor)" :email "casey@example.com" :phone "+15550000002"}]
              :status :ready-to-schedule}
    "site-8" {:id "site-8" :name "Alt-Fabrikgelände -- Rammarbeiten Fundamentsanierung"
              :jurisdiction "DEU" :trades [:pile-driving]
              :site-verified? true :scaffold-inspection-completed? true
              :vibration-level-measured 3.0 :vibration-mitigation-installed? false
              :safety-concern-unresolved? false
              :safety-contacts [{:name "Müller (site supervisor)" :email "mueller@example.com" :phone "+4915000000001"}]
              :status :ready-to-schedule}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- log-site-record!
  [s site-id patch]
  (let [a (site s site-id)
        jurisdiction (or (:jurisdiction patch) (:jurisdiction a) "UNKNOWN")
        seq-n (next-site-record-sequence s jurisdiction)
        result (registry/register-site-record site-id jurisdiction seq-n)]
    {:result result :jurisdiction jurisdiction :site-patch patch}))

(defn- schedule-specialized-operation!
  [s site-id]
  (let [a (site s site-id)
        seq-n (next-schedule-sequence s (:jurisdiction a))
        result (registry/register-schedule-proposal site-id (:jurisdiction a) seq-n)]
    {:result result}))

(defn- flag-safety-concern!
  [s site-id concern-description]
  (let [a (site s site-id)
        seq-n (next-safety-concern-sequence s (:jurisdiction a))
        result (registry/register-safety-concern-flag site-id (:jurisdiction a) seq-n)
        concern-number (get result "concern_number")
        doc (registry/render-safety-concern-notice a concern-number concern-description)]
    {:result (assoc-in result ["record" "document"] doc)
     :site-patch {:safety-concern-unresolved? true}}))

(defn- order-supplies!
  [s site-id]
  (let [a (site s site-id)
        seq-n (next-supply-order-sequence s (:jurisdiction a))
        result (registry/register-supply-order-proposal site-id (:jurisdiction a) seq-n)]
    {:result result}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (site [_ id] (get-in @a [:sites id]))
  (all-sites [_] (sort-by :id (vals (:sites @a))))
  (ledger [_] (:ledger @a))
  (site-record-log-history [_] (:site-record-log @a))
  (schedule-proposal-history [_] (:schedule-proposals @a))
  (safety-concern-flag-history [_] (:safety-concern-flags @a))
  (supply-order-proposal-history [_] (:supply-order-proposals @a))
  (next-site-record-sequence [_ jurisdiction] (get-in @a [:site-record-sequences jurisdiction] 0))
  (next-schedule-sequence [_ jurisdiction] (get-in @a [:schedule-sequences jurisdiction] 0))
  (next-safety-concern-sequence [_ jurisdiction] (get-in @a [:safety-concern-sequences jurisdiction] 0))
  (next-supply-order-sequence [_ jurisdiction] (get-in @a [:supply-order-sequences jurisdiction] 0))
  (commit-record! [s {:keys [op path value]}]
    (let [site-id (first path)]
      (case op
        :log-site-record
        (let [{:keys [result jurisdiction site-patch]} (log-site-record! s site-id value)]
          (swap! a (fn [state]
                     (-> state
                         (update-in [:site-record-sequences jurisdiction] (fnil inc 0))
                         (update-in [:sites site-id] merge site-patch)
                         (update :site-record-log registry/append result))))
          result)

        :schedule-specialized-operation
        (let [{:keys [result]} (schedule-specialized-operation! s site-id)
              jurisdiction (:jurisdiction (site s site-id))]
          (swap! a (fn [state]
                     (-> state
                         (update-in [:schedule-sequences jurisdiction] (fnil inc 0))
                         (update :schedule-proposals registry/append result))))
          result)

        :flag-safety-concern
        (let [{:keys [result site-patch]} (flag-safety-concern! s site-id (:concern-description value))
              jurisdiction (:jurisdiction (site s site-id))]
          (swap! a (fn [state]
                     (-> state
                         (update-in [:safety-concern-sequences jurisdiction] (fnil inc 0))
                         (update-in [:sites site-id] merge site-patch)
                         (update :safety-concern-flags registry/append result))))
          result)

        :order-supplies
        (let [{:keys [result]} (order-supplies! s site-id)
              jurisdiction (:jurisdiction (site s site-id))]
          (swap! a (fn [state]
                     (-> state
                         (update-in [:supply-order-sequences jurisdiction] (fnil inc 0))
                         (update :supply-order-proposals registry/append result))))
          result)

        nil))
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-sites [s sites] (when (seq sites) (swap! a assoc :sites sites)) s))

(defn seed-db
  "A MemStore seeded with the demo site set. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :ledger []
                           :site-record-sequences {} :site-record-log []
                           :schedule-sequences {} :schedule-proposals []
                           :safety-concern-sequences {} :safety-concern-flags []
                           :supply-order-sequences {} :supply-order-proposals []))))

;; ----------------------------- DatomicStore (langchain.db + langchain-store) -----------------------------

(def ^:private site-spec
  "langchain-store.core field-spec for the `site` entity -- drives
  `map->tx`/`pull->map`/`pull-pattern` from data instead of hand-written
  triples (ADR-2607141600)."
  {:id                             {:attr :site/id}
   :name                           {:attr :site/name}
   :jurisdiction                   {:attr :site/jurisdiction}
   :trades                         {:attr :site/trades-edn :blob? true :default []}
   :site-verified?                 {:attr :site/site-verified? :coerce boolean}
   :scaffold-inspection-completed? {:attr :site/scaffold-inspection-completed? :coerce boolean}
   :vibration-level-measured       {:attr :site/vibration-level-measured}
   :vibration-mitigation-installed? {:attr :site/vibration-mitigation-installed? :coerce boolean}
   :safety-concern-unresolved?     {:attr :site/safety-concern-unresolved? :coerce boolean}
   :safety-contacts                {:attr :site/safety-contacts-edn :blob? true :default []}
   :status                         {:attr :site/status}})

(def ^:private site-pull (ls/pull-pattern site-spec))

(defn- site->tx [m] (ls/map->tx site-spec m))
(defn- pull->site [pulled] (ls/pull->map site-spec :id pulled))

(def ^:private schema
  (ls/identity-schema [:site/id
                       :ledger/seq
                       :site-record-log/seq
                       :schedule-proposal/seq
                       :safety-concern-flag/seq
                       :supply-order-proposal/seq
                       :site-record-sequence/jurisdiction
                       :schedule-sequence/jurisdiction
                       :safety-concern-sequence/jurisdiction
                       :supply-order-sequence/jurisdiction]))

(defrecord DatomicStore [conn]
  Store
  (site [_ id]
    (pull->site (d/pull (d/db conn) site-pull [:site/id id])))
  (all-sites [_]
    (->> (d/q '[:find [?id ...] :where [?e :site/id ?id]] (d/db conn))
         (map #(pull->site (d/pull (d/db conn) site-pull [:site/id %])))
         (sort-by :id)))
  (ledger [_] (ls/read-stream conn :ledger/seq :ledger/fact))
  (site-record-log-history [_] (ls/read-stream conn :site-record-log/seq :site-record-log/record))
  (schedule-proposal-history [_] (ls/read-stream conn :schedule-proposal/seq :schedule-proposal/record))
  (safety-concern-flag-history [_] (ls/read-stream conn :safety-concern-flag/seq :safety-concern-flag/record))
  (supply-order-proposal-history [_] (ls/read-stream conn :supply-order-proposal/seq :supply-order-proposal/record))
  (next-site-record-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :site-record-sequence/jurisdiction ?j] [?e :site-record-sequence/next ?n]]
            (d/db conn) jurisdiction) 0))
  (next-schedule-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :schedule-sequence/jurisdiction ?j] [?e :schedule-sequence/next ?n]]
            (d/db conn) jurisdiction) 0))
  (next-safety-concern-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :safety-concern-sequence/jurisdiction ?j] [?e :safety-concern-sequence/next ?n]]
            (d/db conn) jurisdiction) 0))
  (next-supply-order-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :supply-order-sequence/jurisdiction ?j] [?e :supply-order-sequence/next ?n]]
            (d/db conn) jurisdiction) 0))
  (commit-record! [s {:keys [op path value]}]
    (let [site-id (first path)]
      (case op
        :log-site-record
        (let [{:keys [result jurisdiction site-patch]} (log-site-record! s site-id value)
              next-n (inc (next-site-record-sequence s jurisdiction))]
          (d/transact! conn
                       [(site->tx (assoc site-patch :id site-id))
                        {:site-record-sequence/jurisdiction jurisdiction :site-record-sequence/next next-n}])
          (ls/append-blob! conn :site-record-log/seq :site-record-log/record
                           (count (site-record-log-history s)) (get result "record"))
          result)

        :schedule-specialized-operation
        (let [{:keys [result]} (schedule-specialized-operation! s site-id)
              jurisdiction (:jurisdiction (site s site-id))
              next-n (inc (next-schedule-sequence s jurisdiction))]
          (d/transact! conn [{:schedule-sequence/jurisdiction jurisdiction :schedule-sequence/next next-n}])
          (ls/append-blob! conn :schedule-proposal/seq :schedule-proposal/record
                           (count (schedule-proposal-history s)) (get result "record"))
          result)

        :flag-safety-concern
        (let [{:keys [result site-patch]} (flag-safety-concern! s site-id (:concern-description value))
              jurisdiction (:jurisdiction (site s site-id))
              next-n (inc (next-safety-concern-sequence s jurisdiction))]
          (d/transact! conn
                       [(site->tx (assoc site-patch :id site-id))
                        {:safety-concern-sequence/jurisdiction jurisdiction :safety-concern-sequence/next next-n}])
          (ls/append-blob! conn :safety-concern-flag/seq :safety-concern-flag/record
                           (count (safety-concern-flag-history s)) (get result "record"))
          result)

        :order-supplies
        (let [{:keys [result]} (order-supplies! s site-id)
              jurisdiction (:jurisdiction (site s site-id))
              next-n (inc (next-supply-order-sequence s jurisdiction))]
          (d/transact! conn [{:supply-order-sequence/jurisdiction jurisdiction :supply-order-sequence/next next-n}])
          (ls/append-blob! conn :supply-order-proposal/seq :supply-order-proposal/record
                           (count (supply-order-proposal-history s)) (get result "record"))
          result)

        nil))
    s)
  (append-ledger! [s fact]
    (ls/append-blob! conn :ledger/seq :ledger/fact (count (ledger s)) fact)
    fact)
  (with-sites [s sites]
    (when (seq sites) (d/transact! conn (mapv site->tx (vals sites)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data` ({:sites
  ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [sites]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-sites s sites))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo site set -- the Datomic-backed
  analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
