import type {AccessLevel, FileEntry, Health, Package, Permission, Principal, Repository, Token, User} from './types';

const STORAGE_KEY = 'lartifactory.auth';

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

export function hasAuthorization(): boolean {
	return authorization !== null;
}

/** Whether the stored authorization is a web ui session (created from a password) rather than an access token. */
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
 * Loads every page of a paged list endpoint, the server limits the size of a single page and reports the total in X-Total-Count.
 */
async function requestAll<T>(path: string, params: Record<string, string | undefined>, pageSize: number): Promise<T[]> {
	const result: T[] = [];
	for (; ;) {
		const response = await send('GET', `${path}${query({...params, offset: String(result.length), limit: String(pageSize)})}`);
		const page = await parse<T[]>(response);
		result.push(...page);
		const total = Number(response.headers.get('X-Total-Count') ?? result.length);
		if (page.length === 0 || result.length >= total) {
			return result;
		}
	}
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
	// Signs in with the password once, only the returned session token is kept
	createSession: async (username: string, password: string, remember: boolean) =>
		parse<{ username: string; token: string; expiresAt: string }>(await send('POST', '/api/session', {remember}, true, {Authorization: basicAuthorization(username, password)})),
	deleteSession: () => request<void>('DELETE', '/api/session', undefined, true),
	
	repositories: () => request<Repository[]>('GET', '/api/repositories'),
	repository: (name: string) => request<Repository>('GET', `/api/repositories/${enc(name)}`),
	createRepository: (body: { name: string; type: string; description?: string; publicRead: boolean; allowRedeploy: boolean }) =>
		request<Repository>('POST', '/api/repositories', body),
	updateRepository: (name: string, body: { description?: string; publicRead?: boolean; allowRedeploy?: boolean }) =>
		request<Repository>('PATCH', `/api/repositories/${enc(name)}`, body),
	deleteRepository: (name: string) => request<void>('DELETE', `/api/repositories/${enc(name)}`),
	packages: (name: string, q?: string) => requestAll<Package>(`/api/repositories/${enc(name)}/packages`, {q}, 500),
	deletePackage: (name: string, pkg: string, version?: string) =>
		request<void>('DELETE', `/api/repositories/${enc(name)}/packages${query({package: pkg, version})}`),
	files: (name: string, prefix?: string) => requestAll<FileEntry>(`/api/repositories/${enc(name)}/files`, {prefix}, 5000),
	
	users: () => request<User[]>('GET', '/api/users'),
	createUser: (body: { username: string; password: string; admin: boolean }) => request<User>('POST', '/api/users', body),
	updateUser: (username: string, body: { password?: string; currentPassword?: string; admin?: boolean }) => request<User>('PATCH', `/api/users/${enc(username)}`, body),
	deleteUser: (username: string) => request<void>('DELETE', `/api/users/${enc(username)}`),
	
	permissions: (filter: { repository?: string; username?: string } = {}) => request<Permission[]>('GET', `/api/permissions${query(filter)}`),
	setPermission: (body: { repository: string; username: string; level: AccessLevel }) => request<Permission>('PUT', '/api/permissions', body),
	deletePermission: (repository: string, username: string) => request<void>('DELETE', `/api/permissions${query({repository, username})}`),
	
	tokens: (username?: string) => request<Token[]>('GET', `/api/tokens${query({username})}`),
	createToken: (body: { name: string; username?: string; level: AccessLevel; expiresInDays?: number }) => request<Token>('POST', '/api/tokens', body),
	deleteToken: (id: string) => request<void>('DELETE', `/api/tokens/${enc(id)}`),
};

/**
 * Maps the stored path of a file to the url path the format handler of the repository serves it at.
 */
function downloadPath(repository: Repository, file: FileEntry): string {
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
		default:
			return encodePath(file.path);
	}
}

/**
 * Downloads a file of a repository with the current credentials, plain links would not carry the authorization header.
 */
export async function downloadFile(repository: Repository, file: FileEntry): Promise<void> {
	const response = await fetch(`/${repository.type}/${enc(repository.name)}/${downloadPath(repository, file)}`, {headers: headers()});
	if (!response.ok) {
		throw new ApiError(response.status, await errorMessage(response));
	}
	const url = URL.createObjectURL(await response.blob());
	const link = document.createElement('a');
	link.href = url;
	link.download = file.path.substring(file.path.lastIndexOf('/') + 1);
	document.body.appendChild(link);
	link.click();
	link.remove();
	setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
