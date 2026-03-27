# Stage 1: Build the frontend
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
RUN npm run build

# Stage 2: Setup the server
FROM node:20-alpine
# Install dependencies for JBang and Brokk executor
RUN apk add --no-cache git openjdk21-jre curl bash

# Install JBang
RUN curl -sL https://sh.jbang.dev | bash -s - app setup
ENV PATH="/root/.jbang/bin:${PATH}"

# Trust Brokk releases to avoid interactive prompts
RUN jbang trust add https://github.com/BrokkAi/brokk-releases

WORKDIR /app
# Copy the built frontend
COPY --from=builder /app/dist ./dist

# Setup the server
WORKDIR /app/server
COPY server/package*.json ./
RUN npm install
COPY server/ ./

EXPOSE 3001
CMD ["node", "index.js"]
