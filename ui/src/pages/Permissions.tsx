import { useState, type FormEvent } from 'react';
import { api } from '../api';
import { Badge, Button, Card, Empty, ErrorBox, PageHeader, Spinner, TypeBadge, errorText } from '../components/ui';
import { useAsync } from '../hooks';
import { href } from '../router';
import { useSession } from '../session';
import { ACCESS_LEVELS, type AccessLevel } from '../types';

export function Permissions({ params }: { params: URLSearchParams }) {
	const { notify } = useSession();
	const permissions = useAsync(() => api.permissions(), []);
	const users = useAsync(() => api.users(), []);
	const repositories = useAsync(() => api.repositories(), []);
	const [userFilter, setUserFilter] = useState(params.get('username') ?? '');
	const [repositoryFilter, setRepositoryFilter] = useState('');

	const [username, setUsername] = useState('');
	const [repository, setRepository] = useState('*');
	const [level, setLevel] = useState<AccessLevel>('READ');
	const [busy, setBusy] = useState(false);

	const grant = async (event: FormEvent) => {
		event.preventDefault();
		setBusy(true);
		try {
			await api.setPermission({ repository, username, level });
			notify(`Granted ${level} on ${repository === '*' ? 'all repositories' : repository} to ${username}`);
			permissions.reload();
		} catch (e) {
			notify(errorText(e), 'error');
		} finally {
			setBusy(false);
		}
	};
	const revoke = async (repositoryName: string, user: string) => {
		try {
			await api.deletePermission(repositoryName, user);
			notify(`Revoked ${user} on ${repositoryName}`);
			permissions.reload();
		} catch (e) {
			notify(errorText(e), 'error');
		}
	};

	const typeOf = (name: string) => repositories.data?.find(entry => entry.name === name)?.type;
	const rows = (permissions.data ?? [])
		.filter(permission => (!userFilter || permission.username === userFilter) && (!repositoryFilter || permission.repository === repositoryFilter))
		.sort((a, b) => a.username.localeCompare(b.username) || a.repository.localeCompare(b.repository));
	const nonAdmins = (users.data ?? []).filter(user => !user.admin);

	return (
		<>
			<PageHeader title="Permissions" subtitle="Grant users access per repository or for all repositories with *. Administrators always have full access." />

			<Card title="Grant access">
				<form className="inline-form" onSubmit={grant}>
					<select value={username} onChange={event => setUsername(event.target.value)} required aria-label="User">
						<option value="">Select user…</option>
						{nonAdmins.map(user => <option key={user.username} value={user.username}>{user.username}</option>)}
					</select>
					<select value={repository} onChange={event => setRepository(event.target.value)} aria-label="Repository">
						<option value="*">* (all repositories)</option>
						{(repositories.data ?? []).map(entry => <option key={entry.name} value={entry.name}>{entry.name} ({entry.type})</option>)}
					</select>
					<select value={level} onChange={event => setLevel(event.target.value as AccessLevel)} aria-label="Level">
						{ACCESS_LEVELS.map(entry => <option key={entry} value={entry}>{entry}</option>)}
					</select>
					<Button variant="primary" type="submit" disabled={busy || !username}>Grant</Button>
				</form>
				<p className="muted small">READ downloads, WRITE publishes, tags and yanks, DELETE removes artifacts. Granting again replaces the existing level.</p>
			</Card>

			<div className="toolbar">
				<select value={userFilter} onChange={event => setUserFilter(event.target.value)} aria-label="Filter user">
					<option value="">All users</option>
					{(users.data ?? []).map(user => <option key={user.username} value={user.username}>{user.username}</option>)}
				</select>
				<select value={repositoryFilter} onChange={event => setRepositoryFilter(event.target.value)} aria-label="Filter repository">
					<option value="">All repositories</option>
					<option value="*">*</option>
					{(repositories.data ?? []).map(entry => <option key={entry.name} value={entry.name}>{entry.name}</option>)}
				</select>
			</div>

			{permissions.loading && !permissions.data && <Spinner />}
			{permissions.error && <ErrorBox error={permissions.error} onRetry={permissions.reload} />}
			{permissions.data && (rows.length === 0 ? <Empty title="No permissions" /> : (
				<div className="table-wrap">
					<table className="table">
						<thead><tr><th>User</th><th>Repository</th><th>Level</th><th /></tr></thead>
						<tbody>
							{rows.map(permission => {
								const type = typeOf(permission.repository);
								return (
									<tr key={`${permission.username}/${permission.repository}`}>
										<td><strong>{permission.username}</strong></td>
										<td>
											{permission.repository === '*' ? <span className="muted">* (all repositories)</span> : (
												<span className="cell-inline">
													{type && <TypeBadge type={type} />}
													<a className="link" href={href('repositories', permission.repository)}>{permission.repository}</a>
												</span>
											)}
										</td>
										<td><Badge tone={permission.level === 'DELETE' ? 'danger' : permission.level === 'WRITE' ? 'warning' : 'neutral'}>{permission.level}</Badge></td>
										<td className="actions"><Button small variant="ghost" className="danger-text" onClick={() => revoke(permission.repository, permission.username)}>Revoke</Button></td>
									</tr>
								);
							})}
						</tbody>
					</table>
				</div>
			))}
		</>
	);
}
