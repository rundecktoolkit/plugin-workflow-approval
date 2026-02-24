<h1 align="center">Rundeck Approval Job Step Plugin</h1>

<p align="center">
  <strong>Email approval gate for Rundeck workflows with escalation and callback control</strong>
</p>

<p align="center">
  <a href="#overview">Overview</a> •
  <a href="#installation">Installation</a> •
  <a href="#configuration">Configuration</a> •
  <a href="#usage">Usage</a> •
  <a href="#operations-capacity">Operations & Capacity</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Rundeck-Community-5C9E3D?logo=rundeck&logoColor=white" alt="Rundeck Community"/>
  <img src="https://img.shields.io/badge/WorkflowStep-Approval-0F1E57" alt="Workflow Step"/>
  <img src="https://img.shields.io/badge/License-MIT-blue" alt="MIT License"/>
</p>

---

## Overview

This plugin adds an **Approval Job Step** to Rundeck workflows.

It allows a workflow to pause for human approval by sending email links to approvers.

### Key features

- Primary approver email with **Approve** and **Deny** callback links
- Optional secondary approver escalation after a configurable delay
- Timeout handling:
  - continue on timeout (`autoApproveOnTimeout=true`)
  - fail/terminate on timeout (`autoApproveOnTimeout=false`)
- Deny path hard-stop behavior for immediate execution failure
- Rundeck job link included in approval email body
- SMTP secret retrieved from Rundeck Key Storage

## Compatibility

| Platform | Version |
|----------|---------|
| Rundeck Community | 5.x |
| Runbook Automation (Self-Hosted) | 5.x |

## Release

- Current version: `3.0.9`
- Artifact: `releases/approval-job-step-3.0.9.jar`
- Release notes: `releases/CHANGELOG-3.0.9.md`

## Installation

Download the latest JAR from [Releases](../../releases) and install via the Rundeck UI:

1. Navigate to **System Menu** → **Plugins** → **Upload Plugin**
2. Select `approval-job-step-3.0.9.jar`
3. The plugin is available immediately. If your environment uses plugin caching, reload plugins or restart Rundeck.

### Alternative CLI install

```bash
cp releases/approval-job-step-3.0.9.jar $RDECK_BASE/libext/
# then reload plugins or restart Rundeck
```

### Build from source

```bash
./gradlew clean jar
cp build/libs/approval-job-step-3.0.9.jar $RDECK_BASE/libext/
```

## Configuration

### Required parameters

| Parameter | Description |
|-----------|-------------|
| `approvalMessage` | Message shown in approval request email |
| `primaryApproverEmail` | First approver email |
| `smtpServer` | SMTP server host |
| `smtpUsername` | SMTP username |
| `smtpPasswordPath` | Rundeck Key Storage path for SMTP password |
| `fromEmailAddress` | Sender address |

### Common optional parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `approvalTimeoutMinutes` | `60` | Max time to wait for response |
| `autoApproveOnTimeout` | `false` | Continue automatically when timeout is reached |
| `secondaryApproverEmail` | (empty) | Escalation approver |
| `escalationTimeMinutes` | `30` | Delay before escalation email |
| `smtpPort` | `587` | SMTP port |
| `useTls` | `true` | Enable STARTTLS |
| `approvalUrlBase` | `http://localhost:5555` | Base URL used in approve/deny links |
| `checkIntervalSeconds` | `30` | Polling interval while waiting |

## Usage

1. Add **Approval Job Step** as a workflow step (typically first).
2. Configure SMTP and approvers.
3. Set `approvalUrlBase` to a reachable URL for approvers.
4. Run job:
- Approve -> workflow continues.
- Deny -> workflow hard-stops/fails.
- Timeout -> follows `autoApproveOnTimeout`.

### Example job definition

- `examples/approval-gate-example.yaml`

## Operations & Capacity

**Important:** Pending approvals keep execution resources active until approved, denied, or timed out.

Operational impact at scale:

- Open approvals consume active execution/worker capacity.
- Too many pending approvals can delay other jobs.
- Keep timeout values reasonable and monitor concurrent approvals.

See:

- `docs/operations-and-capacity.md`
- `docs/troubleshooting.md`

## Security Notes

- SMTP password is read from Rundeck Key Storage, not plaintext config.
- Callback links are tokenized (`id` + `token`) and validated server-side.
- Use HTTPS in `approvalUrlBase` for production deployments.

## Documentation

- `docs/install-and-setup.md`
- `docs/configuration-reference.md`
- `docs/operations-and-capacity.md`
- `docs/troubleshooting.md`

## License

MIT License — see `LICENSE`.

---

<p align="center">
  <sub>Part of <a href="https://github.com/rundecktoolkit">rundecktoolkit</a> — Community plugins for Rundeck</sub>
</p>
