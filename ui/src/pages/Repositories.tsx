import {type FormEvent, useState} from 'react';
import {api} from '../api';
import {Icon} from '../components/icons';
import {Badge, Button, Empty, ErrorBox, errorText, Field, Modal, PageHeader, SearchField, Segmented, Spinner, Tabs, Toggle, TYPE_INFO, TypeBadge, TypeIcon} from '../components/ui';
import {formatBytes, formatDate, formatNumber, formatRelative} from '../format';
import {loadSummary} from '../inventory';
import {useAsync} from '../hooks';
import {href, navigate} from '../router';
import {useSession} from '../session';
import {canAdminister, IMMUTABLE_TYPES, type Repository, REPOSITORY_TYPES, type RepositoryClass, type RepositorySummary, type RepositoryType, visibleTypes} from '../types';
import {Select} from '../components/Select';
import {SortHeader, useSort} from '../components/sort';
import {Pagination, usePaging} from '../components/paging';

const PROXYABLE_TYPES: RepositoryType[] = ['maven', 'generic'];

export function Repositories() {
	const {principal, guest} = useSession();
	const repositories = useAsync(() => api.repositories(), []);
	const [filter, setFilter] = useState<RepositoryType | ''>('');
	const [search, setSearch] = useState('');
	const [creating, setCreating] = useState(false);
	const [view, setView] = useState<View>(readView);
	// The cards show sizes and counts, the content is only loaded once the card view is used
	const inventory = useAsync(() => (view === 'cards' ? loadSummary().then(summary => summary.repositories) : Promise.resolve(null)), [view]);
	const admin = canAdminister(principal);
	const changeView = (value: View) => {
		setView(value);
		try {
			localStorage.setItem(VIEW_KEY, value);
		} catch {
			// Storage unavailable, the view only applies to this page
		}
	};
	
	const query = search.trim().toLowerCase();
	const rows = (repositories.data ?? [])
		.filter(repository => !filter || repository.type === filter)
		.filter(repository => !query || repository.name.toLowerCase().includes(query) || (repository.description ?? '').toLowerCase().includes(query));
	const {sorted, sort, toggle} = useSort<Repository, 'name' | 'type' | 'description' | 'access' | 'created'>(rows, {
		name: repository => repository.name,
		type: repository => REPOSITORY_TYPES.indexOf(repository.type),
		description: repository => repository.description,
		access: repository => (repository.publicRead ? 0 : 1),
		created: repository => repository.createdAt,
	});
	const {visible, paging} = usePaging(sorted);
	
	return (
		<>
			<PageHeader
				title="Repositories"
				subtitle="Every repository hosts one package format and is served at /{type}/{name}/."
				actions={admin && <Button variant="primary" onClick={() => setCreating(true)}><Icon name="plus" size={16}/>New repository</Button>}
			/>
			
			<Tabs
				tabs={[
					{id: '' as RepositoryType | '', label: <>All <span className="tab-count">{repositories.data?.length ?? 0}</span></>},
					...visibleTypes(repositories.data, guest).map(type => ({id: type as RepositoryType | '', label: <>{TYPE_INFO[type].label} <span className="tab-count">{(repositories.data ?? []).filter(entry => entry.type === type).length}</span></>})),
				]}
				active={filter}
				onChange={setFilter}
			/>
			<div className="toolbar">
				<SearchField placeholder="Search repositories…" value={search} onChange={setSearch}/>
				<div className="toolbar-spacer"/>
				<Segmented<View> label="View" value={view} onChange={changeView} options={[
					{id: 'table', label: 'Table', icon: 'list'},
					{id: 'cards', label: 'Cards', icon: 'grid'},
				]}/>
			</div>
			
			{repositories.loading && !repositories.data && <Spinner/>}
			{repositories.error && <ErrorBox error={repositories.error} onRetry={repositories.reload}/>}
			{repositories.data && (rows.length === 0 ? (
				<Empty title={repositories.data.length === 0 ? 'No repositories yet' : 'No matching repositories'}>
					{admin && repositories.data.length === 0 && <Button variant="primary" onClick={() => setCreating(true)}>Create a repository</Button>}
				</Empty>
			) : (
				view === 'cards' ? <RepositoryCards repositories={sorted} inventory={inventory.data ?? null}/> : (
				<div className="table-wrap">
					<table className="table">
						<thead>
						<tr>
							<SortHeader id="name" sort={sort} onSort={toggle}>Name</SortHeader>
							<SortHeader id="type" sort={sort} onSort={toggle}>Type</SortHeader>
							<SortHeader id="description" sort={sort} onSort={toggle}>Description</SortHeader>
							<SortHeader id="access" sort={sort} onSort={toggle}>Access</SortHeader>
							<SortHeader id="created" sort={sort} onSort={toggle}>Created</SortHeader>
						</tr>
						</thead>
						<tbody>
						{visible.map(repository => (
							<tr key={repository.name} className="clickable" onClick={() => navigate(href('repositories', repository.name))}>
								<td>
									<span className="cell-inline">
										<a className="strong nowrap" href={href('repositories', repository.name)} onClick={event => event.stopPropagation()}>{repository.name}</a>
										{repository.kind !== 'LOCAL' && <Badge tone="accent">{repository.kind.toLowerCase()}</Badge>}
									</span>
								</td>
								<td className="shrink"><TypeBadge type={repository.type}/></td>
								<td className="muted">{repository.description || <span className="faint">—</span>}</td>
								<td className="shrink">
									<span className="cell-inline">
										{repository.publicRead ? <Badge tone="success">public</Badge> : <Badge>private</Badge>}
										{repository.allowRedeploy && <Badge tone="warning">redeploy</Badge>}
									</span>
								</td>
								<td className="muted nowrap">{formatDate(repository.createdAt)}</td>
							</tr>
						))}
						</tbody>
					</table>
					<Pagination paging={paging} noun="repositories"/>
				</div>
				)
			))}
			
			{creating && <CreateRepositoryDialog onClose={() => setCreating(false)}/>}
		</>
	);
}

