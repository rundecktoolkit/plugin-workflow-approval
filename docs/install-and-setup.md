# Install and Setup

## Install

Upload `approval-job-step-3.1.0.jar` through **System Menu -> Plugins -> Upload Plugin** or copy it into `$RDECK_BASE/libext/`.

## Project Configuration

Open **Project Settings -> Approvals** and configure:

- notification method
- approvers and escalation
- email or Slack delivery settings
- approval URL base
- check interval

## Required for Email

- SMTP server
- SMTP username
- SMTP password in Key Storage
- From email address

## Required for Slack

- Slack bot token in Key Storage
- Slack connection test
- Slack user selection for approvers

## Workflow Step

Add **Approval Job Step** to the workflow and choose the approval profile to use.
