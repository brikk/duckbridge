# Operating rules for agents in this repo

## Consult before big workarounds (HARD RULE)
Do NOT invent large workarounds, architectural crutches, or "clever" bypasses
without checking with the user FIRST. When you hit a wall:
1. State the problem and the options plainly.
2. Say which is a real fix vs a crutch, and what each costs.
3. Ask before implementing anything that (a) skips/disables tests,
   (b) masquerades as another format/engine, or (c) degrades a type/semantic.

## Never blame the harness/system to turn a red bar green
If Trino / DuckDB / the parity extension pass something we fail, the bug is
almost certainly ours. Root-cause it (reproduce, read the actual code paths)
before skip-listing. A skip reason must be TRUE and specific, never
"environment variance" hand-waving.

## Prefer failing loud over silently wrong
Guards throw rather than return wrong rows. A pushdown is only emitted when the
parity extension proves DuckDB matches Trino semantics; when in doubt, don't
push down. Never silently over/under-return.

## Testing discipline
- Targeted per-module runs (`./gradlew :trino-duckbridge:test`) are the
  verification loop; scope down to a single test class while iterating.
- Commit at meaningful intervals; do NOT push until asked.
- Keep the module's `dev-docs/` current — every non-production-clean path goes
  there.

See `trino-duckbridge/dev-docs/` for the plan and phase notes.

## Commit identity (Buzz agents)

When you (a Buzz agent) commit on someone's behalf, attribute the commit to the
**human who issued the request**, not to the machine's global git identity.

1. The requester is the signed author pubkey of the triggering Buzz event.
2. Look that pubkey up in [`.buzz/authors.toml`](.buzz/authors.toml) to get the
   git `name` / `email`.
3. Commit as that identity, crediting the model as a co-author:

   ```bash
   GIT_AUTHOR_NAME="<name>" GIT_AUTHOR_EMAIL="<email>" \
   git commit -m "<subject>

   Co-Authored-By: Claude <model> <noreply@anthropic.com>"
   ```

4. **Pubkey not in `.buzz/authors.toml`?** Stop and ask — never silently fall
   back to the machine's global identity. Add the person (reviewed) first.

This matters for a **shared** Buzz agent that acts for several people. A person's
own standalone agent that only ever commits as its owner can ignore it — that
agent's global git identity is already correct.
