(ns specialized.governor-contract-test
  "The governor contract as executable tests -- the specialized-
  construction-coordination analog of `installation.governor-contract-
  test`/`finishing.governor-contract-test`. The single invariant under
  test:

    Specialized Trade Advisor never schedules a specialized operation,
    files a safety-concern flag or a supply order the Specialized Trade
    Governor would reject; `:flag-safety-concern` AND `:schedule-
    specialized-operation` NEVER auto-commit at any phase (BOTH are
    PERMANENT `high-stakes` members here -- a deliberate, documented
    difference from `installation.governor`/`finishing.governor`, see
    `specialized.governor` ns docstring); `:log-site-record` (no direct
    capital/safety risk) and `:order-supplies` (below the cost threshold)
    MAY auto-commit when clean; and every decision (commit OR hold)
    leaves exactly one ledger fact. Every committed record's `:effect` is
    `:propose` -- this actor never performs a real-world actuation."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [specialized.store :as store]
            [specialized.operation :as op]
            [specialized.governor :as governor]
            [specialized.phase :as phase]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :site-supervisor :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

;; ----------------------------- :log-site-record -----------------------------

(deftest clean-log-site-record-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-site-record :subject "site-1" :patch {:id "site-1" :status :in-progress}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :in-progress (:status (store/site db "site-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))
    (is (= "JPN-SRL-000000" (get (first (store/site-record-log-history db)) "record_id")))))

(deftest log-site-record-can-resolve-a-safety-concern
  (let [[db actor] (fresh)]
    (exec-op actor "t1b" {:op :log-site-record :subject "site-6" :patch {:id "site-6" :safety-concern-unresolved? false}} operator)
    (is (false? (:safety-concern-unresolved? (store/site db "site-6"))))))

;; ----------------------------- :schedule-specialized-operation -----------------------------

(deftest clean-schedule-specialized-operation-escalates-then-commits-after-approval
  (testing "site-1 is fully clean (verified, scaffold-inspected, no vibration involved, high confidence) -- still ESCALATES at phase 3 (a PERMANENT high-stakes member here, UNLIKE the sibling installation/finishing schedule ops), human approves, then commits"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t2" {:op :schedule-specialized-operation :subject "site-1" :trade :scaffold-erection :window {}} operator)]
      (is (= :interrupted (:status r1)) "always a human's call for this actor, even when the governor is clean")
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= "JPN-SCH-000000" (get (first (store/schedule-proposal-history db)) "record_id")))
        (is (= 1 (count (store/schedule-proposal-history db))))))))

(deftest fabricated-jurisdiction-is-held
  (testing "site-2 (ATL, no spec-basis in specialized.facts) -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3" {:op :schedule-specialized-operation :subject "site-2" :trade :pile-driving :window {}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-legal-basis} (-> (store/ledger db) first :basis)))
      (is (empty? (store/schedule-proposal-history db)) "no schedule proposal recorded"))))

(deftest not-independently-verified-site-is-held
  (testing "site-3 has site-verified? false -> HARD hold, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :schedule-specialized-operation :subject "site-3" :trade :scaffold-erection :window {}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:site-not-verified} (-> (store/ledger db) first :basis)))
      (is (empty? (store/schedule-proposal-history db))))))

(deftest scaffold-inspection-incomplete-is-held
  (testing "site-4 has scaffold-inspection-completed? false -> HARD hold"
    (let [[db actor] (fresh)
          res (exec-op actor "t5" {:op :schedule-specialized-operation :subject "site-4" :trade :waterproofing :window {}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:scaffold-inspection-incomplete} (-> (store/ledger db) first :basis))))))

(deftest vibration-noncompliant-is-held
  (testing "site-5's vibration-level-measured (82 dB) meets/exceeds JPN's 75 dB trigger with no mitigation installed -> HARD hold, independent of proposal confidence"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :schedule-specialized-operation :subject "site-5" :trade :pile-driving :window {}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:vibration-noncompliant} (-> (store/ledger db) first :basis))))))

