import type {Repository} from './types';

export interface Snippet {
	title: string;
	code: string;
}

/** Snippets that publish, upload or delete, a remote or virtual repository is read only and leaves them out. */
const WRITE_SNIPPETS = new Set(['Gradle publishing (build.gradle.kts)', 'Upload', 'Delete', 'twine upload', 'Publish', 'Push', 'Login & publish', 'Other OCI artifacts (oras)']);

/** Snippets that only store credentials, a public repository needs them for publishing only. */
const CREDENTIAL_SNIPPETS = new Set(['Maven (~/.m2/settings.xml)', 'Login', 'Credentials']);

/**
 * Client configuration snippets for a repository, the token placeholder has to be replaced with an access token.
 * A public repository is read without credentials, so its snippets only carry them where they publish, upload or delete.
 * The url comes from the server, it is the root of the host when the repository is mapped to one with ARTIFACTORY_HOST_REPOSITORIES.
 */
export function setupSnippets(repository: Repository, username: string): Snippet[] {
	const snippets = allSetupSnippets(repository, username);
	if (repository.kind === 'LOCAL') {
		return snippets;
	}
	return snippets
		.filter(snippet => !WRITE_SNIPPETS.has(snippet.title))
		// Without publishing, the credentials of a public repository are not needed at all
		.filter(snippet => !(repository.publicRead && CREDENTIAL_SNIPPETS.has(snippet.title)))
		.map(snippet => snippet.title === 'Maven (pom.xml)'
			? {...snippet, code: snippet.code.replace(/\n<distributionManagement>[\s\S]*<\/distributionManagement>/, '')}
			: snippet.title === 'Login & publish' ? {...snippet, code: snippet.code.split('\n')[0]} : snippet);
}

function allSetupSnippets(repository: Repository, username: string): Snippet[] {
	const url = repository.url;
	const host = url.replace(/^https?:/, '');
	const id = repository.name;
	const open = repository.publicRead;
	// The login of read only commands, empty for a public repository
	const curlLogin = open ? '' : ` -u ${username}:<token>`;
	const indexUrl = open ? url : url.replace('://', `://${username}:<token>@`);
	switch (repository.type) {
		case 'maven':
			return [
				{
					title: 'Gradle (build.gradle.kts)',
					code: open
						? `repositories {\n\tmaven {\n\t\turl = uri("${url}")\n\t}\n}`
						: `repositories {\n\tmaven {\n\t\turl = uri("${url}")\n\t\tcredentials {\n\t\t\tusername = "${username}"\n\t\t\tpassword = "<token>"\n\t\t}\n\t}\n}`,
				},
				{
					title: 'Gradle publishing (build.gradle.kts)',
					code: `publishing {\n\trepositories {\n\t\tmaven {\n\t\t\tname = "${id}"\n\t\t\turl = uri("${url}")\n\t\t\tcredentials {\n\t\t\t\tusername = "${username}"\n\t\t\t\tpassword = "<token>"\n\t\t\t}\n\t\t}\n\t}\n}`,
				},
				{
					title: 'Maven (pom.xml)',
					code: `<repositories>\n\t<repository>\n\t\t<id>${id}</id>\n\t\t<url>${url}</url>\n\t</repository>\n</repositories>\n<distributionManagement>\n\t<repository>\n\t\t<id>${id}</id>\n\t\t<url>${url}</url>\n\t</repository>\n</distributionManagement>`,
				},
				{
					title: 'Maven (~/.m2/settings.xml)',
					code: `<servers>\n\t<server>\n\t\t<id>${id}</id>\n\t\t<username>${username}</username>\n\t\t<password><token></password>\n\t</server>\n</servers>`,
				},
			];
		case 'generic':
			return [
				{title: 'Upload', code: `curl -u ${username}:<token> -T ./file.zip ${url}/path/to/file.zip`},
				{title: 'Download', code: `curl${curlLogin} -O ${url}/path/to/file.zip`},
				{title: 'Delete', code: `curl -u ${username}:<token> -X DELETE ${url}/path/to/file.zip`},
			];
		case 'pypi':
			return [
				{title: 'pip install', code: `pip install --index-url ${indexUrl}/simple/ my-package`},
				{title: 'pip.conf', code: `[global]\nindex-url = ${indexUrl}/simple/`},
				{title: 'twine upload', code: `twine upload --repository-url ${url}/ -u __token__ -p <token> dist/*`},
				{title: '~/.pypirc', code: `[distutils]\nindex-servers = ${id}\n\n[${id}]\nrepository = ${url}/\nusername = __token__\npassword = <token>`},
			];
		case 'npm':
			return [
				{title: '.npmrc', code: open ? `registry=${url}/` : `registry=${url}/\n${host}/:_authToken=<token>`},
				{title: '.npmrc (scoped)', code: open ? `@my-scope:registry=${url}/` : `@my-scope:registry=${url}/\n${host}/:_authToken=<token>`},
				// The token of a public repository is not in the .npmrc above, publishing stores it in the user config
				{title: 'Publish', code: open ? `npm config set "${host}/:_authToken" "<token>"\nnpm publish --registry ${url}/` : `npm publish --registry ${url}/`},
			];
		case 'nuget':
			return [
				{
					title: 'nuget.config',
					code: open
						? `<configuration>\n\t<packageSources>\n\t\t<add key="${id}" value="${url}/v3/index.json" />\n\t</packageSources>\n</configuration>`
						: `<configuration>\n\t<packageSources>\n\t\t<add key="${id}" value="${url}/v3/index.json" />\n\t</packageSources>\n\t<packageSourceCredentials>\n\t\t<${id}>\n\t\t\t<add key="Username" value="${username}" />\n\t\t\t<add key="ClearTextPassword" value="<token>" />\n\t\t</${id}>\n\t</packageSourceCredentials>\n</configuration>`,
				},
				{title: 'Push', code: `dotnet nuget push package.nupkg --source ${url}/v3/index.json --api-key <token>`},
			];
		case 'cargo':
			return [
				{title: '.cargo/config.toml', code: `[registries.${id}]\nindex = "sparse+${url}/index/"`},
				{title: 'Login & publish', code: `cargo login --registry ${id} <token>\ncargo publish --registry ${id}`},
				{title: 'Cargo.toml dependency', code: `[dependencies]\nmy-crate = { version = "1.0", registry = "${id}" }`},
			];
		case 'oci': {
			const image = `${ociImage(repository)}/my-image`;
			return [
				{title: 'Login', code: `docker login ${ociRegistry(url)} -u ${username}`},
				{title: 'Push', code: `docker tag my-image:1.0 ${image}:1.0\ndocker push ${image}:1.0`},
				{title: 'Pull', code: `docker pull ${image}:1.0`},
				{title: 'Other OCI artifacts (oras)', code: `oras push ${image}:1.0 ./artifact.tar.gz`},
			];
		}
		case 'debian': {
			// A public repository is read without credentials, apt then needs no auth.conf entry and the key no login
			const snippets: Snippet[] = [
				{title: 'Signing key', code: `sudo mkdir -p /etc/apt/keyrings\ncurl -fsSL${curlLogin} ${url}/public.key | sudo tee /etc/apt/keyrings/${id}.asc > /dev/null`},
				{
					title: 'Source list',
					code: `sudo tee /etc/apt/sources.list.d/${id}.sources > /dev/null <<'EOF'\nTypes: deb\nURIs: ${url}\nSuites: stable\nComponents: main\nSigned-By: /etc/apt/keyrings/${id}.asc\nEOF`,
				},
				{title: 'Source list (one line)', code: `echo "deb [signed-by=/etc/apt/keyrings/${id}.asc] ${url} stable main" | sudo tee /etc/apt/sources.list.d/${id}.list`},
			];
			if (!open) {
				snippets.push({
					title: 'Credentials',
					code: `sudo tee /etc/apt/auth.conf.d/${id}.conf > /dev/null <<'EOF'\nmachine ${url}\nlogin ${username}\npassword <token>\nEOF\nsudo chmod 600 /etc/apt/auth.conf.d/${id}.conf`,
				});
			}
			snippets.push(
				{title: 'Install', code: `sudo apt update\nsudo apt install my-package`},
				{title: 'Upload', code: `curl -u ${username}:<token> -T ./package.deb ${url}/pool/stable/main/`},
			);
			return snippets;
		}
	}
}

