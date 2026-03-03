BINARY_NAME=kimen

.PHONY: prep-cache build run install tidy fmt vet test sync-e2e sync-e2e-git sync-e2e-all release-check release-snapshot clj-run clj-test bb-run bb-test bb-itest bb-test-all

# Go caches:
# - Default is to use a shared per-user cache dir so isolated agent dirs (worktrees/copies)
#   do not "redownload the world" on each build/test.
# - Override by setting GO_CACHE_DIR (or pre-setting GOMODCACHE/GOCACHE).
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

prep-cache:
	@mkdir -p "$(GOMODCACHE)" "$(GOCACHE)" ./dist

build: prep-cache
	go build -ldflags "$(LDFLAGS)" -o ./dist/$(BINARY_NAME) ./cmd/kimen

run: prep-cache
	go run -ldflags "$(LDFLAGS)" ./cmd/kimen

tidy: prep-cache
	go mod tidy

fmt:
	gofmt -w .

vet: prep-cache
	go vet ./...

test: prep-cache
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

sync-e2e: build
	./scripts/e2e-sync.sh

sync-e2e-git: build
	./scripts/e2e-sync-git.sh

sync-e2e-all: sync-e2e sync-e2e-git

install: prep-cache test build
	go install -ldflags "$(LDFLAGS)" ./cmd/kimen
	@BIN_DIR="$$(go env GOBIN)"; if [ -z "$$BIN_DIR" ]; then BIN_DIR="$$(go env GOPATH)/bin"; fi; echo "Installed: $$BIN_DIR/$(BINARY_NAME)"

release-check: vet test build sync-e2e-all

release-snapshot: prep-cache
	goreleaser release --snapshot --clean

clj-run:
	clojure -M:run

clj-test:
	clojure -M:test

bb-run:
	bb run -- version --json

bb-test:
	bb test

bb-itest:
	bb itest

bb-test-all:
	bb test-all