(deftest unresolved-safety-concern-is-held
  (testing "site-6 has safety-concern-unresolved? true on file -> HARD hold, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t7" {:op :schedule-specialized-operation :subject "site-6" :trade :pile-driving :window {}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:unresolved-safety-concern} (-> (store/ledger db) first :basis)))
      (is (empty? (store/schedule-proposal-history db))))))

(deftest qualitative-jurisdiction-never-fabricates-a-numeric-hold-and-still-escalates-when-clean
  (testing "site-7 (USA, qualitative) -- vibration-noncompliant? never fires there; clean + high confidence -> STILL ESCALATES (permanent high-stakes), commits after approval"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t8" {:op :schedule-specialized-operation :subject "site-7" :trade :pile-driving :window {}} operator)]
      (is (= :interrupted (:status r1)))
      (let [r2 (approve! actor "t8")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= "USA-SCH-000000" (get (first (store/schedule-proposal-history db)) "record_id")))))))

(deftest deu-quantitative-happy-path-below-trigger-escalates-then-commits
  (testing "site-8 (DEU, quantitative DIN 4150-3, 3.0 mm/s well below the 5 mm/s row-2 trigger) -- clean, high confidence -> STILL ESCALATES (permanent high-stakes), commits after approval"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t9" {:op :schedule-specialized-operation :subject "site-8" :trade :pile-driving :window {}} operator)]
      (is (= :interrupted (:status r1)))
      (let [r2 (approve! actor "t9")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= "DEU-SCH-000000" (get (first (store/schedule-proposal-history db)) "record_id")))))))

(deftest low-confidence-schedule-still-escalates-when-governor-clean
  (testing "the SOFT confidence-floor path also escalates, exercised directly via governor/check since :schedule-specialized-operation is already unconditionally high-stakes through the mock advisor's normal path"
    (let [db (store/seed-db)
          request {:op :schedule-specialized-operation :subject "site-1"}
          proposal {:summary "s" :rationale "r" :cites ["x"] :effect :propose
                    :value {:site-id "site-1" :spec-basis "https://example.com"} :stake :schedule-specialized-operation :confidence 0.4}
          verdict (governor/check request {} proposal db)]
      (is (not (:hard? verdict)))
      (is (:escalate? verdict) "confidence below the floor AND permanent high-stakes both independently force escalation"))))

;; ----------------------------- :flag-safety-concern -----------------------------

(deftest flag-safety-concern-always-escalates-even-when-clean
  (testing "site-1 is fully clean -- :flag-safety-concern STILL always interrupts, unconditionally"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t10" {:op :flag-safety-concern :subject "site-1"
                                   :concern-type :scaffold-collapse
                                   :concern-description "wall-tie spacing observed inconsistent with scaffold design drawing"} operator)]
      (is (= :interrupted (:status r1)))
      (let [r2 (approve! actor "t10")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:safety-concern-unresolved? (store/site db "site-1"))))
        (is (some? (get (first (store/safety-concern-flag-history db)) "document")) "rendered notice document present")))))

