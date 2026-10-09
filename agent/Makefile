VERSION ?= $(shell (git describe --tags --always --dirty 2>/dev/null || echo dev) | sed 's/^v//')
LDFLAGS := -s -w -X main.version=$(VERSION)
GOFLAGS_BUILD := -trimpath -ldflags '$(LDFLAGS)'
BIN := build/activitylog-agent
DIST := build/dist
PREFIX ?= $(HOME)/.local

# On macOS the binary carries an embedded Info.plist (see
# internal/platform/Info.plist). Re-signing binds it and sets the code
# signing identifier that Accessibility and Automation permissions are
# granted to. With the default ad-hoc signature the permissions must be
# granted again after every rebuild; set SIGN_IDENTITY to a code signing
# certificate from your keychain to keep them.
SIGN_IDENTITY ?= -
BUNDLE_ID := net.ymotongpoo.activitylog.agent
export MACOSX_DEPLOYMENT_TARGET := 13.0

UNAME := $(shell uname -s)
ifeq ($(UNAME),Darwin)
sign = codesign --force --sign '$(SIGN_IDENTITY)' --identifier $(BUNDLE_ID) $(1)
else
sign = true
endif

.PHONY: build test vet install uninstall release-macos release-linux deb clean

build:
	go build $(GOFLAGS_BUILD) -o $(BIN) ./cmd/activitylog-agent
	$(call sign,$(BIN))

test:
	go test ./...

vet:
	go vet ./...
	GOOS=linux go vet ./...

# Installs to $(PREFIX)/bin and registers a LaunchAgent (macOS) or a systemd
# user unit (Linux). Homebrew users use `brew services` instead.
install: build
	-$(PREFIX)/bin/activitylog-agent service uninstall 2>/dev/null
	install -d $(PREFIX)/bin
	install -m755 $(BIN) $(PREFIX)/bin/activitylog-agent
	$(PREFIX)/bin/activitylog-agent service install

uninstall:
	-$(PREFIX)/bin/activitylog-agent service uninstall
	rm -f $(PREFIX)/bin/activitylog-agent

# Apple silicon (arm64) binary for the Homebrew formula.
release-macos:
	mkdir -p $(DIST)
	dir=build/activitylog-agent-$(VERSION)-darwin-arm64; \
	rm -rf $$dir && mkdir -p $$dir && \
	CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 go build $(GOFLAGS_BUILD) -o $$dir/activitylog-agent ./cmd/activitylog-agent && \
	$(call sign,$$dir/activitylog-agent) && \
	cp README.md ../LICENSE $$dir/ && \
	tar -C build -czf $(DIST)/activitylog-agent-$(VERSION)-darwin-arm64.tar.gz $$(basename $$dir)

release-linux:
	mkdir -p $(DIST)
	for arch in amd64 arm64; do \
		dir=build/activitylog-agent-$(VERSION)-linux-$$arch; \
		rm -rf $$dir && mkdir -p $$dir; \
		CGO_ENABLED=0 GOOS=linux GOARCH=$$arch go build $(GOFLAGS_BUILD) -o $$dir/activitylog-agent ./cmd/activitylog-agent || exit 1; \
		cp README.md ../LICENSE $$dir/; \
		tar -C build -czf $(DIST)/activitylog-agent-$(VERSION)-linux-$$arch.tar.gz $$(basename $$dir); \
	done

# Debian packages built from the release-linux binaries. VERSION must start
# with a digit.
NFPM ?= go run github.com/goreleaser/nfpm/v2/cmd/nfpm@v2.47.0
deb: release-linux
	mkdir -p build/deb
	for arch in amd64 arm64; do \
		cp build/activitylog-agent-$(VERSION)-linux-$$arch/activitylog-agent build/deb/activitylog-agent && \
		ARCH=$$arch VERSION=$(VERSION) $(NFPM) package --config packaging/linux/nfpm.yaml --packager deb --target $(DIST)/ || exit 1; \
	done

clean:
	rm -rf build