type View = 'table' | 'cards';
const VIEW_KEY = 'lartifactory.repositoryView';

function readView(): View {
	try {
		return localStorage.getItem(VIEW_KEY) === 'cards' ? 'cards' : 'table';
	} catch {
		return 'table';
	}
}

/**
 * Repositories as cards with their type colour, content counts, size and last upload.
 */
function RepositoryCards({repositories, inventory}: { repositories: Repository[]; inventory: RepositorySummary[] | null }) {
	return (
		<div className="repo-grid">
			{repositories.map(repository => {
				const content = inventory?.find(entry => entry.name === repository.name);
				const size = content?.size;
				const lastUpload = content?.lastUpload;
				const count = repository.type === 'generic' ? content?.files : content?.packages;
				return (
					<a key={repository.name} className="repo-card" href={href('repositories', repository.name)} style={{'--badge': TYPE_INFO[repository.type].color} as React.CSSProperties}>
						<div className="repo-card-head">
							<TypeIcon type={repository.type} size={34}/>
							<div className="repo-card-title">
								<strong>{repository.name}</strong>
								<span className="muted small">{TYPE_INFO[repository.type].label}{repository.kind !== 'LOCAL' ? ` · ${repository.kind.toLowerCase()}` : ''}</span>
							</div>
							{repository.publicRead ? <Badge tone="success">public</Badge> : <Badge>private</Badge>}
						</div>
						<p className="repo-card-description muted">{repository.description || 'No description'}</p>
						<dl className="repo-card-stats">
							<div>
								<dt>{repository.type === 'generic' ? 'Files' : 'Packages'}</dt>
								<dd>{count === undefined ? '…' : formatNumber(count)}</dd>
							</div>
							<div>
								<dt>Storage</dt>
								<dd>{size === undefined ? '…' : formatBytes(size)}</dd>
							</div>
							<div>
								<dt>Last upload</dt>
								<dd>{content ? (lastUpload ? formatRelative(lastUpload) : 'Never') : '…'}</dd>
							</div>
						</dl>
					</a>
				);
			})}
		</div>
	);
}

