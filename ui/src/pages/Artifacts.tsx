import {useEffect, useMemo, useState} from 'react';
import {api, downloadFile} from '../api';
import {Icon} from '../components/icons';
import {Badge, Button, Empty, ErrorBox, PageHeader, SearchField, Spinner, Stat, Stats, Tabs, TYPE_INFO, TypeBadge} from '../components/ui';
import {compareVersions, formatBytes, formatNumber, formatRelative} from '../format';
import {useAsync} from '../hooks';
import {latestLabel, loadSummary, summarizeTypes, typeCounts} from '../inventory';
import {href, navigate} from '../router';
import {useSession} from '../session';
import {type RepositoryType, visibleTypes} from '../types';
import {Select} from '../components/Select';
import {SortHeader, useServerSort} from '../components/sort';
import {Pagination, useServerPaging} from '../components/paging';

type Column = 'name' | 'repository' | 'versions' | 'size' | 'updated';
type FileColumn = 'path' | 'repository' | 'size' | 'uploaded';

/**
 * Keeps the search text and the value sent to the server apart, so typing only sends a request once it pauses.
 */
function useDebounced(value: string, delay = 250): string {
	const [debounced, setDebounced] = useState(value);
	useEffect(() => {
		const timer = setTimeout(() => setDebounced(value), delay);
		return () => clearTimeout(timer);
	}, [value, delay]);
	return debounced;
}

export function Artifacts({type}: { type: RepositoryType | undefined }) {
	const {guest} = useSession();
	const summary = useAsync(() => loadSummary(true), []);
	const [search, setSearch] = useState('');
	const [repository, setRepository] = useState('');
	const query = useDebounced(search.trim());

	const counts = summary.data ? typeCounts(summary.data) : undefined;
	const total = summary.data ? summarizeTypes(summary.data).filter(entry => entry.type !== 'generic').reduce((sum, entry) => sum + entry.packages, 0) : 0;
	// The all tab sums up every type, the type tabs show their own numbers
	const stats = useMemo(() => {
		if (!summary.data) {
			return undefined;
		}
		return summarizeTypes(summary.data).filter(entry => !type || entry.type === type).reduce((sum, entry) => ({
			repositories: sum.repositories + entry.repositories,
			packages: sum.packages + entry.packages,
			versions: sum.versions + entry.versions,
			files: sum.files + entry.files,
			size: sum.size + entry.size,
		}), {repositories: 0, packages: 0, versions: 0, files: 0, size: 0});
	}, [summary.data, type]);
	const repositories = (summary.data?.repositories ?? []).filter(entry => (!type || entry.type === type) && entry.kind !== 'VIRTUAL');

	const tabs = [
		{id: 'all', label: <>All packages <span className="tab-count">{total}</span></>},
		...visibleTypes(summary.data?.repositories, guest).map(id => ({id, label: <>{TYPE_INFO[id].label} <span className="tab-count">{counts?.[id] ?? 0}</span></>})),
	];

	return (
		<>
			<PageHeader
				title={type ? `${TYPE_INFO[type].label} artifacts` : 'Artifacts'}
				subtitle={type ? `All ${TYPE_INFO[type].ecosystem} packages across your ${TYPE_INFO[type].label} repositories.` : 'All packages across every repository you can read.'}
			/>
			<Tabs tabs={tabs} active={type ?? 'all'} onChange={id => navigate(id === 'all' ? href('artifacts') : href('artifacts', id))}/>

			{summary.error && <ErrorBox error={summary.error} onRetry={summary.reload}/>}

			{stats && (
				<Stats>
					<Stat label="Repositories" value={stats.repositories}/>
					{type !== 'generic' && <Stat label="Packages" value={formatNumber(stats.packages)}/>}
					{type !== 'generic' && <Stat label="Versions" value={formatNumber(stats.versions)}/>}
					<Stat label="Files" value={formatNumber(stats.files)}/>
					<Stat label="Storage" value={formatBytes(stats.size)}/>
				</Stats>
			)}

			<div className="toolbar">
				<SearchField placeholder={type === 'generic' ? 'Search files…' : 'Search packages…'} value={search} onChange={setSearch}/>
				<Select label="Repository" value={repository} onChange={setRepository} options={[
					{value: '', label: 'All repositories'},
					...repositories.map(entry => ({value: entry.name, label: entry.name, hint: type ? undefined : TYPE_INFO[entry.type].label})),
				]}/>
			</div>

			{type === 'generic'
				? <GenericFiles query={query} repository={repository}/>
				: <PackageTable type={type} query={query} repository={repository} empty={type && summary.data && repositories.length === 0
					? <>There is no {TYPE_INFO[type].label} repository yet. <a className="link" href={href('repositories')}>Create one</a>.</> : null}/>}
		</>
	);
}

