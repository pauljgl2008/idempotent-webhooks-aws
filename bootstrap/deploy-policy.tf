resource "aws_iam_role_policy" "deploy" {
  name = "iwh-deploy"
  role = aws_iam_role.deploy.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid = "LambdaFunctions", Effect = "Allow",
        Action = [
          "lambda:CreateFunction", "lambda:GetFunction", "lambda:GetFunctionConfiguration",
          "lambda:UpdateFunctionCode", "lambda:UpdateFunctionConfiguration", "lambda:DeleteFunction",
          "lambda:PublishVersion", "lambda:ListVersionsByFunction", "lambda:CreateAlias",
          "lambda:GetAlias", "lambda:UpdateAlias", "lambda:DeleteAlias", "lambda:ListAliases",
          "lambda:AddPermission", "lambda:RemovePermission", "lambda:GetPolicy",
          "lambda:ListTags", "lambda:TagResource", "lambda:UntagResource",
          "lambda:GetFunctionCodeSigningConfig", "lambda:GetRuntimeManagementConfig",
          "lambda:GetFunctionConcurrency", "lambda:GetFunctionRecursionConfig"
        ],
        Resource = "arn:${local.partition}:lambda:${var.region}:${local.account_id}:function:iwh-*"
      },
      # Event source mapping ARNs contain UUIDs, never function names. Scope create
      # by the target function; scope subsequent mutations by the mapping's tag.
      {
        Sid      = "CreateTaggedMapping", Effect = "Allow", Action = ["lambda:CreateEventSourceMapping"],
        Resource = "arn:${local.partition}:lambda:${var.region}:${local.account_id}:event-source-mapping:*",
        Condition = {
          ArnLike      = { "lambda:FunctionArn" = "arn:${local.partition}:lambda:${var.region}:${local.account_id}:function:iwh-*" }
          StringEquals = { "aws:RequestTag/project" = "iwh" }
        }
      },
      {
        Sid       = "ManageTaggedMappings", Effect = "Allow",
        Action    = ["lambda:GetEventSourceMapping", "lambda:UpdateEventSourceMapping", "lambda:DeleteEventSourceMapping", "lambda:ListTags", "lambda:TagResource", "lambda:UntagResource"],
        Resource  = "arn:${local.partition}:lambda:${var.region}:${local.account_id}:event-source-mapping:*",
        Condition = { StringEquals = { "aws:ResourceTag/project" = "iwh" } }
      },
      {
        Sid       = "TagMappingAtCreation", Effect = "Allow", Action = ["lambda:TagResource"],
        Resource  = "arn:${local.partition}:lambda:${var.region}:${local.account_id}:event-source-mapping:*",
        Condition = { StringEquals = { "aws:RequestTag/project" = "iwh" } }
      },
      {
        Sid      = "Queues", Effect = "Allow",
        Action   = ["sqs:CreateQueue", "sqs:DeleteQueue", "sqs:GetQueueUrl", "sqs:GetQueueAttributes", "sqs:SetQueueAttributes", "sqs:ListQueueTags", "sqs:TagQueue", "sqs:UntagQueue"],
        Resource = "arn:${local.partition}:sqs:${var.region}:${local.account_id}:iwh-*"
      },
      {
        Sid      = "Tables", Effect = "Allow",
        Action   = ["dynamodb:CreateTable", "dynamodb:DeleteTable", "dynamodb:DescribeTable", "dynamodb:UpdateTable", "dynamodb:DescribeContinuousBackups", "dynamodb:UpdateContinuousBackups", "dynamodb:DescribeTimeToLive", "dynamodb:UpdateTimeToLive", "dynamodb:ListTagsOfResource", "dynamodb:TagResource", "dynamodb:UntagResource", "dynamodb:DescribeKinesisStreamingDestination", "dynamodb:DescribeContributorInsights", "dynamodb:GetResourcePolicy"],
        Resource = "arn:${local.partition}:dynamodb:${var.region}:${local.account_id}:table/iwh-*"
      },
      {
        Sid      = "LogGroups", Effect = "Allow",
        Action   = ["logs:CreateLogGroup", "logs:DeleteLogGroup", "logs:PutRetentionPolicy", "logs:DeleteRetentionPolicy", "logs:ListTagsForResource", "logs:TagResource", "logs:UntagResource", "logs:ListTagsLogGroup"],
        Resource = concat(local.log_groups, [for arn in local.log_groups : "${arn}:*"])
      },
      # These API Gateway access-log delivery / discovery APIs do not support
      # resource-level authorization. Only these explicit actions use '*', in-region.
      {
        Sid       = "RegionalLogDelivery", Effect = "Allow",
        Action    = ["logs:DescribeLogGroups", "logs:CreateLogDelivery", "logs:GetLogDelivery", "logs:UpdateLogDelivery", "logs:DeleteLogDelivery", "logs:ListLogDeliveries", "logs:PutResourcePolicy", "logs:DescribeResourcePolicies"],
        Resource  = "*",
        Condition = { StringEquals = { "aws:RequestedRegion" = var.region } }
      },
      # verify-empty.sh must discover resources before filtering by prefix. These
      # list APIs cannot be name-scoped; this grants no resource mutations.
      {
        Sid       = "RegionalInventory", Effect = "Allow",
        Action    = ["lambda:ListFunctions", "sqs:ListQueues", "dynamodb:ListTables", "cloudwatch:DescribeAlarms"],
        Resource  = "*",
        Condition = { StringEquals = { "aws:RequestedRegion" = var.region } }
      },
      {
        Sid      = "Alarms", Effect = "Allow",
        Action   = ["cloudwatch:PutMetricAlarm", "cloudwatch:DeleteAlarms", "cloudwatch:DescribeAlarms", "cloudwatch:ListTagsForResource", "cloudwatch:TagResource", "cloudwatch:UntagResource"],
        Resource = "arn:${local.partition}:cloudwatch:${var.region}:${local.account_id}:alarm:iwh-*"
      },
      {
        Sid      = "AccountBudget", Effect = "Allow",
        Action   = ["budgets:ViewBudget", "budgets:ModifyBudget", "budgets:TagResource", "budgets:UntagResource", "budgets:ListTagsForResource"],
        Resource = "arn:${local.partition}:budgets::${local.account_id}:budget/iwh-*"
      },
      {
        Sid = "StateBucketMetadata", Effect = "Allow", Action = ["s3:GetBucketLocation"], Resource = aws_s3_bucket.state.arn
      },
      {
        Sid       = "StateList", Effect = "Allow", Action = ["s3:ListBucket"], Resource = aws_s3_bucket.state.arn,
        Condition = { StringLike = { "s3:prefix" = ["iwh/*"] } }
      },
      {
        Sid      = "StateAndNativeLock", Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
        Resource = ["${aws_s3_bucket.state.arn}/iwh/terraform.tfstate", "${aws_s3_bucket.state.arn}/iwh/terraform.tfstate.tflock"]
      },
      {
        Sid      = "ReadRuntimeRoles", Effect = "Allow",
        Action   = ["iam:GetRole", "iam:GetRolePolicy", "iam:ListRolePolicies", "iam:ListAttachedRolePolicies", "iam:ListInstanceProfilesForRole", "iam:ListRoleTags"],
        Resource = local.lambda_roles
      },
      # Keep the boundary attached even during destroy. Do not grant boundary
      # removal or policy/version administration; that would allow escalation.
      {
        Sid       = "ManageOnlyBoundedRoles", Effect = "Allow",
        Action    = ["iam:CreateRole", "iam:PutRolePolicy", "iam:DeleteRolePolicy", "iam:AttachRolePolicy", "iam:DetachRolePolicy", "iam:PutRolePermissionsBoundary"],
        Resource  = local.lambda_roles,
        Condition = { ArnEquals = { "iam:PermissionsBoundary" = aws_iam_policy.boundary.arn } }
      },
      # These actions never carry iam:PermissionsBoundary, so a boundary condition would
      # deny them (and break destroy). None of them can widen what the role may do.
      {
        Sid      = "LifecycleOfBoundedRoles", Effect = "Allow",
        Action   = ["iam:DeleteRole", "iam:UpdateRole", "iam:UpdateAssumeRolePolicy", "iam:TagRole", "iam:UntagRole"],
        Resource = local.lambda_roles
      },
      {
        Sid       = "PassOnlyLambdaRoles", Effect = "Allow", Action = ["iam:PassRole"],
        Resource  = "arn:${local.partition}:iam::${local.account_id}:role/iwh-*-lambda",
        Condition = { StringEquals = { "iam:PassedToService" = "lambda.amazonaws.com" } }
      }
    ]
  })
}

