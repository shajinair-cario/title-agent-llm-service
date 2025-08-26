#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------
# Config (mirrors build script)
# ---------------------------------
REGION="${REGION:-us-east-1}"
BUCKET_NAME="${BUCKET_NAME:-doc-extracts}"          # main app bucket
TABLE_NAME="${TABLE_NAME:-doc-process-status}"      # DynamoDB table
PROFILE="${PROFILE:-local}"
PERPLEXITY_BUCKET="${PERPLEXITY_BUCKET:-p-doc-extracts}"  # public bucket from build

# Force AWS CLI and SDK to use creds/config from current directory (same as build)
export AWS_SHARED_CREDENTIALS_FILE="${AWS_SHARED_CREDENTIALS_FILE:-$PWD/aws-credentials}"
export AWS_CONFIG_FILE="${AWS_CONFIG_FILE:-$PWD/aws-config}"
export AWS_PROFILE="$PROFILE"

aws_cli() {
  aws --profile "$PROFILE" --region "$REGION" "$@"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "ERROR: '$1' is required."; exit 1; }
}
require_cmd aws
require_cmd jq

cat <<INFO
==========================================
 AWS Resource Destruction Script
 Region:     $REGION
 Profile:    $PROFILE
 Main Bucket:$BUCKET_NAME
 Pub Bucket: $PERPLEXITY_BUCKET
 DDB Table:  $TABLE_NAME
 Creds:      $AWS_SHARED_CREDENTIALS_FILE
 Config:     $AWS_CONFIG_FILE
==========================================
INFO

read -p "Type 'yes' to DELETE these resources: " CONFIRM
if [[ "$CONFIRM" != "yes" ]]; then
  echo "Aborted."
  exit 1
fi

# ---------------------------------
# Helper: empty & delete a bucket (handles versioning + pagination)
# ---------------------------------
empty_and_delete_bucket() {
  local bucket="$1"

  if ! aws_cli s3api head-bucket --bucket "$bucket" 2>/dev/null; then
    echo "Bucket does not exist: $bucket"
    return 0
  fi

  echo "Emptying bucket (including versions): $bucket"

  # First, attempt a fast recursive delete of current objects (non-versioned/simple)
  # (won't remove previous versions, but speeds up if lots of current keys exist)
  aws_cli s3 rm "s3://$bucket" --recursive --only-show-errors || true

  # Now remove ALL versions & delete markers in batches until gone
  while : ; do
    # List up to 1000 versions + delete markers via AWS CLI paginator
    # Use --max-items to force pagination tokens; we loop until none remain.
    versions_json=$(aws_cli s3api list-object-versions --bucket "$bucket" --output json --max-items 1000 || true)

    # Build a combined array of Versions + DeleteMarkers to delete
    to_delete=$(echo "$versions_json" | jq -c '{Objects: (
        [( .Versions // [] )[] | {Key:.Key, VersionId:.VersionId}] +
        [( .DeleteMarkers // [] )[] | {Key:.Key, VersionId:.VersionId}]
      )}')

    # When there are no more objects to delete, the array will be empty
    if [[ "$(echo "$to_delete" | jq '.Objects | length')" -eq 0 ]]; then
      break
    fi

    # Delete up to 1000 at a time
    echo "$to_delete" > /tmp/objects-to-delete.json
    aws_cli s3api delete-objects --bucket "$bucket" --delete file:///tmp/objects-to-delete.json >/dev/null || true

    # If there's a NextToken, continue; otherwise loop will re-check and exit when empty
  done

  # Optional: try to clear bucket policy & public access block (not strictly required to delete)
  echo "Removing bucket policy and public access block (if present) on: $bucket"
  aws_cli s3api delete-bucket-policy --bucket "$bucket" 2>/dev/null || true
  aws_cli s3api delete-public-access-block --bucket "$bucket" 2>/dev/null || true

  echo "Deleting bucket: $bucket"
  aws_cli s3api delete-bucket --bucket "$bucket"
  echo "Deleted bucket: $bucket"
}

# ---------------------------------
# Delete S3 buckets
# ---------------------------------
empty_and_delete_bucket "$BUCKET_NAME"
empty_and_delete_bucket "$PERPLEXITY_BUCKET"

# ---------------------------------
# Delete DynamoDB table
# ---------------------------------
if aws_cli dynamodb describe-table --table-name "$TABLE_NAME" >/dev/null 2>&1; then
  echo "Deleting DynamoDB table: $TABLE_NAME"
  aws_cli dynamodb delete-table --table-name "$TABLE_NAME"
  echo "Waiting for DynamoDB table deletion to complete..."
  aws_cli dynamodb wait table-not-exists --table-name "$TABLE_NAME"
  echo "Deleted DynamoDB table: $TABLE_NAME"
else
  echo "DynamoDB table does not exist: $TABLE_NAME"
fi

echo "All specified AWS resources have been destroyed."
