#!/usr/bin/env bash
# Debian repository: packages built with dpkg-deb, uploaded with curl and installed with apt in a Debian container.

fresh_work_dir debian
REPO_URL="$ARTIFACTORY_URL/debian/debian-local"
DEB_DIR="$WORK_DIR/debian"

# build_deb <package> <version> <architecture> [depends]: builds <package>_<version>_<architecture>.deb into the work directory
build_deb() {
	local root="$DEB_DIR/build/$1-$2-$3"
	mkdir -p "$root/DEBIAN" "$root/usr/share/$1"
	echo "$1 $2" > "$root/usr/share/$1/version.txt"
	{
		echo "Package: $1"
		echo "Version: $2"
		echo "Architecture: $3"
		echo "Maintainer: LArtifactory Tests <tests@example.com>"
		[[ -n "${4:-}" ]] && echo "Depends: $4"
		echo "Description: LArtifactory test package"
		echo " A package used by the repository tests."
	} > "$root/DEBIAN/control"
	dpkg-deb --root-owner-group -Zxz --build "$root" "$DEB_DIR/$1_$2_$3.deb" > /dev/null
}

if ! command -v dpkg-deb > /dev/null; then
	fail "dpkg-deb is required to build the test packages"
	return 0 2>/dev/null || exit 1
fi
build_deb lat-test-lib 1.0.0-1 all
build_deb lat-test-lib 1.1.0-1 all
build_deb lat-test-app 2.0.0-1 amd64 "lat-test-lib (>= 1.1.0)"
build_deb lat-test-app 2.0.0-1 arm64 "lat-test-lib (>= 1.1.0)"
printf 'not a debian package' > "$DEB_DIR/broken.deb"

check_status "anonymous upload is rejected" 401 -T "$DEB_DIR/lat-test-lib_1.0.0-1_all.deb" "$REPO_URL/pool/stable/main/"
check_status "reader can not upload" 403 -u "lat-reader:$READER_TOKEN" -T "$DEB_DIR/lat-test-lib_1.0.0-1_all.deb" "$REPO_URL/pool/stable/main/"
for deb in lat-test-lib_1.0.0-1_all lat-test-lib_1.1.0-1_all lat-test-app_2.0.0-1_amd64 lat-test-app_2.0.0-1_arm64; do
	check_status "upload $deb" 201 -u "lat-ci:$CI_TOKEN" -T "$DEB_DIR/$deb.deb" "$REPO_URL/pool/stable/main/"
done
check_status "upload to a second distribution" 201 -u "lat-ci:$CI_TOKEN" -T "$DEB_DIR/lat-test-lib_1.0.0-1_all.deb" "$REPO_URL/pool/testing/main/"
check_status "re-upload of identical content is accepted" 200 -u "lat-ci:$CI_TOKEN" -T "$DEB_DIR/lat-test-lib_1.0.0-1_all.deb" "$REPO_URL/pool/stable/main/"
check_status "upload of an invalid package is rejected" 400 -u "lat-ci:$CI_TOKEN" -T "$DEB_DIR/broken.deb" "$REPO_URL/pool/stable/main/"
check_status "upload with an invalid distribution is rejected" 400 -u "lat-ci:$CI_TOKEN" -T "$DEB_DIR/lat-test-lib_1.0.0-1_all.deb" "$REPO_URL/pool/bad%20name/main/"

check_status "anonymous index download is rejected" 401 "$REPO_URL/dists/stable/InRelease"
RELEASE="$(curl -sS -u "lat-reader:$READER_TOKEN" "$REPO_URL/dists/stable/Release")"
check_contains "Release lists the architectures" "Architectures: all amd64 arm64" "$RELEASE"
check_contains "Release lists the component" "Components: main" "$RELEASE"
check_contains "Release lists the amd64 index" "main/binary-amd64/Packages.gz" "$RELEASE"
PACKAGES="$(curl -sS -u "lat-reader:$READER_TOKEN" "$REPO_URL/dists/stable/main/binary-amd64/Packages")"
check_contains "amd64 index lists the amd64 package" "Filename: pool/stable/main/l/lat-test-app/lat-test-app_2.0.0-1_amd64.deb" "$PACKAGES"
check_contains "amd64 index lists the architecture independent package" "Filename: pool/stable/main/l/lat-test-lib/lat-test-lib_1.1.0-1_all.deb" "$PACKAGES"
check_not_contains "amd64 index does not list the arm64 package" "_arm64.deb" "$PACKAGES"
check_equals "compressed index matches the index" "$PACKAGES" "$(curl -sS -u "lat-reader:$READER_TOKEN" "$REPO_URL/dists/stable/main/binary-amd64/Packages.gz" | gunzip)"
check_not_contains "second distribution only lists its own packages" "lat-test-app" "$(curl -sS -u "lat-reader:$READER_TOKEN" "$REPO_URL/dists/testing/main/binary-all/Packages")"
check_status "unknown distribution is not found" 404 -u "lat-reader:$READER_TOKEN" "$REPO_URL/dists/unknown/InRelease"

