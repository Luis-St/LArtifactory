import type { Repository } from './types';

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
				{ title: 'Upload', code: `curl -u ${username}:<token> -T ./file.zip ${url}/path/to/file.zip` },
				{ title: 'Download', code: `curl -u ${username}:<token> -O ${url}/path/to/file.zip` },
				{ title: 'Delete', code: `curl -u ${username}:<token> -X DELETE ${url}/path/to/file.zip` },
			];
		case 'pypi':
			return [
				{ title: 'pip install', code: `pip install --index-url ${url.replace('://', `://${username}:<token>@`)}/simple/ my-package` },
				{ title: 'pip.conf', code: `[global]\nindex-url = ${url.replace('://', `://${username}:<token>@`)}/simple/` },
				{ title: 'twine upload', code: `twine upload --repository-url ${url}/ -u __token__ -p <token> dist/*` },
				{ title: '~/.pypirc', code: `[distutils]\nindex-servers = ${id}\n\n[${id}]\nrepository = ${url}/\nusername = __token__\npassword = <token>` },
			];
		case 'npm':
			return [
				{ title: '.npmrc', code: `registry=${url}/\n${host}/:_authToken=<token>` },
				{ title: '.npmrc (scoped)', code: `@my-scope:registry=${url}/\n${host}/:_authToken=<token>` },
				{ title: 'Publish', code: `npm publish --registry ${url}/` },
			];
		case 'nuget':
			return [
				{
					title: 'nuget.config',
					code: `<configuration>\n\t<packageSources>\n\t\t<add key="${id}" value="${url}/v3/index.json" />\n\t</packageSources>\n\t<packageSourceCredentials>\n\t\t<${id}>\n\t\t\t<add key="Username" value="${username}" />\n\t\t\t<add key="ClearTextPassword" value="<token>" />\n\t\t</${id}>\n\t</packageSourceCredentials>\n</configuration>`,
				},
				{ title: 'Push', code: `dotnet nuget push package.nupkg --source ${url}/v3/index.json --api-key <token>` },
			];
		case 'cargo':
			return [
				{ title: '.cargo/config.toml', code: `[registries.${id}]\nindex = "sparse+${url}/index/"` },
				{ title: 'Login & publish', code: `cargo login --registry ${id} <token>\ncargo publish --registry ${id}` },
				{ title: 'Cargo.toml dependency', code: `[dependencies]\nmy-crate = { version = "1.0", registry = "${id}" }` },
			];
	}
}
