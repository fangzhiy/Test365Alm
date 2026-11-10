# Historical content service fixtures

Source: `61711294ff10e16b8df29418badaa8b455bc80ea`, before additive V23.
The coordinator explicitly approved these test-only historical implementations.

`LegacyRequirementService`, `LegacyTestCaseService`, `LegacyDefectService` and `LegacyDefectRules` retain that commit's SQL and behavior. The only adaptations are removing component annotations, naming the test class, extending the current service and calling its constructor, and reusing the current compatible public DTO constructors instead of redeclaring DTOs. Private historical records/helpers remain frozen. No owner business write or mocked service replaces the real runtime paths.

Inherited current content methods would silently query columns absent from old schemas. `LegacyContentServices` therefore requires every public current service method to have an explicit historical override before registering a fixture. The classes live only in test sources and have no component annotations; the production artifact and API cannot register them. This is not production schema detection or fallback logic.

| Fixture | Public methods from the source |
|---|---|
| LegacyRequirementService | create, list (both overloads), getAuthorized, update, revisions, revision |
| LegacyTestCaseService | create, list (both overloads), getAuthorized, appendRevision, revisions, revision |
| LegacyDefectService | create, fromStep, edit, transition, retest, get, list, history, revision, verification, retests |

`DefectMigrationUpgradeIT` uses the frozen requirement/test services only while populating V17. `ReleaseMigrationUpgradeIT` and `AssetMigrationUpgradeIT` use all three while populating V18/V19. `AssetProvisioningUpgradeIT` uses only the historical requirement service while populating V20. Trace, execution, assets, releases, authorization, RLS, transaction advice, audit, outbox and durable idempotency remain real restricted-runtime paths.

Each test retains its original nonempty old-schema assertions, performs its original target upgrade and complete snapshot/idempotence assertions, then migrates to latest before constructing a new context containing the current production services. The later snapshot projection omits only the four additive V23 keys (`business_fields` on three revisions and `result_business_fields` on requirement idempotency); every original column remains compared. The dedicated V22→V23 test separately checks all original columns and immutable runtime rejection. No V1–V22 migration is changed.
