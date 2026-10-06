#!/usr/bin/env bash
# Maven repository: publish with Gradle (maven-publish) and Maven (deploy), resolve with Maven and Gradle.
# Requires: gradle, mvn, java

fresh_work_dir maven
MAVEN_DIR="$WORK_DIR/maven"
RELEASES="$ARTIFACTORY_URL/maven/maven-releases"
SNAPSHOTS="$ARTIFACTORY_URL/maven/maven-snapshots"
GROUP_PATH="net/luis/lartifactory/test"
GRADLE_ARGS=(--no-daemon -q --project-cache-dir "$MAVEN_DIR/.gradle-cache" "-PartifactoryUrl=$ARTIFACTORY_URL" "-PartifactoryUser=lat-ci" "-PartifactoryPassword=$CI_TOKEN")
export MAVEN_USER=lat-ci MAVEN_PASSWORD="$CI_TOKEN"
MAVEN_ARGS=(-B -q -s "$MAVEN_DIR/settings.xml" "-Dartifactory.url=$ARTIFACTORY_URL")

# Publish
cd "$MAVEN_DIR/gradle-lib" || return
check "gradle publishes gradle-lib 1.0.0" gradle "${GRADLE_ARGS[@]}" publish
check_fails "gradle publish without credentials fails" gradle --no-daemon -q --project-cache-dir "$MAVEN_DIR/.gradle-cache" "-PartifactoryUrl=$ARTIFACTORY_URL" "-PartifactoryUser=nobody" "-PartifactoryPassword=wrong" publish

sed -i 's/gradle-lib release/gradle-lib snapshot 1/' src/main/java/net/luis/lartifactory/test/gradle/GradleLib.java
check "gradle publishes gradle-lib 1.1.0-SNAPSHOT (build 1)" gradle "${GRADLE_ARGS[@]}" -PlibVersion=1.1.0-SNAPSHOT publish
sed -i 's/gradle-lib snapshot 1/gradle-lib snapshot 2/' src/main/java/net/luis/lartifactory/test/gradle/GradleLib.java
check "gradle publishes gradle-lib 1.1.0-SNAPSHOT (build 2)" gradle "${GRADLE_ARGS[@]}" -PlibVersion=1.1.0-SNAPSHOT publish

sed -i 's/gradle-lib snapshot 2/gradle-lib changed release/' src/main/java/net/luis/lartifactory/test/gradle/GradleLib.java
check_fails "republishing release 1.0.0 with different content is rejected" gradle "${GRADLE_ARGS[@]}" publish
JAR_STATUS="$(curl -s -o /dev/null -w '%{http_code}' -u "lat-ci:$CI_TOKEN" -X PUT --data-binary "different" "$RELEASES/$GROUP_PATH/gradle-lib/1.0.0/gradle-lib-1.0.0.jar")"
check_equals "PUT over an existing release returns 409" "409" "$JAR_STATUS"

cd "$MAVEN_DIR/maven-lib" || return
check "mvn deploys maven-lib 1.0.0" mvn "${MAVEN_ARGS[@]}" deploy

# Metadata and checksums
METADATA="$(curl -sS -u "lat-reader:$READER_TOKEN" "$RELEASES/$GROUP_PATH/gradle-lib/maven-metadata.xml")"
check_contains "release metadata lists 1.0.0" "<version>1.0.0</version>" "$METADATA"
check_contains "release metadata has release version" "<release>1.0.0</release>" "$METADATA"
SNAPSHOT_METADATA="$(curl -sS -u "lat-reader:$READER_TOKEN" "$SNAPSHOTS/$GROUP_PATH/gradle-lib/1.1.0-SNAPSHOT/maven-metadata.xml")"
check_contains "snapshot metadata has build number 2" "<buildNumber>2</buildNumber>" "$SNAPSHOT_METADATA"
check_contains "snapshot metadata lists the sources jar" "<classifier>sources</classifier>" "$SNAPSHOT_METADATA"
METADATA_SHA1="$(curl -sS -u "lat-reader:$READER_TOKEN" "$RELEASES/$GROUP_PATH/gradle-lib/maven-metadata.xml.sha1")"
check_equals "metadata checksum matches metadata" "$(printf '%s\n' "$METADATA" | sha1sum | cut -d' ' -f1)" "$METADATA_SHA1"

