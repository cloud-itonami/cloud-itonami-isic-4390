(ns specialized.phase
  "Phase 0->3 staged rollout -- the other-specialized-construction-
  coordination analog of `installation.phase`/`finishing.phase`.

    Phase 0  read-only              -- no writes, still governor-gated.
    Phase 1  assisted-logging       -- `:log-site-record` allowed, every
                                       write needs human approval.
    Phase 2  assisted-coordination  -- adds `:flag-safety-concern` and
                                       `:order-supplies` writes, still
                                       approval.
    Phase 3  supervised-coordination -- adds `:schedule-specialized-
                                       operation`; governor-clean,
                                       high-confidence `:log-site-record`
                                       AND `:order-supplies` BELOW the
                                       cost threshold may auto-commit.
                                       `:schedule-specialized-operation`
                                       is enabled to WRITE at phase 3 but
                                       is NEVER auto-eligible -- see
                                       'Actuation' below.

  ## Actuation (there is none -- read this before changing this file)

  This actor performs NO real-world actuation. Every proposal it can ever
  produce carries `:effect :propose` (see `specialized.governor` ns
  docstring checks 1-4) -- 'committing' a proposal here means only that a
  coordination artifact (a site-record-log entry, a specialized-operation
  schedule PROPOSAL, a safety-concern flag, a supply-order PROPOSAL) is
  now logged in the SSoT + audit ledger. It never dispatches trade
  equipment, never finalizes a structural-completion sign-off -- that
  authority is the site supervisor / building official's exclusively.

  `:flag-safety-concern` AND `:schedule-specialized-operation` are BOTH
  DELIBERATELY ABSENT from every phase's `:auto` set, including phase 3 --
  a permanent structural fact, not a rollout milestone still to come.
  Surfacing a scaffold-collapse/pile-driving-vibration/structural concern
  is exactly the judgment this actor must never let auto-commit, and
  scheduling scaffolding-erection/pile-driving/waterproofing work carries
  the SAME kind of public-safety stakes (scaffold collapse, ground
  vibration damaging a neighboring structure) -- `specialized.governor`'s
  `high-stakes` set enforces the same invariant independently for both
  ops -- two layers, not one, agree on this (see `specialized.governor`
  ns docstring). This is a DELIBERATE, DOCUMENTED difference from
  `installation.phase`/`finishing.phase`, whose own schedule ops ARE
  members of phase 3's `:auto` set -- see `specialized.governor` ns
  docstring for the risk-profile rationale (closer to
  `demolition.phase`/`roadrail.phase`, whose schedule ops likewise never
  auto-commit).

  `:log-site-record` (trade-progress/material-usage/scaffold-inspection-
  status DATA LOGGING, no direct capital or safety risk) and `:order-
  supplies` BELOW the cost threshold (see `specialized.governor/supply-
  order-cost-threshold-usd`) round out phase 3's `:auto` set -- but the
  governor's own cost-threshold/confidence-floor check can still force
  `:order-supplies` to escalate even when it's `:auto`-eligible, the same
  'phase says maybe, governor decides' layering `installation.phase`/
  `finishing.phase`/`demolition.phase` established."
  )

(def read-ops  #{})
(def write-ops #{:log-site-record :schedule-specialized-operation
                 :flag-safety-concern :order-supplies})

;; NOTE the invariant: `:flag-safety-concern` and `:schedule-specialized-
;; operation` are members of `write-ops` (governor-gated like any write)
;; but NEITHER is ever a member of any phase's `:auto` set below. Do not
;; add either there -- see ns docstring 'Actuation' section above before
;; changing this.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"                 :writes #{}                                            :auto #{}}
   1 {:label "assisted-logging"          :writes #{:log-site-record}                             :auto #{}}
   2 {:label "assisted-coordination"     :writes #{:log-site-record :flag-safety-concern
                                                    :order-supplies}                              :auto #{}}
   3 {:label "supervised-coordination"   :writes write-ops
      :auto #{:log-site-record :order-supplies}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:flag-safety-concern` and `:schedule-specialized-operation` are never
    auto-eligible at any phase, so they always escalate once the governor
    clears them (or hold if the governor doesn't). `:log-site-record` and
    `:order-supplies` MAY auto-commit at phase 3 -- see ns docstring."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Specialized Trade Governor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
