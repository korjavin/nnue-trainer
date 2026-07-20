#!/bin/bash
set -e

# Local development script
# Runs frontend (if present) and backend concurrently

cleanup() {
    echo "Shutting down..."
    kill $BACKEND_PID 2>/dev/null || true
    kill $FRONTEND_PID 2>/dev/null || true
    exit 0
}

trap cleanup SIGINT SIGTERM

# Start frontend dev server if frontend directory exists
if [ -d "frontend" ]; then
    echo "Starting frontend dev server..."
    (cd frontend && npm run dev) &
    FRONTEND_PID=$!
    sleep 2
fi

# Start Go backend with hot reload (if air is installed) or plain go run
echo "Starting backend..."
if command -v air &> /dev/null; then
    air &
else
    go run ./cmd/server &
fi
BACKEND_PID=$!

# Wait for backend
wait $BACKEND_PID
