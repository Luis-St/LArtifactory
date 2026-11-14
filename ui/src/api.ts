import {type AccessLevel, type AccountSecurity, type AuditEntry, type MfaChallenge, type MfaMethod, type PasswordResetInfo, type FileEntry, type Health, type Package, type PackageDetail, type PackageSummary, type Page, type Permission, type Principal, type Repository, type SearchResult, sortRepositories, type Summary, type Token, type Tree, type User, type Webhook, type WebhookDelivery} from './types';

const STORAGE_KEY = 'lartifactory.auth';

interface CreatedSession {
	username: string;
	token: string;
	expiresAt: string;
}

export class ApiError extends Error {
	constructor(readonly status: number, message: string) {
		super(message);
	}
}

let authorization: string | null = readStoredAuthorization();
let unauthorizedListener: (() => void) | null = null;

function readStoredAuthorization(): string | null {
	try {
		return sessionStorage.getItem(STORAGE_KEY) ?? localStorage.getItem(STORAGE_KEY);
	} catch {
		return null;
	}
}

export function basicAuthorization(username: string, secret: string): string {
	const bytes = new TextEncoder().encode(`${username}:${secret}`);
	return 'Basic ' + btoa(String.fromCharCode(...bytes));
}

export function setAuthorization(value: string | null, remember = false): void {
	authorization = value;
	try {
		sessionStorage.removeItem(STORAGE_KEY);
		localStorage.removeItem(STORAGE_KEY);
		if (value) {
			(remember ? localStorage : sessionStorage).setItem(STORAGE_KEY, value);
		}
	} catch {
		// Storage is unavailable (private mode), the credentials only live in memory
	}
}

/** The current credentials, caches use it to never hand data loaded for one user to another. */
export function currentAuthorization(): string | null {
	return authorization;
}

export function hasAuthorization(): boolean {
	return authorization !== null;
}

/** Whether the stored authorization is a web ui session, the ui only ever stores sessions, never passwords or tokens. */
export function hasSession(): boolean {
	return authorization !== null && authorization.startsWith('Bearer las_');
}

export function onUnauthorized(listener: () => void): void {
	unauthorizedListener = listener;
}

function headers(extra?: Record<string, string>): Record<string, string> {
	// X-Requested-With prevents the server from sending a WWW-Authenticate challenge, which would open the browser login dialog
	const result: Record<string, string> = {'X-Requested-With': 'XMLHttpRequest', ...extra};
	if (authorization) {
		result.Authorization = authorization;
	}
	return result;
}

async function errorMessage(response: Response): Promise<string> {
	const text = await response.text().catch(() => '');
	if (!text) {
		return `${response.status} ${response.statusText}`;
	}
	try {
		// Errors of the management api are an ErrorResponse, the package protocols answer plain text
		const json = JSON.parse(text);
		if (json && typeof json.error === 'string') {
			return json.description ? `${json.error}: ${json.description}` : json.error;
		}
	} catch {
		// Plain text error
	}
	return text;
}

async function send(method: string, path: string, body?: unknown, silent = false, extraHeaders?: Record<string, string>): Promise<Response> {
	const response = await fetch(path, {
		method,
		headers: {...headers(body === undefined ? undefined : {'Content-Type': 'application/json'}), ...extraHeaders},
		body: body === undefined ? undefined : JSON.stringify(body),
	});
	if (!response.ok) {
		if (response.status === 401 && !silent && authorization && unauthorizedListener) {
			unauthorizedListener();
		}
		throw new ApiError(response.status, await errorMessage(response));
	}
	return response;
}

async function parse<T>(response: Response): Promise<T> {
	if (response.status === 204) {
		return undefined as T;
	}
	const text = await response.text();
	return (text ? JSON.parse(text) : undefined) as T;
}

async function request<T>(method: string, path: string, body?: unknown, silent = false): Promise<T> {
	return parse<T>(await send(method, path, body, silent));
}

/**
 * Loads one page of a paged list endpoint, the server reports the number of all matching rows in X-Total-Count.
 */
async function requestPage<T>(path: string, params: Record<string, string | number | undefined>): Promise<Page<T>> {
	const response = await send('GET', `${path}${query(Object.fromEntries(Object.entries(params).map(([key, value]) => [key, value?.toString()])))}`);
	const rows = await parse<T[]>(response);
	return {rows, total: Number(response.headers.get('X-Total-Count') ?? rows.length)};
}

/** Sorting and paging of a server side list. */
export interface PageRequest {
	offset: number;
	limit: number;
	sort?: string;
	dir?: 'asc' | 'desc';
}

function query(params: Record<string, string | undefined>): string {
	const search = new URLSearchParams();
	for (const [key, value] of Object.entries(params)) {
		if (value !== undefined && value !== '') {
			search.set(key, value);
		}
	}
	const result = search.toString();
	return result ? `?${result}` : '';
}

const enc = encodeURIComponent;

