BINARY_NAME=kimen

.PHONY: prep-cache build run install tidy fmt vet test

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

prep-cache:
	@mkdir -p "$(GOMODCACHE)" "$(GOCACHE)" ./dist

build: prep-cache
	go build -o ./dist/$(BINARY_NAME) ./cmd/kimen

run: prep-cache
	go run ./cmd/kimen

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

install: prep-cache test build
	go install ./cmd/kimen
	@BIN_DIR="$$(go env GOBIN)"; if [ -z "$$BIN_DIR" ]; then BIN_DIR="$$(go env GOPATH)/bin"; fi; echo "Installed: $$BIN_DIR/$(BINARY_NAME)"

