import {type FormEvent, useState} from 'react';
import {api} from '../api';
import {Icon} from '../components/icons';
import {Avatar, Badge, Button, ConfirmDialog, Empty, ErrorBox, errorText, Field, Menu, Modal, PageHeader, SearchField, Spinner, Toggle} from '../components/ui';
import {formatDate} from '../format';
import {useAsync} from '../hooks';
import {href, navigate} from '../router';
import {useSession} from '../session';
import type {AccessLevel, User} from '../types';
import {SortHeader, useSort} from '../components/sort';

export function Users() {
	const {principal, notify} = useSession();
	const users = useAsync(() => api.users(), []);
	const permissions = useAsync(() => api.permissions(), []);
	const [search, setSearch] = useState('');
	const [creating, setCreating] = useState(false);
	const [editing, setEditing] = useState<User | null>(null);
	const [deleting, setDeleting] = useState<User | null>(null);
	const [resettingMfa, setResettingMfa] = useState<User | null>(null);
	const [expanded, setExpanded] = useState<Set<string>>(new Set());
	const toggleExpanded = (username: string) => setExpanded(current => {
		const next = new Set(current);
		if (!next.delete(username)) {
			next.add(username);
		}
		return next;
	});
	
	const toggleAdmin = async (user: User) => {
		try {
			await api.updateUser(user.username, {admin: !user.admin});
			notify(user.admin ? `${user.username} is no longer an administrator` : `${user.username} is now an administrator`);
			users.reload();
		} catch (e) {
			notify(errorText(e), 'error');
		}
	};
	
	const query = search.trim().toLowerCase();
	const rows = [...(users.data ?? [])].filter(user => !query || user.username.toLowerCase().includes(query)).sort((a, b) => a.username.localeCompare(b.username));
	const {sorted, sort, toggle} = useSort<User, 'user' | 'role' | 'permissions' | 'created'>(rows, {
		user: user => user.username,
		role: user => (user.admin ? 0 : 1),
		permissions: user => (user.admin ? Infinity : (permissions.data ?? []).filter(permission => permission.username === user.username).length),
		created: user => user.createdAt,
	});
	
	return (
		<>
			<PageHeader title="Users" subtitle="Users sign in with a password and create access tokens for their clients."
						actions={<Button variant="primary" onClick={() => setCreating(true)}><Icon name="plus" size={16}/>New user</Button>}/>
			{users.data && users.data.length > 5 && (
				<div className="toolbar">
					<SearchField placeholder="Search users…" value={search} onChange={setSearch}/>
				</div>
			)}
			{users.loading && !users.data && <Spinner/>}
			{users.error && <ErrorBox error={users.error} onRetry={users.reload}/>}
			{users.data && (rows.length === 0 ? <Empty title={users.data.length === 0 ? 'No users' : 'No matching users'}/> : (
				<div className="table-wrap">
					<table className="table">
						<thead>
						<tr>
							<SortHeader id="user" sort={sort} onSort={toggle}>User</SortHeader>
							<SortHeader id="role" sort={sort} onSort={toggle}>Role</SortHeader>
							<SortHeader id="permissions" sort={sort} onSort={toggle}>Permissions</SortHeader>
							<SortHeader id="created" sort={sort} onSort={toggle}>Created</SortHeader>
							<th/>
						</tr>
						</thead>
						<tbody>
						{sorted.map(user => {
							const granted = (permissions.data ?? []).filter(permission => permission.username === user.username)
								.sort((a, b) => (a.repository === '*' ? -1 : b.repository === '*' ? 1 : a.repository.localeCompare(b.repository)));
							const self = user.username === principal?.username;
							return (
								<tr key={user.username}>
									<td>
										<span className="cell-inline">
											<Avatar name={user.username} size="small"/>
											<strong>{user.username}</strong>
											{self && <Badge>you</Badge>}
											{user.mfaEnabled && <Badge tone="success" title="Signs in with a second factor">2FA</Badge>}
											{user.email && <span className="muted small">{user.email}</span>}
										</span>
									</td>
									<td className="shrink">{user.admin ? <Badge tone="accent">Administrator</Badge> : <Badge>User</Badge>}</td>
									<td>
										{user.admin ? <span className="muted">Full access</span> : granted.length === 0 ? <span className="faint">No access</span> : (
											<span className="permission-badges">
												{(expanded.has(user.username) ? granted : granted.slice(0, 3)).map(permission => (
													<a key={permission.repository} href={`${href('permissions')}?username=${encodeURIComponent(user.username)}`} title="Manage permissions">
														<Badge tone={levelTone(permission.level)}>
															{permission.repository === '*' ? 'All repositories' : permission.repository} · {permission.level}
														</Badge>
													</a>
												))}
												{granted.length > 3 && (
													<button type="button" className="link small" aria-expanded={expanded.has(user.username)} onClick={() => toggleExpanded(user.username)}>
														{expanded.has(user.username) ? 'Show less' : `+${granted.length - 3} more`}
													</button>
												)}
											</span>
										)}
									</td>
									<td className="muted nowrap">{formatDate(user.createdAt)}</td>
									<td className="actions">
										<Menu label={`Actions for ${user.username}`} items={[
											{label: self ? 'Change password' : 'Set password', icon: 'tokens', onSelect: () => setEditing(user)},
											{label: 'Manage permissions', icon: 'permissions', onSelect: () => navigate(`${href('permissions')}?username=${encodeURIComponent(user.username)}`), hidden: user.admin},
											{label: user.admin ? 'Revoke administrator' : 'Make administrator', icon: 'users', onSelect: () => toggleAdmin(user), hidden: self},
											{label: 'Reset two-factor', icon: 'permissions', onSelect: () => setResettingMfa(user), hidden: self || !user.mfaEnabled},
											{label: 'Delete user', icon: 'trash', onSelect: () => setDeleting(user), danger: true, hidden: self},
										]}/>
									</td>
								</tr>
							);
						})}
						</tbody>
					</table>
				</div>
			))}
			
			{creating && <CreateUserDialog onClose={() => setCreating(false)} onCreated={() => {
				setCreating(false);
				users.reload();
			}}/>}
			{editing && <PasswordDialog username={editing.username} onClose={() => setEditing(null)}/>}
			{resettingMfa && (
				<ConfirmDialog
					title="Reset two-factor authentication"
					message={<>Turn off every second factor of <strong>{resettingMfa.username}</strong> and remove the recovery codes? Only do this after confirming who asks, the password alone then signs in again.</>}
					confirmLabel="Reset"
					onConfirm={async () => {
						await api.resetUserMfa(resettingMfa.username);
						notify(`Two-factor authentication of ${resettingMfa.username} turned off`);
						users.reload();
					}}
					onClose={() => setResettingMfa(null)}
				/>
			)}
			{deleting && (
				<ConfirmDialog
					title="Delete user"
					message={<>Delete <strong>{deleting.username}</strong> with all tokens and permissions?</>}
					onConfirm={async () => {
						await api.deleteUser(deleting.username);
						notify(`User ${deleting.username} deleted`);
						users.reload();
						permissions.reload();
					}}
					onClose={() => setDeleting(null)}
				/>
			)}
		</>
	);
}