export const api = {
	health: () => request<Health>('GET', '/health'),
	// Silent: a failed sign in must not be reported as an expired session
	me: () => request<Principal>('GET', '/api/me', undefined, true),
	// Signs in with the password or an access token once, only the returned session token is kept. A session created from a token
	// is restricted to the level of the token and ends when the token is revoked, so the token itself never reaches browser storage.
	// A user with two-factor authentication gets a challenge instead of a session, completeMfa answers it with a code
	createSession: async (username: string, secret: string, remember: boolean) =>
		parse<CreatedSession | MfaChallenge>(await send('POST', '/api/session', {remember}, true, {Authorization: basicAuthorization(username, secret)})),
	completeMfa: (challenge: string, method: MfaMethod, code: string) => request<CreatedSession>('POST', '/api/session/mfa', {challenge, method, code}, true),
	sendMfaEmail: (challenge: string) => request<void>('POST', '/api/session/mfa/email', {challenge}, true),
	
	accountSecurity: () => request<AccountSecurity>('GET', '/api/account/security'),
	requestEmailChange: (email: string, password: string) => request<void>('POST', '/api/account/email', {email, password}),
	verifyEmail: (code: string) => request<AccountSecurity>('POST', '/api/account/email/verify', {code}),
	removeEmail: (password: string) => request<AccountSecurity>('POST', '/api/account/email/remove', {password}),
	setupTotp: (password: string) => request<{ secret: string; uri: string }>('POST', '/api/account/mfa/totp/setup', {password}),
	enableTotp: (code: string) => request<{ recoveryCodes: string[] | null }>('POST', '/api/account/mfa/totp/enable', {code}),
	enableEmailMfa: (password: string) => request<{ recoveryCodes: string[] | null }>('POST', '/api/account/mfa/email/enable', {password}),
	disableMfa: (method: 'totp' | 'email', password: string) => request<AccountSecurity>('POST', `/api/account/mfa/${method}/disable`, {password}),
	regenerateRecoveryCodes: (password: string) => request<{ recoveryCodes: string[] }>('POST', '/api/account/mfa/recovery-codes', {password}),
	
	passwordResetAvailable: () => request<PasswordResetInfo>('GET', '/api/password-reset', undefined, true),
	requestPasswordReset: (login: string) => request<void>('POST', '/api/password-reset', {login}, true),
	checkPasswordReset: (token: string) => request<PasswordResetInfo>('POST', '/api/password-reset/check', {token}, true),
	completePasswordReset: (token: string, password: string, code?: string) => request<void>('POST', '/api/password-reset/complete', {token, password, code}, true),
	deleteSession: () => request<void>('DELETE', '/api/session', undefined, true),
	
	// Sorted by type in the order of REPOSITORY_TYPES, then by name, so every page lists them the same way
	repositories: () => request<Repository[]>('GET', '/api/repositories').then(sortRepositories),
	repository: (name: string) => request<Repository>('GET', `/api/repositories/${enc(name)}`),
	createRepository: (body: {
		name: string;
		type: string;
		description?: string;
		publicRead: boolean;
		allowRedeploy: boolean;
		remote?: { url: string; cacheEnabled: boolean; username?: string; password?: string };
		members?: string[];
	}) => request<Repository>('POST', '/api/repositories', body),
	updateRepository: (name: string, body: {
		description?: string;
		publicRead?: boolean;
		allowRedeploy?: boolean;
		remote?: { url: string; cacheEnabled: boolean; username?: string; password?: string };
		members?: string[];
	}) => request<Repository>('PATCH', `/api/repositories/${enc(name)}`, body),
	deleteRepository: (name: string) => request<void>('DELETE', `/api/repositories/${enc(name)}`),
	packages: (name: string, page: PageRequest, q?: string) => requestPage<Package>(`/api/repositories/${enc(name)}/packages`, {q, offset: page.offset, limit: page.limit}),
	pkg: (name: string, pkg: string) => request<PackageDetail>('GET', `/api/repositories/${enc(name)}/packages/${enc(pkg)}`),
	tree: (name: string, prefix: string) => request<Tree>('GET', `/api/repositories/${enc(name)}/tree${query({prefix})}`),
	deletePackage: (name: string, pkg: string, version?: string) =>
		request<void>('DELETE', `/api/repositories/${enc(name)}/packages${query({package: pkg, version})}`),
	summary: (repository?: string) => request<Summary>('GET', `/api/summary${query({repository})}`),
	allPackages: (filter: { type?: string; repository?: string; q?: string }, page: PageRequest) => requestPage<PackageSummary>('/api/packages', {...filter, ...page}),
	allFiles: (filter: { type?: string; repository?: string; q?: string }, page: PageRequest) => requestPage<FileEntry>('/api/files', {...filter, ...page}),
	search: (q: string, limit = 8) => request<SearchResult[]>('GET', `/api/search${query({q, limit: String(limit)})}`),
	
	users: () => request<User[]>('GET', '/api/users'),
	createUser: (body: { username: string; password: string; admin: boolean }) => request<User>('POST', '/api/users', body),
	updateUser: (username: string, body: { password?: string; currentPassword?: string; admin?: boolean; revokeTokens?: boolean }) => request<User>('PATCH', `/api/users/${enc(username)}`, body),
	deleteUser: (username: string) => request<void>('DELETE', `/api/users/${enc(username)}`),
	resetUserMfa: (username: string) => request<void>('DELETE', `/api/users/${enc(username)}/mfa`),
	
	permissions: (filter: { repository?: string; username?: string } = {}) => request<Permission[]>('GET', `/api/permissions${query(filter)}`),
	setPermission: (body: { repository: string; username: string; level: AccessLevel }) => request<Permission>('PUT', '/api/permissions', body),
	deletePermission: (repository: string, username: string) => request<void>('DELETE', `/api/permissions${query({repository, username})}`),
	
	tokens: (username?: string) => request<Token[]>('GET', `/api/tokens${query({username})}`),
	createToken: (body: { name: string; username?: string; level: AccessLevel; expiresInDays?: number; allowedIps?: string; rateLimitPerMinute?: number }) => request<Token>('POST', '/api/tokens', body),
	deleteToken: (id: string) => request<void>('DELETE', `/api/tokens/${enc(id)}`),
	
	audit: (filter: { repository?: string; actor?: string; action?: string; from?: string; to?: string }, page: { offset: number; limit: number }) =>
		requestPage<AuditEntry>('/api/audit', {...filter, ...page}),
	
	webhooks: (repository?: string) => request<Webhook[]>('GET', `/api/webhooks${query({repository})}`),
	createWebhook: (body: { repository?: string; url: string; secret?: string; events: string[]; active: boolean }) => request<Webhook>('POST', '/api/webhooks', body),
	updateWebhook: (id: string, body: { url?: string; events?: string[]; active?: boolean; repository?: string; secret?: string }) => request<Webhook>('PATCH', `/api/webhooks/${enc(id)}`, body),
	deleteWebhook: (id: string) => request<void>('DELETE', `/api/webhooks/${enc(id)}`),
	testWebhook: (id: string) => request<WebhookDelivery>('POST', `/api/webhooks/${enc(id)}/test`),
	webhookDeliveries: (id: string) => request<WebhookDelivery[]>('GET', `/api/webhooks/${enc(id)}/deliveries`),
};

