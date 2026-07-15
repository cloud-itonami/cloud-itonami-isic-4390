(ns specialized.governor
  "Specialized Trade Governor -- the independent compliance layer named in
  `blueprint.edn` (`:itonami.blueprint/governor :specialized-governor`)
  that earns the Specialized Trade Advisor the right to commit. The LLM
  has no notion of specialized-construction-trade safety law, whether a
  site's own recorded scaffold inspection / pile-driving-vibration
  readings actually satisfy its jurisdiction's requirements, or when a
  proposal has quietly drifted outside this actor's charter into trade-
  equipment control or structural-completion sign-off, so this MUST be a
  separate system able to *reject* a proposal and fall back to HOLD --
  the specialized-construction-trade analog of `cloud-itonami-isic-4329`'s
  Installation Governor / `cloud-itonami-isic-4330`'s Finishing Governor.

  ## Scope -- this is a COORDINATION-ONLY actor

  This actor NEVER controls trade equipment and NEVER finalizes a
  structural-completion sign-off -- that authority is the site supervisor
  / building official's EXCLUSIVELY. Every proposal this actor's
  Specialized Trade Advisor can produce carries `:effect :propose` and
  NOTHING else -- committing a proposal here means 'this coordination
  artifact is now logged/scheduled/flagged/ordered', never 'a trade crew
  or piece of equipment was dispatched' or 'a structural-completion
  sign-off is authorized'. Checks 1-4 below encode this scope as
  STRUCTURAL, permanent HARD holds -- not policy that could be relaxed by
  a future phase.

  Eight checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them.

    1. Unknown op                -- the proposal's `:op` is outside the
                                     CLOSED four-op allowlist
                                     (`:log-site-record` / `:schedule-
                                     specialized-operation` / `:flag-
                                     safety-concern` / `:order-supplies`).
                                     Permanent, structural.
    2. Effect is not :propose    -- ANY proposal whose `:effect` is not
                                     literally `:propose` is rejected,
                                     unconditionally. Defense-in-depth: a
                                     compromised/malfunctioning advisor
                                     can never slip a real-world actuation
                                     effect past this governor. Permanent,
                                     structural.
    3. Forbidden action class    -- a proposal whose `:value` carries a
                                     `:trade-equipment-control?` /
                                     `:direct-actuation?` / `:finalizes-
                                     structural-completion-sign-off?`
                                     marker true is rejected,
                                     unconditionally -- even though this
                                     actor's own mock advisor never sets
                                     these, the governor checks
                                     independently so a compromised
                                     advisor gains nothing by trying.
                                     Permanent, structural, un-overridable
                                     by ANY human approval -- see README
                                     `Actuation`.
    4. Site/permit not
       independently verified/
       registered                -- for `:schedule-specialized-
                                     operation` / `:flag-safety-concern` /
                                     `:order-supplies`, the site's own
                                     recorded `:site-verified?` ground-
                                     truth field (set only via a
                                     separately-committed `:log-site-
                                     record`, never trusted from the
                                     CURRENT proposal's own confidence)
                                     must be true. `:site-verified?`
                                     encompasses BOTH the site record and
                                     its permit-of-record having been
                                     independently verified/registered --
                                     scaffolding erection and pile-driving
                                     work ordinarily require a local
                                     construction/permit filing before
                                     work begins.
    5. Legal-basis missing       -- for `:schedule-specialized-
                                     operation`, did the proposal cite an
                                     OFFICIAL source (`specialized.
                                     facts`), or invent one for an
                                     uncovered jurisdiction?
    6. Scaffold inspection
       incomplete                -- for `:schedule-specialized-
                                     operation`, has the site's own
                                     recorded `:scaffold-inspection-
                                     completed?` ground-truth field
                                     actually been set true?
    7. Pile-driving-vibration
       noncompliant               -- for `:schedule-specialized-
                                     operation` in a `:quantitative`-
                                     threshold jurisdiction (JPN/DEU),
                                     INDEPENDENTLY recompute whether the
                                     site's own recorded `:vibration-
                                     level-measured` / `:vibration-
                                     mitigation-installed?` fields meet
                                     its jurisdiction's regulatory
                                     vibration trigger (`specialized.
                                     facts/vibration-noncompliant?`) --
                                     needs no proposal inspection at all.
                                     DELIBERATELY does not fire for
                                     `:qualitative` jurisdictions (USA) --
                                     there is no numeric bright line to
                                     independently re-check there.
    8. Unresolved safety concern -- for `:schedule-specialized-
                                     operation`, the site's own recorded
                                     `:safety-concern-unresolved?`
                                     ground-truth field must be false.
                                     Evaluated off the STORE's ground
                                     truth, never the current proposal's
                                     own confidence. Does NOT fire for
                                     `:flag-safety-concern` itself (that
                                     op ALWAYS escalates to a human
                                     regardless of its own content).

  The confidence/high-stakes gate is SOFT: it asks a human to look, and
  the human may approve.

    - `:flag-safety-concern` and `:schedule-specialized-operation` are
      BOTH UNCONDITIONALLY members of `high-stakes` -- they ALWAYS
      escalate to a human, at every phase, regardless of confidence or
      governor cleanliness. This is a DELIBERATE, DOCUMENTED difference
      from `installation.governor`/`finishing.governor`, whose own
      schedule ops are NOT permanent `high-stakes` members (they MAY
      auto-commit at phase 3). Scaffold collapse and pile-driving-induced
      ground vibration damaging a NEIGHBORING structure are genuinely
      closer in public-safety stakes to `demolition.governor`'s /
      `roadrail.governor`'s own schedule ops (which likewise ARE
      unconditional `high-stakes` members, because they coordinate
      potential HEAVY-equipment dispatch adjacent to structural-collapse
      / public-traffic / buried-utility risk) than to
      `installation.governor`'s thermal-insulation/sound-proofing/
      elevator-installation domain. This actor keeps a human in the loop
      for every schedule proposal, not only for `:flag-safety-concern`.
    - `:order-supplies` escalates when its own proposed `:value
      :cost-usd` exceeds `supply-order-cost-threshold-usd`, OR when
      confidence is below `confidence-floor` -- a soft, cost-scoped gate,
      not a permanent per-op membership in `high-stakes`.
    - The HARD checks (1-8) always apply regardless of phase or
      confidence -- only the routing to a human vs. auto-commit changes,
      and for `:schedule-specialized-operation`/`:flag-safety-concern`
      that routing is ALWAYS to a human, never auto."
  (:require [specialized.facts :as facts]
            [specialized.store :as store]))

(def confidence-floor 0.6)

(def supply-order-cost-threshold-usd
  "Above this, a `:order-supplies` proposal always escalates to a human,
  regardless of confidence -- see ns docstring."
  5000)

(def closed-op-allowlist
  #{:log-site-record :schedule-specialized-operation :flag-safety-concern :order-supplies})

(def high-stakes
  "Ops that ALWAYS escalate to a human when the governor is otherwise
  clean, at every phase, unconditionally. `:log-site-record` is
  deliberately NOT a member -- low-risk data logging/normalization.
  `:schedule-specialized-operation` IS a permanent member -- UNLIKE
  `installation.governor`/`finishing.governor`'s own schedule ops, see ns
  docstring for why this actor is stricter here. `:order-supplies` is
  deliberately NOT a permanent member either -- its escalation is a SOFT,
  cost-scoped rule computed in `check` below, not a blanket 'always a
  human' rule."
  #{:flag-safety-concern :schedule-specialized-operation})

;; ----------------------------- checks -----------------------------

(defn- unknown-op-violations
  "The proposal's `:op` must be a member of the CLOSED four-op allowlist.
  Permanent, structural -- see ns docstring check 1."
  [{:keys [op]}]
  (when-not (contains? closed-op-allowlist op)
    [{:rule :unknown-op
      :detail (str op " はこのアクターの許可された4オペレーション（:log-site-record/"
                  ":schedule-specialized-operation/:flag-safety-concern/:order-supplies）"
                  "のいずれにも該当しない")}]))

(defn- effect-not-propose-violations
  "Every proposal from this actor's advisor must carry `:effect
  :propose` -- and nothing else. Permanent, structural -- see ns
  docstring check 2."
  [proposal]
  (when-not (= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str "提案の:effectが:propose以外（" (pr-str (:effect proposal))
                  "）-- このアクターは提案のみで、実際の作動/確定を一切行わない")}]))

(defn- forbidden-action-class-violations
  "A proposal's `:value` must never carry a trade-equipment-control /
  direct-actuation / structural-completion-sign-off-finalization marker.
  Permanent, structural, un-overridable by any human approval -- see ns
  docstring check 3."
  [proposal]
  (let [v (:value proposal)]
    (when (and (map? v)
               (or (true? (:trade-equipment-control? v))
                   (true? (:direct-actuation? v))
                   (true? (:finalizes-structural-completion-sign-off? v))))
      [{:rule :forbidden-action-class
        :detail "工事用機材の直接操作コマンド、または構造物完成サインオフの確定を伴う提案は恒久的に禁止（現場監督/建築主事の専権事項）"}])))

(defn- site-not-verified-violations
  "For `:schedule-specialized-operation` / `:flag-safety-concern` /
  `:order-supplies`, the site's own recorded `:site-verified?`
  ground-truth field (set only via a separately-committed `:log-site-
  record`) must be true. See ns docstring check 4."
  [{:keys [op subject]} st]
  (when (contains? #{:schedule-specialized-operation :flag-safety-concern :order-supplies} op)
    (let [a (store/site st subject)]
      (when-not (true? (:site-verified? a))
        [{:rule :site-not-verified
          :detail (str subject " は現場・許可記録が独立して検証・登録済み（:site-verified?）でない状態での提案")}]))))

(defn- legal-basis-missing-violations
  "For `:schedule-specialized-operation`, the proposal must cite an
  OFFICIAL source -- never invent a jurisdiction's scaffold-inspection/
  vibration requirements. See ns docstring check 5."
  [{:keys [op]} proposal]
  (when (= op :schedule-specialized-operation)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-legal-basis
          :detail "公式legal-basisの引用が無い提案は専門工事スケジュール提案として扱えない"}]))))

(defn- scaffold-inspection-incomplete-violations
  "For `:schedule-specialized-operation`, the site's own recorded
  `:scaffold-inspection-completed?` ground-truth field must be true. See
  ns docstring check 6."
  [{:keys [op subject]} st]
  (when (= op :schedule-specialized-operation)
    (let [a (store/site st subject)]
      (when-not (true? (:scaffold-inspection-completed? a))
        [{:rule :scaffold-inspection-incomplete
          :detail (str subject " は足場点検が完了記録されていない状態での専門工事スケジュール提案")}]))))

(defn- vibration-noncompliant-violations
  "For `:schedule-specialized-operation`, INDEPENDENTLY recompute whether
  the site's own recorded vibration-measurement/mitigation fields meet
  its jurisdiction's regulatory pile-driving-vibration trigger via
  `specialized.facts/vibration-noncompliant?`. Fires ONLY when that
  returns `true` (a :quantitative jurisdiction confirmed noncompliant) --
  `:qualitative`/`nil` never trip this HARD check. See ns docstring
  check 7."
  [{:keys [op subject]} st]
  (when (= op :schedule-specialized-operation)
    (let [a (store/site st subject)]
      (when (true? (facts/vibration-noncompliant? (:jurisdiction a) a))
        [{:rule :vibration-noncompliant
          :detail (str subject " の振動実測値（" (:vibration-level-measured a)
                      "）が法定振動トリガー以上で、振動対策措置が未設置")}]))))

(defn- unresolved-safety-concern-violations
  "For `:schedule-specialized-operation`, the site's own recorded
  `:safety-concern-unresolved?` ground-truth field must be false. See ns
  docstring check 8."
  [{:keys [op subject]} st]
  (when (= op :schedule-specialized-operation)
    (let [a (store/site st subject)]
      (when (true? (:safety-concern-unresolved? a))
        [{:rule :unresolved-safety-concern
          :detail (str subject " は未解決の安全性懸念（足場崩壊/杭打ち振動/構造）がある状態での専門工事スケジュール提案")}]))))

(defn check
  "Censors a Specialized Trade Advisor proposal against the governor
  rules. Returns {:ok? bool :violations [..] :confidence c :escalate?
  bool :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (unknown-op-violations request)
                           (effect-not-propose-violations proposal)
                           (forbidden-action-class-violations proposal)
                           (site-not-verified-violations request st)
                           (legal-basis-missing-violations request proposal)
                           (scaffold-inspection-incomplete-violations request st)
                           (vibration-noncompliant-violations request st)
                           (unresolved-safety-concern-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        cost (get-in proposal [:value :cost-usd])
        cost-stakes? (and (= (:op request) :order-supplies)
                          (number? cost)
                          (> cost supply-order-cost-threshold-usd))
        stakes? (boolean (or (high-stakes (:stake proposal)) cost-stakes?))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
