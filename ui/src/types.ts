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

export interface Repository {
	name: string;
	type: RepositoryType;
	description: string | null;
	publicRead: boolean;
	allowRedeploy: boolean;
	createdAt: string;
	url: string;
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

export interface Token {
	id: string;
	username: string;
	name: string;
	level: AccessLevel;
	createdAt: string;
	expiresAt: string | null;
	token: string | null;
}
