# Operations and Capacity

Pending approvals keep workflow executions active until they are approved, denied, or timed out.

## Why this matters

Each waiting approval continues to occupy execution capacity. At scale, long-running approval waits can slow other work on the platform.

## Recommendations

- keep timeouts short and intentional
- keep escalation delays practical
- monitor concurrent waiting approvals
- avoid using approvals as indefinite task queues
