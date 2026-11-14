import {api, currentAuthorization} from './api';
import {compareVersions, shortDigest} from './format';
import {type RepositoryType, REPOSITORY_TYPES, type Summary} from './types';

/** The parts of a package the latest version is derived from. */
interface Versioned {
	versions: { version: string; yanked: boolean; createdAt: string }[];
	tags: Record<string, string>;
}

export interface TypeSummary {
	type: RepositoryType;
	repositories: number;
	packages: number;
	versions: number;
	files: number;
	size: number;
	lastUpload: string | undefined;
}

export function latestVersion(pkg: Versioned): string | undefined {
	if (pkg.tags.latest) {
		return pkg.tags.latest;
	}
	const versions = pkg.versions.filter(version => !version.yanked).map(version => version.version);
	return versions.sort(compareVersions).at(-1) ?? pkg.versions.map(version => version.version).sort(compareVersions).at(-1);
}

/**
 * The version shown as the latest one, OCI versions are manifest digests so the newest tag is shown instead.
 */
export function latestLabel(type: RepositoryType, pkg: Versioned): string | undefined {
	if (type !== 'oci') {
		return latestVersion(pkg);
	}
	const tags = Object.keys(pkg.tags);
	if (tags.includes('latest')) {
		return 'latest';
	}
	const created = new Map(pkg.versions.map(version => [version.version, version.createdAt]));
	const newestTag = tags.sort((a, b) => (created.get(pkg.tags[b]) ?? '').localeCompare(created.get(pkg.tags[a]) ?? '') || compareVersions(b, a))[0];
	const latest = latestVersion(pkg);
	return newestTag ?? (latest ? shortDigest(latest) : undefined);
}

function newest(values: (string | null | undefined)[]): string | undefined {
	return values.reduce<string | undefined>((result, value) => (value && (!result || value > result) ? value : result), undefined);
}

/**
 * Counts and sizes per type, aggregated from the per repository counts of the server.
 */
export function summarizeTypes(summary: Summary): TypeSummary[] {
	return REPOSITORY_TYPES.map(type => {
		const matching = summary.repositories.filter(repository => repository.type === type);
		return {
			type,
			repositories: matching.length,
			packages: matching.reduce((sum, repository) => sum + repository.packages, 0),
			versions: matching.reduce((sum, repository) => sum + repository.versions, 0),
			files: matching.reduce((sum, repository) => sum + repository.files, 0),
			size: matching.reduce((sum, repository) => sum + repository.size, 0),
			lastUpload: newest(matching.map(repository => repository.lastUpload)),
		};
	});
}

let cachedSummary: { at: number; authorization: string | null; summary: Promise<Summary> } | null = null;

/**
 * Loads the summary of all readable repositories, kept for a minute so the sidebar counts stay instant on every page change.
 */
export function loadSummary(force = false): Promise<Summary> {
	// Signing in or out changes what is visible, the cache only applies to the credentials it was loaded with
	if (force || !cachedSummary || Date.now() - cachedSummary.at > 60_000 || cachedSummary.authorization !== currentAuthorization()) {
		const summary = api.summary();
		cachedSummary = {at: Date.now(), authorization: currentAuthorization(), summary};
		summary.catch(() => (cachedSummary = null));
	}
	return cachedSummary.summary;
}

/**
 * Packages per type (files for generic) for the sidebar.
 */
export function typeCounts(summary: Summary): Record<RepositoryType, number> {
	return Object.fromEntries(summarizeTypes(summary).map(type => [type.type, type.type === 'generic' ? type.files : type.packages])) as Record<RepositoryType, number>;
}