curl -sS -u "lat-reader:$READER_TOKEN" -o "$MAVEN_DIR/downloaded.jar" "$RELEASES/$GROUP_PATH/gradle-lib/1.0.0/gradle-lib-1.0.0.jar"
check_equals "downloaded jar matches its sha1 file" "$(sha1sum "$MAVEN_DIR/downloaded.jar" | cut -d' ' -f1)" "$(curl -sS -u "lat-reader:$READER_TOKEN" "$RELEASES/$GROUP_PATH/gradle-lib/1.0.0/gradle-lib-1.0.0.jar.sha1")"
check_contains "jar is served as java archive" "application/java-archive" "$(curl -sS -I -u "lat-reader:$READER_TOKEN" "$RELEASES/$GROUP_PATH/gradle-lib/1.0.0/gradle-lib-1.0.0.jar")"
check_status "missing artifact returns 404" 404 -u "lat-reader:$READER_TOKEN" "$RELEASES/$GROUP_PATH/gradle-lib/9.9.9/gradle-lib-9.9.9.jar"
check_status "anonymous read of a private repository is rejected" 401 "$RELEASES/$GROUP_PATH/gradle-lib/maven-metadata.xml"
check_contains "non unique snapshot name resolves to the latest build" "gradle-lib snapshot 2" \
	"$(curl -sS -u "lat-reader:$READER_TOKEN" -o "$MAVEN_DIR/snapshot.jar" "$SNAPSHOTS/$GROUP_PATH/gradle-lib/1.1.0-SNAPSHOT/gradle-lib-1.1.0-SNAPSHOT.jar" && unzip -p "$MAVEN_DIR/snapshot.jar" 'net/luis/lartifactory/test/gradle/GradleLib.class' | strings)"

# List
PACKAGES="$(curl -sS -u "lat-reader:$READER_TOKEN" "$ARTIFACTORY_URL/api/repositories/maven-releases/packages")"
check_contains "package api lists gradle-lib" "net.luis.lartifactory.test:gradle-lib" "$PACKAGES"
check_contains "package api lists maven-lib" "net.luis.lartifactory.test:maven-lib" "$PACKAGES"
check_contains "directory listing shows the versions" "1.0.0/" "$(curl -sS -u "lat-reader:$READER_TOKEN" "$RELEASES/$GROUP_PATH/gradle-lib/")"

# Resolve
rm -rf "${HOME:?}/.m2/repository/net/luis/lartifactory"
export MAVEN_USER=lat-reader MAVEN_PASSWORD="$READER_TOKEN"
cd "$MAVEN_DIR/consumer-maven" || return
check "mvn resolves maven-lib and the gradle-lib snapshot" mvn "${MAVEN_ARGS[@]}" -U compile dependency:build-classpath -Dmdep.outputFile=classpath.txt
check_equals "maven consumer runs with the latest snapshot" "maven-lib release / gradle-lib snapshot 2" \
	"$(java -cp "target/classes:$(cat classpath.txt 2>/dev/null)" net.luis.lartifactory.test.consumer.Consumer 2>/dev/null)"

cd "$MAVEN_DIR/consumer-gradle" || return
check_equals "gradle consumer resolves the releases and runs" "maven-lib release / gradle-lib release" \
	"$(gradle --no-daemon -q --refresh-dependencies --project-cache-dir "$MAVEN_DIR/.gradle-cache" "-PartifactoryUrl=$ARTIFACTORY_URL" -PartifactoryUser=lat-reader "-PartifactoryPassword=$READER_TOKEN" run 2>&1 | tail -n 1)"

# Delete
check_status "writer can not delete a version" 403 -u "lat-ci:$CI_TOKEN" -X DELETE "$RELEASES/$GROUP_PATH/maven-lib/1.0.0"
check_status "admin deletes maven-lib 1.0.0" 204 -H "Authorization: Bearer $ADMIN_TOKEN" -X DELETE "$RELEASES/$GROUP_PATH/maven-lib/1.0.0"
check_status "metadata of a deleted artifact is gone" 404 -u "lat-reader:$READER_TOKEN" "$RELEASES/$GROUP_PATH/maven-lib/maven-metadata.xml"
check_not_contains "package api no longer lists maven-lib" "maven-lib" "$(curl -sS -u "lat-reader:$READER_TOKEN" "$ARTIFACTORY_URL/api/repositories/maven-releases/packages")"

cd "$TEST_DIR" || return
