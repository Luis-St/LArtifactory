/** The order types are listed in everywhere in the ui */
export const REPOSITORY_TYPES = ['generic', 'maven', 'nuget', 'cargo', 'npm', 'pypi', 'oci'] as const;
export type RepositoryType = (typeof REPOSITORY_TYPES)[number];

/**
 * The types shown in the navigation and the type tabs. Signed in users see every type, a guest only sees the types
 * of the public repositories, the others would always be empty for them.
 */
export function visibleTypes(repositories: { type: RepositoryType }[] | undefined, guest: boolean): RepositoryType[] {
	if (!guest) {
		return [...REPOSITORY_TYPES];
	}
	return REPOSITORY_TYPES.filter(type => (repositories ?? []).some(repository => repository.type === type));
}

/**
 * Orders repositories by type (in the order of REPOSITORY_TYPES), then by name.
 */
export function sortRepositories<T extends { name: string; type: RepositoryType }>(repositories: T[]): T[] {
	return [...repositories].sort((a, b) => REPOSITORY_TYPES.indexOf(a.type) - REPOSITORY_TYPES.indexOf(b.type) || a.name.localeCompare(b.name));
}

/** npm and Cargo forbid overwriting a published version, the server rejects allowRedeploy for them. */
export const IMMUTABLE_TYPES: RepositoryType[] = ['npm', 'cargo'];

export const ACCESS_LEVELS = ['READ', 'WRITE', 'DELETE'] as const;
export type AccessLevel = (typeof ACCESS_LEVELS)[number];

export interface Principal {
	username: string;
	/** Whether the user is an administrator, also true when signed in with a token, which does not grant administration. */
	admin: boolean;
	tokenLevel: AccessLevel | null;
}

/**
 * Whether the principal may use the administration. The server denies it to tokens and token sessions, even of an administrator,
 * so the ui only offers it when signed in with a password.
 */
export function canAdminister(principal: Principal | null | undefined): boolean {
	return principal?.admin === true && principal.tokenLevel === null;
}

/** Whether an access level includes another one, each level includes the lower ones. */
export function includesLevel(level: AccessLevel | null | undefined, required: AccessLevel): boolean {
	return level != null && ACCESS_LEVELS.indexOf(level) >= ACCESS_LEVELS.indexOf(required);
}

export interface Health {
	status: string;
	database: string;
	/** Whether the server serves the OpenAPI spec and the Swagger ui. */
	apiDocs: boolean;
}

export const REPOSITORY_CLASSES = ['LOCAL', 'REMOTE', 'VIRTUAL'] as const;
export type RepositoryClass = (typeof REPOSITORY_CLASSES)[number];

export interface RemoteInfo {
	url: string;
	cacheEnabled: boolean;
	username: string | null;
	hasPassword: boolean;
}

export interface Repository {
	name: string;
	type: RepositoryType;
	description: string | null;
	publicRead: boolean;
	allowRedeploy: boolean;
	createdAt: string;
	url: string;
	kind: RepositoryClass;
	remote: RemoteInfo | null;
	members: string[];
	/** The effective access level of the caller, null for an anonymous reader of a public repository. */
	access: AccessLevel | null;
}

export interface PackageVersion {
	version: string;
	yanked: boolean;
	createdAt: string;
	createdBy: string | null;
}

export interface Package {
	name: string;
	versions: PackageVersion[];
	tags: Record<string, string>;
}

export interface FileEntry {
	repository: string;
	path: string;
	packageName: string | null;
	version: string | null;
	size: number;
	sha256: string;
	sha1: string;
	md5: string;
	contentType: string;
	createdAt: string;
	createdBy: string | null;
}

export interface User {
	username: string;
	admin: boolean;
	createdAt: string;
}

export interface Permission {
	repository: string;
	username: string;
	level: AccessLevel;
}

export const WEBHOOK_EVENTS = ['artifact.published', 'artifact.deleted', 'package.deleted', 'repository.created', 'repository.deleted'] as const;
export type WebhookEvent = (typeof WEBHOOK_EVENTS)[number];

export interface Webhook {
	id: string;
	repository: string | null;
	url: string;
	hasSecret: boolean;
	events: string[];
	active: boolean;
	createdAt: string;
	createdBy: string | null;
}

export interface WebhookDelivery {
	id: string;
	event: string;
	repository: string | null;
	success: boolean;
	statusCode: number;
	error: string | null;
	createdAt: string;
}

export interface AuditEntry {
	id: string;
	timestamp: string;
	actor: string | null;
	action: string;
	repository: string | null;
	target: string | null;
	ip: string | null;
	detail: string | null;
}

export interface Token {
	id: string;
	username: string;
	name: string;
	level: AccessLevel;
	createdAt: string;
	expiresAt: string | null;
	allowedIps: string | null;
	rateLimit: number;
	lastUsedAt: string | null;
	lastUsedIp: string | null;
	token: string | null;
}

export interface PackageDetail {
	name: string;
	versions: PackageVersion[];
	tags: Record<string, string>;
	files: FileEntry[];
}

/** A package of the cross repository package list, sorted and paged by the server. */
export interface PackageSummary {
	repository: string;
	type: RepositoryType;
	name: string;
	versions: PackageVersion[];
	tags: Record<string, string>;
	files: number;
	size: number;
	updatedAt: string | null;
}

export interface Tree {
	prefix: string;
	folders: string[];
	files: FileEntry[];
}

export interface SearchResult {
	repository: string;
	type: RepositoryType;
	name: string;
}

export interface RepositorySummary {
	name: string;
	type: RepositoryType;
	kind: RepositoryClass;
	description: string | null;
	publicRead: boolean;
	packages: number;
	versions: number;
	files: number;
	size: number;
	lastUpload: string | null;
}

export interface RecentVersion {
	repository: string;
	type: RepositoryType;
	packageName: string;
	version: string;
	createdAt: string;
	createdBy: string | null;
	tags: string[];
}

export interface ActivityBucket {
	/** Start of the hour (UTC) */
	hour: string;
	counts: Partial<Record<RepositoryType, number>>;
}

export interface Summary {
	repositories: RepositorySummary[];
	recent: RecentVersion[];
	activity: ActivityBucket[];
}

/** One page of a list the server sorts and pages, total is the number of all matching rows. */
export interface Page<T> {
	rows: T[];
	total: number;
}
