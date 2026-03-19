# Configuration Reference

## Project Approvals Settings

### Notification Method

- Email
- Slack

### Approval Profiles

Each profile includes:

- primary approver
- secondary approver
- escalation time in minutes

### Shared Advanced Options

- Approval URL Base: base URL used in approval messages
- Check Interval: polling interval while workflow waits

### Email Delivery

- SMTP Server
- SMTP Port
- SMTP Username
- SMTP Password Path
- From Email Address
- Use TLS

### Slack Delivery

- Slack Bot Token Path
- Slack workspace user selection for approvers

## Workflow Step Fields

- Approval Profile
- Approval Message
- Auto-approve on Timeout
