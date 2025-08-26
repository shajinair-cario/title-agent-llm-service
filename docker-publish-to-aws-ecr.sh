#!/usr/bin/env bash
set -euo pipefail

# ===== Config =====
AWS_REGION="us-east-1"
AWS_PROFILE="local"
IMAGE_NAME="doc-extract-service"
IMAGE_TAG="1.0.0"
ECR_REPO="${IMAGE_NAME}" # ECR repo name will match IMAGE_NAME

# Force AWS CLI to use creds/config from current directory
export AWS_SHARED_CREDENTIALS_FILE="$PWD/aws-credentials"
export AWS_CONFIG_FILE="$PWD/aws-config"
export AWS_PROFILE="$AWS_PROFILE"

# ===== AWS CLI helper =====
aws_cli() {
  aws --profile "$AWS_PROFILE" --region "$AWS_REGION" "$@"
}

# ===== Ensure ECR repository exists =====
echo "Checking for ECR repository: $ECR_REPO"
if aws_cli ecr describe-repositories --repository-names "$ECR_REPO" >/dev/null 2>&1; then
  echo "ECR repository already exists: $ECR_REPO"
else
  echo "Creating ECR repository: $ECR_REPO"
  aws_cli ecr create-repository --repository-name "$ECR_REPO" \
    --image-scanning-configuration scanOnPush=true \
    --encryption-configuration encryptionType=AES256
fi

# ===== Get ECR URI =====
ECR_URI=$(aws_cli ecr describe-repositories --repository-names "$ECR_REPO" \
  --query 'repositories[0].repositoryUri' --output text)
echo "ECR URI: $ECR_URI"

# ===== Authenticate Docker with ECR =====
echo "Logging into ECR..."
aws_cli ecr get-login-password | docker login --username AWS --password-stdin "${ECR_URI}"

# ===== Tag & Push Image =====
echo "Tagging local image ${IMAGE_NAME}:${IMAGE_TAG} as ${ECR_URI}:${IMAGE_TAG}"
docker tag "${IMAGE_NAME}:${IMAGE_TAG}" "${ECR_URI}:${IMAGE_TAG}"

echo "Pushing image to ECR..."
docker push "${ECR_URI}:${IMAGE_TAG}"

echo " Published image: ${ECR_URI}:${IMAGE_TAG}"
