# cloud-itonami-4390

Open Business Blueprint for **ISIC Rev.5 4390**: other specialized
construction activities -- the RESIDUAL class of specialized-
construction-trade work not elsewhere classified: scaffolding erection,
foundation-piling work, waterproofing, and similar specialty trades
outside 4321 (electrical installation), 4322 (plumbing, heat and
air-conditioning installation), 4329 (other construction installation)
and 4330 (building completion and finishing).

This repository designs a forkable OSS business for specialized-
construction-trade-project operations coordination: run by a qualified
operator so a community keeps its own operating records instead of
renting a closed SaaS.

## Scope -- this is a COORDINATION-ONLY actor, not equipment control

This is a safety-relevant domain: scaffold-collapse hazards (erection/
inspection defects, wall-tie/base-plate failures), pile-driving-vibration
hazards (ground-borne vibration damaging a NEIGHBORING structure or
disturbing its occupants), waterproofing-related structural concerns, and
structural-completion sign-off. **This actor does NOT hold trade-
equipment-control authority, and it does NOT hold structural-completion-
sign-off authority.** Both are the site supervisor / building official's
exclusive authority, always. The Specialized Trade Advisor (LLM) never
issues a trade-equipment-control command and never finalizes a
structural-completion sign-off; the independent **Specialized Trade
Governor** HARD-blocks any proposal that even tries (un-overridable by
any human approval -- see `specialized.governor` ns docstring). This
actor coordinates *potential* trade-crew/equipment dispatch (a proposed
schedule window, a flagged concern, a supply-order proposal) -- it never
directly actuates.

Structurally, EVERY proposal this actor's advisor can produce carries
`:effect :propose`, and the Specialized Trade Governor HARD-holds any
proposal that doesn't -- this is a permanent invariant this actor shares
with every sibling actor in this fleet's `robotics`-premise family
(`cloud-itonami-isic-4211`/`4210`/`4220`/`4311`/`4329`/`4330`), whose own
non-coordination-only actuation ops DO commit real-world effects; here
`:itonami.blueprint/robotics` is honestly `false` and no op in this
actor's closed allowlist commits anything beyond a coordination artifact.

## Core Contract

```text
site + permit record + independent verification
        |
        v
Advisor -> Specialized Trade Governor -> proceed (log/schedule/flag/order proposal), hold, or human approval
        |
        v
coordination artifacts (schedule proposal, safety-concern flag,
supply-order proposal) + audit ledger -- NEVER trade-equipment dispatch,
NEVER a structural-completion sign-off
```

No automated advice can propose a schedule the governor refuses, suppress
a safety-concern flag, or slip a trade-equipment-control/structural-
completion-sign-off marker past the governor -- and both a flagged safety
concern AND a specialized-operation schedule proposal always need a human
sign-off (see `Actuation` below).

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `4390`). Required capabilities:

- `:identity`
- `:forms`
- `:audit-ledger`
- `:notifications`

## Implemented slice (`src/specialized`)

