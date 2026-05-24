KVIST_ROOT ?= ../kvist
KVIST ?= ./kvist
SRC ?= ../kimen2/src/main.kvist

.PHONY: check build run

check:
	cd $(KVIST_ROOT) && $(KVIST) check $(SRC)

build:
	mkdir -p dist
	rm -rf tmp
	mkdir -p tmp
	cd $(KVIST_ROOT) && $(KVIST) build $(SRC) --generated ../kimen2/tmp/main.odin
	odin build tmp -out:dist/kimen2

run:
	cd $(KVIST_ROOT) && $(KVIST) run $(SRC)