function CreateRepositoryDialog({onClose}: { onClose: () => void }) {
	const {notify} = useSession();
	const allRepositories = useAsync(() => api.repositories(), []);
	const [name, setName] = useState('');
	const [type, setType] = useState<RepositoryType>('maven');
	const [kind, setKind] = useState<RepositoryClass>('LOCAL');
	const [description, setDescription] = useState('');
	const [publicRead, setPublicRead] = useState(false);
	const [allowRedeploy, setAllowRedeploy] = useState(false);
	const [remoteUrl, setRemoteUrl] = useState('');
	const [cacheEnabled, setCacheEnabled] = useState(true);
	const [remoteUser, setRemoteUser] = useState('');
	const [remotePassword, setRemotePassword] = useState('');
	const [members, setMembers] = useState<string[]>([]);
	const [busy, setBusy] = useState(false);
	const [error, setError] = useState<string>();
	
	const proxyable = PROXYABLE_TYPES.includes(type);
	// Candidate members: same type, and not virtual themselves
	const candidates = (allRepositories.data ?? []).filter(r => r.type === type && r.kind !== 'VIRTUAL');
	const toggleMember = (member: string) => setMembers(current => current.includes(member) ? current.filter(m => m !== member) : [...current, member]);
	
	const submit = async (event: FormEvent) => {
		event.preventDefault();
		setBusy(true);
		setError(undefined);
		try {
			const repository = await api.createRepository({
				name: name.trim(),
				type,
				description: description.trim() || undefined,
				publicRead,
				allowRedeploy: allowRedeploy && !IMMUTABLE_TYPES.includes(type),
				remote: kind === 'REMOTE' ? {url: remoteUrl.trim(), cacheEnabled, username: remoteUser.trim() || undefined, password: remotePassword.trim() || undefined} : undefined,
				members: kind === 'VIRTUAL' ? members : undefined,
			});
			notify(`Repository ${repository.name} created`);
			navigate(href('repositories', repository.name) + '?tab=setup');
		} catch (e) {
			setError(errorText(e));
			setBusy(false);
		}
	};
	
	return (
		<Modal title="New repository" onClose={onClose} wide>
			<form className="form" onSubmit={submit}>
				<div className="field">
					<span className="field-label">Type</span>
					<div className="type-picker">
						{REPOSITORY_TYPES.map(id => (
							<button key={id} type="button" className={`type-option${type === id ? ' selected' : ''}`} onClick={() => {
								setType(id);
								setMembers([]);
								if (!PROXYABLE_TYPES.includes(id)) {
									setKind('LOCAL');
								}
							}} aria-pressed={type === id}>
								<TypeBadge type={id}/>
								<small className="muted">{TYPE_INFO[id].ecosystem}</small>
							</button>
						))}
					</div>
				</div>
				<Field label="Kind" hint={proxyable ? 'Local stores artifacts, remote proxies an upstream, virtual groups repositories' : 'Remote and virtual repositories currently support only maven and generic'}>
					<Select<RepositoryClass> label="Kind" value={kind} onChange={setKind} options={[
						{value: 'LOCAL', label: 'Local'},
						{value: 'REMOTE', label: 'Remote (proxy)', disabled: !proxyable},
						{value: 'VIRTUAL', label: 'Virtual (group)', disabled: !proxyable},
					]}/>
				</Field>
				<Field label="Name" hint="Letters, digits, dots, dashes and underscores, e.g. releases or npm-local">
					<input value={name} onChange={event => setName(event.target.value)} required autoFocus pattern="[A-Za-z0-9][A-Za-z0-9._\-]{0,63}" maxLength={64}/>
				</Field>
				<Field label="Description">
					<input value={description} onChange={event => setDescription(event.target.value)}/>
				</Field>
	
				{kind === 'REMOTE' && (
					<>
						<Field label="Upstream URL" hint="Base url of the remote repository to proxy, e.g. https://repo1.maven.org/maven2">
							<input value={remoteUrl} onChange={event => setRemoteUrl(event.target.value)} required placeholder="https://repo1.maven.org/maven2" maxLength={1024}/>
						</Field>
						<Field label="Upstream username" hint="Optional, for authenticated upstreams">
							<input value={remoteUser} onChange={event => setRemoteUser(event.target.value)} autoComplete="off"/>
						</Field>
						<Field label="Upstream password" hint="Optional">
							<input type="password" value={remotePassword} onChange={event => setRemotePassword(event.target.value)} autoComplete="new-password"/>
						</Field>
						<Toggle checked={cacheEnabled} onChange={setCacheEnabled} label="Cache artifacts" description="Store fetched artifacts locally on first download"/>
					</>
				)}
				{kind === 'VIRTUAL' && (
					<Field group label="Members" hint="Repositories resolved in order, the first match wins">
						{candidates.length === 0
							? <span className="muted small">No other {type} repositories to group yet</span>
							: <div className="checkbox-list">
								{candidates.map(repo => (
									<label key={repo.name}>
										<input type="checkbox" checked={members.includes(repo.name)} onChange={() => toggleMember(repo.name)}/>
										<span>{repo.name}</span>
										{repo.kind === 'REMOTE' && <Badge tone="accent">remote</Badge>}
									</label>
								))}
							</div>}
					</Field>
				)}
	
				{kind === 'LOCAL' && !IMMUTABLE_TYPES.includes(type) && <Toggle checked={allowRedeploy} onChange={setAllowRedeploy} label="Allow redeploy" description="Released versions can be overwritten (not recommended)"/>}
				<Toggle checked={publicRead} onChange={setPublicRead} label="Public read" description="Anyone can download without credentials"/>
				{error && <ErrorBox error={error}/>}
				<div className="modal-footer">
					<Button onClick={onClose} disabled={busy}>Cancel</Button>
					<Button variant="primary" type="submit" disabled={busy || !name.trim() || (kind === 'REMOTE' && !remoteUrl.trim()) || (kind === 'VIRTUAL' && members.length === 0)}>{busy ? 'Creating…' : 'Create repository'}</Button>
				</div>
			</form>
		</Modal>
	);
}