curl -sS -u "lat-reader:$READER_TOKEN" -o "$DEB_DIR/public.key" "$REPO_URL/public.key"
curl -sS -u "lat-reader:$READER_TOKEN" -o "$DEB_DIR/InRelease" "$REPO_URL/dists/stable/InRelease"
curl -sS -u "lat-reader:$READER_TOKEN" -o "$DEB_DIR/Release" "$REPO_URL/dists/stable/Release"
curl -sS -u "lat-reader:$READER_TOKEN" -o "$DEB_DIR/Release.gpg" "$REPO_URL/dists/stable/Release.gpg"
if command -v gpg > /dev/null; then
	gpg --batch --quiet --homedir "$(mktemp -d)" --dearmor -o "$DEB_DIR/keyring.gpg" "$DEB_DIR/public.key" 2>/dev/null
	check "InRelease signature verifies" gpgv --keyring "$DEB_DIR/keyring.gpg" "$DEB_DIR/InRelease"
	check "Release.gpg signature verifies" gpgv --keyring "$DEB_DIR/keyring.gpg" "$DEB_DIR/Release.gpg" "$DEB_DIR/Release"
fi
curl -sS -u "lat-ci:$CI_TOKEN" -o "$DEB_DIR/downloaded.deb" "$REPO_URL/pool/stable/main/l/lat-test-app/lat-test-app_2.0.0-1_amd64.deb"
check "downloaded package matches uploaded package" cmp "$DEB_DIR/lat-test-app_2.0.0-1_amd64.deb" "$DEB_DIR/downloaded.deb"
check_contains "package api lists the package" '"lat-test-app"' "$(curl -sS -u "lat-reader:$READER_TOKEN" "$ARTIFACTORY_URL/api/repositories/debian-local/packages")"

# Packages dpkg can not install are rejected with 400 instead of failing with 500 or being accepted
head -c 200 "$DEB_DIR/lat-test-lib_1.0.0-1_all.deb" > "$DEB_DIR/truncated.deb"
check_status "upload of a truncated package is rejected" 400 -u "lat-ci:$CI_TOKEN" -T "$DEB_DIR/truncated.deb" "$REPO_URL/pool/stable/main/"
mkdir -p "$DEB_DIR/order"
(cd "$DEB_DIR/order" && ar x ../lat-test-lib_1.0.0-1_all.deb && ar rc ../order.deb debian-binary data.tar.* control.tar.*)
check_status "upload of a package with the data archive first is rejected" 400 -u "lat-ci:$CI_TOKEN" -T "$DEB_DIR/order.deb" "$REPO_URL/pool/stable/main/"
DUPLICATE_ROOT="$DEB_DIR/build/lat-test-lib-1.0.0-1-all"
cp -r "$DUPLICATE_ROOT" "$DEB_DIR/build/duplicate"
echo "Version: 9.9" >> "$DEB_DIR/build/duplicate/DEBIAN/control"
dpkg-deb --nocheck --root-owner-group --build "$DEB_DIR/build/duplicate" "$DEB_DIR/duplicate.deb" > /dev/null 2>&1
check_status "upload of a package with a duplicate control field is rejected" 400 -u "lat-ci:$CI_TOKEN" -T "$DEB_DIR/duplicate.deb" "$REPO_URL/pool/stable/main/"

