#!/usr/bin/env bash
set -euo pipefail

# ----------------------------
# Config (adjust if needed)
# ----------------------------
REGION="${REGION:-us-east-1}"
BUCKET_NAME="${BUCKET_NAME:-doc-extracts}"      # keep aligned with application-local.yml
TABLE_NAME="${TABLE_NAME:-doc-process-status}"  # keep aligned with application-local.yml
PROFILE="${PROFILE:-local}"
TTL_ATTRIBUTE="${TTL_ATTRIBUTE:-ttl}"           # set to "" to skip TTL

# Perplexity public bucket (fixed name per request)
PERPLEXITY_BUCKET="${PERPLEXITY_BUCKET:-p-doc-extracts}"

# S3 prefixes (match your application-local.yml)
INPUT_PREFIX="${INPUT_PREFIX:-input/}"
OUTPUT_PREFIX="${OUTPUT_PREFIX:-output/}"
TEXTRACT_PREFIX="${TEXTRACT_PREFIX:-textract/}"
OPENAI_PREFIX="${OPENAI_PREFIX:-open-ai-output/}"
OPENAI_PROMPT_PREFIX="${OPENAI_PROMPT_PREFIX:-open-ai-prompt/}"

# Prompt files source: primary, with a fallback path
PROMPTS_DIR_PRIMARY="${PROMPTS_DIR_PRIMARY:-src/main/resources/prompts}"
PROMPTS_DIR_FALLBACK="${PROMPTS_DIR_FALLBACK:-src/mail/resources/prompts}"

# Force AWS CLI and SDK to use creds/config from current directory (optional)
export AWS_SHARED_CREDENTIALS_FILE="${AWS_SHARED_CREDENTIALS_FILE:-$PWD/aws-credentials}"
export AWS_CONFIG_FILE="${AWS_CONFIG_FILE:-$PWD/aws-config}"
export AWS_PROFILE="$PROFILE"

aws_cli() {
  aws --profile "$PROFILE" --region "$REGION" "$@"
}

echo "Using AWS credentials from: $AWS_SHARED_CREDENTIALS_FILE"
echo "Using AWS config from:      $AWS_CONFIG_FILE"
echo "AWS Region:                 $REGION"
echo "AWS Profile:                $PROFILE"
echo "S3 Bucket:                  $BUCKET_NAME"
echo "Perplexity Bucket:          $PERPLEXITY_BUCKET (public)"
echo "DynamoDB Table:             $TABLE_NAME"
echo "S3 Prefixes:"
echo "  input:           $INPUT_PREFIX"
echo "  output:          $OUTPUT_PREFIX"
echo "  textract:        $TEXTRACT_PREFIX"
echo "  open-ai-output:  $OPENAI_PREFIX"
echo "  open-ai-prompt:  $OPENAI_PROMPT_PREFIX"

############################################
# S3 BUCKET (main app bucket)
############################################
if aws_cli s3api head-bucket --bucket "$BUCKET_NAME" 2>/dev/null; then
  echo "S3 bucket already exists: $BUCKET_NAME"
else
  echo "Creating S3 bucket: $BUCKET_NAME in $REGION"
  if [[ "$REGION" == "us-east-1" ]]; then
    aws_cli s3api create-bucket --bucket "$BUCKET_NAME"
  else
    aws_cli s3api create-bucket --bucket "$BUCKET_NAME" --region "$REGION" \
      --create-bucket-configuration LocationConstraint="$REGION"
  fi
  aws_cli s3api put-bucket-versioning \
    --bucket "$BUCKET_NAME" \
    --versioning-configuration Status=Enabled
fi

# Create zero-byte "folder" objects so prefixes appear in S3 console (optional)
create_prefix() {
  local prefix="$1"
  aws_cli s3api put-object --bucket "$BUCKET_NAME" --key "$prefix" >/dev/null || true
}
create_prefix "$INPUT_PREFIX"
create_prefix "$OUTPUT_PREFIX"
create_prefix "$TEXTRACT_PREFIX"
create_prefix "$OPENAI_PREFIX"
create_prefix "$OPENAI_PROMPT_PREFIX"

