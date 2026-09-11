(ns specialized.facts
  "Per-jurisdiction other-specialized-construction regulatory catalog --
  the spec-basis table the Specialized Trade Governor checks every
  `:schedule-specialized-operation` proposal against ('did the advisor
  cite an OFFICIAL public source for this jurisdiction's scaffold-
  inspection duty and pile-driving-vibration limit, or did it invent
  one?'). Same honest-coverage discipline `installation.facts`
  (`cloud-itonami-isic-4329`) / `finishing.facts` (`cloud-itonami-isic-
  4330`) established for this fleet: a jurisdiction not in this table has
  NO spec-basis, full stop -- the advisor must not fabricate one, and the
  governor holds if it tries.

  Coverage is reported HONESTLY (see `coverage`); this is a STARTING
  catalog (JPN/USA/DEU), not a from-scratch survey of all ~194
  jurisdictions. Extending coverage is additive: add one map to `catalog`,
  cite a real source, done -- never invent a jurisdiction's requirements
  to make coverage look bigger.

  Two independent legal bases, both citable and both real:

    1. `:scaffold-inspection-basis` -- the pre-work-shift / post-erection-
       change / post-severe-weather scaffold inspection duty that applies
       once a specialized-trade crew erects or works from scaffolding.
       Deliberately a boolean ground-truth field (`:scaffold-inspection-
       completed?`), the SAME shape `installation.facts`'s `:hazmat-
       survey-basis` uses -- this actor cannot independently recompute
       'was the scaffold actually inspected', only that the site's own
       record says so.
    2. `:vibration-basis` -- the pile-driving/foundation-work vibration
       duty protecting NEIGHBORING structures and occupants, this
       domain's OWN real, independently-recheckable hazard (distinct
       from `installation.facts`'s at-height fall-protection trigger --
       ISIC 4390's residual scope of scaffolding/piling/waterproofing
       work not elsewhere classified is the first actor in this fleet
       that needs a pile-driving-vibration check specifically).

  `:threshold-model` mirrors the SAME honest quantitative/qualitative
  split `installation.facts`/`finishing.facts`/`demolition.facts`
  established -- BUT which jurisdiction lands on which side is NOT copied
  from those siblings; it reflects the REAL regulatory landscape for
  THIS hazard:
    :quantitative -- JPN (振動規制法 sets a fixed 75 dB site-boundary
                     vibration-LEVEL trigger for specified construction
                     work using pile-driving equipment) and DEU (DIN
                     4150-3 sets a fixed peak-particle-velocity (PPV,
                     mm/s) guidance value for ordinary residential/
                     mixed-use structures). `vibration-noncompliant?` can
                     independently recompute a HARD hold from either.
    :qualitative  -- USA. OSHA has NO federal numeric construction-
                     vibration standard -- only the OSH Act Section
                     5(a)(1) General Duty Clause (a workplace 'free from
                     recognized hazards'). This actor does NOT invent a
                     number to make this jurisdiction look automatable.

  CRITICAL honesty note: JPN's trigger is measured in decibels (vibration
  LEVEL, a human-response-weighted logarithmic quantity per the
  Vibration Regulation Act) and DEU's trigger is measured in mm/s (PEAK
  PARTICLE VELOCITY, a structural-damage-oriented linear quantity per DIN
  4150-3) -- these are genuinely DIFFERENT physical quantities, not two
  readings of the same thing in different units. This namespace does NOT
  fabricate a shared unit or a cross-jurisdiction conversion factor: each
  jurisdiction's `:vibration-trigger-value`/`:vibration-trigger-unit` pair
  is only ever compared against a site's own recorded
  `:vibration-level-measured` field for THAT SAME jurisdiction. See
  `vibration-noncompliant?`.

  DEU is used as the EU-jurisdiction proxy, the SAME convention
  `installation.facts`/`finishing.facts`/`demolition.facts`/`construction.
  facts`/`aerospace.facts` established -- there is no ISO-3166 alpha-3
  code for the EU itself. DIN 4150-3 is a purchasable German national
  standard (not a freely-published statute like the JPN/USA citations
  below) -- its provenance link is DIN's own official standard-catalog
  page, honestly labelled as such rather than presented as free full text
  the way laws.e-gov.go.jp/osha.gov/baua.de are. All citations below were
  independently verified against their official/authoritative source
  before being written."
  )

(def catalog
  "iso3 -> requirement map. `:scaffold-inspection-basis` / `:vibration-
  basis` / their `-provenance` pairs, plus `:owner-authority`, are the
  G2-style citation the governor requires before a `:schedule-
  specialized-operation` proposal can ever commit."
  {"JPN" {:name "Japan"
          :owner-authority "厚生労働省（労働基準監督署）／環境省（振動規制法所管、指定地域は地方公共団体）"
          :scaffold-inspection-basis "労働安全衛生規則第567条（点検）-- 足場（つり足場を除く）における作業を行うときは、その日の作業を開始する前に、当該作業に係る足場について点検し、及び異常を認めたときは、直ちに補修すること。強風、大雨、大雪等の悪天候若しくは中震以上の地震又は足場の組立て、一部解体若しくは変更の後においても同様の点検義務を負う。"
          :scaffold-inspection-provenance "https://laws.e-gov.go.jp/law/347M50002000032"
          :vibration-basis "振動規制法（昭和51年法律第64号）-- くい打機（もんけん及び圧入式くい打機を除く。）、くい抜機（油圧式くい抜機を除く。）又はくい打くい抜機（圧入式くい打くい抜機を除く。）を使用する作業その他政令で定める特定建設作業に伴って発生する振動について、指定地域内の特定建設作業の場所の敷地境界線において振動レベル75デシベルを超えないことが規制基準（作業開始7日前までの届出義務も同法第14条に規定）。"
          :vibration-provenance "https://laws.e-gov.go.jp/law/351AC0000000064"
          :threshold-model :quantitative
          :vibration-trigger-value 75
          :vibration-trigger-unit :vibration-level-db
          :threshold-note "指定地域内の特定建設作業の場所の敷地境界線における振動レベルが75デシベルを超える場合、振動規制法上の規制基準を超過（規制運用は都道府県知事等が指定する地域内に限られる点に留意 -- 指定地域外は本チェックの対象外として扱う）。"}
   "USA" {:name "United States"
          :owner-authority "Occupational Safety and Health Administration (OSHA), U.S. Department of Labor"
          :scaffold-inspection-basis "29 CFR 1926.451 (General requirements -- scaffolds must be designed by a qualified person and constructed/loaded per that design; scaffolds and scaffold components must be inspected for visible defects by a competent person before each work shift and after any occurrence that could affect the scaffold's structural integrity)"
          :scaffold-inspection-provenance "https://www.osha.gov/laws-regs/regulations/standardnumber/1926/1926.451"
          :vibration-basis "No dedicated OSHA numeric construction-vibration standard exists for pile-driving-induced ground vibration affecting neighboring structures. The applicable federal duty is the OSH Act Section 5(a)(1) General Duty Clause: 'Each employer shall furnish to each of his employees employment and a place of employment which are free from recognized hazards that are causing or are likely to cause death or serious physical harm' (29 U.S.C. 654). Numeric vibration limits, where they exist, come from state/local ordinances or voluntary industry references (e.g. FDOT's 200 ft sheet-pile monitoring trigger, ISO 4866, or DIN 4150-3 itself used as a reference standard) -- not from a single federal rule."
          :vibration-provenance "https://www.osha.gov/laws-regs/oshact/section5-duties"
          :threshold-model :qualitative
          :vibration-trigger-value nil
          :vibration-trigger-unit nil
          :threshold-note "連邦レベルで一律の振動数値基準はOSHAには存在しない（一般的義務条項（General Duty Clause）のみ）。ここで数値を創作しない -- 州・自治体条例や業界標準（ISO 4866、FDOT基準等）に委ねられている。"}
   "DEU" {:name "Germany (EU jurisdiction proxy, see ns docstring)"
          :owner-authority "Bundesanstalt für Arbeitsschutz und Arbeitsmedizin (BAuA) / Berufsgenossenschaft der Bauwirtschaft (BG BAU); vibration: Deutsches Institut für Normung (DIN), a national standard -- no single EU-wide numeric limit"
          :scaffold-inspection-basis "TRBS 2121 Teil 1 (Gefährdung von Beschäftigten durch Absturz bei der Verwendung von Gerüsten), issued by BAuA under the Betriebssicherheitsverordnung (BetrSichV) -- requires scaffolds to be erected, inspected and used per a documented risk assessment; DGUV Vorschrift 38 (Bauarbeiten) §12 additionally fixes a concrete 2.00 m side-protection trigger for scaffold work specifically."
          :scaffold-inspection-provenance "https://www.baua.de/DE/Angebote/Regelwerk/TRBS/TRBS-2121-Teil-1"
          :vibration-basis "DIN 4150-3:2016-12 (Erschütterungen im Bauwesen -- Teil 3: Einwirkungen auf bauliche Anlagen / 'Vibrations in buildings -- Part 3: Effects on structures') -- specifies Anhaltswerte (guidance values) for short-term vibration by building category (row 1 robust/industrial, row 2 ordinary residential/mixed-use, row 3 sensitive/heritage structures). This catalog cites the row-2 guidance value -- 5 mm/s for the largest horizontal component at the uppermost floor level -- as a conservative general reference; the standard's actual applicable value depends on a building-category classification this actor does not perform on its own (the same kind of judgment call `installation.facts` reserves for its own :qualitative jurisdiction)."
          :vibration-provenance "https://www.dinmedia.de/en/standard/din-4150-3/262430160"
          :threshold-model :quantitative
          :vibration-trigger-value 5
          :vibration-trigger-unit :ppv-mm-s
          :threshold-note "DIN 4150-3のAnhaltswerteは建物区分（row1〜3）ごとに異なる。ここではrow-2（一般住宅・混合用途）の水平最大成分5mm/sを保守的な参照値として引用する -- 実際の運用では建物区分の個別判定が必要な点は率直に開示する。"}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any `:schedule-specialized-operation`
  proposal that tries to cite one."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-4390 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `specialized.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn vibration-noncompliant?
  "Independently recompute whether `site`'s own recorded ground-truth
  fields -- `:vibration-level-measured` (the site's own recorded
  pile-driving/foundation-work vibration reading, in `iso3`'s OWN native
  unit -- dB for JPN, mm/s PPV for DEU) and `:vibration-mitigation-
  installed?` (whether an isolation-trench, pre-boring, monitoring-and-
  stop-work plan or similar mitigation is actually in place) -- leave the
  site out of compliance with `iso3`'s pile-driving-vibration trigger.

  Three-valued, deliberately (the same shape `installation.facts/fall-
  protection-noncompliant?`/`finishing.facts/fall-protection-
  noncompliant?` established):
    true         -- a :quantitative jurisdiction (JPN, DEU) whose own
                    numeric trigger is independently confirmed MET OR
                    EXCEEDED by the site's own recorded actual vibration
                    reading, AND no mitigation measure is recorded as
                    installed -- a bright-line regulatory violation. The
                    Specialized Trade Governor turns this into a HARD,
                    un-overridable hold on `:schedule-specialized-
                    operation`.
    false        -- either below the trigger, or at/above it with a
                    mitigation measure already recorded installed.
    :qualitative -- a jurisdiction with NO fixed numeric trigger (USA).
                    This actor cannot independently confirm
                    'compliant'/'noncompliant' by arithmetic alone. Never
                    fabricate a trigger value here.
    nil          -- no spec-basis at all for `iso3` (a jurisdiction not in
                    `catalog`)."
  [iso3 {:keys [vibration-level-measured vibration-mitigation-installed?]}]
  (when-let [{:keys [threshold-model vibration-trigger-value]} (spec-basis iso3)]
    (case threshold-model
      :quantitative
      (boolean (and (number? vibration-level-measured)
                    (>= vibration-level-measured vibration-trigger-value)
                    (not (true? vibration-mitigation-installed?))))
      :qualitative
      :qualitative
      nil)))
