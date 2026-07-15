# Security Policy

This project handles specialized-construction-site coordination data
(site/permit records, scaffold-inspection status, pile-driving-vibration
readings, safety-concern flags, supply-order proposals). Treat
vulnerabilities as potentially high impact even when the demo data is
synthetic -- a bypass of the Specialized Trade Governor's hard checks
could allow a scaffolding/piling/waterproofing schedule to be proposed
against an unverified site/permit, an incomplete scaffold inspection, a
pile-driving-vibration violation, or an unresolved safety concern.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential exposure
- real site, permit or personal data exposure
- authorization bypass
- Specialized Trade Governor bypass (including any path that lets a
  proposal commit with an `:effect` other than `:propose`, or that lets a
  trade-equipment-control/structural-completion-sign-off marker through)
- audit-ledger tampering
- over-disclosure in safety-concern notices or exports
- tenant isolation failures

## Reporting

Use GitHub private vulnerability reporting when available for the
repository. If that is unavailable, contact the repository maintainers
through the cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on site-safety data, policy enforcement or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets for any real mail/phone transport wired into
  `specialized.notify/fn-notifier` outside Git.
- Keep real site/permit/personal data outside this repository.
- Run the full test suite (`clojure -M:dev:test`) before deployment.
- Export and review audit logs regularly.
- Use least privilege for operators and service accounts.
- Never deploy a fork that has relaxed the Specialized Trade Governor's
  closed op-allowlist or forbidden-action-class check.
