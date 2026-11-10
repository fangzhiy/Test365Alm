# Explicit local-owned audit browser acceptance

`python tools/local_browser_runner.py --source <exact commit>` runs only the three
existing real Keycloak case/run/defect audit flows. Commit facilities and verify their
offline negative tests first. This is a separate local mode, never a simulated CI run.

It archives only the specified Git commit into a new ignored snapshot and generates
fresh credentials, random run/project identities and unused localhost ports. Existing
project resources or credentials are refused. It reads no daily `.env` or demo config,
uses neither 5173/8080 nor W1 demo resources, and makes no production/migration changes.

The shared R02/R03 manifest guard pins local daemon/context and exact resources;
additional source, container creation and image checks precede mutations. PostgreSQL
uses a fresh labelled volume and an LF copy of the existing initializer. Keycloak's
realm is copied into its own container and set to UID1000/mode0400 before starting
the real server; the realm is not mounted from Windows. The local snapshot builds
the real server (including unit tests), installs locked frontend dependencies using
an isolated npm config and starts direct Java/Vite process handles on new ports.
The Java21 location follows AGENTS.md. Existing Edge is used when present.

Only the three specified tests accept local ownership. CI `isOwnedCiRun` remains
unchanged, and IdP stop/restart/disable tests stay CI-only. PostgreSQL scope seeding
uses the verified exact local container ID. No GITHUB identity variables are set.
The application uses its real OIDC/CSRF/DB services. Add `--with-assets` for the
execution runner's real step attachments; the default retains the earlier
assets-disabled mode and cannot satisfy the extended manual execution flow.

`python tools/local_browser_runner.py --source <exact commit> --with-assets`
adds the existing digest-pinned R11 SeaweedFS and ClamAV services to the same
one-use run. Their two loopback ports are freshly allocated along with the four
application ports. All services, networks and volumes carry the same run/source
labels and enter the same exact-ID cleanup manifest. Before each asset readiness
probe and provisioning connection, the harness rechecks daemon/context, source,
container ID/creation/image, service identity and exact loopback port bindings.
The readiness control requires a private S3 response and a real benign ClamAV
INSTREAM=CLEAN; it does not stand in for UI upload/publication/link/download checks.

The initializer never creates a scanner group LOGIN in this mode. After real
application Flyway readiness, the repository's explicit scanner provisioning
entry point creates a fresh, separate restricted LOGIN using the owned PostgreSQL
container's full ID. Both connections require SCRAM; passwords and verifier SQL
stay in private environment/stdin, never argv or raw reports. The frontend flow
also checks the two exact asset container identities and loopback bindings before
its first write. Missing/foreign services fail acceptance; attachments are not
skipped or mocked. The summary records asset mode, readiness and provisioning
separately from business success and cleanup. A failure stops directly owned
processes, redacts diagnostics and removes only the recorded resource IDs.
Real EICAR backend tests remain in the independently owned Linux runner to avoid
Windows quarantine; no Defender exclusions or scanning substitutions are made.

Playwright runs one worker, zero retries, without traces/screenshots/video. An
allowlisted reporter exports only test names, statuses, retry count and durations;
all three exact tests must pass, with no skips. The reporter reads actual workers
and retries from FullConfig and refuses missing or mixed retry configuration.
Existing safe API-path/status and
named-step diagnostics stay in the disposable snapshot's ignored evidence folder.
Raw process logs are credential/header/query redacted; private originals, generated
environment, realm and secret-bearing Compose JSON are removed. Exact own process
handles stop first, then verified container/network/volume IDs are removed through
the manifest guard. Cleanup failure is separate from business failure. No project
down, prefix cleanup, image/cache pruning or operations on another stack occur.

Configuration and daemon refusals use unified finalization. Generated secrets
are removed after partial configuration failure; pre-existing environment, realm
or secret Compose files are refused and preserved. Before any create attempt,
refusal cleanup does not inspect or mutate project resources.
