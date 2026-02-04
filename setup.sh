#!/bin/bash

# Project Setup Script
# Customizes the template for a new project

set -e

echo "=== Project Setup ==="
echo

# Prompt for project details
read -p "Project name (e.g., my-project): " PROJECT_NAME
read -p "Group ID (e.g., com.example): " GROUP_ID
read -p "Base package (e.g., com.example.myproject): " BASE_PACKAGE
read -p "Project description: " DESCRIPTION

# Validate inputs
if [ -z "$PROJECT_NAME" ] || [ -z "$GROUP_ID" ] || [ -z "$BASE_PACKAGE" ]; then
    echo "Error: All fields are required"
    exit 1
fi

echo
echo "Configuring project..."

# Update pom.xml
sed -i "s|<groupId>.*</groupId>|<groupId>${GROUP_ID}</groupId>|g" pom.xml
sed -i "s|<artifactId>.*</artifactId>|<artifactId>${PROJECT_NAME}</artifactId>|g" pom.xml

# Create package directory structure
PACKAGE_PATH=$(echo "$BASE_PACKAGE" | tr '.' '/')
mkdir -p "src/main/java/${PACKAGE_PATH}"
mkdir -p "src/test/java/${PACKAGE_PATH}"

# Update project overview
cat > docs/project/overview.md << EOF
# Project Overview

## Name

${PROJECT_NAME}

## Description

${DESCRIPTION}

## Status

Development

## Key Technologies

- Java 25
- Maven
EOF

echo
echo "=== Setup Complete ==="
echo
echo "Next steps:"
echo "  1. Review pom.xml"
echo "  2. Update docs/project/overview.md with more details"
echo "  3. Update docs/project/architecture.md"
echo "  4. Start coding!"
echo
