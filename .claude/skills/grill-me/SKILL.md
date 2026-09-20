---
name: grill-me
description: Interview the user about design intent before writing code. Use when evaluating architecture options, a significant change, or a requirement with open questions.
---
# Architectural evaluation mode

Do not write code or files yet. Ask questions until the design is clear, one topic at a
time, and summarise the decisions before building. Cover:

- **Requirements.** What exactly should happen, including limits, defaults and what
  happens on failure. Which of these are assumptions?
- **Contract.** Does any endpoint, status code or stored field change? Who depends on it?
- **Threading.** The engine runs stages on fixed thread pools. Does the change block a
  thread, need virtual threads, or share mutable state?
- **Security.** Open redirects, unvalidated input, secrets in logs or prompts, anything
  that should be blocked by `PolicyGuard`.
- **Database cost.** Extra queries on the redirect path (the hot path), new indexes, and
  whether a new column must be nullable for existing data.
- **Rollback.** How is this undone if it goes wrong?
- **Tests.** Which success and failure cases prove it works?

Then write down the decision, with the options rejected and why - as an ADR if it is
significant.
