VERSION ?= $(shell (git describe --tags --always --dirty 2>/dev/null || echo dev) | sed 's/^v//')
LDFLAGS := -s -w -X main.version=$(VERSION)
GOFLAGS_BUILD := -trimpath -ldflags '$(LDFLAGS)'
BIN := build/activitylog-agent
DIST := build/dist

# macOS app bundle. Permissions (Accessibility, Automation) are granted to
# this bundle. With the default ad-hoc signature they must be granted again
# after every rebuild; set SIGN_IDENTITY to a code signing certificate from
# your keychain to keep them.
APP := build/ActivityLogAgent.app
APP_DEST := $(HOME)/Applications/ActivityLogAgent.app
SIGN_IDENTITY ?= -
BUNDLE_ID := net.ymotongpoo.activitylog.agent
export MACOSX_DEPLOYMENT_TARGET := 13.0

.PHONY: build test vet app install-macos uninstall-macos install-linux uninstall-linux \
	release-macos release-linux clean

build:
	go build $(GOFLAGS_BUILD) -o $(BIN) ./cmd/activitylog-agent

test:
	go test ./...

vet:
	go vet ./...
	GOOS=linux go vet ./...

# bundle wraps $(BIN) into $(APP) and signs it.
define bundle
	rm -rf $(APP)
	mkdir -p $(APP)/Contents/MacOS
	cp $(BIN) $(APP)/Contents/MacOS/activitylog-agent
	sed 's/__VERSION__/$(VERSION)/g' packaging/macos/Info.plist > $(APP)/Contents/Info.plist
	codesign --force --sign '$(SIGN_IDENTITY)' --identifier $(BUNDLE_ID) $(APP)
endef

app: build
	$(bundle)

install-macos: app
	-$(APP_DEST)/Contents/MacOS/activitylog-agent service uninstall 2>/dev/null
	mkdir -p $(HOME)/Applications $(HOME)/.local/bin
	rm -rf $(APP_DEST)
	cp -R $(APP) $(APP_DEST)
	ln -sf $(APP_DEST)/Contents/MacOS/activitylog-agent $(HOME)/.local/bin/activitylog-agent
	$(APP_DEST)/Contents/MacOS/activitylog-agent service install

uninstall-macos:
	-$(APP_DEST)/Contents/MacOS/activitylog-agent service uninstall
	rm -f $(HOME)/.local/bin/activitylog-agent
	rm -rf $(APP_DEST)

install-linux: build
	install -Dm755 $(BIN) $(HOME)/.local/bin/activitylog-agent
	$(HOME)/.local/bin/activitylog-agent service install

uninstall-linux:
	-$(HOME)/.local/bin/activitylog-agent service uninstall
	rm -f $(HOME)/.local/bin/activitylog-agent

# Universal (arm64 + x86_64) app bundle, zipped for the Homebrew cask.
release-macos:
	mkdir -p build/arm64 build/amd64 $(DIST)
	CGO_ENABLED=1 GOARCH=arm64 CC="clang -arch arm64" go build $(GOFLAGS_BUILD) -o build/arm64/activitylog-agent ./cmd/activitylog-agent
	CGO_ENABLED=1 GOARCH=amd64 CC="clang -arch x86_64" go build $(GOFLAGS_BUILD) -o build/amd64/activitylog-agent ./cmd/activitylog-agent
	lipo -create -output $(BIN) build/arm64/activitylog-agent build/amd64/activitylog-agent
	$(bundle)
	cd build && ditto -c -k --norsrc --noextattr --noqtn --keepParent ActivityLogAgent.app dist/ActivityLogAgent-$(VERSION)-macos-universal.zip

release-linux:
	mkdir -p $(DIST)
	for arch in amd64 arm64; do \
		dir=build/activitylog-agent-$(VERSION)-linux-$$arch; \
		rm -rf $$dir && mkdir -p $$dir; \
		CGO_ENABLED=0 GOOS=linux GOARCH=$$arch go build $(GOFLAGS_BUILD) -o $$dir/activitylog-agent ./cmd/activitylog-agent || exit 1; \
		cp README.md ../LICENSE $$dir/; \
		tar -C build -czf $(DIST)/activitylog-agent-$(VERSION)-linux-$$arch.tar.gz $$(basename $$dir); \
	done

clean:
	rm -rf build
