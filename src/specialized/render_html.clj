(ns specialized.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  This namespace does NOT describe the actor -- it RUNS it. Every id,
  name, number, record id, rule keyword, hold reason, citation and
  status on the generated page comes out of a real
  `specialized.operation` graph run (`langgraph.graph/run*`, with
  `:thread-id` + `:resume?` for the human-in-the-loop approvals) driven
  against a freshly seeded `specialized.store/seed-db`. Nothing on the
  page is hand-typed domain content.

  Where the page states a RULE rather than an observation, the rule is
  re-derived at build time by calling the real predicate
  (`specialized.phase/gate`, `specialized.facts/vibration-noncompliant?`)
  or by reading the real var (`specialized.governor/high-stakes`,
  `.../closed-op-allowlist`, `.../confidence-floor`,
  `.../supply-order-cost-threshold-usd`, `specialized.phase/phases`)
  rather than being asserted in prose here -- so the page self-corrects
  when the rule changes instead of quietly becoming a lie.

  Determinism: the advisor is the deterministic mock, the store is an
  in-memory atom, no wall-clock/`rand`/timestamp value ever reaches the
  page. Two consecutive runs against the same commit are byte-identical
  (verify with `cmp`).

  Build-time invariants (see `-main`): the render REFUSES to write a
  console if the scenario produced no HARD governor hold, or failed to
  exercise every rule `scenario-hard-rules` claims, or produced no
  approval / no rejection / no notification / no ledger. A console that
  silently lost the governor would misrepresent this actor, so the
  failure mode is a thrown exception, not a quieter page.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [specialized.advisor :as advisor]
            [specialized.facts :as facts]
            [specialized.governor :as governor]
            [specialized.notify :as notify]
            [specialized.operation :as op]
            [specialized.phase :as phase]
            [specialized.store :as store]))

;; ----------------------------- scenario -----------------------------

(def ^:private supervisor
  "The phase-3 operator context every step runs under unless it is
  explicitly probing an earlier rollout phase."
  {:actor-id "op-1" :actor-role :site-supervisor :phase 3})

(defn- at-phase [ph] (assoc supervisor :phase ph))

(def scenario-hard-rules
  "The HARD governor rules THIS scenario is built to exercise. This is
  the scenario's own coverage contract, checked in `-main` -- it is NOT
  a claim that `specialized.governor` has only these rules. If a rule is
  renamed or a step stops reaching it, the build fails loudly instead of
  rendering a console with a quietly thinner governor."
  #{:unknown-op
    :effect-not-propose
    :forbidden-action-class
    :site-not-verified
    :no-legal-basis
    :scaffold-inspection-incomplete
    :vibration-noncompliant
    :unresolved-safety-concern})

(defn- compromised-advisor
  "A deliberately COMPROMISED advisor, injected through the same
  `:advisor` seam `specialized.operation/build` documents. It exists to
  prove governor checks 2 and 3 -- `:effect-not-propose` and
  `:forbidden-action-class` -- actually fire, which this repo's own
  deterministic mock advisor can never demonstrate because it never
  emits a non-`:propose` effect or a forbidden-action marker. The
  governor is supposed to be independent of the advisor; this is how
  that independence gets shown rather than asserted."
  []
  (reify advisor/Advisor
    (-advise [_ _store {:keys [subject probe]}]
      (case probe
        :effect-not-propose
        {:summary    (str subject " への現場記録更新（改竄されたadvisorによる直接作動要求）")
         :rationale  "compromised advisor: :effect を :propose 以外に差し替えた提案"
         :cites      [subject]
         :effect     :actuate
         :value      {:site-id subject}
         :stake      nil
         :confidence 0.99}

        :forbidden-action-class
        {:summary    (str subject " への現場記録更新（工事用機材の直接操作マーカー付き）")
         :rationale  "compromised advisor: :effect は :propose のまま、value に機材操作マーカーを混入させた提案"
         :cites      [subject]
         :effect     :propose
         :value      {:site-id subject :trade-equipment-control? true}
         :stake      nil
         :confidence 0.99}))))

(defn- exec! [actor tid request ctx]
  (g/run* actor {:request request :context ctx} {:thread-id tid}))

(defn- resume! [actor tid approval]
  (g/run* actor {:approval approval} {:thread-id tid :resume? true}))

(defn- step!
  "One scenario step: run the actor, and (when `approval` is given and
  the run actually interrupted) resume it with that human decision.
  Records the raw `run*` results so every later derivation reads real
  run output rather than a re-description of it."
  [runs actor tid ctx request & [approval]]
  (let [r1 (exec! actor tid request ctx)
        r2 (when (and approval (= :interrupted (:status r1)))
             (resume! actor tid approval))]
    (swap! runs conj {:tid tid :request request :phase (:phase ctx)
                      :approval approval :r1 r1 :r2 r2})
    (or r2 r1)))

