output "state_bucket_name" {
  description = "Pass as the infra S3 backend bucket."
  value       = aws_s3_bucket.state.id
}

output "deploy_role_arn" {
  description = "GitHub Actions OIDC deployment role."
  value       = aws_iam_role.deploy.arn
}

output "boundary_arn" {
  description = "Required permissions boundary for every stack Lambda role."
  value       = aws_iam_policy.boundary.arn
}
