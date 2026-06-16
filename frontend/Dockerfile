# syntax=docker/dockerfile:1.7

# ---- Build stage ----
FROM node:20-alpine AS builder
WORKDIR /app

ENV NODE_OPTIONS=--max-old-space-size=1536

COPY frontend/package.json frontend/package-lock.json ./
RUN npm install

COPY frontend/ ./
RUN npm run build

# ---- Runtime stage ----
FROM nginx:1.27-alpine

WORKDIR /usr/share/nginx/html

COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist ./

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