`blueprint.edn` names the governor `:specialized-governor` and is now
`:implemented`. This repo implements it end-to-end -- **Specialized Trade
Advisor ⊣ Specialized Trade Governor** -- following the SAME `.cljc`
actor pattern (langgraph-clj StateGraph, mock-by-default advisor, dual
MemStore/Datomic backend, 0→3 phase rollout) every prior
`cloud-itonami-isic-*` actor in this fleet uses, structured after
[`cloud-itonami-isic-4329`](https://github.com/cloud-itonami/cloud-itonami-isic-4329)
(other construction installation) /
[`cloud-itonami-isic-4330`](https://github.com/cloud-itonami/cloud-itonami-isic-4330)
(building completion and finishing), narrowed to coordination-only
authority as described above and adapted to the specific hazard/trade
profile of scaffolding/piling/waterproofing work (see `Actuation` below
for a structural difference this actor deliberately does NOT share with
those two references).

This repo is fully portable `.cljc` with **no JVM interop anywhere in
`src/`** -- `specialized.notify`'s real-transport seam (`fn-notifier`)
takes caller-injected plain functions instead of embedding a
`java.net.http` client, so the actor runs unmodified on JVM Clojure,
ClojureScript, `nbb`, and `kotoba wasm`/`clojurewasm`. No Rust code is
used anywhere in this repo, per this fleet's `robotics`-premise
convention: this actor coordinates potential trade-crew/equipment
dispatch, it never actuates.

### Why `:schedule-specialized-operation`, not `:schedule-finishing-operation`

This actor's schedule op is named `:schedule-specialized-operation`,
matching ISIC 4390's OWN domain (scaffolding/piling/waterproofing), not
`cloud-itonami-isic-4330`'s `:schedule-finishing-operation` name --
4390's residual scope is not building-completion/finishing work. This
fleet's own mislabeling discipline (verify the assigned ISIC id/name
against the live registry before writing anything) argues against
borrowing a domain-mismatched op name from a sibling actor's different
ISIC class, even when structurally mirroring that sibling closely.

### Closed op-allowlist (4 ops, all `:effect :propose`)

| Op | Ask | Implementation |
|---|---|---|
| `:log-site-record` | trade-progress / material-usage / scaffold-inspection-status data logging | Normalizes and commits a patch onto the site's ground-truth fields (`:site-verified?`, `:scaffold-inspection-completed?`, `:vibration-level-measured`, `:vibration-mitigation-installed?`, concern resolution, etc.) and appends an immutable site-record-log entry. No direct capital/safety risk -- MAY auto-commit at phase 3. |
| `:schedule-specialized-operation` | scaffolding-erection, pile-driving/foundation, or waterproofing scheduling proposal | Drafts a proposed work WINDOW (never a trade-equipment-dispatch command or a structural-completion sign-off). ALWAYS escalates to a human once the governor is clean -- a PERMANENT `high-stakes` member here, see `Actuation`. |
| `:flag-safety-concern` | surface a scaffold-collapse, pile-driving-vibration, or structural concern | Drafts a safety-concern flag; ALWAYS escalates to a human, unconditionally. Once approved, `specialized.notify` sends the notice (mail + phone) to the site's supervisor/safety-officer contact roster. |
| `:order-supplies` | materials/equipment procurement proposal | Drafts a supply-order proposal. Escalates above a cost threshold or below the confidence floor; may auto-commit at phase 3 otherwise. |

**Legal basis is data, not code** -- `src/specialized/facts.cljc`'s
`catalog` is the per-jurisdiction EDN source-of-truth the governor checks
every `:schedule-specialized-operation` proposal against (JPN/USA/DEU
seeded; DEU stands in for the EU, the same convention
`installation.facts`/`finishing.facts`/`demolition.facts`/`construction.
facts`/`aerospace.facts` use for EASA). Every citation below was
independently verified against its official/authoritative source before
being written:

| Jurisdiction | Scaffold-inspection legal basis | Pile-driving-vibration legal basis |
|---|---|---|
| 🇯🇵 Japan | 労働安全衛生規則第567条（点検）-- [e-Gov](https://laws.e-gov.go.jp/law/347M50002000032) | 振動規制法（昭和51年法律第64号）-- 敷地境界線で振動レベル75デシベル -- [e-Gov](https://laws.e-gov.go.jp/law/351AC0000000064) |
| 🇺🇸 USA | 29 CFR 1926.451 (OSHA, Scaffolds -- General requirements) -- [osha.gov](https://www.osha.gov/laws-regs/regulations/standardnumber/1926/1926.451) | OSH Act §5(a)(1) General Duty Clause (no fixed federal numeric vibration standard) -- [osha.gov](https://www.osha.gov/laws-regs/oshact/section5-duties) |
| 🇪🇺 EU (DEU proxy) | TRBS 2121 Teil 1 + DGUV Vorschrift 38 §12 (2.00 m scaffold side-protection trigger) -- [BAuA](https://www.baua.de/DE/Angebote/Regelwerk/TRBS/TRBS-2121-Teil-1) | DIN 4150-3:2016-12 (row-2 residential/mixed-use Anhaltswert, 5 mm/s PPV horizontal) -- [DIN Media](https://www.dinmedia.de/en/standard/din-4150-3/262430160) |

JPN (75 dB site-boundary vibration LEVEL) and DEU (5 mm/s PEAK PARTICLE
VELOCITY) have real numeric vibration triggers in their OWN native,
genuinely DIFFERENT physical unit; the USA deliberately does NOT have a
federal numeric trigger -- `specialized.facts/vibration-noncompliant?`
reports `:qualitative` there rather than fabricating a shared cross-
jurisdiction unit or a number. This actor never converts between dB and
mm/s -- see `specialized.facts` ns docstring for the full honesty
discipline.

**Governor -- eight HARD checks, ALL un-overridable by human approval:**
unknown op (outside the closed 4-op allowlist), `:effect` not `:propose`,
forbidden action class (trade-equipment-control / direct-actuation /
structural-completion-sign-off-finalization markers), site/permit not
independently verified/registered, legal-basis missing, scaffold
inspection incomplete, pile-driving-vibration noncompliant (quantitative
jurisdictions only), unresolved safety concern on file. See
`specialized.governor` ns docstring for the full enumeration, rationale
and real-law citations behind each.

## Actuation

This actor performs **no real-world actuation** -- every committed
record carries `:effect :propose` (see `specialized.governor` ns
docstring). `:flag-safety-concern` NEVER auto-commits at any phase -- it
always needs a human sign-off, even when the governor is completely
clean (`specialized.phase` ns docstring 'Actuation' section,
`specialized.governor`'s `high-stakes` set).

**UNLIKE `cloud-itonami-isic-4329`/`cloud-itonami-isic-4330`, and LIKE
`cloud-itonami-isic-4311` (demolition) / `cloud-itonami-isic-4210` (roads
and railways):** `:schedule-specialized-operation` here IS a permanent
`high-stakes` member -- it NEVER auto-commits, at any phase, even when
the governor is completely clean. Scaffold collapse and pile-driving-
induced ground vibration damaging a neighboring structure are genuinely
closer in public-safety stakes to demolition/heavy-earthwork scheduling
than to `cloud-itonami-isic-4329`'s thermal-insulation/sound-proofing/
elevator-installation domain, so this actor keeps a human in the loop for
EVERY schedule proposal, not only for `:flag-safety-concern`. `:log-site-
record` and `:order-supplies` BELOW the cost threshold
(`specialized.governor/supply-order-cost-threshold-usd`) MAY auto-commit
at phase 3 when the governor is clean and confidence is high. The eight
HARD governor checks still apply UNCONDITIONALLY regardless of phase;
only the routing to a human vs. auto-commit changes, and for
`:schedule-specialized-operation`/`:flag-safety-concern` that routing is
ALWAYS to a human, never auto.

```bash
clojure -M:dev:run    # demo: full coordination episode + every HARD hold
clojure -M:dev:test   # test suite
clojure -M:lint       # clj-kondo, errors fail
```

## License

AGPL-3.0-or-later.
