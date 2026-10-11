import { useState } from 'react';
import { api } from '../api';
import { Badge, Button, Empty, ErrorBox, PageHeader, Spinner } from '../components/ui';
import { formatDate, formatRelative } from '../format';
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

	return (
		<>
			<PageHeader
				title="Audit log"
				subtitle="Administrative actions on repositories, users, permissions and tokens, newest first."
			/>
			<div className="toolbar">
				<label className="cell-inline">
					<span className="muted">Action</span>
					<select value={action} onChange={event => { setAction(event.target.value); setLimit(PAGE_SIZE); }}>
						{ACTIONS.map(entry => <option key={entry.id} value={entry.id}>{entry.label}</option>)}
					</select>
				</label>
				<input placeholder="Filter by repository" value={repository} onChange={event => { setRepository(event.target.value); setLimit(PAGE_SIZE); }} />
				<input placeholder="Filter by user" value={actor} onChange={event => { setActor(event.target.value); setLimit(PAGE_SIZE); }} />
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
									<td className="muted" title={formatDate(entry.timestamp)}>{formatRelative(entry.timestamp)}</td>
									<td><Badge tone={actionTone(entry.action)}>{entry.action}</Badge></td>
									<td>{entry.actor ?? <span className="muted">anonymous</span>}</td>
									<td>{entry.repository ?? <span className="muted">—</span>}</td>
									<td className="mono small">{entry.target ?? <span className="muted">—</span>}</td>
									<td className="muted small">{entry.detail ?? ''}</td>
									<td className="mono small muted">{entry.ip ?? '—'}</td>
								</tr>
							))}
						</tbody>
					</table>
				</div>
			))}
			{hasMore && (
				<div className="toolbar">
					<Button onClick={() => setLimit(value => value + PAGE_SIZE)}>Load more</Button>
				</div>
			)}
		</>
	);
}
