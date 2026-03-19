# Approval Job Step 3.1.0

## Added

- Project-level Approvals settings page
- Email and Slack notification methods
- Slack connection test and Slack delivery test
- Slack user selection from workspace users
- Rundeck-hosted approval landing and preview pages
- Improved Slack approval message layout
- Plugin icon resources for the workflow step

## Changed

- Approval configuration is now managed centrally at the project level
- Workflow step UI is reduced to the runtime fields that matter for a workflow author
- Delivery testing is built into the approvals settings UI
- Package metadata and artifact naming normalized for community distribution

## Operational note

Pending approvals keep execution resources active until approved, denied, or timed out. Long waits can impact platform capacity.