(defn run-demo!
  "Drives a freshly seeded store through one coordination episode.

  Happy path -- site-1 (JPN, scaffold erection): a site-record log entry
  auto-commits at phase 3; a scaffolding schedule proposal ESCALATES
  (`:schedule-specialized-operation` is a permanent `high-stakes` member
  in this actor, never auto at any phase) and a human approves it; a
  scaffold-collapse safety concern ESCALATES, is approved, and the
  notice is actually dispatched to the site's real seeded contact roster
  over mail AND phone; the concern's resolution is logged; a supply
  order below the cost threshold auto-commits and one above it
  escalates and is approved.

  Human rejection: a third supply order escalates and is REJECTED --
  a soft, human decision, structurally different from a governor hold.

  Phase gate: the same clean schedule proposal, replayed at phase 1,
  holds with `:phase-disabled` and NO governor violation at all.

  Approval attribution probe: a `:log-site-record` at phase 1 escalates
  and is approved by a DIFFERENT operator, so the page can measure
  whether the store retains an approver identity.

  Six HARD holds straight off the seed data: an uncovered jurisdiction
  (site-2, ATL), a site that was never independently verified (site-3),
  an incomplete scaffold inspection (site-4), a pile-driving vibration
  reading at/over its jurisdiction's numeric trigger with no mitigation
  installed (site-5), an unresolved safety concern on file (site-6),
  and an op outside the closed four-op allowlist.

  Two further HARD holds through the compromised-advisor injection seam:
  a non-`:propose` effect, and a trade-equipment-control marker.

  Cross-jurisdiction happy paths: site-7 (USA -- honestly `:qualitative`,
  no numeric federal vibration trigger is ever fabricated) and site-8
  (DEU -- `:quantitative` DIN 4150-3 PPV, measured comfortably under the
  trigger), both escalated and approved.

  Returns {:db :runs :notifier}."
  []
  (let [db       (store/seed-db)
        notifier (notify/mock-notifier)
        actor    (op/build db {:notifier notifier})
        rogue    (op/build db {:notifier notifier :advisor (compromised-advisor)})
        runs     (atom [])
        ok       {:status :approved :by "op-1"}]

    ;; -- site-1 full lifecycle -------------------------------------------
    (step! runs actor "t01-log-site-1" supervisor
           {:op :log-site-record :subject "site-1"
            :patch {:id "site-1" :status :ready-to-schedule}})

    (step! runs actor "t02-schedule-site-1" supervisor
           {:op :schedule-specialized-operation :subject "site-1"
            :trade :scaffold-erection
            :window {:proposed-start-date "2026-08-01" :proposed-end-date "2026-08-10"}
            :notes "外壁改修塗装のための外部足場設置工事"}
           ok)

    (step! runs actor "t03-flag-site-1" supervisor
           {:op :flag-safety-concern :subject "site-1"
            :concern-type :scaffold-collapse
            :concern-description "足場の壁つなぎ間隔が仕様書と一致しない箇所を確認、追加点検が必要。"}
           ok)

    (step! runs actor "t04-log-site-1-resolved" supervisor
           {:op :log-site-record :subject "site-1"
            :patch {:id "site-1" :safety-concern-unresolved? false}})

    (step! runs actor "t05-supplies-below" supervisor
           {:op :order-supplies :subject "site-1"
            :items ["scaffold-tube-6m" "coupler-swivel"]
            :cost-usd 800 :vendor "Local Scaffold Supply Co."})

    (step! runs actor "t06-supplies-above" supervisor
           {:op :order-supplies :subject "site-1"
            :items ["hydraulic-pile-hammer-rental"]
            :cost-usd 9000 :vendor "Foundation Equipment Rentals"}
           ok)

    ;; -- a human says no --------------------------------------------------
    (step! runs actor "t07-supplies-rejected" supervisor
           {:op :order-supplies :subject "site-1"
            :items ["vibration-monitoring-array-rental"]
            :cost-usd 12000 :vendor "Foundation Equipment Rentals"}
           {:status :rejected :by "op-1"})

    ;; -- rollout phase gate, no governor violation ------------------------
    (step! runs actor "t08-schedule-phase-1" (at-phase 1)
           {:op :schedule-specialized-operation :subject "site-1"
            :trade :scaffold-erection
            :window {:proposed-start-date "2026-08-01" :proposed-end-date "2026-08-10"}})

    ;; -- approval-attribution probe (different approver) ------------------
    (step! runs actor "t09-log-site-3-approved" (at-phase 1)
           {:op :log-site-record :subject "site-3"
            :patch {:id "site-3" :status :unverified}}
           {:status :approved :by "op-2"})

    ;; -- HARD holds off the seed data -------------------------------------
    (step! runs actor "t10-hold-no-legal-basis" supervisor
           {:op :schedule-specialized-operation :subject "site-2" :trade :pile-driving :window {}})

    (step! runs actor "t11-hold-site-not-verified" supervisor
           {:op :schedule-specialized-operation :subject "site-3" :trade :scaffold-erection :window {}})

    (step! runs actor "t12-hold-inspection-incomplete" supervisor
           {:op :schedule-specialized-operation :subject "site-4" :trade :waterproofing :window {}})

    (step! runs actor "t13-hold-vibration" supervisor
           {:op :schedule-specialized-operation :subject "site-5" :trade :pile-driving :window {}})

    (step! runs actor "t14-hold-unresolved-concern" supervisor
           {:op :schedule-specialized-operation :subject "site-6" :trade :pile-driving :window {}})

    (step! runs actor "t15-hold-unknown-op" supervisor
           {:op :direct-equipment-command :subject "site-1"})

    ;; -- HARD holds against a COMPROMISED advisor -------------------------
    (step! runs rogue "t16-hold-effect-not-propose" supervisor
           {:op :log-site-record :subject "site-1" :probe :effect-not-propose})

    (step! runs rogue "t17-hold-forbidden-action" supervisor
           {:op :log-site-record :subject "site-1" :probe :forbidden-action-class})

    ;; -- cross-jurisdiction happy paths -----------------------------------
    (step! runs actor "t18-schedule-site-7-usa" supervisor
           {:op :schedule-specialized-operation :subject "site-7" :trade :pile-driving
            :window {:proposed-start-date "2026-09-01" :proposed-end-date "2026-09-10"}}
           ok)

    (step! runs actor "t19-schedule-site-8-deu" supervisor
           {:op :schedule-specialized-operation :subject "site-8" :trade :pile-driving
            :window {:proposed-start-date "2026-09-15" :proposed-end-date "2026-09-25"}}
           ok)

    {:db db :runs @runs :notifier notifier}))