/**
 * The registry host of an OCI repository, Docker only talks to /v2/ at the root of a host.
 */
export function ociRegistry(url: string): string {
	return url.replace(/^https?:\/\//, '').replace(/\/oci\/[^/]+$/, '');
}

/**
 * The image name prefix of an OCI repository: the repository is the first path component of the image, except on a host
 * mapped to the repository, where the images are at the root.
 */
export function ociImage(repository: Repository): string {
	const mapped = !/\/oci\/[^/]+$/.test(repository.url);
	return mapped ? ociRegistry(repository.url) : `${ociRegistry(repository.url)}/${repository.name}`;
}

/**
 * Snippets that add one version of a package to a project, the repository itself has to be configured as shown by setupSnippets.
 */
export function installSnippets(repository: Repository, name: string, version: string, tag?: string): Snippet[] {
	const url = repository.url;
	const id = repository.name;
	switch (repository.type) {
		case 'maven': {
			const [group = name, artifact = name] = name.split(':');
			return [
				{title: 'Gradle (Kotlin)', code: `implementation("${group}:${artifact}:${version}")`},
				{title: 'Gradle (Groovy)', code: `implementation '${group}:${artifact}:${version}'`},
				{title: 'Maven', code: `<dependency>\n\t<groupId>${group}</groupId>\n\t<artifactId>${artifact}</artifactId>\n\t<version>${version}</version>\n</dependency>`},
			];
		}
		case 'npm':
			return [
				{title: 'npm', code: `npm install ${name}@${version}`},
				{title: 'pnpm', code: `pnpm add ${name}@${version}`},
				{title: 'yarn', code: `yarn add ${name}@${version}`},
				{title: 'package.json', code: `"${name}": "${version}"`},
			];
		case 'pypi':
			return [
				{title: 'pip', code: `pip install ${name}==${version} --index-url ${url}/simple/`},
				{title: 'requirements.txt', code: `--index-url ${url}/simple/\n${name}==${version}`},
			];
		case 'nuget':
			return [
				{title: '.NET CLI', code: `dotnet add package ${name} --version ${version} --source ${url}/v3/index.json`},
				{title: 'PackageReference', code: `<PackageReference Include="${name}" Version="${version}" />`},
			];
		case 'cargo':
			return [
				{title: 'cargo add', code: `cargo add ${name}@${version} --registry ${id}`},
				{title: 'Cargo.toml', code: `[dependencies]\n${name} = { version = "${version}", registry = "${id}" }`},
			];
		case 'oci': {
			const image = `${ociImage(repository)}/${name}`;
			return [
				{title: 'docker pull', code: tag ? `docker pull ${image}:${tag}` : `docker pull ${image}@${version}`},
				{title: 'By digest', code: `docker pull ${image}@${version}`},
				{title: 'Dockerfile', code: `FROM ${image}${tag ? `:${tag}` : `@${version}`}`},
			];
		}
		case 'debian':
			return [
				{title: 'apt', code: `sudo apt-get update\nsudo apt-get install ${name}=${version}`},
			];
		case 'generic':
			return [];
	}
}
