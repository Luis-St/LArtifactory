#!/usr/bin/env bash
# NuGet repository: pack, push, unlist and restore with the dotnet cli.
# Requires: dotnet sdk 8 or newer

fresh_work_dir nuget
NUGET_DIR="$WORK_DIR/nuget"
FEED="$ARTIFACTORY_URL/nuget/nuget-local"
SOURCE="$FEED/v3/index.json"
export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1 DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1
export NUGET_PACKAGES="$NUGET_DIR/packages"

cat > "$NUGET_DIR/consumer/nuget.config" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<configuration>
	<packageSources>
		<clear />
		<add key="lartifactory" value="$SOURCE" allowInsecureConnections="true" />
	</packageSources>
	<packageSourceCredentials>
		<lartifactory>
			<add key="Username" value="lat-reader" />
			<add key="ClearTextPassword" value="$READER_TOKEN" />
		</lartifactory>
	</packageSourceCredentials>
</configuration>
EOF

# Push
cd "$NUGET_DIR/library" || return
check "dotnet pack 1.0.0" dotnet pack -c Release -o "$NUGET_DIR/out" -p:Version=1.0.0
check "dotnet nuget push 1.0.0" dotnet nuget push "$NUGET_DIR/out/LArtifactory.TestLib.1.0.0.nupkg" --source "$SOURCE" --api-key "$CI_TOKEN"
check_fails "pushing 1.0.0 again is rejected" dotnet nuget push "$NUGET_DIR/out/LArtifactory.TestLib.1.0.0.nupkg" --source "$SOURCE" --api-key "$CI_TOKEN"
check "pushing 1.0.0 again with --skip-duplicate succeeds" dotnet nuget push "$NUGET_DIR/out/LArtifactory.TestLib.1.0.0.nupkg" --source "$SOURCE" --api-key "$CI_TOKEN" --skip-duplicate
check_fails "push with a read only api key is rejected" dotnet nuget push "$NUGET_DIR/out/LArtifactory.TestLib.1.0.0.nupkg" --source "$SOURCE" --api-key "$READER_TOKEN"
sed -i 's/TestLib 1.0.0/TestLib 1.1.0/' Greeter.cs
check "dotnet pack 1.1.0" dotnet pack -c Release -o "$NUGET_DIR/out" -p:Version=1.1.0
check "dotnet nuget push 1.1.0" dotnet nuget push "$NUGET_DIR/out/LArtifactory.TestLib.1.1.0.nupkg" --source "$SOURCE" --api-key "$CI_TOKEN"

# Read
AUTH=(-u "lat-reader:$READER_TOKEN")
check_status "service index is public" 200 "$SOURCE"
check_status "anonymous version list access is rejected" 401 "$FEED/v3-flatcontainer/lartifactory.testlib/index.json"
check_contains "service index lists the package base address" "$FEED/v3-flatcontainer/" "$(curl -sS "${AUTH[@]}" "$SOURCE")"
check_equals "flat container lists both versions" '{"versions":["1.0.0","1.1.0"]}' "$(curl -sS "${AUTH[@]}" "$FEED/v3-flatcontainer/lartifactory.testlib/index.json")"
curl -sS "${AUTH[@]}" -o "$NUGET_DIR/downloaded.nupkg" "$FEED/v3-flatcontainer/lartifactory.testlib/1.0.0/lartifactory.testlib.1.0.0.nupkg"
check "downloaded nupkg matches the pushed nupkg" cmp "$NUGET_DIR/out/LArtifactory.TestLib.1.0.0.nupkg" "$NUGET_DIR/downloaded.nupkg"
check_contains "nuspec is served" "<id>LArtifactory.TestLib</id>" "$(curl -sS "${AUTH[@]}" "$FEED/v3-flatcontainer/lartifactory.testlib/1.0.0/lartifactory.testlib.nuspec")"
REGISTRATION="$(curl -sS "${AUTH[@]}" "$FEED/v3/registration/lartifactory.testlib/index.json")"
check_equals "registration lists both versions" "2" "$(json_get "$REGISTRATION" "data['items'][0]['count']")"
SEARCH="$(curl -sS "${AUTH[@]}" "$FEED/v3/query?q=testlib")"
check_equals "search finds the package" "LArtifactory.TestLib 1.1.0" "$(json_get "$SEARCH" "data['data'][0]['id'] + ' ' + data['data'][0]['version']")"

# Restore
cd "$NUGET_DIR/consumer" || return
check_equals "dotnet run restores and uses 1.0.0" "hello from TestLib 1.0.0" "$(dotnet run -p:TestLibVersion=1.0.0 2>&1 | tail -n 1)"
check_equals "dotnet run restores and uses 1.1.0" "hello from TestLib 1.1.0" "$(dotnet run -p:TestLibVersion=1.1.0 2>&1 | tail -n 1)"
check_contains "dotnet list package shows the resolved version" "1.1.0" "$(dotnet list package -p:TestLibVersion=1.1.0 2>&1)"

# Unlist
check_fails "reader can not unlist" dotnet nuget delete LArtifactory.TestLib 1.1.0 --source "$SOURCE" --api-key "$READER_TOKEN" --non-interactive
check "dotnet nuget delete unlists 1.1.0" dotnet nuget delete LArtifactory.TestLib 1.1.0 --source "$SOURCE" --api-key "$CI_TOKEN" --non-interactive
SEARCH="$(curl -sS "${AUTH[@]}" "$FEED/v3/query?q=testlib")"
check_equals "search shows 1.0.0 after unlisting 1.1.0" "1.0.0" "$(json_get "$SEARCH" "data['data'][0]['version']")"
check_contains "unlisted version can still be restored" "1.1.0" "$(curl -sS "${AUTH[@]}" "$FEED/v3-flatcontainer/lartifactory.testlib/index.json")"
check_status "relist 1.1.0" 200 -X POST -H "X-NuGet-ApiKey: $CI_TOKEN" "$FEED/v3/package/LArtifactory.TestLib/1.1.0"
check_equals "search shows 1.1.0 again" "1.1.0" "$(json_get "$(curl -sS "${AUTH[@]}" "$FEED/v3/query?q=testlib")" "data['data'][0]['version']")"

PACKAGES="$(curl -sS "${AUTH[@]}" "$ARTIFACTORY_URL/api/repositories/nuget-local/packages")"
check_contains "package api lists the package" '"name":"lartifactory.testlib"' "$PACKAGES"

cd "$TEST_DIR" || return