# Parallel uploads of the same package: one stores it, the others find the identical file
build_deb lat-test-parallel 1.0-1 all
for i in 1 2 3 4 5 6 7 8; do
	curl -s -o /dev/null -w '%{http_code}\n' -u "lat-ci:$CI_TOKEN" -T "$DEB_DIR/lat-test-parallel_1.0-1_all.deb" "$REPO_URL/pool/stable/main/" > "$DEB_DIR/parallel-$i" &
done
wait
PARALLEL="$(cat "$DEB_DIR"/parallel-* | sort | uniq -c | tr -s ' ' | tr '\n' ',')"
check_equals "parallel identical uploads are stored once and accepted" " 7 200, 1 201," "$PARALLEL"

# Conditional requests and indexes by hash, checked again after the next change
INRELEASE_HEADERS="$(curl -sS -D - -o /dev/null -u "lat-reader:$READER_TOKEN" "$REPO_URL/dists/stable/InRelease" | tr -d '\r')"
LAST_MODIFIED="$(grep -i '^Last-Modified:' <<< "$INRELEASE_HEADERS" | cut -d' ' -f2-)"
ETAG="$(grep -i '^ETag:' <<< "$INRELEASE_HEADERS" | cut -d' ' -f2-)"
check_status "unchanged InRelease is not modified since its date" 304 -u "lat-reader:$READER_TOKEN" -H "If-Modified-Since: $LAST_MODIFIED" "$REPO_URL/dists/stable/InRelease"
check_status "an etag list with a weak match is not modified" 304 -u "lat-reader:$READER_TOKEN" -H "If-None-Match: \"other\", W/$ETAG" "$REPO_URL/dists/stable/InRelease"
OLD_HASH="$(curl -sS -u "lat-reader:$READER_TOKEN" "$REPO_URL/dists/stable/Release" | sed -n '/^SHA256:/,/^SHA512:/p' | grep ' main/binary-arm64/Packages$' | awk '{print $1}')"
OLD_BY_HASH="$REPO_URL/dists/stable/main/binary-arm64/by-hash/SHA256/$OLD_HASH"
check_status "the arm64 index is served by hash" 200 -u "lat-reader:$READER_TOKEN" "$OLD_BY_HASH"

# A repository with architecture independent packages only
INDEP_URL="$ARTIFACTORY_URL/debian/debian-indep"
api -o /dev/null -X DELETE "$ARTIFACTORY_URL/api/repositories/debian-indep"
check_status "create debian repository debian-indep" 201 -u "$ADMIN_USER:$ADMIN_PASSWORD" -H 'Content-Type: application/json' \
	-X POST "$ARTIFACTORY_URL/api/repositories" -d '{"name":"debian-indep","type":"debian"}'
build_deb lat-test-indep 1.0-1 all
check_status "upload an architecture independent package" 201 -u "lat-ci:$CI_TOKEN" -T "$DEB_DIR/lat-test-indep_1.0-1_all.deb" "$INDEP_URL/pool/stable/main/"
check_contains "a repository with architecture independent packages only lists amd64" "main/binary-amd64/Packages" "$(curl -sS -u "lat-reader:$READER_TOKEN" "$INDEP_URL/dists/stable/Release")"

APT_CONTAINER=""
if command -v docker > /dev/null && [[ "$(uname -m)" == "x86_64" ]]; then
	cat > "$DEB_DIR/install.sh" <<-EOF
		set -e
		mkdir -p /etc/apt/keyrings
		cp /work/public.key /etc/apt/keyrings/lartifactory.asc
		echo "deb [signed-by=/etc/apt/keyrings/lartifactory.asc] $REPO_URL stable main" > /etc/apt/sources.list.d/lartifactory.list
		echo "deb [signed-by=/etc/apt/keyrings/lartifactory.asc] $INDEP_URL stable main" > /etc/apt/sources.list.d/lartifactory-indep.list
		printf 'machine %s\nlogin lat-reader\npassword %s\nmachine %s\nlogin lat-reader\npassword %s\n' "$REPO_URL" "$READER_TOKEN" "$INDEP_URL" "$READER_TOKEN" > /etc/apt/auth.conf.d/lartifactory.conf
		chmod 600 /etc/apt/auth.conf.d/lartifactory.conf
		apt-get -o Acquire::http::Proxy=false update
	EOF
	APT_CONTAINER="$(docker run -d --rm --network host -v "$DEB_DIR:/work:ro" debian:trixie-slim sleep 900)"
	check "apt updates from the repositories" docker exec "$APT_CONTAINER" bash /work/install.sh
	check "apt installs the package and its dependency from the repository" \
		docker exec "$APT_CONTAINER" bash -c 'apt-get -o Acquire::http::Proxy=false install -y --no-install-recommends lat-test-app && dpkg -s lat-test-lib | grep -q "Version: 1.1.0-1"'
	check "apt installs a package of a repository with architecture independent packages only" \
		docker exec "$APT_CONTAINER" apt-get -o Acquire::http::Proxy=false install -y --no-install-recommends lat-test-indep
