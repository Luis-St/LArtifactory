import { useState, type FormEvent } from 'react';
import { api } from '../api';
import { Badge, Button, Empty, ErrorBox, Field, Modal, PageHeader, Spinner, TYPE_INFO, Toggle, TypeBadge, errorText } from '../components/ui';
import { formatDate } from '../format';
import { useAsync } from '../hooks';
import { href, navigate } from '../router';
import { useSession } from '../session';
import { REPOSITORY_TYPES, type RepositoryType } from '../types';

export function Repositories() {
	const { principal } = useSession();
	const repositories = useAsync(() => api.repositories(), []);
	const [filter, setFilter] = useState<RepositoryType | ''>('');
	const [search, setSearch] = useState('');
	const [creating, setCreating] = useState(false);

	const query = search.trim().toLowerCase();
	const rows = (repositories.data ?? [])
		.filter(repository => !filter || repository.type === filter)
		.filter(repository => !query || repository.name.toLowerCase().includes(query) || (repository.description ?? '').toLowerCase().includes(query))
		.sort((a, b) => a.name.localeCompare(b.name));

	return (
		<>
			<PageHeader
				title="Repositories"
				subtitle="Every repository hosts one package format and is served at /{type}/{name}/."
				actions={principal?.admin && <Button variant="primary" onClick={() => setCreating(true)}>New repository</Button>}
			/>

			<div className="toolbar">
				<input className="search" type="search" placeholder="Search repositories…" value={search} onChange={event => setSearch(event.target.value)} />
				<select value={filter} onChange={event => setFilter(event.target.value as RepositoryType | '')} aria-label="Type">
					<option value="">All types</option>
					{REPOSITORY_TYPES.map(type => <option key={type} value={type}>{TYPE_INFO[type].label}</option>)}
				</select>
			</div>

			{repositories.loading && !repositories.data && <Spinner />}
			{repositories.error && <ErrorBox error={repositories.error} onRetry={repositories.reload} />}
			{repositories.data && (rows.length === 0 ? (
				<Empty title={repositories.data.length === 0 ? 'No repositories yet' : 'No matching repositories'}>
					{principal?.admin && repositories.data.length === 0 && <Button variant="primary" onClick={() => setCreating(true)}>Create a repository</Button>}
				</Empty>
			) : (
				<div className="table-wrap">
					<table className="table">
						<thead>
							<tr>
								<th>Name</th>
								<th>Type</th>
								<th>Description</th>
								<th>Access</th>
								<th>Created</th>
							</tr>
						</thead>
						<tbody>
							{rows.map(repository => (
								<tr key={repository.name} className="clickable" onClick={() => navigate(href('repositories', repository.name))}>
									<td><a className="strong" href={href('repositories', repository.name)} onClick={event => event.stopPropagation()}>{repository.name}</a></td>
									<td><TypeBadge type={repository.type} /></td>
									<td className="muted">{repository.description || '—'}</td>
									<td className="cell-inline">
										{repository.publicRead ? <Badge tone="success">public read</Badge> : <Badge>private</Badge>}
										{repository.allowRedeploy && <Badge tone="warning">redeploy</Badge>}
									</td>
									<td className="muted">{formatDate(repository.createdAt)}</td>
								</tr>
							))}
						</tbody>
					</table>
				</div>
			))}

			{creating && <CreateRepositoryDialog onClose={() => setCreating(false)} />}
		</>
	);
}

function CreateRepositoryDialog({ onClose }: { onClose: () => void }) {
	const { notify } = useSession();
	const [name, setName] = useState('');
	const [type, setType] = useState<RepositoryType>('maven');
	const [description, setDescription] = useState('');
	const [publicRead, setPublicRead] = useState(false);
	const [allowRedeploy, setAllowRedeploy] = useState(false);
	const [busy, setBusy] = useState(false);
	const [error, setError] = useState<string>();

	const submit = async (event: FormEvent) => {
		event.preventDefault();
		setBusy(true);
		setError(undefined);
		try {
			const repository = await api.createRepository({ name: name.trim(), type, description: description.trim() || undefined, publicRead, allowRedeploy });
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
							<button key={id} type="button" className={`type-option${type === id ? ' selected' : ''}`} onClick={() => setType(id)} aria-pressed={type === id}>
								<TypeBadge type={id} />
								<small className="muted">{TYPE_INFO[id].ecosystem}</small>
							</button>
						))}
					</div>
				</div>
				<Field label="Name" hint="Letters, digits, dots, dashes and underscores, e.g. releases or npm-local">
					<input value={name} onChange={event => setName(event.target.value)} required autoFocus pattern="[A-Za-z0-9][A-Za-z0-9._\-]{0,63}" maxLength={64} />
				</Field>
				<Field label="Description">
					<input value={description} onChange={event => setDescription(event.target.value)} />
				</Field>
				<Toggle checked={publicRead} onChange={setPublicRead} label="Public read" description="Anyone can download without credentials" />
				<Toggle checked={allowRedeploy} onChange={setAllowRedeploy} label="Allow redeploy" description="Released versions can be overwritten (not recommended)" />
				{error && <ErrorBox error={error} />}
				<div className="modal-footer">
					<Button onClick={onClose} disabled={busy}>Cancel</Button>
					<Button variant="primary" type="submit" disabled={busy || !name.trim()}>{busy ? 'Creating…' : 'Create repository'}</Button>
				</div>
			</form>
		</Modal>
	);
}
