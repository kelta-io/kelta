# Slice 1 — Caller Identity Plumbing

> Parent: [`README.md`](README.md). **Security — never auto-merged.**

## 1. Goal & scope

**Delivers**
- A request-scoped `CallerContext` (caller UUID + user type + admin bypass flags) bound once per
  worker request, so owner scoping (slice 2), the self-profile endpoint (slice 3) and the
  owner-guard hook (slice 4) read one resolved identity instead of each re-resolving
  `X-User-Id`.
- PAT requests carry the **PAT owner's** user type (a portal user's PAT is PORTAL).
- Cerbos principals carry `attr.userId` (UUID); `$CURRENT_USER` and the UI "Restrict to own
  records" helper compile to `P.attr.userId`, so own-record CEL rules finally match.

**Does not** change what any caller may access — this slice is plumbing plus the CEL fix.

## 2. UI samples

`CelExpressionEditor.tsx` "Restrict to own records" inserts `R.attr.createdBy == P.attr.userId`
(was `== P.id`). No other UI.

## 3. Data & API contracts

```java
// runtime-core, io.kelta.runtime.context
public record CallerContext(String userId, UserType userType, boolean viewAll, boolean modifyAll) {
    public enum UserType { INTERNAL, PORTAL }
    public static Optional<CallerContext> current();               // empty = internal tier
    public static <T> T callAs(CallerContext c, Callable<T> work);  // tests / internal impersonation
    public boolean ownerScoped(OwnerScope scope) { ... }           // used by slice 2
}
```

- Bound by a worker `CallerContextFilter`, ordered **after** `TenantContextFilter` (needs the
  tenant to resolve the user) and before controllers.
- Inputs: `X-User-Id` (email or UUID) → `UserIdResolver.resolve(id, tenant)`; `X-User-Type`;
  `X-User-Profile-Id` → system permissions (`VIEW_ALL_DATA`, `MODIFY_ALL_DATA`) via the existing
  cached `BootstrapRepository.findProfileSystemPermissions`.
- No identity headers → no context (internal tier). Identity headers present but unresolvable
  (not a UUID after resolution) → **401** `{code:"CALLER_UNRESOLVED"}` — fail closed, matching the
  hooks' `CALLER_REJECTED`.
- Gateway, PAT path: `UserIdentityResolutionFilter` (already loads the PAT owner) adds the
  owner's `user_type` to the principal claims so `HeaderTransformationFilter` stamps it.
- Cerbos principal (worker `CerbosAuthorizationService` and gateway `CerbosPrincipalBuilder`):
  add `attr.userId` (UUID; worker from `CallerContext`, gateway from the resolved identity).
  `CerbosPolicySyncService.convertVisualToCel`: `$CURRENT_USER` → `<attr> == P.attr.userId`.

## 4. DB migrations

None. (Existing `profile_custom_rules` rows containing `== P.id` are rewritten by the policy
generator at sync time — see §5; no data migration.)

## 5. File-by-file code changes

| File | Change |
|---|---|
| `runtime-core/.../context/CallerContext.java` (new) | record + ScopedValue holder, like `TenantContext` |
| `kelta-worker/.../filter/CallerContextFilter.java` (new) | resolve + bind; 401 on unresolvable identity |
| `kelta-worker/.../config/*FilterConfig` | register after `TenantContextFilter` |
| `kelta-gateway/.../filter/UserIdentityResolutionFilter.java` | PAT principal: add `user_type` claim from the owner row |
| `kelta-gateway/.../filter/HeaderTransformationFilter.java` | unchanged logic; javadoc notes PAT `user_type` source |
| `kelta-gateway/.../authz/cerbos/CerbosPrincipalBuilder.java` | `attr.userId` |
| `kelta-worker/.../service/CerbosAuthorizationService.java` | `attr.userId` from `CallerContext` |
| `kelta-worker/.../service/CerbosPolicySyncService.java` | `$CURRENT_USER` → `P.attr.userId`; when generating, rewrite legacy `== P.id` comparisons against UUID-typed fields to `P.attr.userId` |
| `kelta-ui/.../CelExpressionEditor.tsx` | helper text |
| Existing hooks (`WatchGuardHook` …) | read `CallerContext` instead of re-resolving (behaviour unchanged) |

## 6. Test plan

- Worker unit: `CallerContextFilter` — email resolves to UUID; UUID passes through; unknown email
  → 401; no headers → no context; `VIEW_ALL_DATA` sets `viewAll`.
- Gateway unit: a PAT whose owner is PORTAL yields `X-User-Type: PORTAL`; INTERNAL owner → INTERNAL.
- **Real-PDP golden test** (`CerbosGeneratedPolicyIT` pattern): a custom rule
  `R.attr.createdBy == P.attr.userId` allows the owner and denies another user — the regression
  that was impossible before.
- kelta-test-harness: a portal user's PAT calling a collection route is treated as PORTAL.

## 7. Docs to update

`architecture.md` → identity headers + `CallerContext`; `integrations.md` (Cerbos principal
attributes); `concerns.md` (record the `P.id` = email history).

## 8. Risks & open questions

- **Native images:** `CallerContext` is plain code, but any new reflectively serialized type
  must go in `reflect-config.json` (worker, gateway) — none planned.
- Resolution cost: one cached lookup per request (`JdbcUserIdResolver` caches hits).
- Existing tenant CEL rules using `P.id` as an **email** (e.g. `R.attr.ownerEmail == P.id`) must
  keep working — the rewrite only touches comparisons against UUID-typed (LOOKUP/`createdBy`)
  fields.
