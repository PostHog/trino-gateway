# Baseline comparison

A baseline separates a behavior gap from a broken fixture. Keep execution
results, artifact identities, timings, logs, and environment details private.

Pin a pre-change Gateway artifact. Use disposable PostgreSQL, independent
Gateway processes, and independent synthetic coordinators. First establish a
positive pooled query control. Then run the same Host, header non-effect,
unknown-host, no-fallback, and owner-affinity assertions against both artifacts.

Older artifacts do not recognize the new hostname configuration. A comparison
may need a private compatibility setup to establish its positive control.
Record that setup change explicitly. Never count a configuration/startup error
as evidence of misrouting. Preserve unresolved ledger state rather than resetting
it to make an assertion pass.

Inspect backend dispatch and recorded query/coordinator identity, not HTTP status
alone. Trino can return HTTP 200 with a query error. A missing API establishes a
missing capability, not an independently proven routing error.

A genuine regression fails before the change and passes afterward without
expected-failure annotations or skipped assertions. Keep unsuccessful attempts
and fixture corrections in the private receipt. Freeze compiled classes for each
run; concurrent rebuilding can produce infrastructure errors.

See [README.md](README.md) for current pooled fixtures and
[VALIDATION.md](VALIDATION.md) for required coverage. Real-client verification
requires a compatible coordinator identity contract; do not invent identities
for an incompatible stock image.
