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

if command -v docker > /dev/null && [[ "$(uname -m)" == "x86_64" ]]; then
	cat > "$DEB_DIR/install.sh" <<-EOF
		set -e
		cp /work/public.key /etc/apt/keyrings/lartifactory.asc
		echo "deb [signed-by=/etc/apt/keyrings/lartifactory.asc] $REPO_URL stable main" > /etc/apt/sources.list.d/lartifactory.list
		printf 'machine %s\nlogin lat-reader\npassword %s\n' "$REPO_URL" "$READER_TOKEN" > /etc/apt/auth.conf.d/lartifactory.conf
		chmod 600 /etc/apt/auth.conf.d/lartifactory.conf
		apt-get -o Acquire::http::Proxy=false update
		apt-get -o Acquire::http::Proxy=false install -y --no-install-recommends lat-test-app
		cat /usr/share/lat-test-app/version.txt /usr/share/lat-test-lib/version.txt
	EOF
	mkdir -p "$DEB_DIR/apt"
	check "apt installs the package and its dependency from the repository" \
		docker run --rm --network host -v "$DEB_DIR:/work:ro" debian:trixie-slim bash -c 'mkdir -p /etc/apt/keyrings && bash /work/install.sh && dpkg -s lat-test-lib | grep -q "Version: 1.1.0-1"'
fi

check_status "writer can not delete" 403 -u "lat-ci:$CI_TOKEN" -X DELETE "$REPO_URL/pool/stable/main/l/lat-test-app/lat-test-app_2.0.0-1_arm64.deb"
check_status "admin deletes a package file" 204 -H "Authorization: Bearer $ADMIN_TOKEN" -X DELETE "$REPO_URL/pool/stable/main/l/lat-test-app/lat-test-app_2.0.0-1_arm64.deb"
check_not_contains "deleted package is removed from the Release" "binary-arm64" "$(curl -sS -u "lat-reader:$READER_TOKEN" "$REPO_URL/dists/stable/Release")"
check_status "admin deletes a package version with the management api" 204 -u "$ADMIN_USER:$ADMIN_PASSWORD" -X DELETE "$ARTIFACTORY_URL/api/repositories/debian-local/packages?package=lat-test-lib&version=1.0.0-1"
check_status "a distribution without packages is gone" 404 -u "lat-reader:$READER_TOKEN" "$REPO_URL/dists/testing/InRelease"
