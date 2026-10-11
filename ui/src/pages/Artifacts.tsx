import { useMemo, useState } from 'react';
import { downloadFile } from '../api';
import { Badge, Button, Empty, ErrorBox, PageHeader, Spinner, Stat, TYPE_INFO, Tabs, TypeBadge } from '../components/ui';
import { compareVersions, formatBytes, formatNumber, formatRelative } from '../format';
import { useAsync } from '../hooks';
import { loadInventory, summarizePackages, summarizeTypes, type PackageSummary, type RepositoryInventory } from '../inventory';
import { href, navigate } from '../router';
import { useSession } from '../session';
import { REPOSITORY_TYPES, type RepositoryType } from '../types';

type Sort = 'name' | 'updated' | 'size' | 'versions';

export function Artifacts({ type }: { type: RepositoryType | undefined }) {
	const inventory = useAsync(loadInventory, []);
	const [search, setSearch] = useState('');
	const [repository, setRepository] = useState('');
	const [sort, setSort] = useState<Sort>('updated');

	const all = useMemo(() => (inventory.data ?? []).flatMap(summarizePackages), [inventory.data]);
	const counts = useMemo(() => Object.fromEntries(REPOSITORY_TYPES.map(id => [id, all.filter(entry => entry.repository.type === id).length])), [all]);
	const summary = inventory.data && type ? summarizeTypes(inventory.data).find(entry => entry.type === type) : undefined;
	const repositories = (inventory.data ?? []).map(entry => entry.repository).filter(entry => !type || entry.type === type);

	const rows = useMemo(() => {
		const query = search.trim().toLowerCase();
		const filtered = all.filter(entry =>
			(!type || entry.repository.type === type)
			&& (!repository || entry.repository.name === repository)
			&& (!query || entry.pkg.name.toLowerCase().includes(query)),
		);
		const comparators: Record<Sort, (a: PackageSummary, b: PackageSummary) => number> = {
			name: (a, b) => a.pkg.name.localeCompare(b.pkg.name),
			updated: (a, b) => (b.updatedAt ?? '').localeCompare(a.updatedAt ?? ''),
			size: (a, b) => b.size - a.size,
			versions: (a, b) => b.pkg.versions.length - a.pkg.versions.length,
		};
		return filtered.sort(comparators[sort]);
	}, [all, type, repository, search, sort]);

	const tabs = [
		{ id: 'all', label: <>All <span className="tab-count">{all.length}</span></> },
		...REPOSITORY_TYPES.map(id => ({ id, label: <>{TYPE_INFO[id].label} <span className="tab-count">{counts[id] ?? 0}</span></> })),
	];

	return (
		<>
			<PageHeader
				title={type ? `${TYPE_INFO[type].label} artifacts` : 'Artifacts'}
				subtitle={type ? `All ${TYPE_INFO[type].ecosystem} packages across your ${TYPE_INFO[type].label} repositories.` : 'All packages across every repository you can read.'}
			/>
			<Tabs tabs={tabs} active={type ?? 'all'} onChange={id => navigate(id === 'all' ? href('artifacts') : href('artifacts', id))} />

			{inventory.loading && !inventory.data && <Spinner label="Loading artifacts" />}
			{inventory.error && <ErrorBox error={inventory.error} onRetry={inventory.reload} />}

			{summary && (
				<div className="stats-row">
					<Stat label="Repositories" value={summary.repositories} />
					<Stat label="Packages" value={formatNumber(summary.packages)} />
					<Stat label="Versions" value={formatNumber(summary.versions)} />
					<Stat label="Files" value={formatNumber(summary.files)} />
					<Stat label="Storage" value={formatBytes(summary.size)} />
				</div>
			)}

			{inventory.data && type === 'generic' && <GenericFiles inventory={inventory.data} />}
			
			{inventory.data && type !== 'generic' && (
				<>
					<div className="toolbar">
						<input className="search" type="search" placeholder="Search packages…" value={search} onChange={event => setSearch(event.target.value)} />
						<select value={repository} onChange={event => setRepository(event.target.value)} aria-label="Repository">
							<option value="">All repositories</option>
							{repositories.map(entry => <option key={entry.name} value={entry.name}>{entry.name}</option>)}
						</select>
						<select value={sort} onChange={event => setSort(event.target.value as Sort)} aria-label="Sort">
							<option value="updated">Recently updated</option>
							<option value="name">Name</option>
							<option value="versions">Most versions</option>
							<option value="size">Largest</option>
						</select>
					</div>

					{rows.length === 0 ? (
						<Empty title={all.length === 0 ? 'No artifacts yet' : 'No matching packages'}>
							{type && repositories.length === 0 ? <>There is no {TYPE_INFO[type].label} repository yet. <a className="link" href={href('repositories')}>Create one</a>.</> : null}
						</Empty>
					) : (
						<div className="table-wrap">
							<table className="table">
								<thead>
									<tr>
										<th>Package</th>
										<th>Latest</th>
										<th>Repository</th>
										<th className="num">Versions</th>
										<th className="num">Size</th>
										<th>Updated</th>
									</tr>
								</thead>
								<tbody>
									{rows.map(entry => {
										const yanked = entry.pkg.versions.filter(version => version.yanked).length;
										const target = `${href('repositories', entry.repository.name)}?package=${encodeURIComponent(entry.pkg.name)}`;
										return (
											<tr key={`${entry.repository.name}/${entry.pkg.name}`} className="clickable" onClick={() => navigate(target)}>
												<td>
													<a className="mono strong" href={target} onClick={event => event.stopPropagation()}>{entry.pkg.name}</a>
													{Object.keys(entry.pkg.tags).filter(tag => tag !== 'latest').slice(0, 3).map(tag => <Badge key={tag}>{tag}</Badge>)}
												</td>
												<td className="mono">{entry.latest ?? '—'}</td>
												<td>
													<span className="cell-inline">{!type && <TypeBadge type={entry.repository.type} />}{entry.repository.name}</span>
												</td>
												<td className="num">
													{entry.pkg.versions.length}
													{yanked > 0 && <span className="muted small"> ({yanked} {entry.repository.type === 'nuget' ? 'unlisted' : 'yanked'})</span>}
												</td>
												<td className="num">{formatBytes(entry.size)}</td>
												<td className="muted" title={entry.pkg.versions.map(version => version.version).sort(compareVersions).join(', ')}>{formatRelative(entry.updatedAt)}</td>
											</tr>
										);
									})}
								</tbody>
							</table>
						</div>
					)}
				</>
			)}
		</>
	);
}

