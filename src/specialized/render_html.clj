(ns specialized.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for `cloud-itonami-isic-4390`: this
  repo previously had NO demo page and no generator at all. This
  namespace drives the REAL actor stack -- `specialized.operation`
  (langgraph StateGraph) -> `specialized.governor` ->
  `specialized.store` -- and renders the resulting SSoT, audit ledger,
  coordination-artifact registers and notifier send-log. Nothing on the
  page is hand-typed domain data: every site, jurisdiction, record id,
  hold rule, hold detail, approver and notice document below is read
  back out of the objects the run actually produced.

  Scenario design (see `run-demo!`) is adapted from this repo's own
  `specialized.sim` demo driver (`clojure -M:dev:run`, confirmed to run
  green BEFORE this file was written), extended so that ALL EIGHT of
  `specialized.governor`'s HARD checks actually fire, plus the phase
  gate's `:phase-disabled` hold and an approver REJECTION -- three
  disposition kinds `specialized.sim` does not reach.

  Determinism: the store is freshly seeded per run, the mock advisor and
  mock notifier are deterministic, and NOTHING here reads a clock or a
  random source -- two consecutive runs are byte-identical. Verify with
  `diff <(clojure -M:dev:render-html /dev/stdout) ...` or by rendering
  into two `mktemp -d` scratch files and comparing.

  Build-time invariant (NOT a comment -- see `-main`): the process
  THROWS unless the real governor output contains at least one
  `:governor-hold` fact for EVERY rule in `hard-rule-order`. A scenario
  that silently stops exercising a check fails the build instead of
  quietly rendering a thinner page.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [specialized.advisor :as advisor]
            [specialized.facts :as facts]
            [specialized.notify :as notify]
            [specialized.operation :as op]
            [specialized.phase :as phase]
            [specialized.store :as store]))

;; ----------------------------- run harness -----------------------------

(defn- ctx
  "The operator context. `phase` varies per scenario so the rollout gate
  itself is exercised, not just described."
  [phase]
  {:actor-id "op-1" :actor-role :site-supervisor :phase phase})

(defn- probe-advisor
  "An advisor that returns a caller-supplied proposal verbatim. Used ONLY
  to stand in for a COMPROMISED/malfunctioning advisor, so
  `specialized.governor`'s two defense-in-depth structural checks
  (`:effect-not-propose`, `:forbidden-action-class`) can be exercised
  against the real governor -- this repo's own mock advisor can never
  produce such a proposal, which is exactly why the governor checks
  independently. Implements this repo's own `specialized.advisor/Advisor`
  protocol; the graph, governor, phase gate and store are untouched."
  [proposal]
  (reify advisor/Advisor
    (-advise [_ _ _] proposal)))

(defn- supervisor
  "The approver for a site, DERIVED from that site's own seeded
  `:safety-contacts` roster -- never an invented operator name."
  [db site-id]
  (-> (store/site db site-id) :safety-contacts first :name))

(defn- safety-officer
  "The second contact on a site's seeded roster (the safety officer),
  used as the approver who REJECTS -- again seed-derived."
  [db site-id]
  (-> (store/site db site-id) :safety-contacts second :name))