function PackageTable({type, query, repository, empty}: { type: RepositoryType | undefined; query: string; repository: string; empty: React.ReactNode }) {
	const {sort, toggle, params} = useServerSort<Column>();
	const {offset, limit, paging} = useServerPaging([type, query, repository, sort?.key, sort?.direction]);
	const page = useAsync(() => api.allPackages({type, repository, q: query}, {offset, limit, ...params}), [type, query, repository, offset, limit, params.sort, params.dir]);

	if (page.error) {
		return <ErrorBox error={page.error} onRetry={page.reload}/>;
	}
	if (!page.data) {
		return <Spinner label="Loading artifacts"/>;
	}
	if (page.data.total === 0) {
		return <Empty title={query || repository ? 'No matching packages' : 'No artifacts yet'}>{empty}</Empty>;
	}
	return (
		<div className={`table-wrap${page.loading ? ' loading' : ''}`}>
			<table className="table">
				<thead>
				<tr>
					<SortHeader id="name" sort={sort} onSort={toggle}>Package</SortHeader>
					<th>Latest</th>
					<SortHeader id="repository" sort={sort} onSort={toggle}>Repository</SortHeader>
					<SortHeader id="versions" sort={sort} onSort={toggle} className="num">Versions</SortHeader>
					<SortHeader id="size" sort={sort} onSort={toggle} className="num">Size</SortHeader>
					<SortHeader id="updated" sort={sort} onSort={toggle}>Updated</SortHeader>
				</tr>
				</thead>
				<tbody>
				{page.data.rows.map(entry => {
					const yanked = entry.versions.filter(version => version.yanked).length;
					const latest = latestLabel(entry.type, entry);
					const target = href('repositories', entry.repository, 'packages', entry.name);
					return (
						<tr key={`${entry.repository}/${entry.name}`} className="clickable" onClick={() => navigate(target)}>
							<td>
								<span className="cell-inline">
									<a className="name strong" href={target} onClick={event => event.stopPropagation()}>{entry.name}</a>
									{Object.keys(entry.tags).filter(tag => tag !== 'latest' && tag !== latest).slice(0, 3).map(tag => <Badge key={tag}>{tag}</Badge>)}
								</span>
							</td>
							<td>{latest ? <Badge tone="accent">{latest}</Badge> : <span className="faint">—</span>}</td>
							<td className="nowrap">
								<span className="cell-inline">{!type && <TypeBadge type={entry.type}/>}{entry.repository}</span>
							</td>
							<td className="num">
								{entry.versions.length}
								{yanked > 0 && <span className="muted small"> ({yanked} {entry.type === 'nuget' ? 'unlisted' : 'yanked'})</span>}
							</td>
							<td className="num">{formatBytes(entry.size)}</td>
							<td className="muted nowrap" title={entry.versions.map(version => version.version).sort(compareVersions).join(', ')}>{formatRelative(entry.updatedAt ?? undefined)}</td>
						</tr>
					);
				})}
				</tbody>
			</table>
			<Pagination paging={paging(page.data.total)} noun="packages"/>
		</div>
	);
}

/**
 * Generic repositories store plain files without package metadata, so they are listed by file.
 */
function GenericFiles({query, repository}: { query: string; repository: string }) {
	const {notify} = useSession();
	const {sort, toggle, params} = useServerSort<FileColumn>();
	const {offset, limit, paging} = useServerPaging([query, repository, sort?.key, sort?.direction]);
	const page = useAsync(() => api.allFiles({type: 'generic', repository, q: query}, {offset, limit, ...params}), [query, repository, offset, limit, params.sort, params.dir]);

	if (page.error) {
		return <ErrorBox error={page.error} onRetry={page.reload}/>;
	}
	if (!page.data) {
		return <Spinner label="Loading files"/>;
	}
	if (page.data.total === 0) {
		return <Empty title={query || repository ? 'No matching files' : 'No generic files yet'}/>;
	}
	return (
		<div className={`table-wrap${page.loading ? ' loading' : ''}`}>
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
				{page.data.rows.map(file => (
					<tr key={`${file.repository}/${file.path}`}>
						<td className="name break">{file.path}</td>
						<td className="nowrap"><a className="link" href={`${href('repositories', file.repository)}?tab=files`}>{file.repository}</a></td>
						<td className="num">{formatBytes(file.size)}</td>
						<td className="muted nowrap" title={file.createdAt}>{file.createdBy ? `${file.createdBy} · ` : ''}{formatRelative(file.createdAt)}</td>
						<td className="actions">
							<Button small variant="ghost" className="btn-icon" title="Download" aria-label={`Download ${file.path}`}
									onClick={() => downloadFile({type: 'generic', name: file.repository}, file).catch(error => notify(error.message, 'error'))}>
								<Icon name="download" size={16}/>
							</Button>
						</td>
					</tr>
				))}
				</tbody>
			</table>
			<Pagination paging={paging(page.data.total)} noun="files"/>
		</div>
	);
}
