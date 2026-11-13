import {useMemo, useState} from 'react';
import {downloadFile} from '../api';
import {Icon} from '../components/icons';
import {Badge, Button, Empty, ErrorBox, PageHeader, SearchField, Spinner, Stat, Stats, Tabs, TYPE_INFO, TypeBadge} from '../components/ui';
import {compareVersions, formatBytes, formatNumber, formatRelative} from '../format';
import {useAsync} from '../hooks';
import {loadInventory, type PackageSummary, type RepositoryInventory, summarizePackages, summarizeTypes} from '../inventory';
import {href, navigate} from '../router';
import {useSession} from '../session';
import {REPOSITORY_TYPES, type RepositoryType, visibleTypes} from '../types';
import {Select} from '../components/Select';
import {SortHeader, useSort} from '../components/sort';
import {Pagination, usePaging} from '../components/paging';

type Column = 'name' | 'latest' | 'repository' | 'versions' | 'size' | 'updated';
type FileColumn = 'path' | 'repository' | 'size' | 'uploaded';

export function Artifacts({type}: { type: RepositoryType | undefined }) {
	const {guest} = useSession();
	const inventory = useAsync(loadInventory, []);
	const [search, setSearch] = useState('');
	const [repository, setRepository] = useState('');
	
	const all = useMemo(() => (inventory.data ?? []).flatMap(summarizePackages), [inventory.data]);
	// Generic repositories have no packages, their tab counts files instead
	const counts = useMemo(() => Object.fromEntries(REPOSITORY_TYPES.map(id => [id, id === 'generic'
		? (inventory.data ?? []).filter(entry => entry.repository.type === id).reduce((sum, entry) => sum + entry.files.length, 0)
		: all.filter(entry => entry.repository.type === id).length])), [all, inventory.data]);
	// The all tab sums up every type, the type tabs show their own numbers
	const summary = useMemo(() => {
		if (!inventory.data) {
			return undefined;
		}
		const types = summarizeTypes(inventory.data).filter(entry => !type || entry.type === type);
		return types.reduce((sum, entry) => ({
			repositories: sum.repositories + entry.repositories,
			packages: sum.packages + entry.packages,
			versions: sum.versions + entry.versions,
			files: sum.files + entry.files,
			size: sum.size + entry.size,
		}), {repositories: 0, packages: 0, versions: 0, files: 0, size: 0});
	}, [inventory.data, type]);
	const repositories = (inventory.data ?? []).map(entry => entry.repository).filter(entry => !type || entry.type === type);
	
	const rows = useMemo(() => {
		const query = search.trim().toLowerCase();
		const filtered = all.filter(entry =>
			(!type || entry.repository.type === type)
			&& (!repository || entry.repository.name === repository)
			&& (!query || entry.pkg.name.toLowerCase().includes(query)),
		);
		// Default order: recently updated first
		return filtered.sort((a, b) => (b.updatedAt ?? '').localeCompare(a.updatedAt ?? ''));
	}, [all, type, repository, search]);
	const {sorted, sort, toggle} = useSort<PackageSummary, Column>(rows, {
		name: entry => entry.pkg.name,
		latest: entry => entry.latest,
		repository: entry => entry.repository.name,
		versions: entry => entry.pkg.versions.length,
		size: entry => entry.size,
		updated: entry => entry.updatedAt,
	}, {latest: (a, b) => compareVersions(a.latest ?? '', b.latest ?? '')});
	const {visible, paging} = usePaging(sorted);
	
	const tabs = [
		{id: 'all', label: <>All packages <span className="tab-count">{all.length}</span></>},
		...visibleTypes(inventory.data?.map(entry => entry.repository), guest).map(id => ({id, label: <>{TYPE_INFO[id].label} <span className="tab-count">{counts[id] ?? 0}</span></>})),
	];
	
	return (
		<>
			<PageHeader
				title={type ? `${TYPE_INFO[type].label} artifacts` : 'Artifacts'}
				subtitle={type ? `All ${TYPE_INFO[type].ecosystem} packages across your ${TYPE_INFO[type].label} repositories.` : 'All packages across every repository you can read.'}
			/>
			<Tabs tabs={tabs} active={type ?? 'all'} onChange={id => navigate(id === 'all' ? href('artifacts') : href('artifacts', id))}/>
			
			{inventory.loading && !inventory.data && <Spinner label="Loading artifacts"/>}
			{inventory.error && <ErrorBox error={inventory.error} onRetry={inventory.reload}/>}
			
			{summary && (
				<Stats>
					<Stat label="Repositories" value={summary.repositories}/>
					{type !== 'generic' && <Stat label="Packages" value={formatNumber(summary.packages)}/>}
					{type !== 'generic' && <Stat label="Versions" value={formatNumber(summary.versions)}/>}
					<Stat label="Files" value={formatNumber(summary.files)}/>
					<Stat label="Storage" value={formatBytes(summary.size)}/>
				</Stats>
			)}
			
			{inventory.data && type === 'generic' && <GenericFiles inventory={inventory.data}/>}
			
			{inventory.data && type !== 'generic' && (
				<>
					<div className="toolbar">
						<SearchField placeholder="Search packages…" value={search} onChange={setSearch}/>
						<Select label="Repository" value={repository} onChange={setRepository} options={[
							{value: '', label: 'All repositories'},
							...repositories.map(entry => ({value: entry.name, label: entry.name, hint: type ? undefined : TYPE_INFO[entry.type].label})),
						]}/>
						<div className="toolbar-spacer"/>
						<span className="muted small">{rows.length} of {type ? counts[type] ?? 0 : all.length} packages</span>
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
									<SortHeader id="name" sort={sort} onSort={toggle}>Package</SortHeader>
									<SortHeader id="latest" sort={sort} onSort={toggle}>Latest</SortHeader>
									<SortHeader id="repository" sort={sort} onSort={toggle}>Repository</SortHeader>
									<SortHeader id="versions" sort={sort} onSort={toggle} className="num">Versions</SortHeader>
									<SortHeader id="size" sort={sort} onSort={toggle} className="num">Size</SortHeader>
									<SortHeader id="updated" sort={sort} onSort={toggle}>Updated</SortHeader>
								</tr>
								</thead>
								<tbody>
								{visible.map(entry => {
									const yanked = entry.pkg.versions.filter(version => version.yanked).length;
									const target = href('repositories', entry.repository.name, 'packages', entry.pkg.name);
									return (
										<tr key={`${entry.repository.name}/${entry.pkg.name}`} className="clickable" onClick={() => navigate(target)}>
											<td>
												<span className="cell-inline">
													<a className="name strong" href={target} onClick={event => event.stopPropagation()}>{entry.pkg.name}</a>
													{Object.keys(entry.pkg.tags).filter(tag => tag !== 'latest' && tag !== entry.latest).slice(0, 3).map(tag => <Badge key={tag}>{tag}</Badge>)}
												</span>
											</td>
											<td>{entry.latest ? <Badge tone="accent">{entry.latest}</Badge> : <span className="faint">—</span>}</td>
											<td className="nowrap">
												<span className="cell-inline">{!type && <TypeBadge type={entry.repository.type}/>}{entry.repository.name}</span>
											</td>
											<td className="num">
												{entry.pkg.versions.length}
												{yanked > 0 && <span className="muted small"> ({yanked} {entry.repository.type === 'nuget' ? 'unlisted' : 'yanked'})</span>}
											</td>
											<td className="num">{formatBytes(entry.size)}</td>
											<td className="muted nowrap" title={entry.pkg.versions.map(version => version.version).sort(compareVersions).join(', ')}>{formatRelative(entry.updatedAt)}</td>
										</tr>
									);
								})}
								</tbody>
							</table>
							<Pagination paging={paging} noun="packages"/>
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
function GenericFiles({inventory}: { inventory: RepositoryInventory[] }) {
	const {notify} = useSession();
	const [search, setSearch] = useState('');
	const query = search.trim().toLowerCase();
	const files = inventory
		.filter(entry => entry.repository.type === 'generic')
		.flatMap(entry => entry.files.map(file => ({repository: entry.repository, file})))
		.filter(entry => !query || entry.file.path.toLowerCase().includes(query))
		.sort((a, b) => b.file.createdAt.localeCompare(a.file.createdAt));
	const {sorted, sort, toggle} = useSort<(typeof files)[number], FileColumn>(files, {
		path: entry => entry.file.path,
		repository: entry => entry.repository.name,
		size: entry => entry.file.size,
		uploaded: entry => entry.file.createdAt,
	});
	const {visible, paging} = usePaging(sorted);
	
	return (
		<>
			<div className="toolbar">
				<SearchField placeholder="Search files…" value={search} onChange={setSearch}/>
			</div>
			{files.length === 0 ? (
				<Empty title={query ? 'No matching files' : 'No generic files yet'}/>
			) : (
				<div className="table-wrap">
					<table className="table">
						<thead>
						<tr>
							<SortHeader id="path" sort={sort} onSort={toggle}>Path</SortHeader>
							<SortHeader id="repository" sort={sort} onSort={toggle}>Repository</SortHeader>
							<SortHeader id="size" sort={sort} onSort={toggle} className="num">Size</SortHeader>
							<SortHeader id="uploaded" sort={sort} onSort={toggle}>Uploaded</SortHeader>
							<th/>
						</tr>
						</thead>
						<tbody>
						{visible.map(({repository, file}) => (
							<tr key={`${repository.name}/${file.path}`}>
								<td className="name break">{file.path}</td>
								<td className="nowrap"><a className="link" href={`${href('repositories', repository.name)}?tab=files`}>{repository.name}</a></td>
								<td className="num">{formatBytes(file.size)}</td>
								<td className="muted nowrap" title={file.createdAt}>{file.createdBy ? `${file.createdBy} · ` : ''}{formatRelative(file.createdAt)}</td>
								<td className="actions">
									<Button small variant="ghost" className="btn-icon" title="Download" aria-label={`Download ${file.path}`} onClick={() => downloadFile(repository, file).catch(error => notify(error.message, 'error'))}>
										<Icon name="download" size={16}/>
									</Button>
								</td>
							</tr>
						))}
						</tbody>
					</table>
					<Pagination paging={paging} noun="files"/>
				</div>
			)}
		</>
	);
}
