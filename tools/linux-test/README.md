# Local Linux backend verification

This opt-in test facility has **full authority over the current local Docker daemon**
through its socket. Labels restrict this tool's operations; they are not a security sandbox.
Use only after explicit authorization. No remote daemon, privileged container, host PID,
host networking, Windows source/tmp/cache bind, daily `.env`, or user credentials are used.

The Dockerfile pins official Docker Library Maven/Temurin and Docker CLI manifest digests.
Ubuntu's signed package repositories supply Python3; installed package versions and actual
Java/Maven/Python/Docker versions are recorded, along with the resulting image content ID.
It does not change production dependencies. Built images and build cache are retained local
artifacts; the tool never prunes images or shared cache.

Commit the test facilities first, then run from the repository root:

```powershell
python tools/linux_test_runner.py --source <exact-40-character-commit>
```

Only `git archive` of that commit enters the runner writable layer. The runner verifies
local context and daemon identity, adds random run/source labels, and records exact IDs,
creation times and image identities. The test-only Testcontainers SPI preserves existing
labels and adds the same provenance to sibling containers, including Ryuk. Ryuk remains
enabled and unprivileged. A separate disposable HTTP peer first checks
`host.docker.internal` published-port connectivity. Then the existing real ClamAV/EICAR
method runs, followed by units and the full integration suite, sequentially. Each phase
uses the same immutable source snapshot and runner. A failed phase stops progression.

The business-fields/folder/history snapshot expects 89 unit and 175 integration methods, with no failures/errors/
skips or duplicate identities. Changing those counts requires reviewing the actual tests.
Compared with the history-only snapshot, folders add three unit methods, nine
`FolderDatabaseIT` methods and one real OIDC folder HTTP method. Business fields add four `BusinessFieldsTest` unit methods, seven
`BusinessFieldsDatabaseIT` methods and one `BusinessFieldsMigrationUpgradeIT` method.
The scan preflight expects exactly 89 units and one real scan method; unit expects
89 units; full integration expects 89 units and 175 integrations. Existing latest
migration assertions require formal V23 (23 migrations); the deliberate failure
probe is V25, with V24 reserved for the execution workline. These remain assertions
rather than additional methods. The coordinator explicitly authorized this exact
inventory update; commands, zero-failure checks and resource guards stay fixed.
Raw Maven logs, upload temp files, caches and EICAR bytes stay inside Linux. Only runtime
versions, package names/versions, method identities and aggregate status JSON are exported
under ignored `local-evidence/linux-tests/<run>/`. No XML properties/system-out, exception
messages, stack traces or arbitrary directory copies are exported.

Cleanup preserves Ryuk's normal lifecycle and removes only individually reverified
run/source-labelled container IDs. `docker rm -v` includes their attached anonymous
volumes; no named-volume, network, prefix-based or project-wide deletion is performed.
If daemon/identity/ownership changes, cleanup reports failure and refuses those mutations.
This is independent of CI-owned browser acceptance; it sets no CI identity variables.

Reviewer directory adds `BusinessFieldsDatabaseIT.reviewerDirectoryIsReadableByMembersBoundedSearchableAndRejectsForeignOrInactiveScope`, bringing the reviewed inventory to 89/175.
