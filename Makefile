.PHONY: clean
clean:
	./gradlew clean

.PHONY: build
build:
	./gradlew shadowJar

.PHONY: publish
publish:
	@echo "./gradlew clean assemble publishToSonatype closeAndReleaseSonatypeStagingRepository"
	@./gradlew clean assemble publishToSonatype closeAndReleaseSonatypeStagingRepository \
		-PsonatypeUsername="$(NEXUS_USERNAME)" \
		-PsonatypePassword="$(NEXUS_PASSWORD)" \
		-Psigning.secretKeyRingFile="$(NEXUS_GPG_SECRING_FILE)" \
		-Psigning.password="$(NEXUS_GPG_PASSWORD)" \
		-Psigning.keyId="$(NEXUS_GPG_KEY_ID)" --stacktrace
	

.PHONY: test
test:
	./gradlew test

