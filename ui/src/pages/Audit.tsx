import { useState } from 'react';
import { api } from '../api';
import { Badge, Button, Empty, ErrorBox, PageHeader, Spinner } from '../components/ui';
import { formatDate, formatIp, formatRelative } from '../format';
import { useAsync } from '../hooks';

const PAGE_SIZE = 100;

const ACTIONS: { id: string; label: string }[] = [
	{ id: '', label: 'All actions' },
	{ id: 'repository.create', label: 'Repository created' },
	{ id: 'repository.update', label: 'Repository updated' },
	{ id: 'repository.delete', label: 'Repository deleted' },
	{ id: 'package.delete', label: 'Package deleted' },
	{ id: 'user.create', label: 'User created' },
	{ id: 'user.update', label: 'User updated' },
	{ id: 'user.delete', label: 'User deleted' },
	{ id: 'permission.set', label: 'Permission granted' },
	{ id: 'permission.delete', label: 'Permission revoked' },
	{ id: 'token.create', label: 'Token created' },
	{ id: 'token.delete', label: 'Token revoked' },
];

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Token ids are uuids, the first block is enough to tell them apart in the log. */
function shortId(value: string): string {
	return UUID.test(value) ? `${value.substring(0, 8)}…` : value;
}

function actionTone(action: string): 'neutral' | 'success' | 'warning' | 'danger' | 'accent' {
	if (action.endsWith('.delete')) {
		return 'danger';
	}
	if (action.endsWith('.create') || action.endsWith('.set')) {
		return 'success';
	}
	return 'neutral';
}

export function Audit() {
	const [repository, setRepository] = useState('');
	const [actor, setActor] = useState('');
	const [action, setAction] = useState('');
	const [limit, setLimit] = useState(PAGE_SIZE);

	const entries = useAsync(
		() => api.audit({ repository: repository || undefined, actor: actor || undefined, action: action || undefined, limit: limit + 1 }),
		[repository, actor, action, limit],
	);

	const data = entries.data;
	const hasMore = data !== undefined && data.length > limit;
	const rows = hasMore ? data!.slice(0, limit) : data;
	const filtered = repository !== '' || actor !== '' || action !== '';
	const clear = () => {
		setRepository('');
		setActor('');
		setAction('');
		setLimit(PAGE_SIZE);
	};

	return (
		<>
			<PageHeader
				title="Audit log"
				subtitle="Administrative actions on repositories, users, permissions and tokens, newest first."
			/>
			<div className="toolbar">
				<select value={action} aria-label="Action" onChange={event => { setAction(event.target.value); setLimit(PAGE_SIZE); }}>
					{ACTIONS.map(entry => <option key={entry.id} value={entry.id}>{entry.label}</option>)}
				</select>
				<input placeholder="Repository" aria-label="Filter by repository" value={repository} onChange={event => { setRepository(event.target.value); setLimit(PAGE_SIZE); }} />
				<input placeholder="User" aria-label="Filter by user" value={actor} onChange={event => { setActor(event.target.value); setLimit(PAGE_SIZE); }} />
				{filtered && <Button variant="ghost" onClick={clear}>Clear filters</Button>}
			</div>

			{entries.loading && !data && <Spinner />}
			{entries.error && <ErrorBox error={entries.error} onRetry={entries.reload} />}
			{rows && (rows.length === 0 ? <Empty title="No audit entries">No actions match the current filters.</Empty> : (
				<div className="table-wrap">
					<table className="table">
						<thead><tr><th>When</th><th>Action</th><th>User</th><th>Repository</th><th>Target</th><th>Detail</th><th>IP</th></tr></thead>
						<tbody>
							{rows.map(entry => (
								<tr key={entry.id}>
									<td className="muted nowrap" title={formatDate(entry.timestamp)}>{formatRelative(entry.timestamp)}</td>
									<td className="shrink"><Badge tone={actionTone(entry.action)} title={ACTIONS.find(candidate => candidate.id === entry.action)?.label}>{entry.action}</Badge></td>
									<td className="nowrap">{entry.actor ? <button type="button" className="link" onClick={() => { setActor(entry.actor!); setLimit(PAGE_SIZE); }}>{entry.actor}</button> : <span className="muted">anonymous</span>}</td>
									<td className="nowrap">{entry.repository && entry.repository !== '*' ? <button type="button" className="link" onClick={() => { setRepository(entry.repository!); setLimit(PAGE_SIZE); }}>{entry.repository}</button> : <span className="faint">{entry.repository ?? '—'}</span>}</td>
									<td className="mono small" title={entry.target ?? undefined}>{entry.target ? shortId(entry.target) : <span className="faint">—</span>}</td>
									<td className="muted small">{entry.detail ?? ''}</td>
									<td className="mono small muted nowrap">{formatIp(entry.ip)}</td>
								</tr>
							))}
						</tbody>
					</table>
				</div>
			))}
			{hasMore && (
				<div className="toolbar">
					<Button onClick={() => setLimit(value => value + PAGE_SIZE)} disabled={entries.loading}>{entries.loading ? 'Loading…' : 'Load more'}</Button>
					<span className="muted small">Showing {rows?.length} entries</span>
				</div>
			)}
		</>
	);
}
