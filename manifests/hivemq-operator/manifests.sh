#!/usr/bin/env bash

PROMETHEUS_HELM_CHARTS_URL=https://prometheus-community.github.io/helm-charts

# change current directory to project root
cd "$(dirname "$0")"/../.. || exit 1

echo "Create manifests directory"
mkdir -p manifests && cd manifests || exit 1

echo "Update HiveMQ Operator dependencies"
prometheus_repo=$(helm repo list -o json | jq -r '.[] | select(.url == "'${PROMETHEUS_HELM_CHARTS_URL}'") | .name')
if [ -z "$prometheus_repo" ]; then
  echo "Adding Prometheus Helm Chart dependency"
  helm repo add prometheus ${PROMETHEUS_HELM_CHARTS_URL}
fi
helm dependency build ../charts/hivemq-operator

echo "Create HiveMQ Operator Templates"
helm template hivemq-operator ../charts/hivemq-operator -n hivemq -f ./hivemq-operator/manifest.yaml --skip-tests --output-dir . > /dev/null

echo "Flatten directory structure"
find hivemq-operator/templates -type f -exec mv {} hivemq-operator/operator/ \; > /dev/null
if [ -d hivemq-operator/templates ]; then
  rm -r hivemq-operator/templates
fi

# Shorten Helm's a little redundant naming
sed -i.bak 's|operator\-operator|operator|' hivemq-operator/operator/*.yaml
find . -type f -name "*.bak" -delete
