#!/bin/bash

# MVP Deployment Script
# Automates build, deployment, and testing

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
DOCKER_IMAGE="ledger-service:1.0.0"
DOCKER_REGISTRY="${DOCKER_REGISTRY:-docker.io}"
NAMESPACE="ledger-system"
DEPLOYMENT_NAME="ledger-service"

# Functions
print_header() {
    echo -e "${BLUE}═══════════════════════════════════════${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}═══════════════════════════════════════${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_info() {
    echo -e "${YELLOW}ℹ $1${NC}"
}

# Check prerequisites
check_prerequisites() {
    print_header "Checking Prerequisites"

    command -v java >/dev/null 2>&1 || { print_error "Java 21+ not found"; exit 1; }
    print_success "Java found: $(java -version 2>&1 | head -n 1)"

    command -v mvn >/dev/null 2>&1 || { print_error "Maven not found"; exit 1; }
    print_success "Maven found"

    command -v docker >/dev/null 2>&1 || { print_error "Docker not found"; exit 1; }
    print_success "Docker found"
}

# Build application
build_app() {
    print_header "Building Application"
    mvn clean package -DskipTests
    print_success "Application built successfully"
}

# Build Docker image
build_docker() {
    print_header "Building Docker Image"
    docker build -t $DOCKER_IMAGE .
    print_success "Docker image built: $DOCKER_IMAGE"
}

# Start with Docker Compose
start_docker_compose() {
    print_header "Starting Services with Docker Compose"
    docker-compose up -d

    # Wait for services to be ready
    print_info "Waiting for services to be ready..."
    sleep 10

    # Health check
    for i in {1..30}; do
        if curl -s http://localhost/actuator/health > /dev/null 2>&1; then
            print_success "Services are ready!"
            return 0
        fi
        print_info "Waiting... ($i/30)"
        sleep 2
    done

    print_error "Services did not become ready"
    exit 1
}

# Test API endpoints
test_endpoints() {
    print_header "Testing API Endpoints"

    BASE_URL="http://localhost"

    # Create accounts
    print_info "Creating test accounts..."
    curl -s -X POST "$BASE_URL/api/v1/accounts?accountId=alice&initialBalance=5000" > /dev/null
    print_success "Account alice created"

    curl -s -X POST "$BASE_URL/api/v1/accounts?accountId=bob&initialBalance=5000" > /dev/null
    print_success "Account bob created"

    # Test transfer
    print_info "Testing transfer..."
    RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/transfer" \
        -H "Content-Type: application/json" \
        -d '{
            "transactionId":"txn-test-001",
            "fromAccountId":"alice",
            "toAccountId":"bob",
            "amount":500
        }')

    if echo $RESPONSE | grep -q "COMPLETED"; then
        print_success "Transfer completed successfully"
    else
        print_error "Transfer failed"
        echo $RESPONSE
        exit 1
    fi

    # Check balance
    print_info "Checking balances..."
    ALICE_BALANCE=$(curl -s "$BASE_URL/api/v1/accounts/alice/balance" | grep -o '"balance":[0-9]*' | cut -d: -f2)
    BOB_BALANCE=$(curl -s "$BASE_URL/api/v1/accounts/bob/balance" | grep -o '"balance":[0-9]*' | cut -d: -f2)

    print_success "Alice balance: $ALICE_BALANCE"
    print_success "Bob balance: $BOB_BALANCE"

    # Health check
    print_info "Checking health..."
    HEALTH=$(curl -s http://localhost/actuator/health | grep -o '"status":"[^"]*' | cut -d: -f2)
    print_success "Health status: $HEALTH"
}

