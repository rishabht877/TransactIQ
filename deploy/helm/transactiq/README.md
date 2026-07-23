# TransactIQ Helm chart

Deploys the full system to Kubernetes: infra (Kafka KRaft, MySQL, Redis, Ollama) + the three
app services (payment-gateway, payment-processor, fraud-service), all as first-party templates
(no external subchart repos).

## Run on Minikube

```bash
# 1. Start a cluster
minikube start --memory 6144 --cpus 4

# 2. Build the app images INTO Minikube's Docker daemon (so no registry push is needed)
eval $(minikube docker-env)
docker build -f services/payment-gateway/Dockerfile   -t transactiq/payment-gateway:local   .
docker build -f services/payment-processor/Dockerfile -t transactiq/payment-processor:local .
docker build -f services/fraud-service/Dockerfile     -t transactiq/fraud-service:local     .

# 3. Install
helm install transactiq deploy/helm/transactiq

# 4. Watch pods come Ready (infra first, then apps via initContainers)
kubectl get pods -w

# 5. Try it
kubectl port-forward svc/payment-gateway 8080:8080 &
curl -i -X POST localhost:8080/api/payments \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: k8s-1' \
  -d '{"amount":49.99,"currency":"USD","customerId":"c1","country":"US"}'
```

## Enable the LLM fraud triage (optional)

The chart ships with the LLM **disabled** (rules-only) so it works without a multi-GB model
pull. To enable:

```bash
kubectl exec deploy/ollama -- ollama pull llama3.2      # pull the model into the pod
helm upgrade transactiq deploy/helm/transactiq --set fraud.llm.enabled=true
```

## Notes / trade-offs

- Infra uses `emptyDir` (ephemeral) for simplicity — swap for PVCs to persist across restarts.
- Single-node Kafka/MySQL/Redis. For production you'd use hardened community subcharts (Bitnami)
  or managed services, and a StatefulSet + PVC for Kafka.
- App pods use `initContainers` to wait for their dependencies, and Actuator liveness/readiness
  probes for health-gated rollout.
