# Troubleshooting

## Email not arriving

- use the SMTP test
- use the test email delivery action
- check spam/junk folders
- confirm the From address aligns with the SMTP account

## Slack users not showing

- test Slack connection
- confirm bot token path is correct
- confirm the Slack app has the needed scopes and is installed in the workspace

## Approval links not working

- verify Approval URL Base points to a reachable Rundeck host
- prefer HTTPS and a trusted hostname outside local-only testing

## Plugin uninstall

Remove the plugin JAR from `$RDECK_BASE/libext/` and reload plugins or restart Rundeck.