(deftest flag-safety-concern-triggers-notification-only-after-approval
  (let [[_db actor] (fresh)
        r1 (exec-op actor "t11" {:op :flag-safety-concern :subject "site-1"
                                 :concern-type :scaffold-collapse :concern-description "suspected undersized base-plate load path"} operator)]
    (is (nil? (:notify-result (:state r1))) "no notify before human approval")
    (let [r2 (approve! actor "t11")
          notify-result (:notify-result (:state r2))]
      (is (= 2 (count notify-result)) "one result entry per site-1 safety-contact")
      (is (every? #(= :sent (get-in % [:mail :status])) notify-result))
      (is (every? #(= :sent (get-in % [:phone :status])) notify-result)))))

;; ----------------------------- :order-supplies -----------------------------

(deftest order-supplies-below-threshold-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t12" {:op :order-supplies :subject "site-1"
                                  :items ["scaffold-tube-6m"] :cost-usd 800 :vendor "Local Scaffold Supply Co."} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 1 (count (store/supply-order-proposal-history db))))))

(deftest order-supplies-above-threshold-escalates
  (let [[db actor] (fresh)
        r1 (exec-op actor "t13" {:op :order-supplies :subject "site-1"
                                 :items ["hydraulic-pile-hammer-rental"] :cost-usd 9000 :vendor "Foundation Equipment Rentals"} operator)]
    (is (= :interrupted (:status r1)) "above cost threshold -- always a human's call")
    (let [r2 (approve! actor "t13")]
      (is (= :commit (get-in r2 [:state :disposition])))
      (is (= 1 (count (store/supply-order-proposal-history db)))))))

;; ----------------------------- closed op-allowlist -----------------------------

(deftest op-outside-the-closed-allowlist-is-held
  (testing "an op outside {:log-site-record :schedule-specialized-operation :flag-safety-concern :order-supplies} -> HARD hold, never reaches a human, regardless of what the advisor's default branch returns"
    (let [[db actor] (fresh)
          res (exec-op actor "t14" {:op :direct-equipment-command :subject "site-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:unknown-op} (-> (store/ledger db) first :basis))))))

;; ----------------------------- effect / forbidden-action-class (direct governor/check) -----------------------------
;; The mock advisor never produces these -- they exercise the governor's
;; defense-in-depth against a hypothetically compromised/malfunctioning
;; advisor, so they are tested directly against `governor/check` rather
;; than through the full actor (which can only ever see what the mock
;; advisor actually proposes).

(deftest effect-not-propose-is-a-permanent-hard-violation
  (let [db (store/seed-db)
        request {:op :log-site-record :subject "site-1"}
        proposal {:summary "s" :rationale "r" :cites ["x"] :effect :direct-write
                  :value {:id "site-1"} :stake nil :confidence 0.99}
        verdict (governor/check request {} proposal db)]
    (is (:hard? verdict))
    (is (some #{:effect-not-propose} (map :rule (:violations verdict))))
    (is (not (:ok? verdict)))))

(deftest forbidden-action-class-markers-are-permanent-hard-violations
  (doseq [marker [:trade-equipment-control? :direct-actuation? :finalizes-structural-completion-sign-off?]]
    (testing marker
      (let [db (store/seed-db)
            request {:op :schedule-specialized-operation :subject "site-1"}
            proposal {:summary "s" :rationale "r" :cites ["x"] :effect :propose
                      :value {marker true} :stake :schedule-specialized-operation :confidence 0.99}
            verdict (governor/check request {} proposal db)]
        (is (:hard? verdict))
        (is (some #{:forbidden-action-class} (map :rule (:violations verdict))))))))

;; ----------------------------- ledger discipline -----------------------------

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-site-record :subject "site-1" :patch {:id "site-1" :status :in-progress}} operator)
      (exec-op actor "b" {:op :schedule-specialized-operation :subject "site-2" :trade :pile-driving :window {}} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))

(deftest approver-rejection-is-held-not-committed
  (let [[db actor] (fresh)
        r1 (exec-op actor "t15" {:op :flag-safety-concern :subject "site-1"
                                 :concern-type :scaffold-collapse :concern-description "test"} operator)]
    (is (= :interrupted (:status r1)))
    (let [r2 (g/run* actor {:approval {:status :rejected :by "op-1"}} {:thread-id "t15" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/safety-concern-flag-history db))))))

;; ----------------------------- phase structural invariants (belt-and-suspenders) -----------------------------

(deftest flag-safety-concern-never-auto-at-any-phase
  (testing "structural invariant: never auto-eligible, even when clean, at any phase"
    (is (= :escalate (:disposition (phase/gate 3 {:op :flag-safety-concern} :commit)))
        ":flag-safety-concern must escalate to a human even when the governor is clean at phase 3")))

(deftest schedule-specialized-operation-never-auto-at-any-phase-unlike-siblings
  (testing "structural invariant proving the deliberate difference from installation.phase/finishing.phase"
    (is (= :escalate (:disposition (phase/gate 3 {:op :schedule-specialized-operation} :commit)))
        ":schedule-specialized-operation must ALWAYS escalate to a human even when the governor is clean at phase 3 -- see specialized.governor/specialized.phase ns docstrings")))
