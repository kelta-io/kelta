# Kelta Local Development
# ─────────────────────────────────────────────────────────────────────────────
# First time:   make setup
# Every day:    make up
# After setup:  make seed   ← confirms health + prints credentials
# ─────────────────────────────────────────────────────────────────────────────

COMPOSE       := docker compose
COMPOSE_AI    := docker compose --profile ai
COMPOSE_FULL  := docker compose --profile ai --profile tools
COMPOSE_TELE  := docker compose --profile telehealth

# JVM-mode overlay: builds the Java services from their Dockerfile.jvm variants
# instead of GraalVM native-image. Three concurrent native builds need ~24 GB
# allocated to Docker; the JVM path fits a default allocation and is ~5x faster.
# See the header of docker-compose.jvm.yml.
JVM_FILES         := -f docker-compose.yml -f docker-compose.jvm.yml
COMPOSE_JVM       := docker compose $(JVM_FILES)
COMPOSE_JVM_AI    := docker compose $(JVM_FILES) --profile ai
COMPOSE_JVM_FULL  := docker compose $(JVM_FILES) --profile ai --profile tools

.PHONY: setup gen-keys copy-env up up-ai up-full up-telehealth \
        up-jvm up-jvm-ai up-jvm-full rebuild-jvm \
        down reset seed rebuild logs debug ps help

# ─── First-time setup ────────────────────────────────────────────────────────

## setup: copy .env.example → .env and generate dev RSA key (idempotent)
setup: copy-env gen-keys
	@echo ""
	@echo "✅  Setup complete. Run 'make up' to start the stack."

## copy-env: copy .env.example to .env if it doesn't already exist
copy-env:
	@if [ ! -f .env ]; then \
		cp .env.example .env; \
		echo "📄  Created .env from .env.example"; \
	else \
		echo "📄  .env already exists — skipping copy"; \
	fi

## gen-keys: generate dev RSA-2048 JWK + AES-256 encryption key (idempotent)
gen-keys:
	@if grep -q "^JWK_SET=." .env 2>/dev/null; then \
		echo "🔑  JWK_SET already set in .env — skipping key generation"; \
	else \
		echo "🔑  Generating dev RSA-2048 JWK..."; \
		JWK=$$(docker run --rm node:20-alpine node -e " \
			const c = require('crypto'); \
			const {privateKey} = c.generateKeyPairSync('rsa',{modulusLength:2048}); \
			const k = privateKey.export({format:'jwk'}); \
			k.kid='dev-2025'; k.use='sig'; k.alg='RS256'; \
			process.stdout.write(JSON.stringify({keys:[k]})); \
		"); \
		echo "JWK_SET=$$JWK" >> .env; \
		echo "✅  JWK_SET written to .env"; \
	fi
	@if grep -q "^KELTA_ENCRYPTION_KEY=." .env 2>/dev/null; then \
		echo "🔑  KELTA_ENCRYPTION_KEY already set in .env — skipping"; \
	else \
		KEY=$$(openssl rand -base64 32); \
		echo "KELTA_ENCRYPTION_KEY=$$KEY" >> .env; \
		echo "✅  KELTA_ENCRYPTION_KEY written to .env"; \
	fi
	@for k in KELTA_TELEHEALTH_VISIT_SECRET CAMPAIGN_TRACKING_SECRET KELTA_MAILBOX_VERP_SECRET; do \
		if grep -q "^$$k=." .env 2>/dev/null; then \
			echo "🔑  $$k already set in .env — skipping"; \
		else \
			echo "$$k=$$(openssl rand -base64 48)" >> .env; \
			echo "✅  $$k written to .env"; \
		fi; \
	done

