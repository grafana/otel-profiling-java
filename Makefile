.PHONY: clean build build-otel-extension build-lib publish test \
	itest itest-otel-extension itest-otel-library itest-otel-extension-manual-start

clean:
	./gradlew clean

build:
	./gradlew :otel-extension:shadowJar :lib:jar

build-otel-extension:
	./gradlew :otel-extension:shadowJar

build-lib:
	./gradlew :lib:jar

publish:
	@echo "./gradlew clean assemble publishToSonatype closeAndReleaseSonatypeStagingRepository"
	@./gradlew clean assemble publishToSonatype closeAndReleaseSonatypeStagingRepository \
		-PsonatypeUsername="$(NEXUS_USERNAME)" \
		-PsonatypePassword="$(NEXUS_PASSWORD)" \
		-Psigning.secretKeyRingFile="$(NEXUS_GPG_SECRING_FILE)" \
		-Psigning.password="$(NEXUS_GPG_PASSWORD)" \
		-Psigning.keyId="$(NEXUS_GPG_KEY_ID)" --stacktrace

test:
	./gradlew test

itest-otel-extension: build
	cd itest && go test -v -timeout 20m -count=1 -run '^TestOtelExtension$$' ./...

itest-otel-library: build
	cd itest && go test -v -timeout 20m -count=1 -run '^TestOtelLibrary$$' ./...

itest-otel-extension-manual-start: build
	cd itest && go test -v -timeout 20m -count=1 -run '^TestOtelExtensionManualStart$$' ./...

itest: build
	cd itest && go test -v -timeout 20m -count=1 -run '^TestOtelExtension$$' ./...
	cd itest && go test -v -timeout 20m -count=1 -run '^TestOtelLibrary$$' ./...
	cd itest && go test -v -timeout 20m -count=1 -run '^TestOtelExtensionManualStart$$' ./...
