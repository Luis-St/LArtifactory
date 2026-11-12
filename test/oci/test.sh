#!/usr/bin/env bash
# OCI repository: build, push and pull images with docker, build on a pulled base image, tags and deletes with curl.
# Requires: docker (the server must be reachable as localhost or be configured as insecure registry for plain http)

fresh_work_dir oci
OCI_DIR="$WORK_DIR/oci"
REGISTRY="$HOST_PORT"
IMAGE="$REGISTRY/oci-local/lartifactory/test-image"
API="$ARTIFACTORY_URL/v2/oci-local/lartifactory/test-image"
# Separate client configuration, the logins of the tests must not end up in the docker config of the user
export DOCKER_CONFIG="$OCI_DIR/docker-config"
mkdir -p "$DOCKER_CONFIG"
READER=(-u "lat-reader:$READER_TOKEN")
MANIFEST_TYPES="application/vnd.oci.image.index.v1+json,application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.docker.distribution.manifest.v2+json"

docker_login() {
	echo "$2" | docker login "$REGISTRY" -u "$1" --password-stdin
}

# Read contents of an image without running it, the test images have no shell
image_file() {
	local container
	container="$(docker create "$1" /none)" || return 1
	docker cp "$container:$2" - | tar -xO
	docker rm "$container" > /dev/null
}

# Ping and login
check_status "anonymous ping is challenged" 401 "$ARTIFACTORY_URL/v2/"
check_contains "ping returns the api version" "registry/2.0" "$(curl -sS -I "$ARTIFACTORY_URL/v2/")"
check_contains "challenge points to the token endpoint" "Bearer realm=\"$ARTIFACTORY_URL/v2/token\"" "$(curl -sS -I "$ARTIFACTORY_URL/v2/")"
check_contains "token endpoint hands out anonymous tokens" '"token":"anonymous"' "$(curl -sS "$ARTIFACTORY_URL/v2/token")"
check_status "ping with a token" 200 "${READER[@]}" "$ARTIFACTORY_URL/v2/"
check_fails "docker login with a wrong password is rejected" docker_login lat-ci wrong-password
check "docker login with a read only token" docker_login lat-reader "$READER_TOKEN"

# Push
check "docker builds the test image" docker build -q -t "$IMAGE:1.0" "$OCI_DIR/image"
check_fails "reader can not push" docker push "$IMAGE:1.0"
check "docker login with a write token" docker_login lat-ci "$CI_TOKEN"
check "docker pushes test-image:1.0" docker push "$IMAGE:1.0"
check_contains "re-push reuses the stored layers" "already exists" "$(docker push "$IMAGE:1.0" 2>&1)"
check "docker tags the image as latest" docker tag "$IMAGE:1.0" "$IMAGE:latest"
check "docker pushes test-image:latest" docker push "$IMAGE:latest"
sed -i 's/1.0/1.1/' "$OCI_DIR/image/hello.txt"
check "docker builds a changed image" docker build -q -t "$IMAGE:1.0" "$OCI_DIR/image"
check_fails "moving an existing tag is rejected" docker push "$IMAGE:1.0"
check "docker pushes the changed image as 1.1" docker tag "$IMAGE:1.0" "$IMAGE:1.1"
check "docker pushes test-image:1.1" docker push "$IMAGE:1.1"
check "docker tags the image under another name" docker tag "$IMAGE:1.1" "$REGISTRY/oci-local/lartifactory/other-image:1.1"
check_not_contains "push to another image reuses the shared layers" "Pushed" "$(docker push "$REGISTRY/oci-local/lartifactory/other-image:1.1" 2>&1)"

# Pull
check "docker removes the local images" docker rmi "$IMAGE:1.0" "$IMAGE:1.1" "$IMAGE:latest" "$REGISTRY/oci-local/lartifactory/other-image:1.1"
check "docker login as reader" docker_login lat-reader "$READER_TOKEN"
check "docker pulls test-image:1.0" docker pull "$IMAGE:1.0"
check_equals "pulled image has the content of 1.0" "hello from lartifactory-test-image 1.0" "$(image_file "$IMAGE:1.0" /hello.txt)"
check "docker pulls test-image:1.1" docker pull "$IMAGE:1.1"
check_equals "pulled image has the content of 1.1" "hello from lartifactory-test-image 1.1" "$(image_file "$IMAGE:1.1" /hello.txt)"
check "docker builds the consumer on the pulled base image" docker build -q --build-arg "BASE=$IMAGE:1.0" -t lartifactory-test-consumer "$OCI_DIR/consumer"
check_equals "consumer contains the base image content" "hello from lartifactory-test-image 1.0" "$(image_file lartifactory-test-consumer /hello.txt)"
check "docker logs out" docker logout "$REGISTRY"
check_fails "anonymous pull from a private repository is rejected" docker pull "$IMAGE:latest"

