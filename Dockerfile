# syntax=docker/dockerfile:1.7

# ---- Frontend build stage ----
FROM node:20-alpine AS frontend-builder

WORKDIR /frontend

COPY frontend/package.json frontend/package-lock.json frontend/.npmrc ./
RUN npm ci

COPY frontend/ ./
RUN npm run build

# ---- Backend build stage ----
FROM golang:1.23-alpine AS backend-builder

RUN apk add --no-cache gcc musl-dev

WORKDIR /workspace

# Cache module downloads
COPY go.mod go.sum ./
RUN go mod download

# Build the binary
COPY . .
RUN CGO_ENABLED=0 go build -o /app/mag-gateway .

# ---- Runtime stage ----
FROM alpine:3.20

RUN apk add --no-cache ca-certificates tzdata wget

WORKDIR /app

RUN mkdir -p /app/data /app/static

COPY --from=backend-builder /app/mag-gateway /app/mag-gateway
COPY --from=frontend-builder /frontend/dist/ /app/static/

EXPOSE 1399

HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:1399/actuator/health || exit 1

ENV GOMEMLIMIT=512MiB
ENV MAG_STATIC_DIR=/app/static

ENTRYPOINT ["/app/mag-gateway"]
