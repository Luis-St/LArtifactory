import { useState, type FormEvent } from 'react';
import { api } from '../api';
import { Badge, Button, ConfirmDialog, Empty, ErrorBox, Field, Modal, PageHeader, Spinner, Toggle, errorText } from '../components/ui';
import { formatDate } from '../format';
import { useAsync } from '../hooks';
import { href } from '../router';
import { useSession } from '../session';
import type { User } from '../types';

export function Users() {
	const { principal, notify } = useSession();
	const users = useAsync(() => api.users(), []);
	const permissions = useAsync(() => api.permissions(), []);
	const [creating, setCreating] = useState(false);
	const [editing, setEditing] = useState<User | null>(null);
	const [deleting, setDeleting] = useState<User | null>(null);

	const toggleAdmin = async (user: User) => {
		try {
			await api.updateUser(user.username, { admin: !user.admin });
			notify(user.admin ? `${user.username} is no longer an administrator` : `${user.username} is now an administrator`);
			users.reload();
		} catch (e) {
			notify(errorText(e), 'error');
		}
	};

	return (
		<>
			<PageHeader title="Users" subtitle="Users sign in with a password and create access tokens for their clients." actions={<Button variant="primary" onClick={() => setCreating(true)}>New user</Button>} />
			{users.loading && !users.data && <Spinner />}
			{users.error && <ErrorBox error={users.error} onRetry={users.reload} />}
			{users.data && (users.data.length === 0 ? <Empty title="No users" /> : (
				<div className="table-wrap">
					<table className="table">
						<thead>
							<tr>
								<th>Username</th>
								<th>Role</th>
								<th>Permissions</th>
								<th>Created</th>
								<th />
							</tr>
						</thead>
						<tbody>
							{users.data.sort((a, b) => a.username.localeCompare(b.username)).map(user => {
								const granted = (permissions.data ?? []).filter(permission => permission.username === user.username);
								const self = user.username === principal?.username;
								return (
									<tr key={user.username}>
										<td>
											<span className="cell-inline">
												<span className="avatar avatar-small" aria-hidden>{user.username.substring(0, 1).toUpperCase()}</span>
												<strong>{user.username}</strong>
												{self && <span className="muted small">(you)</span>}
											</span>
										</td>
										<td>{user.admin ? <Badge tone="accent">Administrator</Badge> : <Badge>User</Badge>}</td>
										<td>
											{user.admin ? <span className="muted">All repositories</span> : granted.length === 0 ? <span className="muted">None</span> : (
												<a className="link" href={`${href('permissions')}?username=${encodeURIComponent(user.username)}`}>
													{granted.map(permission => `${permission.repository}: ${permission.level}`).slice(0, 3).join(', ')}{granted.length > 3 ? ` +${granted.length - 3}` : ''}
												</a>
											)}
										</td>
										<td className="muted">{formatDate(user.createdAt)}</td>
										<td className="actions">
											<Button small variant="ghost" onClick={() => setEditing(user)}>Set password</Button>
											<Button small variant="ghost" onClick={() => toggleAdmin(user)} disabled={self}>{user.admin ? 'Revoke admin' : 'Make admin'}</Button>
											<Button small variant="ghost" className="danger-text" onClick={() => setDeleting(user)} disabled={self}>Delete</Button>
										</td>
									</tr>
								);
							})}
						</tbody>
					</table>
				</div>
			))}

			{creating && <CreateUserDialog onClose={() => setCreating(false)} onCreated={() => { setCreating(false); users.reload(); }} />}
			{editing && <PasswordDialog username={editing.username} onClose={() => setEditing(null)} />}
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

function CreateUserDialog({ onClose, onCreated }: { onClose: () => void; onCreated: () => void }) {
	const { notify } = useSession();
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
			await api.createUser({ username: username.trim(), password, admin });
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
				<Field label="Username"><input value={username} onChange={event => setUsername(event.target.value)} required autoFocus autoComplete="off" /></Field>
				<Field label="Password" hint="At least 8 characters">
					<input type="password" value={password} onChange={event => setPassword(event.target.value)} required minLength={8} autoComplete="new-password" />
				</Field>
				<Toggle checked={admin} onChange={setAdmin} label="Administrator" description="Full access to all repositories and the management api" />
				{error && <ErrorBox error={error} />}
				<div className="modal-footer">
					<Button onClick={onClose} disabled={busy}>Cancel</Button>
					<Button variant="primary" type="submit" disabled={busy}>{busy ? 'Creating…' : 'Create user'}</Button>
				</div>
			</form>
		</Modal>
	);
}

export function PasswordDialog({ username, onClose }: { username: string; onClose: () => void }) {
	const { notify } = useSession();
	const [password, setPassword] = useState('');
	const [confirm, setConfirm] = useState('');
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
			await api.updateUser(username, { password });
			notify(`Password of ${username} changed`);
			onClose();
		} catch (e) {
			setError(errorText(e));
			setBusy(false);
		}
	};

	return (
		<Modal title={`Set password for ${username}`} onClose={onClose}>
			<form className="form" onSubmit={submit}>
				<Field label="New password" hint="At least 8 characters">
					<input type="password" value={password} onChange={event => setPassword(event.target.value)} required minLength={8} autoFocus autoComplete="new-password" />
				</Field>
				<Field label="Confirm password">
					<input type="password" value={confirm} onChange={event => setConfirm(event.target.value)} required autoComplete="new-password" />
				</Field>
				{error && <ErrorBox error={error} />}
				<div className="modal-footer">
					<Button onClick={onClose} disabled={busy}>Cancel</Button>
					<Button variant="primary" type="submit" disabled={busy}>{busy ? 'Saving…' : 'Set password'}</Button>
				</div>
			</form>
		</Modal>
	);
}
