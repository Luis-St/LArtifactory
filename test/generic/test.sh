#!/usr/bin/env bash
# Generic repository: plain PUT/GET/HEAD/DELETE of files with curl.

fresh_work_dir generic
REPO_URL="$ARTIFACTORY_URL/generic/generic-local"
FILE="$WORK_DIR/generic/hello.txt"
SHA256="$(sha256sum "$FILE" | cut -d' ' -f1)"

check_status "anonymous upload is rejected" 401 -T "$FILE" "$REPO_URL/docs/hello.txt"
check_status "reader can not upload" 403 -u "lat-reader:$READER_TOKEN" -T "$FILE" "$REPO_URL/docs/hello.txt"
check_status "upload with token as password" 201 -u "lat-ci:$CI_TOKEN" -T "$FILE" "$REPO_URL/docs/hello.txt"
check_status "upload with checksum header" 201 -u "lat-ci:$CI_PASSWORD" -H "X-Checksum-Sha256: $SHA256" -T "$FILE" "$REPO_URL/docs/v1/hello.txt"
check_status "upload with wrong checksum header is rejected" 400 -u "lat-ci:$CI_PASSWORD" -H "X-Checksum-Sha256: 0000" -T "$FILE" "$REPO_URL/docs/v2/hello.txt"
check_status "re-upload of identical content is accepted" 201 -u "lat-ci:$CI_TOKEN" -T "$FILE" "$REPO_URL/docs/hello.txt"
check_status "re-upload of different content conflicts" 409 -u "lat-ci:$CI_TOKEN" --data-binary "changed" -X PUT "$REPO_URL/docs/hello.txt"

check_status "anonymous download is rejected" 401 "$REPO_URL/docs/hello.txt"
curl -sS -u "lat-reader:$READER_TOKEN" -o "$WORK_DIR/generic/downloaded.txt" "$REPO_URL/docs/hello.txt"
check "downloaded file matches uploaded file" cmp "$FILE" "$WORK_DIR/generic/downloaded.txt"
check_equals "sha256 checksum file" "$SHA256" "$(curl -sS -u "lat-reader:$READER_TOKEN" "$REPO_URL/docs/hello.txt.sha256")"
check_equals "sha1 checksum file" "$(sha1sum "$FILE" | cut -d' ' -f1)" "$(curl -sS -u "lat-reader:$READER_TOKEN" "$REPO_URL/docs/hello.txt.sha1")"
HEADERS="$(curl -sS -I -u "lat-reader:$READER_TOKEN" "$REPO_URL/docs/hello.txt")"
check_contains "HEAD returns the content length" "Content-Length: $(stat -c %s "$FILE")" "$HEADERS"
check_contains "HEAD returns the sha256 etag" "$SHA256" "$HEADERS"
check_equals "range request returns partial content" "Hello" "$(curl -sS -u "lat-reader:$READER_TOKEN" -r 0-4 "$REPO_URL/docs/hello.txt")"

LISTING="$(curl -sS -u "lat-reader:$READER_TOKEN" "$REPO_URL/docs/")"
check_contains "directory listing shows the file" "hello.txt" "$LISTING"
check_contains "directory listing shows sub directories" "v1/" "$LISTING"
FILES="$(curl -sS -u "lat-reader:$READER_TOKEN" "$ARTIFACTORY_URL/api/repositories/generic-local/files?prefix=docs/")"
check_contains "file api lists the file" "\"path\":\"docs/hello.txt\"" "$FILES"
check_contains "file api lists the uploader" "\"createdBy\":\"lat-ci\"" "$FILES"

check_status "writer can not delete" 403 -u "lat-ci:$CI_TOKEN" -X DELETE "$REPO_URL/docs/hello.txt"
check_status "admin deletes a file" 204 -H "Authorization: Bearer $ADMIN_TOKEN" -X DELETE "$REPO_URL/docs/hello.txt"
check_status "deleted file is gone" 404 -u "lat-reader:$READER_TOKEN" "$REPO_URL/docs/hello.txt"
check_status "admin deletes a directory" 204 -H "Authorization: Bearer $ADMIN_TOKEN" -X DELETE "$REPO_URL/docs"
check_status "deleted directory is gone" 404 -u "lat-reader:$READER_TOKEN" "$REPO_URL/docs/v1/hello.txt"