export function levelTone(level: AccessLevel): 'neutral' | 'warning' | 'danger' {
	return level === 'DELETE' ? 'danger' : level === 'WRITE' ? 'warning' : 'neutral';
}

function CreateUserDialog({onClose, onCreated}: { onClose: () => void; onCreated: () => void }) {
	const {notify} = useSession();
	const [username, setUsername] = useState('');
	const [password, setPassword] = useState('');
	const [admin, setAdmin] = useState(false);
	const [busy, setBusy] = useState(false);
	const [error, setError] = useState<string>();
	
	const submit = async (event: FormEvent) => {
		event.preventDefault();
		setBusy(true);
		setError(undefined);
		try {
			await api.createUser({username: username.trim(), password, admin});
			notify(`User ${username.trim()} created`);
			onCreated();
		} catch (e) {
			setError(errorText(e));
			setBusy(false);
		}
	};
	
	return (
		<Modal title="New user" onClose={onClose}>
			<form className="form" onSubmit={submit}>
				<Field label="Username"><input value={username} onChange={event => setUsername(event.target.value)} required autoFocus autoComplete="off"/></Field>
				<Field label="Password" hint="At least 12 characters">
					<input type="password" value={password} onChange={event => setPassword(event.target.value)} required minLength={12} autoComplete="new-password"/>
				</Field>
				<Toggle checked={admin} onChange={setAdmin} label="Administrator" description="Full access to all repositories and the management api"/>
				{error && <ErrorBox error={error}/>}
				<div className="modal-footer">
					<Button onClick={onClose} disabled={busy}>Cancel</Button>
					<Button variant="primary" type="submit" disabled={busy}>{busy ? 'Creating…' : 'Create user'}</Button>
				</div>
			</form>
		</Modal>
	);
}

export function PasswordDialog({username, onClose}: { username: string; onClose: () => void }) {
	const {notify, principal} = useSession();
	// Changing the own password requires the current one, the server rejects it otherwise
	const self = principal?.username === username;
	const [current, setCurrent] = useState('');
	const [password, setPassword] = useState('');
	const [confirm, setConfirm] = useState('');
	const [revokeTokens, setRevokeTokens] = useState(false);
	const [busy, setBusy] = useState(false);
	const [error, setError] = useState<string>();
	
	const submit = async (event: FormEvent) => {
		event.preventDefault();
		if (password !== confirm) {
			setError('The passwords do not match');
			return;
		}
		setBusy(true);
		setError(undefined);
		try {
			// Tokens survive a password change unless they are revoked here, other sessions always end
			await api.updateUser(username, {password, ...(self ? {currentPassword: current} : {}), ...(revokeTokens ? {revokeTokens: true} : {})});
			notify(revokeTokens ? `Password of ${username} changed and all access tokens revoked` : `Password of ${username} changed`);
			onClose();
		} catch (e) {
			setError(errorText(e));
			setBusy(false);
		}
	};
	
	return (
		<Modal title={`Set password for ${username}`} onClose={onClose}>
			<form className="form" onSubmit={submit}>
				{self && (
					<Field label="Current password">
						<input type="password" value={current} onChange={event => setCurrent(event.target.value)} required autoFocus autoComplete="current-password"/>
					</Field>
				)}
				<Field label="New password" hint="At least 12 characters">
					<input type="password" value={password} onChange={event => setPassword(event.target.value)} required minLength={12} autoFocus={!self} autoComplete="new-password"/>
				</Field>
				<Field label="Confirm password">
					<input type="password" value={confirm} onChange={event => setConfirm(event.target.value)} required autoComplete="new-password"/>
				</Field>
				<Toggle checked={revokeTokens} onChange={setRevokeTokens} label="Also revoke all access tokens"
						description="Other sessions always end. Revoke the tokens as well if the account may be compromised, CI jobs then need new tokens."/>
				{error && <ErrorBox error={error}/>}
				<div className="modal-footer">
					<Button onClick={onClose} disabled={busy}>Cancel</Button>
					<Button variant="primary" type="submit" disabled={busy}>{busy ? 'Saving…' : 'Set password'}</Button>
				</div>
			</form>
		</Modal>
	);
}
