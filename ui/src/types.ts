export const REPOSITORY_TYPES = ['maven', 'generic', 'pypi', 'npm', 'nuget', 'cargo'] as const;
export type RepositoryType = (typeof REPOSITORY_TYPES)[number];

export const ACCESS_LEVELS = ['READ', 'WRITE', 'DELETE'] as const;
export type AccessLevel = (typeof ACCESS_LEVELS)[number];

export interface Principal {
	username: string;
	admin: boolean;
	tokenLevel: AccessLevel | null;
}

export interface Health {
	status: string;
	database: string;
}

export const REPOSITORY_CLASSES = ['LOCAL', 'REMOTE', 'VIRTUAL'] as const;
export type RepositoryClass = (typeof REPOSITORY_CLASSES)[number];

export interface RemoteInfo {
	url: string;
	cacheEnabled: boolean;
	username: string | null;
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
