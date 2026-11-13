import type {Repository} from './types';

export interface Snippet {
	title: string;
	code: string;
}

/**
 * Client configuration snippets for a repository, the token placeholder has to be replaced with an access token.
 */
export function setupSnippets(repository: Repository, username: string): Snippet[] {
	const url = repository.url;
	const host = url.replace(/^https?:/, '');
	const id = repository.name;
	switch (repository.type) {
		case 'maven':
			return [
				{
					title: 'Gradle (build.gradle.kts)',
					code: `repositories {\n\tmaven {\n\t\turl = uri("${url}")\n\t\tcredentials {\n\t\t\tusername = "${username}"\n\t\t\tpassword = "<token>"\n\t\t}\n\t}\n}`,
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
				{title: 'Download', code: `curl -u ${username}:<token> -O ${url}/path/to/file.zip`},
				{title: 'Delete', code: `curl -u ${username}:<token> -X DELETE ${url}/path/to/file.zip`},
			];
		case 'pypi':
			return [
				{title: 'pip install', code: `pip install --index-url ${url.replace('://', `://${username}:<token>@`)}/simple/ my-package`},
				{title: 'pip.conf', code: `[global]\nindex-url = ${url.replace('://', `://${username}:<token>@`)}/simple/`},
				{title: 'twine upload', code: `twine upload --repository-url ${url}/ -u __token__ -p <token> dist/*`},
				{title: '~/.pypirc', code: `[distutils]\nindex-servers = ${id}\n\n[${id}]\nrepository = ${url}/\nusername = __token__\npassword = <token>`},
			];
		case 'npm':
			return [
				{title: '.npmrc', code: `registry=${url}/\n${host}/:_authToken=<token>`},
				{title: '.npmrc (scoped)', code: `@my-scope:registry=${url}/\n${host}/:_authToken=<token>`},
				{title: 'Publish', code: `npm publish --registry ${url}/`},
			];
		case 'nuget':
			return [
				{
					title: 'nuget.config',
					code: `<configuration>\n\t<packageSources>\n\t\t<add key="${id}" value="${url}/v3/index.json" />\n\t</packageSources>\n\t<packageSourceCredentials>\n\t\t<${id}>\n\t\t\t<add key="Username" value="${username}" />\n\t\t\t<add key="ClearTextPassword" value="<token>" />\n\t\t</${id}>\n\t</packageSourceCredentials>\n</configuration>`,
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
			const image = `${ociRegistry(url)}/${id}/my-image`;
			return [
				{title: 'Login', code: `docker login ${ociRegistry(url)} -u ${username}`},
				{title: 'Push', code: `docker tag my-image:1.0 ${image}:1.0\ndocker push ${image}:1.0`},
				{title: 'Pull', code: `docker pull ${image}:1.0`},
				{title: 'Other OCI artifacts (oras)', code: `oras push ${image}:1.0 ./artifact.tar.gz`},
			];
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
			const image = `${ociRegistry(url)}/${id}/${name}`;
			return [
				{title: 'docker pull', code: tag ? `docker pull ${image}:${tag}` : `docker pull ${image}@${version}`},
				{title: 'By digest', code: `docker pull ${image}@${version}`},
				{title: 'Dockerfile', code: `FROM ${image}${tag ? `:${tag}` : `@${version}`}`},
			];
		}
		case 'generic':
			return [];
	}
}
