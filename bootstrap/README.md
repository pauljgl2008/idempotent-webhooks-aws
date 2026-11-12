# Account bootstrap

Run this configuration once as the account owner using temporary AWS SSO
credentials. It creates the versioned, SSE-S3 encrypted state bucket, GitHub OIDC
provider, `iwh-boundary`, and `iwh-github-deploy`. It does not create the application
or the secret. Terraform >= 1.10 and AWS provider ~> 6.0 are required.

Bootstrap deliberately uses **local state**. Keep `terraform.tfstate` and its backup
in a secure owner-controlled location outside version control, with a recoverable
backup. The state is necessary to update or retire bootstrap resources. Do not
upload this state into the application's backend or share it with GitHub Actions.
The state bucket has `prevent_destroy`; application teardown leaves bootstrap in
place. Never remove that protection until state has been safely archived.

From the repository root, an account owner can run the following commands in
PowerShell (these instructions do not execute a deployment):

```powershell
aws sso login --profile <owner-profile>
$env:AWS_PROFILE = '<owner-profile>'
terraform -chdir=bootstrap init
terraform -chdir=bootstrap plan -out=bootstrap.tfplan
terraform -chdir=bootstrap apply bootstrap.tfplan
terraform -chdir=bootstrap output
```

The defaults are `region=us-east-1` and
`github_repo=pauljgl2008/idempotent-webhooks-aws`. For another region, supply
`-var='region=...'` consistently at plan time. The bucket name is
`iwh-terraform-state-<account-id>`; one bootstrap per account is intended.
If the account already has the GitHub OIDC provider, the owner must import that
provider into this local state before planning instead of creating a second one.

Configure the GitHub **aws** environment to permit deployment only from `main`
and require a reviewer. Configure its deployment job with `id-token: write` and
the `deploy_role_arn` output. PR CI must not receive AWS credentials. The trust
policy uses exact `StringEquals` matches for the repository/environment subject
and `sts.amazonaws.com` audience; it contains no wildcard subject.

Pass the three bootstrap outputs to the workflow: `state_bucket_name` initializes
the backend, `deploy_role_arn` is assumed using OIDC, and `boundary_arn` is supplied
as the application's required Terraform variable. For example:

```powershell
terraform -chdir=infra init `
  -backend-config='bucket=iwh-terraform-state-<account-id>' `
  -backend-config='region=us-east-1'
terraform -chdir=infra plan -var='boundary_arn=arn:aws:iam::<account-id>:policy/iwh-boundary'
```

The infra backend fixes `key=iwh/terraform.tfstate`, `encrypt=true`, and
`use_lockfile=true`. Native S3 lock files require Terraform >= 1.10; no DynamoDB
lock table is created. The deploy role can access only that state object and its
`.tflock` companion. Workspace discovery uses `iwh/workspaces` so bucket listing
stays under `iwh/`; use the default Terraform workspace for deployments.

Before planning the application, build the shaded JARs at
`ingest/target/ingest-1.0.0-lambda.jar` and
`process/target/process-1.0.0-lambda.jar`. Create the standard SecureString with
`scripts/put-secret.sh` outside Terraform using the SSM managed key. Its default
name is `/iwh/hmac-secret`. Terraform builds its ARN without an SSM parameter data
source, so the secret value never enters state. Changing the parameter alone does
not refresh the secret cached in a published SnapStart snapshot; republish ingest
after rotating it.

The runtime boundary allows only log writes, event queue operations, table item
operations, `/iwh/*` parameter reads, and decryption through regional SSM with
`alias/aws/ssm`. Each Lambda's inline policies narrow that ceiling further.
KMS Decrypt is scoped to key ARNs with `kms:ResourceAliases`, because alias ARNs
cannot authorize Decrypt. See the [KMS alias authorization documentation](https://docs.aws.amazon.com/kms/latest/developerguide/alias-authorization.html).
No X-Ray permissions or managed broad execution policies are used.

The deploy role uses explicit actions and `iwh-*` resource names. Role mutations
require `iam:PermissionsBoundary` equal to `iwh-boundary`; passing roles is limited
to `iwh-*-lambda` and `lambda.amazonaws.com`. It cannot edit the boundary policy
or remove a role's boundary. Leave the boundary attached while deleting roles.

Some control-plane operations cannot use name scopes. HTTP API ARNs use generated
IDs: creation requires `project=iwh` and an `iwh-*` API name; existing tagged APIs
and stages use `aws:ResourceTag/project`. Untaggable HTTP API routes and
integrations use regional resource paths, since HTTP APIs do not inherit API tags
onto those children. Mapping UUIDs are constrained by function ARN on creation
and project tags thereafter. CloudWatch Logs delivery and discovery actions need
`Resource="*"`; explicitly listed regional inventory APIs also use that scope so
`scripts/verify-empty.sh` can discover and filter resources by prefix. These
inventory actions grant no mutations. See [API Gateway tagging support](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-tagging-supported-resources.html).

The optional `alert_email` adds the monthly USD 1 budget's 80% actual notification.
It does not connect the CloudWatch alarms to email. Check alarm state and resolve
DLQ items during scenarios. Budget refreshes are delayed; a budget is a warning,
not a cap. Destroy the application after scenarios; retain permanent processed
markers for as long as the application and its at-most-once guarantee are needed.

For offline configuration checks, neither credentials nor AWS API calls are
needed:

```powershell
terraform -chdir=bootstrap fmt -recursive
terraform -chdir=bootstrap init -backend=false
terraform -chdir=bootstrap validate
terraform -chdir=infra fmt -recursive
terraform -chdir=infra init -backend=false
terraform -chdir=infra validate
```

Provider initialization downloads plugins from the Terraform registry. Validation
checks configuration and provider schemas; it does not exercise AWS permissions
or deploy resources. Commit the generated `.terraform.lock.hcl` files to keep
provider selections reproducible. Keep `.terraform/`, local state, saved plans,
credentials, and secrets out of version control.
