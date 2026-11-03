FROM node:24-alpine AS build
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ .
RUN npm run build

FROM nginx:1.27-alpine
# Defaults reproduce the original hardcoded docker-compose config (see deploy/nginx/nginx.conf.template)
# so neither compose file needs to set these; Cloud Run deploys override all five.
ENV PORT=80 \
    DECISION_SERVICE_URL=http://decision-service:8080 \
    DECISION_SERVICE_HOST=decision-service \
    AI_SERVICE_URL=http://ai-service:8081 \
    AI_SERVICE_HOST=ai-service
COPY deploy/nginx/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /build/dist/frontend/browser /usr/share/nginx/html
EXPOSE 80
