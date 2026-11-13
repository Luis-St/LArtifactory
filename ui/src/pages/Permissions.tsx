import {type FormEvent, useState} from 'react';
import {api} from '../api';
import {Badge, Button, Card, Empty, ErrorBox, errorText, PageHeader, Spinner, TYPE_INFO, TypeBadge} from '../components/ui';
import {useAsync} from '../hooks';
import {href} from '../router';
import {useSession} from '../session';
import {ACCESS_LEVELS, type AccessLevel, type Permission, sortRepositories} from '../types';
import {levelTone} from './Users';
import {Select} from '../components/Select';
import {SortHeader, useSort} from '../components/sort';
import {Pagination, usePaging} from '../components/paging';

export function Permissions({params}: { params: URLSearchParams }) {
	const {notify} = useSession();
	const permissions = useAsync(() => api.permissions(), []);
	const users = useAsync(() => api.users(), []);
	const repositories = useAsync(() => api.repositories(), []);
	const [userFilter, setUserFilter] = useState(params.get('username') ?? '');
	const [repositoryFilter, setRepositoryFilter] = useState('');
	
	const [username, setUsername] = useState(params.get('username') ?? '');
	const [repository, setRepository] = useState('*');
	const [level, setLevel] = useState<AccessLevel>('READ');
	const [busy, setBusy] = useState(false);
	
	const grant = async (event: FormEvent) => {
		event.preventDefault();
		setBusy(true);
		try {
			await api.setPermission({repository, username, level});
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
	const {sorted, sort, toggle} = useSort<Permission, 'user' | 'repository' | 'level'>(rows, {
		user: permission => permission.username,
		repository: permission => permission.repository,
		level: permission => ACCESS_LEVELS.indexOf(permission.level),
	});
	const {visible, paging} = usePaging(sorted);
	const nonAdmins = (users.data ?? []).filter(user => !user.admin);
	
	return (
		<>
			<PageHeader title="Permissions" subtitle="Grant users access per repository or for all repositories with *. Administrators always have full access."/>
			
			<Card title="Grant access">
				<form className="inline-form" onSubmit={grant}>
					<Select label="User" placeholder="Select user…" value={username} onChange={setUsername}
							options={nonAdmins.map(user => ({value: user.username, label: user.username}))}/>
					<Select label="Repository" value={repository} onChange={setRepository} options={[
						{value: '*', label: 'All repositories (*)'},
						...sortRepositories(repositories.data ?? []).map(entry => ({value: entry.name, label: entry.name, hint: TYPE_INFO[entry.type].label})),
					]}/>
					<Select<AccessLevel> label="Level" value={level} onChange={setLevel} options={ACCESS_LEVELS.map(entry => ({value: entry, label: entry}))}/>
					<Button variant="primary" type="submit" disabled={busy || !username}>Grant</Button>
				</form>
				<p className="muted small">READ downloads, WRITE publishes, tags and yanks, DELETE removes artifacts. Granting again replaces the existing level.</p>
			</Card>
			
			<div className="toolbar">
				<Select label="Filter user" value={userFilter} onChange={setUserFilter} options={[
					{value: '', label: 'All users'},
					...(users.data ?? []).map(user => ({value: user.username, label: user.username})),
				]}/>
				<Select label="Filter repository" value={repositoryFilter} onChange={setRepositoryFilter} options={[
					{value: '', label: 'All repositories'},
					{value: '*', label: 'Granted for all (*)'},
					...sortRepositories(repositories.data ?? []).map(entry => ({value: entry.name, label: entry.name, hint: TYPE_INFO[entry.type].label})),
				]}/>
				{(userFilter || repositoryFilter) && <Button variant="ghost" onClick={() => {
					setUserFilter('');
					setRepositoryFilter('');
				}}>Clear filters</Button>}
				<div className="toolbar-spacer"/>
				<span className="muted small">{rows.length} {rows.length === 1 ? 'permission' : 'permissions'}</span>
			</div>
			
			{permissions.loading && !permissions.data && <Spinner/>}
			{permissions.error && <ErrorBox error={permissions.error} onRetry={permissions.reload}/>}
			{permissions.data && (rows.length === 0 ? <Empty title="No permissions">{userFilter || repositoryFilter ? 'No permissions match the filters.' : 'Grant a user access to a repository above.'}</Empty> : (
				<div className="table-wrap">
					<table className="table">
						<thead>
						<tr>
							<SortHeader id="user" sort={sort} onSort={toggle}>User</SortHeader>
							<SortHeader id="repository" sort={sort} onSort={toggle}>Repository</SortHeader>
							<SortHeader id="level" sort={sort} onSort={toggle}>Level</SortHeader>
							<th/>
						</tr>
						</thead>
						<tbody>
						{visible.map(permission => {
							const type = typeOf(permission.repository);
							return (
								<tr key={`${permission.username}/${permission.repository}`}>
									<td><strong>{permission.username}</strong></td>
									<td>
										{permission.repository === '*' ? <span className="muted">* (all repositories)</span> : (
											<span className="cell-inline">
													{type && <TypeBadge type={type}/>}
												<a className="link" href={href('repositories', permission.repository)}>{permission.repository}</a>
												</span>
										)}
									</td>
									<td><Badge tone={levelTone(permission.level)}>{permission.level}</Badge></td>
									<td className="actions"><Button small variant="ghost" className="danger-text" onClick={() => revoke(permission.repository, permission.username)}>Revoke</Button></td>
								</tr>
							);
						})}
						</tbody>
					</table>
					<Pagination paging={paging} noun="permissions"/>
				</div>
			))}
		</>
	);
}
