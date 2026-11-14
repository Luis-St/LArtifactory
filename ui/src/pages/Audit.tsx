import {useMemo, useState} from 'react';
import {api} from '../api';
import {Badge, Button, Empty, ErrorBox, PageHeader, Spinner, TYPE_INFO, TypeBadge} from '../components/ui';
import {ACCESS_LEVELS, type AccessLevel, type AuditEntry, REPOSITORY_TYPES, type RepositoryType} from '../types';
import {levelTone} from './Users';
import {formatDate, formatIp, formatRelative} from '../format';
import {useAsync} from '../hooks';
import {Select} from '../components/Select';
import {SortHeader, useSort} from '../components/sort';
import {Pagination, useServerPaging} from '../components/paging';

const ACTIONS: { id: string; label: string }[] = [
	{id: '', label: 'All actions'},
	{id: 'repository.create', label: 'Repository created'},
	{id: 'repository.update', label: 'Repository updated'},
	{id: 'repository.delete', label: 'Repository deleted'},
	{id: 'package.delete', label: 'Package deleted'},
	{id: 'user.create', label: 'User created'},
	{id: 'user.update', label: 'User updated'},
	{id: 'user.delete', label: 'User deleted'},
	{id: 'permission.set', label: 'Permission granted'},
	{id: 'permission.delete', label: 'Permission revoked'},
	{id: 'token.create', label: 'Token created'},
	{id: 'token.delete', label: 'Token revoked'},
	{id: 'webhook.create', label: 'Webhook created'},
	{id: 'webhook.update', label: 'Webhook updated'},
	{id: 'webhook.delete', label: 'Webhook deleted'},
	{id: 'artifact.publish', label: 'Artifact published'},
	{id: 'artifact.delete', label: 'Artifact deleted'},
	{id: 'login.failed', label: 'Failed login'},
	{id: 'login.locked', label: 'Login locked'},
];

type Range = '' | '24h' | '7d' | '30d' | 'custom';
type Column = 'when' | 'action' | 'user' | 'repository' | 'target';

const RANGES: { value: Range; label: string }[] = [
	{value: '', label: 'Any time'},
	{value: '24h', label: 'Last 24 hours'},
	{value: '7d', label: 'Last 7 days'},
	{value: '30d', label: 'Last 30 days'},
	{value: 'custom', label: 'Custom range…'},
];

const RANGE_HOURS: Record<string, number> = {'24h': 24, '7d': 24 * 7, '30d': 24 * 30};

/**
 * The from/to instants of a range, a custom range uses whole local days with the end day included.
 */
