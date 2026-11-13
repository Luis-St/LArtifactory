import {api, currentAuthorization} from './api';
import {compareVersions, shortDigest} from './format';
import {type FileEntry, type Package, type Repository, REPOSITORY_TYPES, type RepositoryType} from './types';

export interface RepositoryInventory {
	repository: Repository;
	packages: Package[];
	files: FileEntry[];
	/** Set if the packages or files of the repository could not be loaded */
	error?: string;
}

export interface PackageSummary {
	repository: Repository;
	pkg: Package;
	latest: string | undefined;
	updatedAt: string | undefined;
	size: number;
	fileCount: number;
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

/**
 * Loads all readable repositories with their packages and files.
 */
export async function loadInventory(): Promise<RepositoryInventory[]> {
	const repositories = await api.repositories();
	return Promise.all(repositories.map(async repository => {
		try {
			const [packages, files] = await Promise.all([api.packages(repository.name), api.files(repository.name)]);
			return {repository, packages, files};
		} catch (error) {
			return {repository, packages: [], files: [], error: error instanceof Error ? error.message : String(error)};
		}
	}));
}

export function latestVersion(pkg: Package): string | undefined {
	if (pkg.tags.latest) {
		return pkg.tags.latest;
	}
	const versions = pkg.versions.filter(version => !version.yanked).map(version => version.version);
	return versions.sort(compareVersions).at(-1) ?? pkg.versions.map(version => version.version).sort(compareVersions).at(-1);
}

/**
 * The version shown as the latest one, OCI versions are manifest digests so the newest tag is shown instead.
 */
export function latestLabel(repository: Repository, pkg: Package): string | undefined {
	if (repository.type !== 'oci') {
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

function newest(values: (string | undefined)[]): string | undefined {
	return values.reduce<string | undefined>((result, value) => (value && (!result || value > result) ? value : result), undefined);
}

export function summarizePackages(inventory: RepositoryInventory): PackageSummary[] {
	return inventory.packages.map(pkg => {
		const files = inventory.files.filter(file => file.packageName === pkg.name);
		return {
			repository: inventory.repository,
			pkg,
			latest: latestLabel(inventory.repository, pkg),
			updatedAt: newest(pkg.versions.map(version => version.createdAt)),
			size: files.reduce((sum, file) => sum + file.size, 0),
			fileCount: files.length,
		};
	});
}

export function summarizeTypes(inventories: RepositoryInventory[]): TypeSummary[] {
	return REPOSITORY_TYPES.map(type => {
		const matching = inventories.filter(inventory => inventory.repository.type === type);
		const files = matching.flatMap(inventory => inventory.files);
		const packages = matching.flatMap(inventory => inventory.packages);
		return {
			type,
			repositories: matching.length,
			packages: packages.length,
			versions: packages.reduce((sum, pkg) => sum + pkg.versions.length, 0),
			files: files.length,
			size: files.reduce((sum, file) => sum + file.size, 0),
			lastUpload: newest(files.map(file => file.createdAt)),
		};
	});
}

export interface RecentUpload {
	repository: Repository;
	packageName: string;
	version: string;
	createdAt: string;
	createdBy: string | null;
	/** Tags pointing at the version, the readable name of an OCI manifest */
	tags: string[];
}

export function recentUploads(inventories: RepositoryInventory[], limit: number): RecentUpload[] {
	return inventories
		.flatMap(inventory => inventory.packages.flatMap(pkg => pkg.versions.map(version => ({
			repository: inventory.repository,
			packageName: pkg.name,
			version: version.version,
			createdAt: version.createdAt,
			createdBy: version.createdBy,
			tags: Object.entries(pkg.tags).filter(([, target]) => target === version.version).map(([tag]) => tag),
		}))))
		.sort((a, b) => b.createdAt.localeCompare(a.createdAt))
		.slice(0, limit);
}

export interface PackageIndex {
	repositories: Repository[];
	packages: { repository: Repository; pkg: Package }[];
	/** Number of files per generic repository, generic repositories have no packages */
	genericFiles: Record<string, number>;
}

let cachedIndex: { at: number; authorization: string | null; index: Promise<PackageIndex> } | null = null;

/**
 * Loads repositories and their packages for the search and the sidebar counts, kept for a minute so both stay instant.
 */
export function loadPackageIndex(): Promise<PackageIndex> {
	// Signing in or out changes what is visible, the cache only applies to the credentials it was loaded with
	if (!cachedIndex || Date.now() - cachedIndex.at > 60_000 || cachedIndex.authorization !== currentAuthorization()) {
		const index = api.repositories().then(async repositories => {
			const [packages, files] = await Promise.all([
				Promise.all(repositories.map(repository => api.packages(repository.name).then(list => list.map(pkg => ({repository, pkg})), () => []))),
				Promise.all(repositories.filter(repository => repository.type === 'generic').map(repository => api.files(repository.name).then(list => [repository.name, list.length] as const, () => [repository.name, 0] as const))),
			]);
			return {repositories, packages: packages.flat(), genericFiles: Object.fromEntries(files)};
		});
		cachedIndex = {at: Date.now(), authorization: currentAuthorization(), index};
		index.catch(() => (cachedIndex = null));
	}
	return cachedIndex.index;
}

/**
 * Packages per type (files for generic), undefined while loading.
 */
export function typeCounts(index: PackageIndex): Record<RepositoryType, number> {
	return Object.fromEntries(REPOSITORY_TYPES.map(type => [type, type === 'generic'
		? Object.values(index.genericFiles).reduce((sum, count) => sum + count, 0)
		: index.packages.filter(entry => entry.repository.type === type).length])) as Record<RepositoryType, number>;
}
