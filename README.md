# Approval Job Step Plugin

Community-maintained Rundeck `WorkflowStep` plugin for email-based approvals with escalation and callback links.

## Important operational warning

**Pending approvals keep execution resources active until approved, denied, or timed out.**

At scale, many open approvals can reduce worker/thread capacity and delay other jobs.

## Release

- Current: `3.0.9`
- Artifact: `releases/approval-job-step-3.0.9.jar`
- Release notes: `releases/CHANGELOG-3.0.9.md`

## Quick install

```bash
cp releases/approval-job-step-3.0.9.jar $RDECK_BASE/libext/
# then restart Rundeck
```

## Build from source

```bash
./gradlew clean jar
cp build/libs/approval-job-step-3.0.9.jar $RDECK_BASE/libext/
```

## Example job

- `examples/approval-gate-example.yaml`

## Compatibility

- Rundeck 5.x
- Plugin service: `WorkflowStep`
- Plugin ID: `approval-job-step`

## Docs

- `docs/install-and-setup.md`
- `docs/configuration-reference.md`
- `docs/operations-and-capacity.md`
- `docs/troubleshooting.md`

## Core behavior

- Approve: workflow continues.
- Deny: hard-stop/fail path.
- Timeout:
  - `autoApproveOnTimeout=true` -> continue.
  - `autoApproveOnTimeout=false` -> fail and stop.