## gen-vapid: generate a dev VAPID key pair for browser Web Push (idempotent)
gen-vapid:
	@if grep -q "^KELTA_PUSH_VAPID_PUBLIC_KEY=." .env 2>/dev/null; then \
		echo "🔑  KELTA_PUSH_VAPID_PUBLIC_KEY already set in .env — skipping"; \
	else \
		echo "🔑  Generating VAPID P-256 key pair..."; \
		docker run --rm node:20-alpine node -e " \
			const c = require('crypto'); \
			const {publicKey, privateKey} = c.generateKeyPairSync('ec',{namedCurve:'prime256v1'}); \
			const pub = publicKey.export({format:'jwk'}), priv = privateKey.export({format:'jwk'}); \
			const b = s => Buffer.from(s,'base64url'); \
			const point = Buffer.concat([Buffer.from([4]), b(pub.x), b(pub.y)]); \
			console.log('KELTA_PUSH_VAPID_PUBLIC_KEY=' + point.toString('base64url')); \
			console.log('KELTA_PUSH_VAPID_PRIVATE_KEY=' + priv.d); \
			console.log('KELTA_PUSH_VAPID_SUBJECT=mailto:admin@localhost'); \
		" >> .env; \
		echo "✅  VAPID keys written to .env (restart kelta-worker to activate web push)"; \
	fi

# ─── Stack lifecycle ─────────────────────────────────────────────────────────

## up: start default stack (infra + auth + worker + gateway + ui)
up: setup
	$(COMPOSE) up -d

## up-ai: start default stack + AI service
up-ai: setup
	$(COMPOSE_AI) up -d

## up-full: start full stack (default + ai + tools)
up-full: setup
	$(COMPOSE_FULL) up -d

## up-telehealth: start default stack + LiveKit SFU (video visits)
up-telehealth: setup
	$(COMPOSE_TELE) up -d

# ─── JVM mode (faster local builds, low memory) ──────────────────────────────
# Same stack as `up`, but Java services build from Dockerfile.jvm. Use these if
# `make up` dies with "cannot allocate memory" — that's GraalVM native-image
# running out of room, not a code failure. See docker-compose.jvm.yml.

## up-jvm: start default stack using JVM images (fast build, low memory)
up-jvm: setup
	$(COMPOSE_JVM) up -d

## up-jvm-ai: start JVM stack + AI service
up-jvm-ai: setup
	$(COMPOSE_JVM_AI) up -d

## up-jvm-full: start full JVM stack (default + ai + tools)
up-jvm-full: setup
	$(COMPOSE_JVM_FULL) up -d

## down: stop and remove containers (keeps volumes)
down:
	$(COMPOSE) --profile ai --profile tools --profile observability --profile telehealth down

## reset: wipe everything (volumes included) and start fresh
reset: down
	$(COMPOSE) --profile ai --profile tools --profile observability down -v
	$(MAKE) up
	$(MAKE) seed

## seed: run the bootstrap container to confirm health + print credentials
seed:
	$(COMPOSE) --profile seed run --rm kelta-bootstrap

# ─── Per-service operations ──────────────────────────────────────────────────

## rebuild SVC=<name>: rebuild and recreate one service (e.g. make rebuild SVC=kelta-worker)
rebuild:
	@[ -n "$(SVC)" ] || (echo "Usage: make rebuild SVC=<service-name>"; exit 1)
	$(COMPOSE) build $(SVC)
	$(COMPOSE) up -d --no-deps $(SVC)

## rebuild-jvm SVC=<name>: same as rebuild, but builds the JVM image variant
rebuild-jvm:
	@[ -n "$(SVC)" ] || (echo "Usage: make rebuild-jvm SVC=<service-name>"; exit 1)
	$(COMPOSE_JVM) build $(SVC)
	$(COMPOSE_JVM) up -d --no-deps $(SVC)

## logs SVC=<name>: tail logs for a service (omit SVC for all)
logs:
	@if [ -n "$(SVC)" ]; then \
		$(COMPOSE) logs -f $(SVC); \
	else \
		$(COMPOSE) logs -f; \
	fi

## debug SVC=<name>: stop a service container so you can run it in the IDE
debug:
	@[ -n "$(SVC)" ] || (echo "Usage: make debug SVC=<service-name>"; exit 1)
	$(COMPOSE) stop $(SVC)
	@echo ""
	@echo "▶  $(SVC) container stopped."
	@echo "   Launch it from IntelliJ using .run/$(SVC).run.xml"
	@echo "   (Issuer is http://auth.localhost:8081 — see README → hybrid mode)"

## ps: show running containers
ps:
	$(COMPOSE) --profile ai --profile tools ps

# ─── Help ────────────────────────────────────────────────────────────────────

## help: show this help
help:
	@echo "Kelta dev targets:"
	@grep -E '^## ' $(MAKEFILE_LIST) | sed 's/## /  /'