############################################
# PERPLEXITY PUBLIC BUCKET (name: pBucket)
############################################
if aws_cli s3api head-bucket --bucket "$PERPLEXITY_BUCKET" 2>/dev/null; then
  echo "Perplexity S3 bucket already exists: $PERPLEXITY_BUCKET"
else
  echo "Creating Perplexity S3 bucket (public): $PERPLEXITY_BUCKET in $REGION"
  if [[ "$REGION" == "us-east-1" ]]; then
    aws_cli s3api create-bucket --bucket "$PERPLEXITY_BUCKET"
  else
    aws_cli s3api create-bucket --bucket "$PERPLEXITY_BUCKET" --region "$REGION" \
      --create-bucket-configuration LocationConstraint="$REGION"
  fi

  # Optional: versioning (can omit if not needed)
  aws_cli s3api put-bucket-versioning \
    --bucket "$PERPLEXITY_BUCKET" \
    --versioning-configuration Status=Enabled

  # Disable Block Public Access so our public policy can take effect
  aws_cli s3api put-public-access-block \
    --bucket "$PERPLEXITY_BUCKET" \
    --public-access-block-configuration '{
      "BlockPublicAcls": false,
      "IgnorePublicAcls": false,
      "BlockPublicPolicy": false,
      "RestrictPublicBuckets": false
    }'

  # Public-read bucket policy (objects readable by anyone)
  POLICY_JSON=$(cat <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowPublicRead",
      "Effect": "Allow",
      "Principal": "*",
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::${PERPLEXITY_BUCKET}/*"
    }
  ]
}
EOF
)
  aws_cli s3api put-bucket-policy --bucket "$PERPLEXITY_BUCKET" --policy "$POLICY_JSON"

  echo "Perplexity bucket '$PERPLEXITY_BUCKET' created and configured for public read."
fi

############################################
# UPLOAD PROMPT FILES (YAML) TO OPENAI PROMPT PREFIX
############################################
PROMPTS_DIR=""
if [[ -d "$PROMPTS_DIR_PRIMARY" ]]; then
  PROMPTS_DIR="$PROMPTS_DIR_PRIMARY"
elif [[ -d "$PROMPTS_DIR_FALLBACK" ]]; then
  PROMPTS_DIR="$PROMPTS_DIR_FALLBACK"
fi

if [[ -n "$PROMPTS_DIR" ]]; then
  echo "Uploading prompt files from: $PROMPTS_DIR -> s3://$BUCKET_NAME/$OPENAI_PROMPT_PREFIX"
  aws_cli s3 sync "$PROMPTS_DIR" "s3://$BUCKET_NAME/$OPENAI_PROMPT_PREFIX" \
    --exclude "*" --include "*.yml" --include "*.yaml"
  echo "Prompt files uploaded."
else
  echo "WARNING: No prompt directory found. Checked:"
  echo "  - $PROMPTS_DIR_PRIMARY"
  echo "  - $PROMPTS_DIR_FALLBACK"
  echo "Skipping prompt upload."
fi

############################################
# DYNAMODB TABLE
############################################
if aws_cli dynamodb describe-table --table-name "$TABLE_NAME" >/dev/null 2>&1; then
  echo "DynamoDB table already exists: $TABLE_NAME"
else
  echo "Creating DynamoDB table: $TABLE_NAME"
  aws_cli dynamodb create-table \
    --table-name "$TABLE_NAME" \
    --attribute-definitions AttributeName=documentId,AttributeType=S \
    --key-schema AttributeName=documentId,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST
  aws_cli dynamodb wait table-exists --table-name "$TABLE_NAME"
fi

############################################
# TTL (optional)
############################################
if [[ -n "$TTL_ATTRIBUTE" ]]; then
  echo "Enabling TTL on $TABLE_NAME (attribute: $TTL_ATTRIBUTE)"
  aws_cli dynamodb update-time-to-live \
    --table-name "$TABLE_NAME" \
    --time-to-live-specification "Enabled=true,AttributeName=$TTL_ATTRIBUTE" || true
else
  echo "TTL not configured (TTL_ATTRIBUTE is empty). Skipping."
fi

echo "AWS resources ready."
echo "Prompts (if present) are in s3://$BUCKET_NAME/$OPENAI_PROMPT_PREFIX"