fi

check_status "writer can not delete" 403 -u "lat-ci:$CI_TOKEN" -X DELETE "$REPO_URL/pool/stable/main/l/lat-test-app/lat-test-app_2.0.0-1_arm64.deb"
check_status "admin deletes a package file" 204 -H "Authorization: Bearer $ADMIN_TOKEN" -X DELETE "$REPO_URL/pool/stable/main/l/lat-test-app/lat-test-app_2.0.0-1_arm64.deb"
check_not_contains "deleted package is removed from the index" "_arm64.deb" "$(curl -sS -u "lat-reader:$READER_TOKEN" "$REPO_URL/dists/stable/main/binary-arm64/Packages")"
check_status "InRelease is modified since its date after a delete" 200 -u "lat-reader:$READER_TOKEN" -H "If-Modified-Since: $LAST_MODIFIED" "$REPO_URL/dists/stable/InRelease"
check_status "the previous arm64 index is still served by hash" 200 -u "lat-reader:$READER_TOKEN" "$OLD_BY_HASH"
check_status "admin deletes the newest library version with the management api" 204 -u "$ADMIN_USER:$ADMIN_PASSWORD" -X DELETE "$ARTIFACTORY_URL/api/repositories/debian-local/packages?package=lat-test-lib&version=1.1.0-1"
if [[ -n "$APT_CONTAINER" ]]; then
	check "apt sees the deleted version after an update" \
		docker exec "$APT_CONTAINER" bash -c 'apt-get -o Acquire::http::Proxy=false update && apt-cache madison lat-test-lib | tee /dev/stderr | grep -q "1.0.0-1 | http" && ! apt-cache madison lat-test-lib | grep -q "1.1.0-1 | http"'
	docker stop "$APT_CONTAINER" > /dev/null
fi
check_status "admin deletes a package version with the management api" 204 -u "$ADMIN_USER:$ADMIN_PASSWORD" -X DELETE "$ARTIFACTORY_URL/api/repositories/debian-local/packages?package=lat-test-lib&version=1.0.0-1"
check_status "a distribution without packages is gone" 404 -u "lat-reader:$READER_TOKEN" "$REPO_URL/dists/testing/InRelease"

# A redeploy of a version with an epoch replaces the file of the version without epoch, and that version with it
check_status "admin allows redeploys" 200 -u "$ADMIN_USER:$ADMIN_PASSWORD" -H 'Content-Type: application/json' -X PATCH -d '{"allowRedeploy":true}' "$ARTIFACTORY_URL/api/repositories/debian-local"
build_deb lat-test-epoch 1.0 amd64
build_deb lat-test-epoch 1:1.0 amd64
check_status "upload a version without epoch" 201 -u "lat-ci:$CI_TOKEN" -T "$DEB_DIR/lat-test-epoch_1.0_amd64.deb" "$REPO_URL/pool/stable/main/"
check_status "redeploy the same file name with an epoch" 201 -u "lat-ci:$CI_TOKEN" -T "$DEB_DIR/lat-test-epoch_1:1.0_amd64.deb" "$REPO_URL/pool/stable/main/"
EPOCH_PACKAGE="$(curl -sS -u "lat-reader:$READER_TOKEN" "$ARTIFACTORY_URL/api/repositories/debian-local/packages/lat-test-epoch")"
check_contains "the package lists the version with epoch" '"1:1.0"' "$EPOCH_PACKAGE"
check_not_contains "the replaced version without epoch is gone" '"version":"1.0"' "$EPOCH_PACKAGE"