/**
 * Generic repositories store plain files without package metadata, so they are listed by file.
 */
function GenericFiles({ inventory }: { inventory: RepositoryInventory[] }) {
	const { notify } = useSession();
	const [search, setSearch] = useState('');
	const query = search.trim().toLowerCase();
	const files = inventory
		.filter(entry => entry.repository.type === 'generic')
		.flatMap(entry => entry.files.map(file => ({ repository: entry.repository, file })))
		.filter(entry => !query || entry.file.path.toLowerCase().includes(query))
		.sort((a, b) => b.file.createdAt.localeCompare(a.file.createdAt));
	
	return (
		<>
			<div className="toolbar">
				<input className="search" type="search" placeholder="Search files…" value={search} onChange={event => setSearch(event.target.value)} />
			</div>
			{files.length === 0 ? (
				<Empty title={query ? 'No matching files' : 'No generic files yet'} />
			) : (
				<div className="table-wrap">
					<table className="table">
						<thead>
							<tr>
								<th>Path</th>
								<th>Repository</th>
								<th className="num">Size</th>
								<th>Uploaded</th>
								<th />
							</tr>
						</thead>
						<tbody>
							{files.slice(0, 500).map(({ repository, file }) => (
								<tr key={`${repository.name}/${file.path}`}>
									<td className="mono break">{file.path}</td>
									<td><a className="link" href={href('repositories', repository.name)}>{repository.name}</a></td>
									<td className="num">{formatBytes(file.size)}</td>
									<td className="muted">{file.createdBy ? `${file.createdBy} · ` : ''}{formatRelative(file.createdAt)}</td>
									<td className="actions"><Button small variant="ghost" onClick={() => downloadFile(repository, file).catch(error => notify(error.message, 'error'))}>Download</Button></td>
								</tr>
							))}
						</tbody>
					</table>
					{files.length > 500 && <p className="muted small table-note">Showing the newest 500 of {formatNumber(files.length)} files.</p>}
				</div>
			)}
		</>
	);
}
