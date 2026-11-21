# One EC2 instance running Docker Compose on AWS, with Oracle Cloud as the fallback

Everything runs on one EC2 t4g.medium (Arm, 4 GiB) in eu-west-2 under Docker Compose: ledger, simulator, projector, Postgres 18, single-node Kafka, Caddy and Grafana Alloy. Terraform provisions it. Deploys arrive through GitHub OIDC and SSM, with no SSH.

AWS is the cloud UK Java job ads name. The $200 new-account credit (Paid plan; expires 12 months after opening) covers about 6 months on this instance. After that, the same Compose file moves to an Oracle Cloud Always Free Arm VM.

## Considered options

- **RDS, MSK and ECS:** the production-shaped answer. MSK Serverless alone costs about $548/month, and managed Postgres plus containers would use up the credit within weeks.
- **t4g.small (2 GiB):** cheaper, but Kafka, Postgres and three JVMs would swap, which would invalidate the load-test numbers.
- **AWS's Free plan:** it closes the account after 6 months.
- **Heroku:** can't run Kafka, and gives Spring Boot only 512 MB.
- **Azure:** viable, but less often named in the target job ads.

## Consequences

There is a single point of failure, and deploys cause a few seconds of downtime. Both are accepted for a demo and stated in the README. UK VAT may not be covered by credits.