;; ----------------------------- derivations over the run -----------------------------

(defn- outcome
  "What actually happened to one step, read off the raw `run*` results --
  never off a label written next to the call site."
  [{:keys [r1 r2]}]
  (let [s1 (:state r1)]
    (cond
      (= :interrupted (:status r1))
      (cond
        (nil? r2)                                     :awaiting-approval
        (= :commit (get-in r2 [:state :disposition])) :approved-commit
        :else                                         :rejected-hold)

      (= :commit (:disposition s1)) :auto-commit
      (seq (get-in s1 [:verdict :violations])) :hard-hold
      :else :phase-hold)))

(def ^:private outcome-label
  {:auto-commit       ["ok"       "auto-commit"]
   :approved-commit   ["ok"       "human-approved commit"]
   :rejected-hold     ["warn"     "human-rejected"]
   :hard-hold         ["critical" "HARD governor hold"]
   :phase-hold        ["warn"     "phase-gate hold"]
   :awaiting-approval ["warn"     "awaiting approval"]})

(defn- audit-of [{:keys [r1 r2]}]
  (get-in (or r2 r1) [:state :audit] []))

(defn- approval-granted-fact [run]
  (first (filter #(= :approval-granted (:t %)) (audit-of run))))

(defn- hard-holds [ledger]
  (filter #(and (= :governor-hold (:t %)) (seq (:violations %))) ledger))

(defn- phase-holds [ledger]
  (filter #(and (= :governor-hold (:t %)) (empty? (:violations %))) ledger))

(defn- rules-exercised [ledger]
  (into (sorted-set) (mapcat #(map :rule (:violations %)) (hard-holds ledger))))

(defn- approver-key-hits
  "Walk `data` and return the sorted, distinct key NAMES matching
  /approv/i whose value is exactly `approver`.

  This is a MEASUREMENT, not an assertion: nothing here decides whether
  this repo's store retains an approver identity. If `commit-record!`
  later starts persisting one, this finds it and the page corrects
  itself with no edit to this file."
  [data approver]
  (letfn [(kname [k] (cond (keyword? k) (name k) (string? k) k :else (str k)))
          (walk [x]
            (cond
              (map? x)        (concat (for [[k v] x
                                            :when (and (re-find #"(?i)approv" (kname k))
                                                       (= v approver))]
                                        (kname k))
                                      (mapcat walk (vals x)))
              (sequential? x) (mapcat walk x)
              (set? x)        (mapcat walk x)
              :else           nil))]
    (vec (sort (distinct (walk data))))))

(def ^:private artifact-families
  "op -> [human label, store read fn]. The persisted surface a committed
  op of that kind lands on, used to measure approver retention."
  {:log-site-record                ["site directory"              store/all-sites]
   :schedule-specialized-operation ["schedule-proposal history"   store/schedule-proposal-history]
   :flag-safety-concern            ["safety-concern-flag history" store/safety-concern-flag-history]
   :order-supplies                 ["supply-order-proposal history" store/supply-order-proposal-history]})

(def ^:private seeded-site-keys
  "The field set the seed data actually declares -- used to surface any
  key that the ACTOR put on a site entity that the seed never had."
  (into #{} (mapcat keys (vals (:sites (store/demo-data))))))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(defn- span [cls text] (str "<span class=\"" cls "\">" (esc text) "</span>"))
(defn- code [text] (str "<code>" (esc text) "</code>"))
(defn- muted [text] (span "muted" text))

(defn- link [url label]
  (str "<a href=\"" (esc url) "\" rel=\"noreferrer\">" (esc label) "</a>"))

(defn- url? [x] (and (string? x) (str/starts-with? x "http")))

(defn- host-of [url]
  (or (second (re-find #"https?://([^/]+)" url)) url))

(defn- truncate [s n]
  (if (> (count s) n) (str (subs s 0 n) "…") s))

(defn- kw-name [x] (if (keyword? x) (str ":" (name x)) (str x)))

(defn- yes-no
  ([v] (yes-no v "ok" "critical"))
  ([v true-cls false-cls]
   (cond (true? v)  (span true-cls "true")
         (false? v) (span false-cls "false")
         :else      (muted "nil"))))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" (esc %) "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lede body]
  (str "  <section class=\"card\">\n"
       "    <h2>" (esc title) "</h2>\n"
       (when lede (str "    <p class=\"muted\">" lede "</p>\n"))
       body
       "  </section>\n"))

;; ----------------------------- sections -----------------------------

(defn- summary-section [{:keys [db runs notifier]}]
  (let [ledger    (vec (store/ledger db))
        cov       (facts/coverage)
        outcomes  (frequencies (map outcome runs))
        rules     (rules-exercised ledger)]
    (section
     "This run"
     (str "Every number below was counted out of the run this page was built from — "
          (esc (str (count runs))) " actor invocations against "
          (code "specialized.store/seed-db") ", no timestamps, no sampling.")
     (table ["Measure" "Value"]
            [(row "actor graph runs" (esc (count runs)))
             (row "ledger facts persisted" (esc (count ledger)))
             (row "committed coordination artifacts"
                  (esc (count (filter #(= :committed (:t %)) ledger))))
             (row "HARD governor holds" (span "critical" (str (count (hard-holds ledger)))))
             (row "distinct HARD rules exercised"
                  (str (esc (count rules)) " &middot; "
                       (str/join ", " (map #(code (kw-name %)) rules))))
             (row "phase-gate holds (no governor violation)"
                  (esc (count (phase-holds ledger))))
             (row "human approvals granted"
                  (esc (count (filter #(= :approved-commit %) (map outcome runs)))))
             (row "human rejections"
                  (esc (count (filter #(= :approval-rejected (:t %)) ledger))))
             (row "safety-concern notice sends (mail + phone)"
                  (esc (count (notify/sent-log notifier))))
             (row "sites in the directory" (esc (count (store/all-sites db))))
             (row "jurisdictions with an official spec-basis"
                  (str (esc (:covered cov)) " / " (esc (:requested cov)) " &middot; "
                       (esc (str/join ", " (:covered-jurisdictions cov)))))
             (row "step outcomes"
                  (str/join " &middot; "
                            (for [[k n] (sort-by (comp str key) outcomes)]
                              (let [[cls label] (outcome-label k)]
                                (str (span cls label) " &times;" (esc n))))))]))))

(defn- last-fact-for [ledger site-id]
  (last (filter #(= (:subject %) site-id) ledger)))

(defn- site-status-cell [ledger site-id]
  (let [f (last-fact-for ledger site-id)]
    (cond
      (nil? f) (muted "no activity in this run")
      (= :committed (:t f)) (span "ok" (str "committed · " (name (:op f))))
      (= :approval-rejected (:t f)) (span "warn" (str "human-rejected · " (name (:op f))))
      (= :governor-hold (:t f))
      (if-let [rule (-> f :violations first :rule)]
        (span "critical" (str "HARD hold · " (name rule)))
        (span "warn" (str "phase-gate hold · " (name (or (:phase-reason f) :unknown)))))
      :else (muted (name (:t f))))))

(defn- vibration-cell [{:keys [jurisdiction vibration-level-measured] :as site}]
  (let [sb  (facts/spec-basis jurisdiction)
        res (facts/vibration-noncompliant? jurisdiction site)
        trig (when sb
               (if (:vibration-trigger-value sb)
                 (str (:vibration-trigger-value sb) " " (name (:vibration-trigger-unit sb)))
                 "no numeric trigger"))]
    (str (cond
           (true? res)          (span "critical" "noncompliant")
           (false? res)         (span "ok" "compliant")
           (= :qualitative res) (span "muted" "qualitative — not arithmetically decidable")
           :else                (span "warn" "no spec-basis"))
         "<br>"
         (muted (str "measured " (pr-str vibration-level-measured)
                     " · trigger " (or trig "n/a"))))))

(defn- extra-keys-cell [site]
  (let [extra (sort (map str (remove seeded-site-keys (keys site))))]
    (if (seq extra)
      (span "warn" (str/join ", " extra))
      (muted "—"))))

(defn- site-row [ledger {:keys [id name jurisdiction trades site-verified?
                                scaffold-inspection-completed?
                                vibration-mitigation-installed?
                                safety-concern-unresolved? status] :as site}]
  (row (code id)
       (esc name)
       (esc jurisdiction)
       (esc (str/join ", " (map clojure.core/name trades)))
       (yes-no site-verified?)
       (yes-no scaffold-inspection-completed?)
       (vibration-cell site)
       (yes-no vibration-mitigation-installed? "ok" "warn")
       (yes-no safety-concern-unresolved? "critical" "ok")
       (code (kw-name status))
       (extra-keys-cell site)
       (site-status-cell ledger id)))

(defn- sites-section [db]
  (let [ledger (vec (store/ledger db))
        sites  (store/all-sites db)]
    (section
     "Specialized-construction sites"
     (str "The live site directory after the run, read back through "
          (code "specialized.store/all-sites") ". The vibration verdict column is "
          "not a stored field — it is recomputed here by calling "
          (code "specialized.facts/vibration-noncompliant?")
          ", the same predicate the governor calls. "
          (code "extra keys")
          " lists any field the ACTOR added that the seed schema never declared.")
     (table ["Site" "Name" "Jur." "Trades" "verified?" "scaffold insp.?"
             "vibration (recomputed)" "mitigation?" "concern open?" "status"
             "extra keys" "last fact this run"]
            (map (partial site-row ledger) sites)))))

(defn- gate-cell [ph op]
  (let [{:keys [disposition reason]} (phase/gate ph {:op op} :commit)]
    (str (case disposition
           :hold     (span "critical" "disabled")
           :escalate (span "warn" "human approval")
           :commit   (span "ok" "auto-commit")
           (muted (str disposition)))
         (when reason (str "<br>" (muted (kw-name reason)))))))

(defn- observed-cell [runs op]
  (let [obs (frequencies (map outcome (filter #(= op (:op (:request %))) runs)))]
    (if (empty? obs)
      (muted "not exercised")
      (str/join "<br>"
                (for [[k n] (sort-by (comp str key) obs)]
                  (let [[cls label] (outcome-label k)]
                    (str (span cls label) " &times;" (esc n))))))))

(defn- action-gate-section [runs]
  (let [ops    (vec (sort-by str governor/closed-op-allowlist))
        phs    (vec (sort (keys phase/phases)))
        ;; derived, not asserted: an op is "never auto-eligible" when NO
        ;; phase in the real table lets it auto-commit.
        never-auto (filterv (fn [o] (not-any? #(= :commit (:disposition (phase/gate % {:op o} :commit))) phs)) ops)
        ;; derived, not asserted: a governor HOLD survives every phase gate.
        hold-wins? (every? (fn [[ph o]] (= :hold (:disposition (phase/gate ph {:op o} :hold))))
                           (for [ph phs o ops] [ph o]))]
    (section
     "Action gate"
     (str "Each cell was produced at build time by calling "
          (code "(specialized.phase/gate phase {:op op} :commit)")
          " — this table is the real gate, not a description of it. "
          "Permanent " (code "high-stakes") " members (read from "
          (code "specialized.governor/high-stakes") "): "
          (str/join ", " (map #(code (kw-name %)) (sort-by str governor/high-stakes))) ". "
          "Ops that no phase ever auto-commits: "
          (str/join ", " (map #(code (kw-name %)) never-auto)) ". "
          "Re-checked here across " (esc (* (count phs) (count ops)))
          " phase&times;op combinations: a governor HOLD survives the phase gate in every one — "
          (if hold-wins? (span "ok" "verified this build") (span "critical" "NOT verified this build"))
          ". Soft escalation thresholds read from the real vars: "
          (code (str "confidence-floor " governor/confidence-floor)) ", "
          (code (str "supply-order-cost-threshold-usd " governor/supply-order-cost-threshold-usd)) ".")
     (table (concat ["Op" "high-stakes?"]
                    (map #(str "phase " % " · " (:label (get phase/phases %))) phs)
                    ["observed this run"])
            (for [o ops]
              (apply row
                     (concat [(code (kw-name o))
                              (if (contains? governor/high-stakes o)
                                (span "warn" "always human")
                                (muted "no"))]
                             (map #(gate-cell % o) phs)
                             [(observed-cell runs o)])))))))

(defn- hold-rules-section [db]
  (let [ledger (vec (store/ledger db))
        holds  (hard-holds ledger)
        by-rule (->> (for [h holds v (:violations h)] (assoc v :subject (:subject h) :op (:op h)))
                     (group-by :rule)
                     (sort-by (comp str key)))
        phs    (phase-holds ledger)]
    (section
     "HARD governor holds this run produced"
     (str "A HARD hold never reaches a human — "
          (code ":request-approval")
          " is not on its path at all, so no approver can override it. "
          "Rules and detail text below are lifted verbatim out of the "
          (code ":governor-hold")
          " facts this run wrote to the ledger. "
          (esc (count phs)) " further hold(s) came from the rollout PHASE gate with no governor "
          "violation at all — a structurally different reason to stop, shown separately in the ledger.")
     (table ["Rule" "Holds" "Sites" "Op" "Detail (verbatim from the run)"]
            (for [[rule vs] by-rule]
              (row (code (kw-name rule))
                   (span "critical" (str (count vs)))
                   (str/join ", " (map #(code %) (sort (distinct (map :subject vs)))))
                   (str/join ", " (map #(code (kw-name %)) (sort-by str (distinct (map :op vs)))))
                   (esc (:detail (first vs)))))))))

(defn- jurisdiction-section []
  (let [cov (facts/coverage)]
    (section
     "Jurisdiction spec-basis catalog"
     (str "Read straight out of " (code "specialized.facts/catalog") " at build time. "
          (esc (:note cov))
          " A jurisdiction absent from this table has NO spec-basis: the advisor may not invent one, "
          "and the governor holds any schedule proposal that tries — that is exactly what "
          (code "site-2") " (ATL) demonstrates above.")
     (table ["Jur." "Name" "Threshold model" "Vibration trigger" "Scaffold-inspection basis" "Sources"]
            (for [[iso3 m] (sort-by key facts/catalog)]
              (row (code iso3)
                   (esc (:name m))
                   (if (= :quantitative (:threshold-model m))
                     (span "ok" "quantitative")
                     (span "warn" "qualitative"))
                   (if (:vibration-trigger-value m)
                     (esc (str (:vibration-trigger-value m) " "
                               (name (:vibration-trigger-unit m))))
                     (muted "none — not fabricated"))
                   (esc (truncate (:scaffold-inspection-basis m) 220))
                   (str/join "<br>"
                             (for [u [(:scaffold-inspection-provenance m) (:vibration-provenance m)]
                                   :when (url? u)]
                               (link u (host-of u))))))))))

(defn- approval-section [{:keys [db runs]}]
  (let [ledger    (vec (store/ledger db))
        approved  (filter #(= :approved-commit (outcome %)) runs)
        rows
        (for [r approved
              :let [op       (:op (:request r))
                    subject  (:subject (:request r))
                    fact     (approval-granted-fact r)
                    approver (:by fact)
                    [label read-fn] (get artifact-families op)
                    hits     (when read-fn (approver-key-hits (read-fn db) approver))
                    led-hits (approver-key-hits ledger approver)]]
          (row (code (:tid r))
               (code (kw-name op))
               (code subject)
               (if approver (esc approver) (span "critical" "MISSING from the audit fact"))
               (if (seq hits)
                 (span "ok" (str "retained in " label " as " (str/join ", " hits)))
                 (span "warn" (str "NOT retained in " (or label "the persisted artifact"))))
               (if (seq led-hits)
                 (span "ok" (str/join ", " led-hits))
                 (span "warn" "not retained")))
              )]
    (section
     "Approval attribution (measured, not assumed)"
     (str "Each row was measured by searching the persisted artifact for a key matching "
          (code "/approv/i") " whose value equals the approver named in this run's own "
          (code ":approval-granted") " audit fact. Nothing here is hard-coded: if "
          (code "specialized.store/commit-record!")
          " is later changed to persist an approver, these cells flip to "
          (span "ok" "retained") " with no edit to the generator. "
          "Where a cell says " (span "warn" "NOT retained")
          ", the approver is real and auditable in the run's audit channel but is absent from the "
          "durable record — that distinction is disclosed here rather than left as a blank column.")
     (table ["Thread" "Op" "Site" "Approver (audit fact)" "Retained in the artifact?" "Retained in the ledger?"]
            rows))))

(defn- artifacts-section [db]
  (let [families [["Site-record log"        (store/site-record-log-history db)]
                  ["Schedule proposals"     (store/schedule-proposal-history db)]
                  ["Safety-concern flags"   (store/safety-concern-flag-history db)]
                  ["Supply-order proposals" (store/supply-order-proposal-history db)]]
        rows (for [[label recs] families
                   r recs]
               (row (esc label)
                    (code (get r "record_id"))
                    (esc (get r "kind"))
                    (code (get r "site_id"))
                    (esc (get r "jurisdiction"))
                    (yes-no (get r "immutable"))))]
    (str
     (section
      "Coordination artifacts committed"
      (str "Every record id below was minted by " (code "specialized.registry")
           " during this run — jurisdiction-scoped sequence numbers, not invented reference formats. "
           "None of these is a real-world actuation: this actor holds no trade-equipment-control and no "
           "structural-completion sign-off authority, so a committed artifact means only "
           "\"this coordination record is now on file\".")
      (table ["Kind" "Record id" "record kind" "Site" "Jur." "immutable"] rows))
     ;; the safety-concern notice document, verbatim
     (section
      "Safety-concern notice document (verbatim)"
      (str "Rendered by " (code "specialized.registry/render-safety-concern-notice")
           " during this run and dispatched to the site's seeded contact roster. "
           "It cites the jurisdiction's scaffold-inspection legal basis inline, so the notice is "
           "self-evidencing about which regulation grounds the concern.")
      (str/join
       (for [r (store/safety-concern-flag-history db)]
         (str "    <details><summary>" (code (get r "record_id")) "</summary>\n"
              "    <pre>" (esc (get r "document")) "</pre>\n"
              "    </details>\n")))))))

(defn- notify-section [{:keys [db notifier]}]
  (let [sent     (notify/sent-log notifier)
        contacts (mapcat :safety-contacts (store/all-sites db))]
    (section
     "Safety-concern notice dispatch"
     (str "The mock transport (" (code "specialized.notify/mock-notifier")
          ") recorded these sends. Recipients come from the flagged site's own seeded "
          (code ":safety-contacts") " roster — the site supervisor and safety officer, never the trade "
          "crew directly, because this actor coordinates and does not dispatch a crew. "
          "The notice goes out only AFTER a human approved the flag: "
          (code ":flag-safety-concern") " is never auto-eligible at any phase. "
          (esc (count sent)) " send(s) fanned out of "
          (esc (count contacts)) " contact(s) seeded across the whole directory.")
     (table ["Channel" "To" "Status" "Content"]
            (for [s sent]
              (row (code (kw-name (:channel s)))
                   (esc (:to s))
                   (if (= :sent (:status s)) (span "ok" "sent") (span "critical" (str (:status s))))
                   (esc (truncate (or (:subject s) (:message s) "") 160))))))))

(defn- basis-bit [x]
  (cond
    (keyword? x) (code (kw-name x))
    (url? x)     (link x (host-of x))
    (string? x)  (esc (truncate x 44))
    :else        (esc (str x))))

(defn- ledger-basis-cell [{:keys [violations basis phase-reason phase disposition]}]
  (cond
    (seq violations) (str/join ", " (map #(code (kw-name (:rule %))) violations))
    phase-reason     (str (span "warn" (str "phase gate · " (name phase-reason)))
                          " " (muted (str "phase " phase)))
    (seq basis)      (str/join ", " (map basis-bit basis))
    :else            (muted (str (or disposition "")))))

(defn- ledger-section [db]
  (let [ledger (vec (store/ledger db))]
    (section
     "Audit ledger (this run)"
     (str "The append-only decision-fact log, in the order the actor wrote it — "
          (esc (count ledger)) " facts. Holds and commits share one log, which is the point: "
          "\"why did nothing happen\" is as queryable as \"what happened\".")
     (table ["#" "Fact" "Op" "Site" "Disposition" "Basis"]
            (map-indexed
             (fn [i {:keys [t op subject disposition] :as f}]
               (row (esc (inc i))
                    (case t
                      :committed         (span "ok" "committed")
                      :governor-hold     (if (seq (:violations f))
                                           (span "critical" "governor-hold")
                                           (span "warn" "governor-hold"))
                      :approval-rejected (span "warn" "approval-rejected")
                      (esc (name t)))
                    (code (kw-name (or op :n-a)))
                    (code subject)
                    (esc (name (or disposition :n-a)))
                    (ledger-basis-cell f)))
             ledger)))))

(defn- provenance-section [blueprint]
  (section
   "How this page was made"
   nil
   (table ["Fact" "Value"]
          [(row "generator" (code "specialized.render-html"))
           (row "command" (code "clojure -M:dev:render-html"))
           (row "actor stack"
                (str (code "specialized.operation") " &rarr; " (code "specialized.governor")
                     " &rarr; " (code "specialized.phase") " &rarr; " (code "specialized.store")
                     " (driven with " (code "langgraph.graph/run*") ")"))
           (row "seed" (code "specialized.store/seed-db"))
           (row "advisor" (str (code "specialized.advisor/mock-advisor")
                               " (deterministic) plus one deliberately compromised advisor "
                               "injected through the documented " (code ":advisor")
                               " seam to prove governor checks 2 and 3 fire"))
           (row "determinism" (esc "no timestamp, no rand, no wall-clock value reaches this page; two runs at the same commit are byte-identical"))
           (row "ISIC rev.5" (code (:itonami.blueprint/isic-rev5 blueprint)))
           (row "governor" (code (kw-name (:itonami.blueprint/governor blueprint))))
           (row "robotics authority"
                (if (:itonami.blueprint/robotics blueprint)
                  (span "warn" "true")
                  (span "ok" "false — holds no trade-equipment-control authority")))
           (row "social impact"
                (str/join ", " (map #(code (kw-name %))
                                    (:itonami.blueprint/social-impact blueprint))))
           (row "styling" (str (code "jp-go-dds.skin/dds+skin")
                               " (デジタル庁デザインシステム)"))])))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole console from a completed `run-demo!` result and the
  repo's own `blueprint.edn`."
  [{:keys [db] :as demo} blueprint]
  (let [ledger (vec (store/ledger db))]
    (str
     "<!doctype html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-" (esc (:itonami.blueprint/isic-rev5 blueprint))
     " &middot; " (esc (:itonami.blueprint/name blueprint)) "</title>\n"
     "<style>" (jp-go-dds.skin/dds+skin) "</style>\n"
     "</head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>" (esc (:itonami.blueprint/name blueprint))
     " (ISIC " (esc (:itonami.blueprint/isic-rev5 blueprint)) ") — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · generated at build time from a real actor run · "
     (esc (count ledger)) " ledger facts · "
     (esc (count (hard-holds ledger))) " HARD governor holds</span>\n"
     "</header>\n"
     "<main>\n"
     (summary-section demo)
     (sites-section db)
     (action-gate-section (:runs demo))
     (hold-rules-section db)
     (jurisdiction-section)
     (approval-section demo)
     (artifacts-section db)
     (notify-section demo)
     (ledger-section db)
     (provenance-section blueprint)
     "</main>\n"
     "</body></html>\n")))

;; ----------------------------- entry point -----------------------------

(defn- read-blueprint!
  "The ISIC id/title/governor on the page come from the repo's own
  manifest, never from a string typed here. Absent input is a failure,
  not a quieter page."
  []
  (let [f (io/file "blueprint.edn")]
    (when-not (.exists f)
      (throw (ex-info "blueprint.edn not found — run this from the repo root"
                      {:cwd (System/getProperty "user.dir")})))
    (edn/read-string (slurp f))))

(defn- check-invariants!
  "Refuse to write a console that would misrepresent the actor."
  [{:keys [db runs notifier]}]
  (let [ledger (vec (store/ledger db))
        hard   (hard-holds ledger)
        rules  (rules-exercised ledger)
        missing (into (sorted-set) (remove rules scenario-hard-rules))]
    (when (empty? ledger)
      (throw (ex-info "empty ledger — the scenario produced no decision facts at all" {})))
    (when (empty? runs)
      (throw (ex-info "no actor runs — nothing was driven through the graph" {})))
    (when (zero? (count hard))
      (throw (ex-info "no HARD hold in scenario — console would misrepresent the governor"
                      {:ledger-facts (count ledger)})))
    (when (seq missing)
      (throw (ex-info "scenario failed to exercise every HARD rule it claims"
                      {:missing (vec missing) :exercised (vec rules)})))
    (when-not (some #(= :approved-commit (outcome %)) runs)
      (throw (ex-info "no human-approved commit — the approval path is unproven" {})))
    (when-not (some #(= :approval-rejected (:t %)) ledger)
      (throw (ex-info "no human rejection — the rejection path is unproven" {})))
    (when-not (some #(= :phase-hold (outcome %)) runs)
      (throw (ex-info "no phase-gate hold — the rollout gate is unproven" {})))
    (when (empty? (notify/sent-log notifier))
      (throw (ex-info "no safety-concern notice dispatched — the notify seam is unproven" {})))
    {:ledger ledger :hard hard :rules rules}))

(defn -main [& args]
  (let [out       (or (first args) "docs/samples/operator-console.html")
        blueprint (read-blueprint!)
        demo      (run-demo!)
        {:keys [ledger hard rules]} (check-invariants! demo)
        html      (render demo blueprint)]
    (io/make-parents out)
    (spit out html)
    (println (str "wrote " out
                  " (" (count html) " bytes, "
                  (count (:runs demo)) " actor runs, "
                  (count ledger) " ledger facts, "
                  (count hard) " HARD holds over "
                  (count rules) " distinct rules: "
                  (str/join " " (map str rules)) ", "
                  (count (notify/sent-log (:notifier demo))) " notice sends)"))))