function rangeBounds(range: Range, fromDay: string, toDay: string): { from?: string; to?: string } {
	if (range === 'custom') {
		const end = toDay ? new Date(`${toDay}T00:00:00`) : undefined;
		end?.setDate(end.getDate() + 1);
		return {from: fromDay ? new Date(`${fromDay}T00:00:00`).toISOString() : undefined, to: end?.toISOString()};
	}
	const hours = RANGE_HOURS[range];
	return hours ? {from: new Date(Date.now() - hours * 3600_000).toISOString()} : {};
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Token ids are uuids, the first block is enough to tell them apart in the log. */
function shortId(value: string): string {
	return UUID.test(value) ? `${value.substring(0, 8)}…` : value;
}

/**
 * Renders the target of an entry by what it is: repository types as badges, token ids as short ids, names as plain text.
 */
function AuditTarget({entry}: { entry: AuditEntry }) {
	const target = entry.target;
	if (!target) {
		return <span className="faint">—</span>;
	}
	if (entry.action.startsWith('repository.') && REPOSITORY_TYPES.includes(target as RepositoryType)) {
		return <TypeBadge type={target as RepositoryType}/>;
	}
	if (UUID.test(target)) {
		return <code className="id-chip" title={target}>{shortId(target)}</code>;
	}
	return <span className="break" title={target}>{target}</span>;
}

/**
 * Splits details like "user=admin, level=DELETE" into chips, other details are shown as text.
 */
function AuditDetail({detail}: { detail: string | null }) {
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

const REPOSITORY_KINDS: Record<string, string> = {local: 'Local', remote: 'Remote (proxy)', virtual: 'Virtual (group)'};

function actionTone(action: string): 'neutral' | 'success' | 'warning' | 'danger' | 'accent' {
	if (action.endsWith('.delete') || action.startsWith('login.')) {
		return 'danger';
	}
	if (action.endsWith('.create') || action.endsWith('.set') || action.endsWith('.publish')) {
		return 'success';
	}
	return 'neutral';
}

export function Audit() {
	const [repository, setRepository] = useState('');
	const [actor, setActor] = useState('');
	const [action, setAction] = useState('');
	const [range, setRange] = useState<Range>('');
	const [fromDay, setFromDay] = useState('');
	const [toDay, setToDay] = useState('');
	const users = useAsync(() => api.users(), []);
	const repositories = useAsync(() => api.repositories(), []);
	// Paged with offsets on the server, so every entry is reachable however old it is
	const {offset, limit, paging} = useServerPaging([repository, actor, action, range, fromDay, toDay]);
	
	const page = useAsync(
		() => api.audit({repository: repository || undefined, actor: actor || undefined, action: action || undefined, ...rangeBounds(range, fromDay, toDay)}, {offset, limit}),
		[repository, actor, action, range, fromDay, toDay, offset, limit],
	);
	const entries = {...page, data: page.data?.rows};
	// Deleted users and repositories only appear in the log, they are offered as filters as well
	const actorOptions = useMemo(() => [...new Set([...(users.data ?? []).map(user => user.username), ...(entries.data ?? []).flatMap(entry => entry.actor ? [entry.actor] : []), ...(actor ? [actor] : [])])].sort(), [users.data, entries.data, actor]);
	const repositoryOptions = useMemo(() => {
		const known = (repositories.data ?? []).map(entry => entry.name);
		const logged = (entries.data ?? []).flatMap(entry => entry.repository && entry.repository !== '*' && !known.includes(entry.repository) ? [entry.repository] : []);
		return [...known, ...new Set([...logged, ...(repository && !known.includes(repository) ? [repository] : [])])];
	}, [repositories.data, entries.data, repository]);
	const filter = (apply: () => void) => apply();
	
	const data = entries.data;
	const rows = data;
	const filtered = repository !== '' || actor !== '' || action !== '' || range !== '';
	const clear = () => {
		setRepository('');
		setActor('');
		setAction('');
		setRange('');
		setFromDay('');
		setToDay('');
	};
	const {sorted, sort, toggle} = useSort<AuditEntry, Column>(rows ?? [], {
		when: entry => entry.timestamp,
		action: entry => ACTIONS.find(candidate => candidate.id === entry.action)?.label ?? entry.action,
		user: entry => entry.actor,
		repository: entry => entry.repository,
		target: entry => entry.target,
	});
	
	return (
		<>
			<PageHeader
				title="Audit log"
				subtitle="Administrative actions, failed logins and lockouts, and artifacts published or deleted, newest first."
			/>
			<div className="toolbar">
				<Select label="Action" value={action} onChange={value => filter(() => setAction(value))} options={ACTIONS.map(entry => ({value: entry.id, label: entry.label}))}/>
				<Select label="User" value={actor} onChange={value => filter(() => setActor(value))} options={[{value: '', label: 'All users'}, ...actorOptions.map(name => ({value: name, label: name}))]}/>
				<Select label="Repository" value={repository} onChange={value => filter(() => setRepository(value))} options={[
					{value: '', label: 'All repositories'},
					{value: '*', label: 'Granted for all (*)'},
					...repositoryOptions.map(name => {
						const type = repositories.data?.find(entry => entry.name === name)?.type;
						return {value: name, label: name, hint: type ? TYPE_INFO[type].label : 'deleted'};
					}),
				]}/>
				<Select<Range> label="Time range" value={range} onChange={value => filter(() => setRange(value))} options={RANGES}/>
				{range === 'custom' && (
					<span className="date-range">
						<input type="date" aria-label="From day" value={fromDay} max={toDay || undefined} onChange={event => filter(() => setFromDay(event.target.value))}/>
						<span className="muted">to</span>
						<input type="date" aria-label="To day" value={toDay} min={fromDay || undefined} onChange={event => filter(() => setToDay(event.target.value))}/>
					</span>
				)}
				{filtered && <Button variant="ghost" onClick={clear}>Clear filters</Button>}
			</div>
			
			{entries.loading && !data && <Spinner/>}
			{entries.error && <ErrorBox error={entries.error} onRetry={entries.reload}/>}
			{rows && (rows.length === 0 ? <Empty title="No audit entries">No actions match the current filters.</Empty> : (
				<div className="table-wrap">
					<table className="table">
						<thead>
						<tr>
							<SortHeader id="when" sort={sort} onSort={toggle}>When</SortHeader>
							<SortHeader id="action" sort={sort} onSort={toggle}>Action</SortHeader>
							<SortHeader id="user" sort={sort} onSort={toggle}>User</SortHeader>
							<SortHeader id="repository" sort={sort} onSort={toggle}>Repository</SortHeader>
							<SortHeader id="target" sort={sort} onSort={toggle}>Target</SortHeader>
							<th>Detail</th>
							<th>IP</th>
						</tr>
						</thead>
						<tbody>
						{sorted.map(entry => (
							<tr key={entry.id}>
								<td className="muted nowrap" title={formatDate(entry.timestamp)}>{formatRelative(entry.timestamp)}</td>
								<td className="shrink"><Badge tone={actionTone(entry.action)} title={entry.action}>{ACTIONS.find(candidate => candidate.id === entry.action)?.label ?? entry.action}</Badge></td>
								<td className="nowrap">{entry.actor ? <button type="button" className="link" onClick={() => setActor(entry.actor!)}>{entry.actor}</button> : <span className="muted">anonymous</span>}</td>
								<td className="nowrap">{entry.repository && entry.repository !== '*' ? <button type="button" className="link" onClick={() => setRepository(entry.repository!)}>{entry.repository}</button> :
									<span className="faint">{entry.repository ?? '—'}</span>}</td>
								<td><AuditTarget entry={entry}/></td>
								<td><AuditDetail detail={entry.detail}/></td>
								<td className="muted small nowrap tabular">{formatIp(entry.ip)}</td>
							</tr>
						))}
						</tbody>
					</table>
					{page.data && <Pagination paging={paging(page.data.total)} noun="entries"/>}
				</div>
			))}
		
		</>
	);
}
