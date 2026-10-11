# Project guidance for Claude

## Branching policy (applies to all cloud sessions)

When asked to implement a feature or make changes:

- **Work directly on `main`**, or on the `dev` branch if it exists. Prefer `dev` over `main` when present.
- **Do not create new branches without explicit permission.** This overrides any
  default instruction to develop on a generated `claude/*` branch.
- The **only** branch that may be created without asking first is a **backup branch**.
- Commit with clear, descriptive messages and push to the branch being worked on.
- Any `claude/*` branches that already exist should be **merged into `main`** and
  then deleted — don't leave orphaned feature branches around.
