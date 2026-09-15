# syntax=docker/dockerfile:1.7

# ---- Build stage ----
FROM golang:1.23-alpine AS builder

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

RUN mkdir -p /app/data

COPY --from=builder /app/mag-gateway /app/mag-gateway

EXPOSE 1399

HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:1399/actuator/health || exit 1

ENV GOMEMLIMIT=512MiB

ENTRYPOINT ["/app/mag-gateway"]
