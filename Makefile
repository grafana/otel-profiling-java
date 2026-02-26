.PHONY: clean build build-agent-extension build-lib publish test

clean:
	./gradlew clean

build:
	./gradlew :agent-extension:shadowJar :lib:jar

build-agent-extension:
	./gradlew :agent-extension:shadowJar

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
