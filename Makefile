BINARY_NAME := kimen
INSTALL_BIN_DIR ?= $(HOME)/.local/bin
ARGS ?=

.PHONY: build build-jar build-native run install \
	test test-unit test-integration test-all release-check \
	clj-run clj-test bb-run bb-test bb-itest bb-test-all \
	tidy fmt vet \
	go-prep-cache go-build go-run go-install go-test go-vet go-fmt go-tidy \
	go-sync-e2e go-sync-e2e-git go-sync-e2e-all go-release-check go-release-snapshot

# Go caches are kept for reference/parity targets.
UNAME_S := $(shell uname -s)
ifeq ($(UNAME_S),Darwin)
	DEFAULT_GO_CACHE_DIR := $(HOME)/Library/Caches/kimen-go
else
	DEFAULT_GO_CACHE_DIR := $(HOME)/.cache/kimen-go
endif

GO_CACHE_DIR ?= $(DEFAULT_GO_CACHE_DIR)
export GOMODCACHE ?= $(GO_CACHE_DIR)/gomodcache
export GOCACHE ?= $(GO_CACHE_DIR)/gocache

VERSION ?= $(shell (git describe --tags --dirty --always --match 'v[0-9][0-9][0-9][0-9].*' 2>/dev/null || git describe --tags --dirty --always 2>/dev/null) || echo dev)
COMMIT ?= $(shell git rev-parse --short HEAD 2>/dev/null || echo none)
DATE ?= $(shell date -u +"%Y-%m-%dT%H:%M:%SZ")

LDFLAGS ?= \
	-X kimen/internal/buildinfo.Version=$(VERSION) \
	-X kimen/internal/buildinfo.Commit=$(COMMIT) \
	-X kimen/internal/buildinfo.Date=$(DATE)

# Clojure-first targets
build: build-jar

build-jar:
	clojure -T:build uber
	@mkdir -p ./dist
	@cp ./target/kimen.jar ./dist/kimen.jar
	@cp ./bin/kimen ./dist/$(BINARY_NAME)
	@chmod +x ./dist/$(BINARY_NAME)
	@echo "Built ./dist/kimen.jar and ./dist/$(BINARY_NAME)"

build-native:
	clojure -T:build native

run:
	bb run -- $(ARGS)

install: build-jar
	@mkdir -p "$(INSTALL_BIN_DIR)"
	@cp ./bin/$(BINARY_NAME) "$(INSTALL_BIN_DIR)/$(BINARY_NAME)"
	@chmod +x "$(INSTALL_BIN_DIR)/$(BINARY_NAME)"
	@echo "Installed launcher: $(INSTALL_BIN_DIR)/$(BINARY_NAME)"
	@echo "Artifacts: ./dist/kimen.jar ./dist/$(BINARY_NAME)"

test: test-all

test-unit:
	bb test

test-integration:
	bb itest

test-all:
	bb test-all

release-check: test-all build-jar

# Direct task aliases
clj-run:
	clojure -M:run -- $(ARGS)

clj-test:
	clojure -M:test

bb-run:
	bb run -- $(ARGS)

bb-test:
	bb test

bb-itest:
	bb itest

bb-test-all:
	bb test-all

# Legacy Go aliases (kept for reference and parity verification)
tidy: go-tidy
fmt: go-fmt
vet: go-vet

go-prep-cache:
	@mkdir -p "$(GOMODCACHE)" "$(GOCACHE)" ./dist

go-build: go-prep-cache
	go build -ldflags "$(LDFLAGS)" -o ./dist/$(BINARY_NAME) ./cmd/kimen

go-run: go-prep-cache
	go run -ldflags "$(LDFLAGS)" ./cmd/kimen

go-tidy: go-prep-cache
	go mod tidy

go-fmt:
	gofmt -w .

go-vet: go-prep-cache
	go vet ./...

go-test: go-prep-cache
	@# If an in-repo module cache exists under tmp/, `go test ./...` will walk it and fail.
	@# These caches are often read-only, so we rename them into an ignored dir (leading '_').
	@# If a previous ignored cache already exists, move the new one aside (to avoid leaving tmp/gomodcache behind).
	@if [ -d ./tmp/gomodcache ]; then \
		if [ -d ./tmp/_gomodcache ]; then \
			i=1; while [ -d "./tmp/_gomodcache$$i" ]; do i=$$((i+1)); done; \
			mv ./tmp/gomodcache "./tmp/_gomodcache$$i"; \
		else \
			mv ./tmp/gomodcache ./tmp/_gomodcache; \
		fi; \
	fi
	@if [ -d ./tmp/gocache ]; then \
		if [ -d ./tmp/_gocache ]; then \
			i=1; while [ -d "./tmp/_gocache$$i" ]; do i=$$((i+1)); done; \
			mv ./tmp/gocache "./tmp/_gocache$$i"; \
		else \
			mv ./tmp/gocache ./tmp/_gocache; \
		fi; \
	fi
	go test ./...

go-sync-e2e: go-build
	./scripts/e2e-sync.sh

go-sync-e2e-git: go-build
	./scripts/e2e-sync-git.sh

go-sync-e2e-all: go-sync-e2e go-sync-e2e-git

go-install: go-prep-cache go-test go-build
	go install -ldflags "$(LDFLAGS)" ./cmd/kimen
	@BIN_DIR="$$(go env GOBIN)"; if [ -z "$$BIN_DIR" ]; then BIN_DIR="$$(go env GOPATH)/bin"; fi; echo "Installed: $$BIN_DIR/$(BINARY_NAME)"

go-release-check: go-vet go-test go-build go-sync-e2e-all

go-release-snapshot: go-prep-cache
	goreleaser release --snapshot --clean
