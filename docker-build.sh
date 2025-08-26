#!/bin/bash
set -e

# Variables
IMAGE_NAME="title-nlp-service"
IMAGE_TAG="0.0.1"
SPRING_PROFILE="local"

echo "Running Spotless formatting..."
mvn spotless:apply

echo "Cleaning and building project..."
mvn clean install

echo "Removing old Docker image (if exists)..."
if docker image inspect ${IMAGE_NAME}:${IMAGE_TAG} >/dev/null 2>&1; then
  docker rmi -f ${IMAGE_NAME}:${IMAGE_TAG}
  echo "Removed old image: ${IMAGE_NAME}:${IMAGE_TAG}"
else
  echo "No existing image found for ${IMAGE_NAME}:${IMAGE_TAG}"
fi

echo "Building Docker image..."
docker build -t ${IMAGE_NAME}:${IMAGE_TAG} .

echo "Build completed successfully."
echo ""
echo "To run with Spring profile '${SPRING_PROFILE}':"
echo "  docker run -p 8080:8080 -e SPRING_PROFILES_ACTIVE=${SPRING_PROFILE} ${IMAGE_NAME}:${IMAGE_TAG}"