# Anonymous pull from a public repository, this needs the bearer token flow
PUBLIC_IMAGE="$REGISTRY/oci-public/lartifactory/test-image"
api -X DELETE "$ARTIFACTORY_URL/api/repositories/oci-public" > /dev/null
api -X POST "$ARTIFACTORY_URL/api/repositories" -d '{"name":"oci-public","type":"oci","publicRead":true}' > /dev/null
check "docker login with a write token for the public repository" docker_login lat-ci "$CI_TOKEN"
check "docker tags the image for the public repository" docker tag "$IMAGE:1.1" "$PUBLIC_IMAGE:1.1"
check "docker pushes to the public repository" docker push "$PUBLIC_IMAGE:1.1"
check "docker removes the public image" docker rmi "$PUBLIC_IMAGE:1.1"
check "docker logs out of the public repository" docker logout "$REGISTRY"
check "anonymous docker pull from a public repository" docker pull "$PUBLIC_IMAGE:1.1"
docker rmi "$PUBLIC_IMAGE:1.1" > /dev/null 2>&1

# Registry api
TAGS="$(curl -sS "${READER[@]}" "$API/tags/list")"
check_equals "tag list has the client side name" "oci-local/lartifactory/test-image" "$(json_get "$TAGS" "data['name']")"
check_equals "tag list has all tags" "['1.0', '1.1', 'latest']" "$(json_get "$TAGS" "data['tags']")"
check_equals "tag list is paginated" "['1.0']" "$(json_get "$(curl -sS "${READER[@]}" "$API/tags/list?n=1")" "data['tags']")"
CATALOG="$(curl -sS "${READER[@]}" "$ARTIFACTORY_URL/v2/_catalog")"
check_contains "catalog lists the image" "oci-local/lartifactory/test-image" "$CATALOG"
check_contains "catalog lists the mounted image" "oci-local/lartifactory/other-image" "$CATALOG"
HEADERS="$(curl -sS -I "${READER[@]}" -H "Accept: $MANIFEST_TYPES" "$API/manifests/1.0")"
DIGEST="$(echo "$HEADERS" | tr -d '\r' | sed -n 's/^Docker-Content-Digest: //Ip')"
check_contains "manifest has a sha256 digest" "sha256:" "$DIGEST"
check_status "manifest is served by digest" 200 "${READER[@]}" "$API/manifests/$DIGEST"
check_status "manifest is served below the repository url" 200 "${READER[@]}" "$ARTIFACTORY_URL/oci/oci-local/v2/lartifactory/test-image/manifests/1.0"
check_status "unknown tag is not found" 404 "${READER[@]}" "$API/manifests/2.0"
check_status "unknown repository is not found" 404 "${READER[@]}" "$ARTIFACTORY_URL/v2/no-such-repository/test-image/tags/list"
PACKAGES="$(curl -sS "${READER[@]}" "$ARTIFACTORY_URL/api/repositories/oci-local/packages")"
check_contains "package api lists the image" '"name":"lartifactory/test-image"' "$PACKAGES"
check_contains "package api lists the tags" '"latest":' "$PACKAGES"

# Delete
check_status "reader can not delete a tag" 403 "${READER[@]}" -X DELETE "$API/manifests/latest"
check_status "admin deletes the latest tag" 202 -H "Authorization: Bearer $ADMIN_TOKEN" -X DELETE "$API/manifests/latest"
check_status "deleted tag is gone" 404 "${READER[@]}" "$API/manifests/latest"
check_status "manifest of a deleted tag remains" 200 "${READER[@]}" "$API/manifests/$DIGEST"
check_status "admin deletes the manifest of 1.0" 202 -H "Authorization: Bearer $ADMIN_TOKEN" -X DELETE "$API/manifests/$DIGEST"
check_status "tag of a deleted manifest is gone" 404 "${READER[@]}" "$API/manifests/1.0"
check_status "other tags remain" 200 "${READER[@]}" "$API/manifests/1.1"

docker rmi "$IMAGE:1.0" "$IMAGE:1.1" lartifactory-test-consumer > /dev/null 2>&1
unset DOCKER_CONFIG
cd "$TEST_DIR" || return
