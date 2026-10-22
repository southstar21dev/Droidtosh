.PHONY: proto-sync fixtures generate-swift test-swift test-android test-relay e2e verify-generated build-all install install-android install-mac

# An explicit DEVELOPER_DIR wins (CI exports the Xcode it selected); otherwise prefer
# /Applications/Xcode.app over xcode-select, which may point at the Command Line Tools.
XCODE_DEVELOPER_DIR := $(or $(DEVELOPER_DIR),$(shell if [ -d /Applications/Xcode.app/Contents/Developer ]; then echo /Applications/Xcode.app/Contents/Developer; else xcode-select -p; fi))

build-all:
	./scripts/build-all.sh

# Rebuild and replace the installed apps in place, keeping data and pairing (docs/RELEASING.md).
install: install-android install-mac

install-android:
	./scripts/install-android.sh

install-mac:
	DEVELOPER_DIR=$(XCODE_DEVELOPER_DIR) ./scripts/install-mac.sh

proto-sync:
	./protocol/sync-protos.sh

fixtures:
	./protocol/generate-fixtures.sh

generate-swift: proto-sync
	./protocol/generate-swift.sh

test-swift:
	cd macos/DroidsyncKit && DEVELOPER_DIR=$(XCODE_DEVELOPER_DIR) swift test

test-android:
	cd android && ./gradlew :app:testDebugUnitTest

test-relay:
	cd relay && go test ./...

# Real relay + headless Mac peer (Swift) + JVM phone peer (Kotlin); the scenarios are in e2e/*_test.go.
e2e:
	cd e2e && DEVELOPER_DIR=$(XCODE_DEVELOPER_DIR) go test -count=1 -v -timeout 15m ./...

# Sequential on purpose: fixtures must be regenerated before proto-sync copies them.
verify-generated:
	$(MAKE) fixtures
	$(MAKE) generate-swift
	git diff --exit-code -- macos android protocol/fixtures/bin
