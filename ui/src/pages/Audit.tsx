import { useState } from 'react';
import { api } from '../api';
import { Badge, Button, Empty, ErrorBox, PageHeader, Spinner, TypeBadge } from '../components/ui';
import { ACCESS_LEVELS, type AccessLevel, REPOSITORY_TYPES, type AuditEntry, type RepositoryType } from '../types';
import { levelTone } from './Users';
import { formatDate, formatIp, formatRelative } from '../format';
import { useAsync } from '../hooks';
import {Select} from '../components/Select';

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

/**
 * Renders the target of an entry by what it is: repository types as badges, token ids as short ids, names as plain text.
 */
function AuditTarget({ entry }: { entry: AuditEntry }) {
	const target = entry.target;
	if (!target) {
		return <span className="faint">—</span>;
	}
	if (entry.action.startsWith('repository.') && REPOSITORY_TYPES.includes(target as RepositoryType)) {
		return <TypeBadge type={target as RepositoryType} />;
	}
	if (UUID.test(target)) {
		return <code className="id-chip" title={target}>{shortId(target)}</code>;
	}
	return <span className="break" title={target}>{target}</span>;
}

/**
 * Splits details like "user=admin, level=DELETE" into chips, other details are shown as text.
 */
function AuditDetail({ detail }: { detail: string | null }) {
	if (!detail) {
		return null;
	}
	if ((ACCESS_LEVELS as readonly string[]).includes(detail)) {
		return <Badge tone={levelTone(detail as AccessLevel)}>{detail}</Badge>;
	}
	const parts = detail.split(/,\s*/);
	if (!parts.every(part => /^[\w.-]+=\S*$/.test(part))) {
		return <span className="muted small">{REPOSITORY_KINDS[detail] ?? detail}</span>;
	}
	return (
		<span className="detail-chips">
			{parts.map(part => {
				const [key, value] = part.split('=', 2);
				return <span key={key} className="detail-chip"><span className="faint">{key}</span> {value}</span>;
			})}
		</span>
	);
}

const REPOSITORY_KINDS: Record<string, string> = { local: 'Local', remote: 'Remote (proxy)', virtual: 'Virtual (group)' };

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
				<Select label="Action" value={action} onChange={value => { setAction(value); setLimit(PAGE_SIZE); }} options={ACTIONS.map(entry => ({value: entry.id, label: entry.label}))}/>
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
									<td className="shrink"><Badge tone={actionTone(entry.action)} title={entry.action}>{ACTIONS.find(candidate => candidate.id === entry.action)?.label ?? entry.action}</Badge></td>
									<td className="nowrap">{entry.actor ? <button type="button" className="link" onClick={() => { setActor(entry.actor!); setLimit(PAGE_SIZE); }}>{entry.actor}</button> : <span className="muted">anonymous</span>}</td>
									<td className="nowrap">{entry.repository && entry.repository !== '*' ? <button type="button" className="link" onClick={() => { setRepository(entry.repository!); setLimit(PAGE_SIZE); }}>{entry.repository}</button> : <span className="faint">{entry.repository ?? '—'}</span>}</td>
									<td><AuditTarget entry={entry} /></td>
									<td><AuditDetail detail={entry.detail} /></td>
									<td className="muted small nowrap tabular">{formatIp(entry.ip)}</td>
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