# Deploy to Kubernetes
deploy_kubernetes() {
    print_header "Deploying to Kubernetes"

    # Create namespace
    print_info "Creating namespace..."
    kubectl create namespace $NAMESPACE 2>/dev/null || print_info "Namespace already exists"

    # Create secret
    print_info "Creating secrets..."
    kubectl create secret generic postgres-secret \
        -n $NAMESPACE \
        --from-literal=password=postgres \
        --dry-run=client -o yaml | kubectl apply -f -

    # Deploy PostgreSQL
    print_info "Deploying PostgreSQL..."
    kubectl apply -f k8s-postgres.yaml

    # Wait for PostgreSQL
    print_info "Waiting for PostgreSQL to be ready..."
    kubectl wait --for=condition=ready pod -l app=postgres \
        -n $NAMESPACE --timeout=300s
    print_success "PostgreSQL is ready"

    # Push image (optional)
    if [ ! -z "$DOCKER_REGISTRY" ] && [ "$DOCKER_REGISTRY" != "docker.io" ]; then
        print_info "Pushing image to registry: $DOCKER_REGISTRY"
        docker tag $DOCKER_IMAGE $DOCKER_REGISTRY/$DOCKER_IMAGE
        docker push $DOCKER_REGISTRY/$DOCKER_IMAGE
    fi

    # Deploy ledger service
    print_info "Deploying ledger service..."
    kubectl apply -f k8s-deployment.yaml

    # Wait for rollout
    print_info "Waiting for deployment to be ready..."
    kubectl rollout status deployment/$DEPLOYMENT_NAME \
        -n $NAMESPACE --timeout=300s
    print_success "Deployment is ready"
}

# Get service endpoint
get_endpoint() {
    print_header "Service Endpoint"

    # Try to get LoadBalancer IP
    EXTERNAL_IP=$(kubectl get svc ledger-svc -n $NAMESPACE \
        -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null)

    if [ -z "$EXTERNAL_IP" ]; then
        # Fallback to port-forward
        print_info "LoadBalancer IP not available, use port-forward:"
        echo "kubectl port-forward -n $NAMESPACE svc/ledger-svc 8080:80"
        EXTERNAL_IP="localhost:8080"
    fi

    echo -e "${GREEN}Service available at: http://$EXTERNAL_IP${NC}"
    echo ""
    echo "Test with:"
    echo "  curl http://$EXTERNAL_IP/actuator/health"
}

# Run load test
run_load_test() {
    print_header "Running Load Test"

    command -v k6 >/dev/null 2>&1 || {
        print_error "k6 not installed. Install from: https://k6.io/"
        return 1
    }

    BASE_URL="${1:-http://localhost}"

    print_info "Running k6 load test..."
    k6 run --env BASE_URL=$BASE_URL load-test.js

    print_success "Load test completed"
}

# Cleanup
cleanup() {
    print_header "Cleaning Up"

    print_info "Stopping Docker Compose services..."
    docker-compose down -v

    print_success "Cleanup completed"
}

# Show usage
usage() {
    cat <<EOF
Usage: $0 <command>

Commands:
  check           - Check prerequisites
  build           - Build application
  docker          - Build Docker image
  compose-up      - Start with Docker Compose
  test            - Test API endpoints
  k8s-deploy      - Deploy to Kubernetes
  k8s-endpoint    - Get Kubernetes endpoint
  load-test       - Run k6 load test (requires BASE_URL env var)
  cleanup         - Stop Docker Compose services
  full            - Full local development cycle (build → docker → compose → test)
  k8s-full        - Full Kubernetes cycle (build → docker → k8s-deploy)

Examples:
  $0 full
  $0 k8s-full
  BASE_URL=http://localhost:8080 $0 load-test
EOF
}

# Main
main() {
    case "${1:-help}" in
        check)
            check_prerequisites
            ;;
        build)
            build_app
            ;;
        docker)
            build_docker
            ;;
        compose-up)
            start_docker_compose
            ;;
        test)
            test_endpoints
            ;;
        k8s-deploy)
            deploy_kubernetes
            ;;
        k8s-endpoint)
            get_endpoint
            ;;
        load-test)
            run_load_test "${BASE_URL:-http://localhost}"
            ;;
        cleanup)
            cleanup
            ;;
        full)
            check_prerequisites
            build_app
            build_docker
            start_docker_compose
            test_endpoints
            echo ""
            print_success "MVP is running! Access at: http://localhost"
            echo ""
            read -p "Press Enter to stop services and cleanup..." -t 300
            cleanup
            ;;
        k8s-full)
            check_prerequisites
            build_app
            build_docker
            deploy_kubernetes
            get_endpoint
            ;;
        *)
            usage
            exit 1
            ;;
    esac
}

main "$@"