/**
 * Maps the stored path of a file to the url path the format handler of the repository serves it at.
 */
function downloadPath(repository: Pick<Repository, 'type'>, file: FileEntry): string {
	const encodePath = (path: string) => path.split('/').map(enc).join('/');
	switch (repository.type) {
		case 'npm': {
			// Stored as {name}/-/{tarball}, scoped names are sent with an encoded slash
			const index = file.path.lastIndexOf('/-/');
			return index < 0 ? encodePath(file.path) : `${enc(file.path.substring(0, index))}/-/${enc(file.path.substring(index + 3))}`;
		}
		case 'nuget':
			return `v3-flatcontainer/${encodePath(file.path)}`;
		case 'cargo':
			return file.packageName && file.version ? `api/v1/crates/${enc(file.packageName)}/${enc(file.version)}/download` : encodePath(file.path);
		case 'oci': {
			// Blobs are stored as blobs/sha256/{hex} and shared by all images, manifests as {image}/manifests/sha256/{hex}
			const blob = /^blobs\/sha256\/([a-f0-9]{64})$/.exec(file.path);
			if (blob) {
				return `v2/blob/blobs/sha256:${blob[1]}`;
			}
			const manifest = /^(.+)\/manifests\/sha256\/([a-f0-9]{64})$/.exec(file.path);
			return manifest ? `v2/${manifest[1]}/manifests/sha256:${manifest[2]}` : encodePath(file.path);
		}
		default:
			return encodePath(file.path);
	}
}

/**
 * The url path of a file of a repository.
 */
export function filePath(repository: Pick<Repository, 'type' | 'name'>, file: FileEntry): string {
	return `/${repository.type}/${enc(repository.name)}/${downloadPath(repository, file)}`;
}

/**
 * Downloads a file of a repository. A plain link carries no authorization header, so a signed in user first gets a single use ticket
 * for the path, the browser then streams the download to disk instead of buffering the whole file in memory.
 */
export async function downloadFile(repository: Pick<Repository, 'type' | 'name'>, file: FileEntry): Promise<void> {
	const path = filePath(repository, file);
	const url = authorization ? (await request<{ url: string }>('POST', '/api/downloads', {path})).url : path;
	const link = document.createElement('a');
	link.href = url;
	link.download = file.path.substring(file.path.lastIndexOf('/') + 1);
	document.body.appendChild(link);
	link.click();
	link.remove();
}

/**
 * Deletes a stored file, used for the files of generic repositories and the cached files of remote repositories.
 */
export async function deleteFile(repository: Pick<Repository, 'type' | 'name'>, file: FileEntry): Promise<void> {
	await send('DELETE', filePath(repository, file));
}