resource "aws_iam_role_policy" "api" {
  name = "iwh-deploy-http-api"
  role = aws_iam_role.deploy.id
  # HTTP API resource ARNs use generated IDs and cannot be name-scoped. Use
  # regional ARNs and project=iwh request/resource tags wherever supported.
  # HTTP API child routes/integrations do not support tags or inherit API tags:
  # those operations require narrow path scopes within the selected region.
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ListRegionalApis", Effect = "Allow", Action = ["apigateway:GET"],
        Resource = "arn:${local.partition}:apigateway:${var.region}::/apis"
      },
      {
        Sid       = "CreateProjectApi", Effect = "Allow", Action = ["apigateway:POST"],
        Resource  = "arn:${local.partition}:apigateway:${var.region}::/apis",
        Condition = { StringEquals = { "aws:RequestTag/project" = "iwh" }, StringLike = { "apigateway:Request/ApiName" = "iwh-*" } }
      },
      {
        Sid       = "ManageProjectApi", Effect = "Allow", Action = ["apigateway:GET", "apigateway:PATCH", "apigateway:DELETE"],
        Resource  = "arn:${local.partition}:apigateway:${var.region}::/apis/*",
        Condition = { StringEquals = { "aws:ResourceTag/project" = "iwh" } }
      },
      {
        Sid       = "CreateProjectStage", Effect = "Allow", Action = ["apigateway:POST"],
        Resource  = "arn:${local.partition}:apigateway:${var.region}::/apis/*/stages",
        Condition = { StringEquals = { "aws:RequestTag/project" = "iwh" } }
      },
      {
        Sid    = "UntaggedHttpApiChildren", Effect = "Allow",
        Action = ["apigateway:GET", "apigateway:POST", "apigateway:PATCH", "apigateway:DELETE"],
        Resource = [
          "arn:${local.partition}:apigateway:${var.region}::/apis/*/integrations",
          "arn:${local.partition}:apigateway:${var.region}::/apis/*/integrations/*",
          "arn:${local.partition}:apigateway:${var.region}::/apis/*/routes",
          "arn:${local.partition}:apigateway:${var.region}::/apis/*/routes/*"
        ]
      },
      {
        Sid      = "ReadProjectTags", Effect = "Allow", Action = ["apigateway:GET"],
        Resource = "arn:${local.partition}:apigateway:${var.region}::/tags/*"
      },
      {
        Sid       = "WriteProjectTags", Effect = "Allow", Action = ["apigateway:PUT"],
        Resource  = "arn:${local.partition}:apigateway:${var.region}::/tags/*",
        Condition = { StringEquals = { "aws:RequestTag/project" = "iwh" } }
      }
    ]
  })
}
