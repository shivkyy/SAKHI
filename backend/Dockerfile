# SAKHI Live Tracking Microservice
# Optimized, secure container build

FROM node:22-alpine AS runner

WORKDIR /app

# Set production environment
ENV NODE_ENV=production
ENV PORT=3000
ENV HOST=0.0.0.0
ENV DATA_PATH=/app/data/sessions.json

# Copy package descriptors
COPY package.json ./

# Install only production dependencies (zero external deps required)
RUN npm install --omit=dev --ignore-scripts || true

# Copy server application code & static assets
COPY server.js sessionStore.js ./
COPY public ./public

# Create writable data directory for session storage
RUN mkdir -p /app/data && chown -R node:node /app

# Run as unprivileged node user
USER node

# Expose HTTP service port
EXPOSE 3000

# Container healthcheck
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD node -e "const http=require('http'); http.get('http://127.0.0.1:3000/health', r => process.exit(r.statusCode === 200 ? 0 : 1)).on('error', () => process.exit(1));"

# Launch server
CMD ["node", "server.js"]