(defn- scenario
  "Runs one operation end to end and returns a record of what the REAL
  stack did with it. `approval` (optional) resumes the human-in-the-loop
  interrupt. The returned `:state` is the graph's own final state map --
  every field the page renders comes from it or from the store."
  [{:keys [actor db label tid request phase approval]}]
  (let [s1 (:state (g/run* actor {:request request :context (ctx phase)} {:thread-id tid}))
        s  (if approval
             (:state (g/run* actor {:approval approval} {:thread-id tid :resume? true}))
             s1)]
    {:label label :tid tid :phase phase
     :op (:op request) :subject (:subject request)
     :site-name (:name (store/site db (:subject request)))
     :approval approval
     :state s}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario set that reaches every
  disposition this actor can produce.

  Auto-commit (phase 3, governor clean, not high-stakes):
    site-1 `:log-site-record` twice, `:order-supplies` below the
    5000 USD threshold.

  Human approval (escalated, then approved by the site's OWN seeded
  supervisor contact):
    site-1 `:schedule-specialized-operation` (JPN, scaffold erection),
    site-1 `:flag-safety-concern` (which then actually dispatches the
    notice over mail+phone to the site's whole seeded contact roster),
    site-1 `:order-supplies` above the cost threshold,
    site-7 `:schedule-specialized-operation` (USA -- a `:qualitative`
    jurisdiction, so check 7 correctly does NOT fire and no numeric
    trigger is fabricated),
    site-8 `:schedule-specialized-operation` (DEU -- `:quantitative`
    DIN 4150-3, site reading 3.0 mm/s is below the 5 mm/s guidance
    value, so check 7 correctly does not fire either),
    site-4 `:log-site-record` at PHASE 2, where that op is not yet
    auto-eligible -- the approver-attribution probe (see `render`).

  Human REJECTION:
    site-1 `:order-supplies` at 12000 USD, rejected by the site's
    seeded safety officer -> `:approval-rejected` hold.

  Phase gate (not a governor rule):
    site-1 `:schedule-specialized-operation` at PHASE 1, where that op
    cannot write at all -> `:phase-disabled` hold.

  HARD governor holds -- none of these ever reaches a human. All EIGHT
  checks in `specialized.governor`'s ns docstring fire at least once:
    1 `:unknown-op`               -- an op outside the closed four-op allowlist
    2 `:effect-not-propose`       -- compromised advisor emitting `:effect :actuate`
    3 `:forbidden-action-class`   -- compromised advisor setting `:trade-equipment-control? true`
    4 `:site-not-verified`        -- site-3 (schedule) AND site-3 (order-supplies), two ops, one rule
    5 `:no-legal-basis`           -- site-2, jurisdiction ATL is not in `specialized.facts/catalog`
    6 `:scaffold-inspection-incomplete` -- site-3 and site-4
    7 `:vibration-noncompliant`   -- site-5, 82 dB against JPN's 75 dB 振動規制法 trigger, no mitigation
    8 `:unresolved-safety-concern`-- site-6, an open concern on file

  Returns {:db .. :notifier .. :runs [..]}."
  []
  (let [db (store/seed-db)
        notifier (notify/mock-notifier)
        actor (op/build db {:notifier notifier})
        actuating (op/build db {:notifier notifier
                                :advisor (probe-advisor
                                          {:summary "現場記録更新を直接実行"
                                           :rationale "（改ざんされた助言者の想定出力）"
                                           :cites [] :effect :actuate
                                           :value {:id "site-1"} :stake nil :confidence 0.99})})
        commanding (op/build db {:notifier notifier
                                 :advisor (probe-advisor
                                           {:summary "現場記録更新に機材操作コマンドを同梱"
                                            :rationale "（改ざんされた助言者の想定出力）"
                                            :cites [] :effect :propose
                                            :value {:id "site-1" :trade-equipment-control? true}
                                            :stake nil :confidence 0.99})})
        run (fn [m] (scenario (merge {:actor actor :db db} m)))
        approved (fn [site] {:status :approved :by (supervisor db site)})]
    [db notifier
     [;; ---- auto-commit ----
      (run {:label "現場記録を更新（進捗ログ）" :tid "t01" :phase 3
            :request {:op :log-site-record :subject "site-1"
                      :patch {:id "site-1" :status :ready-to-schedule}}})

      ;; ---- escalate -> approve ----
      (run {:label "足場設置スケジュール提案（JPN）" :tid "t02" :phase 3
            :request {:op :schedule-specialized-operation :subject "site-1"
                      :trade :scaffold-erection
                      :window {:proposed-start-date "2026-08-01" :proposed-end-date "2026-08-10"}
                      :notes "外壁改修塗装のための外部足場設置工事"}
            :approval (approved "site-1")})

      (run {:label "安全性懸念をフラグ（足場崩壊）" :tid "t03" :phase 3
            :request {:op :flag-safety-concern :subject "site-1"
                      :concern-type :scaffold-collapse
                      :concern-description "足場の壁つなぎ間隔が仕様書と一致しない箇所を確認、追加点検が必要。"}
            :approval (approved "site-1")})

      (run {:label "現場記録を更新（懸念の解消を記録）" :tid "t04" :phase 3
            :request {:op :log-site-record :subject "site-1"
                      :patch {:id "site-1" :safety-concern-unresolved? false}}})

      (run {:label "資材発注提案 800 USD（閾値未満）" :tid "t05" :phase 3
            :request {:op :order-supplies :subject "site-1"
                      :items ["scaffold-tube-6m" "coupler-swivel"]
                      :cost-usd 800 :vendor "Local Scaffold Supply Co."}})

      (run {:label "機材レンタル発注提案 9000 USD（閾値超過）" :tid "t06" :phase 3
            :request {:op :order-supplies :subject "site-1"
                      :items ["hydraulic-pile-hammer-rental"]
                      :cost-usd 9000 :vendor "Foundation Equipment Rentals"}
            :approval (approved "site-1")})

      ;; ---- escalate -> REJECT ----
      (run {:label "機材レンタル発注提案 12000 USD（承認者が却下）" :tid "t07" :phase 3
            :request {:op :order-supplies :subject "site-1"
                      :items ["vibration-monitoring-array-rental"]
                      :cost-usd 12000 :vendor "Foundation Equipment Rentals"}
            :approval {:status :rejected :by (safety-officer db "site-1")}})

      ;; ---- phase gate (not a governor rule) ----
      (run {:label "フェーズ1でスケジュール提案（書込み自体が未解禁）" :tid "t08" :phase 1
            :request {:op :schedule-specialized-operation :subject "site-1"
                      :trade :scaffold-erection :window {}}})

      ;; ---- HARD holds: all eight governor checks ----
      (run {:label "許可4オペレーション外の操作" :tid "t09" :phase 3
            :request {:op :direct-equipment-command :subject "site-1"}})

      (scenario {:actor actuating :db db :tid "t10" :phase 3
                 :label "改ざんされた助言者が :effect :actuate を提出"
                 :request {:op :log-site-record :subject "site-1" :patch {:id "site-1"}}})

      (scenario {:actor commanding :db db :tid "t11" :phase 3
                 :label "改ざんされた助言者が機材操作コマンドを同梱"
                 :request {:op :log-site-record :subject "site-1" :patch {:id "site-1"}}})

      (run {:label "未登録法域（ATL）のスケジュール提案" :tid "t12" :phase 3
            :request {:op :schedule-specialized-operation :subject "site-2"
                      :trade :pile-driving :window {}}})

      (run {:label "未検証現場のスケジュール提案" :tid "t13" :phase 3
            :request {:op :schedule-specialized-operation :subject "site-3"
                      :trade :scaffold-erection :window {}}})

      (run {:label "未検証現場への資材発注提案（同じ規則・別オペレーション）" :tid "t14" :phase 3
            :request {:op :order-supplies :subject "site-3"
                      :items ["waterproofing-membrane-roll"] :cost-usd 400
                      :vendor "Local Scaffold Supply Co."}})

      (run {:label "足場点検未完了のスケジュール提案" :tid "t15" :phase 3
            :request {:op :schedule-specialized-operation :subject "site-4"
                      :trade :waterproofing :window {}}})

      (run {:label "振動規制法トリガー超過のスケジュール提案" :tid "t16" :phase 3
            :request {:op :schedule-specialized-operation :subject "site-5"
                      :trade :pile-driving :window {}}})

      (run {:label "未解決の安全性懸念があるスケジュール提案" :tid "t17" :phase 3
            :request {:op :schedule-specialized-operation :subject "site-6"
                      :trade :pile-driving :window {}}})

      ;; ---- cross-jurisdiction happy paths ----
      (run {:label "杭打ちスケジュール提案（USA -- 数値基準なし、創作しない）" :tid "t18" :phase 3
            :request {:op :schedule-specialized-operation :subject "site-7"
                      :trade :pile-driving
                      :window {:proposed-start-date "2026-09-01" :proposed-end-date "2026-09-10"}}
            :approval (approved "site-7")})

      (run {:label "杭打ちスケジュール提案（DEU -- DIN 4150-3、実測3.0mm/s）" :tid "t19" :phase 3
            :request {:op :schedule-specialized-operation :subject "site-8"
                      :trade :pile-driving
                      :window {:proposed-start-date "2026-09-15" :proposed-end-date "2026-09-25"}}
            :approval (approved "site-8")})

      ;; ---- approver-attribution probe: phase 2, where :log-site-record
      ;;      is NOT yet auto-eligible, so a human actually approves it.
      (run {:label "フェーズ2で現場記録を更新（承認者帰属の実測プローブ）" :tid "t20" :phase 2
            :request {:op :log-site-record :subject "site-4"
                      :patch {:id "site-4" :status :inspection-pending}}
            :approval (approved "site-4")})]]))

;; ----------------------------- derived views -----------------------------

(def ^:private hard-rule-order
  "The eight HARD checks `specialized.governor/check` can emit, in that
  namespace's own priority order (its ns docstring, checks 1-8). This
  vector is the BUILD-TIME EVIDENCE FLOOR enforced by `-main`: the run
  must produce at least one real `:governor-hold` for every rule named
  here, or the build fails. If `specialized.governor` gains a ninth
  check, add it here AND add a scenario that fires it -- an unexercised
  check must not be able to hide behind a green build."
  [:unknown-op
   :effect-not-propose
   :forbidden-action-class
   :site-not-verified
   :no-legal-basis
   :scaffold-inspection-incomplete
   :vibration-noncompliant
   :unresolved-safety-concern])

(defn- holds
  "Every `:governor-hold` fact the run actually appended to the store's
  audit ledger."
  [db]
  (filter #(= :governor-hold (:t %)) (store/ledger db)))

(defn- violations-by-rule
  "rule -> [violation-with-subject ..], read out of the real ledger."
  [db]
  (->> (holds db)
       (mapcat (fn [f] (map #(assoc % :subject (:subject f) :op (:op f)) (:violations f))))
       (group-by :rule)))

(defn- audit-of [run] (vec (:audit (:state run))))

(defn- fact-of [run t] (first (filter #(= t (:t %)) (audit-of run))))

(defn- route-of
  "How the real stack routed this operation -- derived from the graph's
  own audit trail, never from the scenario label."
  [run]
  (let [a (audit-of run)
        ts (set (map :t a))
        hold (first (filter #(= :governor-hold (:t %)) a))]
    (cond
      (and hold (seq (:basis hold)))     {:kind :hard-hold  :label "HARD hold（人に届かない）" :css "critical"}
      (and hold (:phase-reason hold))    {:kind :phase-hold :label (str "フェーズ差止（" (name (:phase-reason hold)) "）") :css "warn"}
      hold                               {:kind :hard-hold  :label "HARD hold（人に届かない）" :css "critical"}
      (ts :approval-rejected)            {:kind :rejected   :label "承認者が却下 → hold" :css "critical"}
      (ts :approval-granted)             {:kind :approved   :label "人が承認 → commit" :css "ok"}
      (ts :approval-requested)           {:kind :pending    :label "承認待ち" :css "warn"}
      (ts :committed)                    {:kind :auto       :label "自動commit（フェーズ3）" :css "ok"}
      :else                              {:kind :unknown    :label "—" :css "muted"})))

(defn- registers
  "Every persisted register this store actually keeps, by name. The
  approver-retention probe below scans these -- and ONLY these -- so the
  disclosure it renders is a measurement of the store, not a claim about
  it."
  [db]
  {:sites                  (vec (store/all-sites db))
   :site-record-log        (vec (store/site-record-log-history db))
   :schedule-proposals     (vec (store/schedule-proposal-history db))
   :safety-concern-flags   (vec (store/safety-concern-flag-history db))
   :supply-order-proposals (vec (store/supply-order-proposal-history db))
   :ledger                 (vec (store/ledger db))})

(def ^:private op->registers
  "Which register `specialized.store/commit-record!` writes for each op --
  i.e. where an approver identity attached to the committed record's
  `[:value :approved-by]` would have to land to be retrievable later."
  {:log-site-record                [:sites :site-record-log]
   :schedule-specialized-operation [:schedule-proposals]
   :flag-safety-concern            [:sites :safety-concern-flags]
   :order-supplies                 [:supply-order-proposals]})

(defn- contains-value?
  "Does `needle` occur anywhere inside `x` (maps, vectors, entries)?"
  [x needle]
  (boolean (some #(= needle %) (tree-seq coll? seq x))))

(defn- approver-retention
  "MEASURED, not assumed. `specialized.operation`'s `:request-approval`
  node attaches the approver to the committed record at
  `[:value :approved-by]`. Whether that survives depends entirely on what
  `specialized.store/commit-record!` does with `:value` for that op -- so
  this walks the store's OWN registers looking for the approver string
  the run actually used, and reports what it finds. Nothing here is
  hardcoded: if the store is changed so that every op retains its
  approver, this function reports that on the next build."
  [db runs]
  (let [regs (registers db)]
    (->> runs
         (filter #(= :approved (:kind (route-of %))))
         (map (fn [run]
                (let [granted (fact-of run :approval-granted)
                      by (:by granted)
                      targets (get op->registers (:op run) [])
                      found (vec (filter #(contains-value? (get regs %) by) targets))]
                  {:op (:op run) :subject (:subject run) :by by
                   :targets targets :found found
                   :retained? (boolean (seq found))
                   ;; the ledger is checked separately: the :approval-granted
                   ;; fact lives in the graph's :audit channel, and the store's
                   ;; :commit node appends only the :committed fact -- so
                   ;; "is the approver in the ledger?" is its own question.
                   :in-ledger? (contains-value? (:ledger regs) by)}))))))

;; ----------------------------- html -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kw [v] (if (keyword? v) (name v) (str v)))

(defn- tr [& cells] (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- tick [b] (if b "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" (esc %) "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n" (str/join "\n" rows) "\n      </tbody>\n"
       "    </table>\n"))

(defn- section [title lede body]
  (str "  <section class=\"card\">\n"
       "    <h2>" (esc title) "</h2>\n"
       (when lede (str "    <p class=\"muted\">" lede "</p>\n"))
       body
       "  </section>\n"))

;; --- section 1: sites ---

(defn- vibration-cell [site]
  (let [iso3 (:jurisdiction site)
        sb (facts/spec-basis iso3)
        measured (:vibration-level-measured site)
        verdict (facts/vibration-noncompliant? iso3 site)]
    (str (if (nil? measured) "<span class=\"muted\">未実測</span>"
             (str "<span class=\"num\">" (esc measured) "</span> "
                  (esc (some-> (:vibration-trigger-unit sb) kw))))
         " / trigger "
         (if (:vibration-trigger-value sb)
           (str "<span class=\"num\">" (esc (:vibration-trigger-value sb)) "</span>")
           "<span class=\"muted\">数値基準なし</span>")
         " → "
         (condp = verdict
           true  "<span class=\"critical\">超過・対策未設置</span>"
           false "<span class=\"ok\">適合</span>"
           :qualitative "<span class=\"warn\">qualitative（判定不能・創作しない）</span>"
           "<span class=\"muted\">spec-basis無し</span>"))))

(defn- site-row [db runs site]
  (let [last-run (last (filter #(= (:id site) (:subject %)) runs))
        r (some-> last-run route-of)]
    (tr (code (:id site))
        (esc (:name site))
        (code (:jurisdiction site))
        (str/join " " (map #(code (kw %)) (:trades site)))
        (tick (:site-verified? site))
        (tick (:scaffold-inspection-completed? site))
        (vibration-cell site)
        (if (:safety-concern-unresolved? site)
          "<span class=\"critical\">未解決</span>" "<span class=\"ok\">無し</span>")
        (if r (str "<span class=\"" (:css r) "\">" (esc (:label r)) "</span>")
            "<span class=\"muted\">この実行では未操作</span>"))))

;; --- section 2: jurisdiction coverage ---

(defn- jurisdiction-row [iso3]
  (let [c (facts/spec-basis iso3)]
    (tr (code iso3)
        (esc (:name c))
        (esc (:owner-authority c))
        (str "<span class=\"" (if (= :quantitative (:threshold-model c)) "ok" "warn") "\">"
             (esc (kw (:threshold-model c))) "</span>")
        (if (:vibration-trigger-value c)
          (str "<span class=\"num\">" (esc (:vibration-trigger-value c)) "</span> "
               (esc (kw (:vibration-trigger-unit c))))
          "<span class=\"muted\">なし（創作しない）</span>")
        (str "<a href=\"" (esc (:scaffold-inspection-provenance c)) "\">足場点検</a> / "
             "<a href=\"" (esc (:vibration-provenance c)) "\">振動</a>"))))

;; --- section 3: governor hard checks ---

(defn- hard-check-row [by-rule rule]
  (let [vs (get by-rule rule)]
    (tr (code rule)
        (if (seq vs) (str "<span class=\"ok\">" (count vs) "</span>")
            "<span class=\"critical\">0</span>")
        (str/join " " (map #(code (:subject %)) (distinct (map #(select-keys % [:subject]) vs))))
        (str/join " " (map #(code (kw %)) (distinct (map :op vs))))
        (esc (:detail (first vs))))))

;; --- section 4: phase gate ---

(defn- phase-row [[n {:keys [label writes auto]}]]
  (tr (str "<span class=\"num\">" n "</span>")
      (code label)
      (if (seq writes) (str/join " " (map #(code (kw %)) (sort writes))) "<span class=\"muted\">—</span>")
      (if (seq auto) (str/join " " (map #(code (kw %)) (sort auto))) "<span class=\"muted\">—</span>")))

;; --- section 5: dispositions ---

(defn- disposition-row [run]
  (let [r (route-of run)
        prop (fact-of run :advisor-proposal)
        hold (fact-of run :governor-hold)]
    (tr (code (:tid run))
        (esc (:label run))
        (code (kw (:op run)))
        (code (:subject run))
        (str "<span class=\"num\">" (:phase run) "</span>")
        (str "<span class=\"num\">" (esc (:confidence prop)) "</span>")
        (str "<span class=\"" (:css r) "\">" (esc (:label r)) "</span>")
        (cond
          (seq (:basis hold)) (str/join " " (map #(code (kw %)) (:basis hold)))
          (:phase-reason hold) (code (kw (:phase-reason hold)))
          (fact-of run :approval-rejected) (code :approver-rejected)
          :else "<span class=\"muted\">—</span>")
        (or (some-> (or (fact-of run :approval-granted) (fact-of run :approval-rejected)) :by esc)
            "<span class=\"muted\">—</span>"))))

;; --- section 6: coordination artifacts ---

(defn- artifact-row [kind rec]
  (tr (code kind)
      (code (get rec "record_id"))
      (esc (get rec "kind"))
      (code (get rec "site_id"))
      (code (get rec "jurisdiction"))
      (tick (get rec "immutable"))))

;; --- section 7: approver attribution ---

(defn- attribution-row [{:keys [op subject by targets found retained? in-ledger?]}]
  (tr (code (kw op))
      (code subject)
      (esc by)
      (str/join " " (map #(code (kw %)) targets))
      (if retained?
        (str "<span class=\"ok\">保持: " (str/join ", " (map #(code (kw %)) found)) "</span>")
        "<span class=\"warn\">監査記録のみ — 記録本体には保持されない</span>")
      (if in-ledger?
        "<span class=\"ok\">台帳に出現</span>"
        "<span class=\"warn\">台帳には出現しない</span>")))

;; --- section 8: notifier ---

(defn- notice-row [msg]
  (tr (code (kw (:channel msg)))
      (esc (:to msg))
      (str "<span class=\"ok\">" (esc (kw (:status msg))) "</span>")
      (esc (or (:subject msg) (:message msg)))))

;; --- section 9: ledger ---

(defn- ledger-row [{:keys [t op subject disposition basis phase-reason confidence]}]
  (tr (let [css (case t :committed "ok" :governor-hold "critical" :approval-rejected "critical" "warn")]
        (str "<span class=\"" css "\">" (esc (kw t)) "</span>"))
      (code (kw op))
      (code subject)
      (code (kw disposition))
      (if (seq basis)
        (str/join " " (map (fn [b] (code (if (keyword? b) (kw b) (subs (str b) 0 (min 60 (count (str b))))))) basis))
        (if phase-reason (code (kw phase-reason)) "<span class=\"muted\">—</span>"))
      (if confidence (str "<span class=\"num\">" (esc confidence) "</span>") "<span class=\"muted\">—</span>")))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole console from a real `run-demo!` result. Every table
  below is built from `db` / `notifier` / `runs` -- there is no hand-typed
  domain row anywhere in this function."
  [db notifier runs]
  (let [sites (store/all-sites db)
        by-rule (violations-by-rule db)
        seeded-jurisdictions (vec (sort (distinct (map :jurisdiction sites))))
        cov (facts/coverage seeded-jurisdictions)
        attribution (approver-retention db runs)
        ledger (store/ledger db)
        sent (notify/sent-log notifier)
        notice (get (last (store/safety-concern-flag-history db)) "document")]
    (str
     "<!doctype html>\n<html lang=\"ja\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-4390 · 分類外専門工事 運行調整オペレーターコンソール</title>\n"
     "<style>" (jp-go-dds.skin/dds+skin) "</style>\n"
     "</head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>その他の専門工事業（ISIC 4390） — 運行調整オペレーターコンソール</h1>\n"
     "  <span class=\"badge\">read-only サンプル · governor-gated · 提案のみ（:effect :propose）· 機材操作/完成サインオフの権限なし</span>\n"
     "</header>\n"
     "<main class=\"container\">\n"

     (section "現場ディレクトリ（SSoT）"
              (str "この実行の <code>specialized.store</code> の全レコード。振動列は "
                   "<code>specialized.facts/vibration-noncompliant?</code> を法域ごとの実基準に対して"
                   "その場で再計算したもの（提案の自己申告は使わない）。")
              (table ["現場" "名称" "法域" "工種" "現場検証済" "足場点検完了" "振動実測 / 法定トリガー" "安全性懸念" "この実行の最終処理"]
                     (map (partial site-row db runs) sites)))

     (section "法域 spec-basis カバレッジ"
              (str "<code>specialized.facts/catalog</code> の実データ。"
                   (esc (:note cov))
                   " 種まきデータに現れる法域 " (esc (pr-str seeded-jurisdictions))
                   " のうち、spec-basis を持つのは <span class=\"num\">" (:covered cov) "</span>/"
                   "<span class=\"num\">" (:requested cov) "</span>"
                   "（未収載: " (str/join " " (map code (:missing-jurisdictions cov)))
                   " — 未収載法域の要件は創作せず、governor が HARD hold する）。")
              (table ["法域" "名称" "所管" "threshold-model" "振動トリガー" "出典"]
                     (map jurisdiction-row (:covered-jurisdictions cov))))

     (section "Specialized Trade Governor — 8つの HARD チェック（この実行で実際に発火）"
              (str "件数・現場・詳細文はすべて <code>specialized.store/ledger</code> の "
                   "<code>:governor-hold</code> ファクトから読み出したもの。HARD 違反は"
                   "承認者が上書きできず、人に届く前に止まる。"
                   "<strong>この表の各行が 0 件になるとビルドが失敗する</strong>"
                   "（<code>render-html/-main</code> の evidence floor）。")
              (table ["規則" "発火件数" "対象現場" "オペレーション" "governor が返した実際の詳細"]
                     (map (partial hard-check-row by-rule) hard-rule-order)))

     (section "ロールアウト・フェーズゲート"
              (str "<code>specialized.phase/phases</code> のデータそのもの。"
                   "<code>:schedule-specialized-operation</code> と <code>:flag-safety-concern</code> は"
                   "どのフェーズの auto 集合にも入らない — ロールアウトの未達ではなく恒久的な構造。")
              (table ["フェーズ" "ラベル" "書込み可" "自動commit可"]
                     (map phase-row (sort-by key phase/phases))))

     (section "この実行の処理内訳"
              (str "各行は 1 回のグラフ実行（<code>langgraph</code> StateGraph、"
                   "<code>interrupt-before #{:request-approval}</code>）。"
                   "「経路」列はシナリオのラベルではなく、グラフ自身の <code>:audit</code> "
                   "チャネルから導出している。")
              (table ["thread" "シナリオ" "op" "現場" "phase" "confidence" "経路" "規則 / 理由" "承認者"]
                     (map disposition-row runs)))

     (section "コミットされた調整成果物（append-only レジスタ）"
              (str "<code>specialized.registry</code> が構築し、store が追記した実レコード。"
                   "採番は法域スコープの連番で、国際的な検査桁標準は存在しないため創作していない。")
              (table ["レジスタ" "record_id" "kind" "現場" "法域" "immutable"]
                     (concat
                      (map (partial artifact-row :site-record-log) (store/site-record-log-history db))
                      (map (partial artifact-row :schedule-proposals) (store/schedule-proposal-history db))
                      (map (partial artifact-row :safety-concern-flags) (store/safety-concern-flag-history db))
                      (map (partial artifact-row :supply-order-proposals) (store/supply-order-proposal-history db)))))

     (section "承認者の帰属 — 実測した開示"
              (str "<code>specialized.operation</code> の <code>:request-approval</code> ノードは"
                   "承認者を確定レコードの <code>[:value :approved-by]</code> に載せる。"
                   "それが後から引けるかは <code>specialized.store/commit-record!</code> が"
                   "そのオペレーションで <code>:value</code> をどう扱うか次第なので、"
                   "<strong>この表はレンダリング時に store の実レジスタを走査して求めた測定値</strong>であり、"
                   "固定文言ではない。store が直れば次のビルドで自動的に表示が変わる。"
                   "承認者名は各現場の種まき <code>:safety-contacts</code> 名簿から取っており、"
                   "アクター ID（<code>op-1</code>）とは別の文字列なので取り違えは起きない。")
              (table ["op" "現場" "承認者" "store が書くレジスタ" "レコード本体に保持されたか" "監査台帳"]
                     (map attribution-row attribution)))

     (section "安全性懸念通知の実送信ログ"
              (str "<code>:flag-safety-concern</code> が人の承認を得た後、"
                   "<code>specialized.notify</code> が現場の <code>:safety-contacts</code> 名簿全員へ"
                   "メール＋電話の両チャネルで同一通知を配信した実ログ（mock transport）。")
              (str (table ["チャネル" "宛先" "状態" "件名 / メッセージ"] (map notice-row sent))
                   "    <h3>送信された通知文書（specialized.registry/render-safety-concern-notice）</h3>\n"
                   "    <pre>" (esc notice) "</pre>\n"))

     (section "監査台帳（この実行）"
              (str "<code>specialized.store/append-ledger!</code> が追記した全ファクト。"
                   "追記のみで、上書きも削除も無い。")
              (table ["ファクト" "op" "現場" "disposition" "basis / 理由" "confidence"]
                     (map ledger-row ledger)))

     "  <footer>\n"
     "    <p>build-time 生成 — <code>clojure -M:dev:render-html</code>"
     " (<code>specialized.render-html</code>)。"
     "この文書のすべての行は実際のアクター実行（<code>specialized.operation</code> →"
     " <code>specialized.governor</code> → <code>specialized.store</code>）の出力から導出されており、"
     "手書きのドメインデータは含まれない。時刻・乱数を読まないため再実行しても byte 一致する。</p>\n"
     "  </footer>\n"
     "</main>\n</body></html>\n")))

;; ----------------------------- entry point -----------------------------

(defn- verify!
  "Build-time invariants. These THROW -- a demo page that quietly stops
  demonstrating the governor is worse than no page, because it looks the
  same as one that does."
  [db notifier runs]
  (let [hs (holds db)
        by-rule (violations-by-rule db)
        missing (remove by-rule hard-rule-order)
        approvals (filter #(= :approved (:kind (route-of %))) runs)
        rejections (filter #(= :rejected (:kind (route-of %))) runs)]
    (when (zero? (count hs))
      (throw (ex-info "render-html: the real governor produced ZERO :governor-hold facts -- refusing to write a console that shows no enforcement"
                      {:ledger-facts (count (store/ledger db))})))
    (when (seq missing)
      (throw (ex-info (str "render-html: HARD-check evidence floor not met -- these governor rules never fired: "
                           (pr-str (vec missing)))
                      {:exercised (vec (sort (map key by-rule)))
                       :required (vec hard-rule-order)})))
    (when (empty? approvals)
      (throw (ex-info "render-html: no human approval was granted -- the human-in-the-loop path is undemonstrated" {})))
    (when (empty? rejections)
      (throw (ex-info "render-html: no human rejection occurred -- the approver-can-say-no path is undemonstrated" {})))
    (when (empty? (notify/sent-log notifier))
      (throw (ex-info "render-html: the notifier sent nothing -- the safety-concern outreach path is undemonstrated" {})))
    {:holds (count hs) :rules (count by-rule)
     :approvals (count approvals) :rejections (count rejections)
     :sent (count (notify/sent-log notifier))}))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        [db notifier runs] (run-demo!)
        stats (verify! db notifier runs)
        html (render db notifier runs)]
    (spit out html)
    (println "wrote" out
             (str "(" (count html) " bytes, "
                  (count runs) " scenarios, "
                  (count (store/ledger db)) " ledger facts, "
                  (:holds stats) " governor holds across " (:rules stats) " distinct HARD rules, "
                  (:approvals stats) " approved / " (:rejections stats) " rejected, "
                  (:sent stats) " notices sent)"))))
