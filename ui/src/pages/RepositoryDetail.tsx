import {type FormEvent, type ReactNode, useEffect, useMemo, useState} from 'react';
import {api, deleteFile, downloadFile} from '../api';
import {Icon} from '../components/icons';
import {Badge, Button, Card, ConfirmDialog, CopyButton, Empty, ErrorBox, errorText, Field, PageHeader, SearchField, SnippetTabs, Spinner, Stat, Stats, Tabs, Toggle, TYPE_INFO, TypeBadge} from '../components/ui';
import {compareVersions, fileName, formatBytes, formatDate, formatNumber, formatRelative, isDigest, shortDigest} from '../format';
import {useAsync} from '../hooks';
import {latestLabel} from '../inventory';
import {href, navigate, versionHref} from '../router';
import {useSession} from '../session';
import {ociImage, setupSnippets} from '../setup';
import {ACCESS_LEVELS, type AccessLevel, canAdminister, type FileEntry, IMMUTABLE_TYPES, includesLevel, type Package, type Repository} from '../types';
import {Select} from '../components/Select';
import {SortHeader, useSort} from '../components/sort';
import {Pagination, usePaging, useServerPaging} from '../components/paging';

type Tab = 'packages' | 'files' | 'setup' | 'settings' | 'access';
type BrowserColumn = 'name' | 'size' | 'uploaded';

/**
 * The url a client is configured with, it differs from the repository url for formats with an index document.
 */
function clientUrl(repository: Repository): string {
	switch (repository.type) {
		case 'cargo':
			return `sparse+${repository.url}/index/`;
		case 'nuget':
			return `${repository.url}/v3/index.json`;
		case 'oci':
			return ociImage(repository);
		default:
			return repository.url;
	}
}

export function RepositoryDetail({name, params}: { name: string; params: URLSearchParams }) {
	const {principal} = useSession();
	const repository = useAsync(() => api.repository(name), [name]);
	// Counts and sizes are aggregated by the server, the page only loads the packages and folders it shows
	const summary = useAsync(() => api.summary(name).then(result => result.repositories[0]), [name]);
	const admin = canAdminister(principal);
	
	const isGeneric = repository.data?.type === 'generic';
	const requested = params.get('tab') as Tab | null;
	const tab: Tab = requested ?? (isGeneric ? 'files' : 'packages');
	const setTab = (id: Tab) => navigate(`${href('repositories', name)}?tab=${id}`);
	
	if (repository.loading && !repository.data) {
		return <Spinner/>;
	}
	if (repository.error || !repository.data) {
		return (
			<>
				<PageHeader crumbs={[{label: 'Repositories', href: href('repositories')}, {label: name}]} title={name}/>
				<ErrorBox error={repository.error ?? 'Repository not found'} onRetry={repository.reload}/>
			</>
		);
	}
	const repo = repository.data;
	const url = clientUrl(repo);
	const stats = summary.data;
	const changed = () => summary.reload();
	
	const tabs: { id: Tab; label: ReactNode }[] = [
		...(isGeneric ? [] : [{id: 'packages' as Tab, label: <>Packages {stats && <span className="tab-count">{stats.packages}</span>}</>}]),
		{id: 'files', label: <>Files {stats && <span className="tab-count">{stats.files}</span>}</>},
		{id: 'setup', label: 'Client setup'},
		...(admin ? [{id: 'access' as Tab, label: 'Access'}, {id: 'settings' as Tab, label: 'Settings'}] : []),
	];
	
	return (
		<>
			<PageHeader
				crumbs={[{label: 'Repositories', href: href('repositories')}, {label: repo.name}]}
				title={<span className="cell-inline">{repo.name} <TypeBadge type={repo.type}/>{repo.kind !== 'LOCAL' && <Badge tone="accent">{repo.kind.toLowerCase()}</Badge>}</span>}
				subtitle={repo.description || `${TYPE_INFO[repo.type].label} repository`}
				actions={
					<div className="url-chip" title={url}>
						<code>{url}</code>
						<CopyButton value={url} label="Copy url" iconOnly/>
					</div>
				}
			/>
			
			<Stats>
				{!isGeneric && <Stat label="Packages" value={stats ? formatNumber(stats.packages) : '…'}/>}
				{!isGeneric && <Stat label="Versions" value={stats ? formatNumber(stats.versions) : '…'}/>}
				<Stat label="Files" value={stats ? formatNumber(stats.files) : '…'}/>
				<Stat label="Storage" value={stats ? formatBytes(stats.size) : '…'}/>
				<Stat label="Access" value={repo.publicRead ? 'Public' : 'Private'} hint={repo.allowRedeploy ? 'Redeploy allowed' : 'Releases immutable'}/>
			</Stats>
			
			<Tabs tabs={tabs} active={tab} onChange={setTab}/>
			
			<div className="fade-in" key={tab}>
				{tab === 'packages' && <PackageList repository={repo} selected={params.get('package')} onChanged={changed}/>}
				{tab === 'files' && <FileBrowser repository={repo} onChanged={changed}/>}
				{tab === 'setup' && <Setup repository={repo}/>}
				{tab === 'settings' && admin && <Settings repository={repo} onSaved={repository.reload}/>}
				{tab === 'access' && admin && <RepositoryAccess repository={repo}/>}
			</div>
		</>
	);
}

function PackageList({repository, selected, onChanged}: { repository: Repository; selected: string | null; onChanged: () => void }) {
	const [search, setSearch] = useState('');
	const [query, setQuery] = useState('');
	const [reload, setReload] = useState(0);
	useEffect(() => {
		const timer = setTimeout(() => setQuery(search.trim()), 250);
		return () => clearTimeout(timer);
	}, [search]);
	const {offset, limit, paging} = useServerPaging([query]);
	const packages = useAsync(() => api.packages(repository.name, {offset, limit}, query), [repository.name, query, offset, limit, reload]);
	const [expanded, setExpanded] = useState<string | null>(selected);
	useEffect(() => {
		if (selected) {
			setExpanded(selected);
		}
	}, [selected]);
	const changed = () => {
		setReload(value => value + 1);
		onChanged();
	};
	
	if (packages.error) {
		return <ErrorBox error={packages.error} onRetry={packages.reload}/>;
	}
	if (!packages.data) {
		return <Spinner/>;
	}
	if (packages.data.total === 0 && !query) {
		return <EmptyWithSetup repository={repository} title="No packages yet" text="Configure your client with one of these snippets and publish the first package."/>;
	}
	const rows = packages.data.rows;
	return (
		<>
			<div className="toolbar">
				<SearchField placeholder="Filter packages…" value={search} onChange={setSearch}/>
				<div className="toolbar-spacer"/>
				<span className="muted small">{formatNumber(packages.data.total)} packages</span>
			</div>
			{rows.length === 0 ? <Empty title="No matching packages"/> : (
				<>
					<div className="package-list">
						{rows.map(pkg => (
							<PackageRow
								key={pkg.name}
								repository={repository}
								pkg={pkg}
								open={expanded === pkg.name || (rows.length === 1 && packages.data!.total === 1 && expanded === null)}
								onToggle={() => setExpanded(expanded === pkg.name ? '' : pkg.name)}
								onChanged={changed}
							/>
						))}
					</div>
					<Pagination paging={paging(packages.data.total)} noun="packages"/>
				</>
			)}
		</>
	);
}

function PackageRow({repository, pkg, open, onToggle, onChanged}: { repository: Repository; pkg: Package; open: boolean; onToggle: () => void; onChanged: () => void }) {
	const {notify} = useSession();
	const [deleting, setDeleting] = useState<{ version?: string } | null>(null);
	// The files of a package are only loaded once it is opened
	const detail = useAsync(() => (open ? api.pkg(repository.name, pkg.name) : Promise.resolve(null)), [open, repository.name, pkg.name]);
	const files = detail.data?.files ?? [];
	const canDelete = includesLevel(repository.access, 'DELETE') && repository.kind === 'LOCAL';
	const oci = repository.type === 'oci';
	// OCI versions are digests, they are ordered by publication instead
	const versions = useMemo(() => [...pkg.versions].sort((a, b) => oci ? b.createdAt.localeCompare(a.createdAt) : compareVersions(b.version, a.version)), [pkg.versions, oci]);
	const latest = latestLabel(repository.type, pkg);
	const tagsByVersion = Object.entries(pkg.tags).reduce<Record<string, string[]>>((result, [tag, version]) => {
		(result[version] ??= []).push(tag);
		return result;
	}, {});
	const yankedLabel = repository.type === 'nuget' ? 'unlisted' : 'yanked';
	const versionLabel = (version: string) => isDigest(version) ? shortDigest(version) : version;
	
	return (
		<div className={`package${open ? ' open' : ''}`}>
			<button type="button" className="package-head" onClick={onToggle} aria-expanded={open}>
				<span className="chevron" aria-hidden><Icon name="chevron-right" size={16}/></span>
				<span className="name strong package-name">{pkg.name}</span>
				{latest && <Badge tone="accent">{latest}</Badge>}
				<span className="package-meta muted small">
					{pkg.versions.length} {pkg.versions.length === 1 ? 'version' : 'versions'}{detail.data ? ` · ${formatBytes(files.reduce((sum, file) => sum + file.size, 0))}` : ''}
				</span>
			</button>
			{open && (
				<div className="package-body">
					<table className="table table-compact">
						<thead>
						<tr>
							<th>{oci ? 'Manifest' : 'Version'}</th>
							<th>Published</th>
							<th>By</th>
							<th>Files</th>
							<th/>
						</tr>
						</thead>
						<tbody>
						{versions.map(version => {
							const versionFiles = files.filter(file => file.version === version.version);
							return (
								<tr key={version.version} className="clickable" title="Open this version" onClick={() => navigate(versionHref(repository.name, pkg.name, version.version))}>
									<td className="shrink">
										<span className="cell-inline">
											<span className={`mono${version.yanked ? ' strike' : ''}`} title={version.version}>{versionLabel(version.version)}</span>
											{version.yanked && <Badge tone="warning">{yankedLabel}</Badge>}
											{(tagsByVersion[version.version] ?? []).map(tag => <Badge key={tag} tone="accent">{tag}</Badge>)}
										</span>
									</td>
									<td className="muted shrink" title={formatDate(version.createdAt)}>{formatRelative(version.createdAt)}</td>
									<td className="muted shrink">{version.createdBy ?? '—'}</td>
									<td>
										<div className="file-chips">
											{versionFiles.map(file => (
												<button key={file.path} type="button" className="file-chip" title={`Download ${file.path}\nsha256 ${file.sha256}`}
														onClick={event => {
															event.stopPropagation();
															downloadFile(repository, file).catch(error => notify(error.message, 'error'));
														}}>
													<span>{fileName(file.path)}</span>
													<span className="muted">{formatBytes(file.size)}</span>
												</button>
											))}
											{versionFiles.length === 0 && <span className="faint">{detail.data ? '—' : '…'}</span>}
										</div>
									</td>
									<td className="actions">
										{canDelete && (
											<Button small variant="ghost" className="btn-icon danger-text" title={`Delete ${oci ? 'manifest' : 'version'}`} aria-label={`Delete ${version.version}`}
													onClick={event => {
														event.stopPropagation();
														setDeleting({version: version.version});
													}}>
												<Icon name="trash" size={16}/>
											</Button>
										)}
									</td>
								</tr>
							);
						})}
						</tbody>
					</table>
					<div className="package-footer">
						<a className="btn btn-secondary btn-small" href={href('repositories', repository.name, 'packages', pkg.name)}>Package details<Icon name="chevron-right" size={14}/></a>
						<span className="muted small">Click a version to open it, a file to download it.</span>
						<div className="toolbar-spacer"/>
						{canDelete && <Button small variant="ghost" className="danger-text" onClick={() => setDeleting({})}><Icon name="trash" size={15}/>Delete package</Button>}
					</div>
				</div>
			)}
			{deleting && (
				<ConfirmDialog
					title={deleting.version ? `Delete ${oci ? 'manifest' : 'version'}` : 'Delete package'}
					message={deleting.version
						? <>Delete <strong>{pkg.name} {versionLabel(deleting.version)}</strong> and its files from <strong>{repository.name}</strong>? Clients depending on it will break.</>
						: <>Delete <strong>{pkg.name}</strong> with all {pkg.versions.length} versions from <strong>{repository.name}</strong>?</>}
					onConfirm={async () => {
						await api.deletePackage(repository.name, pkg.name, deleting.version);
						notify(deleting.version ? `Deleted ${pkg.name} ${versionLabel(deleting.version)}` : `Deleted ${pkg.name}`);
						onChanged();
					}}
					onClose={() => setDeleting(null)}
				/>
			)}
		</div>
	);
}

interface Folder {
	name: string;
	/** Number and size of the files below the folder, only known when the browser was given all files */
	stats?: { count: number; size: number };
}

/**
 * The folders and files directly below a prefix of a given list of files.
 */
function listFolder(files: FileEntry[], prefix: string): { folders: Folder[]; entries: FileEntry[] } {
	const folderStats = new Map<string, { count: number; size: number }>();
	const direct: FileEntry[] = [];
	for (const file of files) {
		if (!file.path.startsWith(prefix)) {
			continue;
		}
		const rest = file.path.substring(prefix.length);
		const slash = rest.indexOf('/');
		if (slash < 0) {
			direct.push(file);
		} else {
			const folder = rest.substring(0, slash);
			const stats = folderStats.get(folder) ?? {count: 0, size: 0};
			stats.count++;
			stats.size += file.size;
			folderStats.set(folder, stats);
		}
	}
	return {
		folders: [...folderStats.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([name, stats]) => ({name, stats})),
		entries: direct.sort((a, b) => a.path.localeCompare(b.path)),
	};
}

/**
 * Folder view of files, root is the folder it starts in and the topmost one it can go up to (e.g. the folder of a package version).
 * Given files (the files of one version) are browsed in place, otherwise each folder of the repository is loaded from the server.
 */
export function FileBrowser({repository, files, root = '', rootLabel, onChanged}: { repository: Repository; files?: FileEntry[]; root?: string; rootLabel?: string; onChanged?: () => void }) {
	const {notify} = useSession();
	const [prefix, setPrefix] = useState(root);
	const [selected, setSelected] = useState<FileEntry | null>(null);
	const [deleting, setDeleting] = useState<FileEntry | null>(null);
	const [reload, setReload] = useState(0);
	const tree = useAsync(() => (files ? Promise.resolve(null) : api.tree(repository.name, prefix)), [files, repository.name, prefix, reload]);
	// Files of a generic repository and cached files of a remote repository are deleted one by one, packages are deleted as a whole
	const canDelete = includesLevel(repository.access, 'DELETE') && (repository.kind === 'REMOTE' || (repository.kind === 'LOCAL' && repository.type === 'generic'));
	
	const {folders, entries} = useMemo(() => {
		if (files) {
			return listFolder(files, prefix);
		}
		return {folders: (tree.data?.folders ?? []).map(name => ({name})), entries: tree.data?.files ?? []};
	}, [files, prefix, tree.data]);
	
	const folderSort = useSort<Folder, BrowserColumn>(folders, {
		name: folder => folder.name,
		size: folder => folder.stats?.size,
		uploaded: () => null,
	});
	const fileSort = useSort<FileEntry, BrowserColumn>(entries, {
		name: file => file.path,
		size: file => file.size,
		uploaded: file => file.createdAt,
	});
	const sort = fileSort.sort;
	const {visible: visibleFiles, paging} = usePaging(fileSort.sorted);
	const toggle = (key: BrowserColumn) => {
		fileSort.toggle(key);
		folderSort.toggle(key);
	};
	const crumbs = prefix.substring(root.length).split('/').filter(Boolean);
	const download = (file: FileEntry) => downloadFile(repository, file).catch(error => notify(error.message, 'error'));
	const openFolder = (value: string) => {
		setPrefix(value);
		setSelected(null);
	};
	
	if (files && files.length === 0) {
		return <Empty title="No files">This version has no files.</Empty>;
	}
	if (!files && tree.error) {
		return <ErrorBox error={tree.error} onRetry={tree.reload}/>;
	}
	if (!files && !tree.data) {
		return <Spinner/>;
	}
	if (!files && prefix === root && folders.length === 0 && entries.length === 0) {
		return repository.kind === 'LOCAL'
			? <EmptyWithSetup repository={repository} title="No files yet" text="Upload the first file with one of these commands."/>
			: <Empty title="No files">{repository.kind === 'REMOTE' ? 'Nothing was cached from the upstream yet.' : 'A virtual repository stores no files itself, browse its members.'}</Empty>;
	}
	return (
		<div className={`browser${selected ? ' with-details' : ''}`}>
			<div className={`table-wrap${tree.loading ? ' loading' : ''}`}>
				<nav className="breadcrumbs" aria-label="Folder">
					<button type="button" className={`crumb${crumbs.length === 0 ? ' crumb-current' : ''}`} onClick={() => openFolder(root)}>{rootLabel ?? repository.name}</button>
					{crumbs.map((crumb, index) => (
						<span key={index} className="cell-inline">
							<span className="faint">/</span>
							<button type="button" className={`crumb${index === crumbs.length - 1 ? ' crumb-current' : ''}`} onClick={() => openFolder(root + crumbs.slice(0, index + 1).join('/') + '/')}>{crumb}</button>
						</span>
					))}
				</nav>
				<table className="table table-compact">
					<thead>
					<tr>
						<SortHeader id="name" sort={sort} onSort={toggle}>Name</SortHeader>
						<SortHeader id="size" sort={sort} onSort={toggle} className="num">Size</SortHeader>
						<SortHeader id="uploaded" sort={sort} onSort={toggle}>Uploaded</SortHeader>
						<th/>
					</tr>
					</thead>
					<tbody>
					{prefix !== root && (
						<tr className="clickable" onClick={() => openFolder(root + crumbs.slice(0, -1).join('/') + (crumbs.length > 1 ? '/' : ''))}>
							<td colSpan={4}><span className="entry-name muted"><span className="entry-icon"><Icon name="arrow-left" size={16}/></span>Parent folder</span></td>
						</tr>
					)}
					{folderSort.sorted.map(folder => (
						<tr key={folder.name} className="clickable" onClick={() => openFolder(prefix + folder.name + '/')}>
							<td><span className="entry-name"><span className="entry-icon entry-icon-folder"><Icon name="folder" size={16}/></span><span className="name">{folder.name}</span></span></td>
							<td className="num muted">{folder.stats ? formatBytes(folder.stats.size) : ''}</td>
							<td className="muted nowrap">{folder.stats ? `${folder.stats.count} ${folder.stats.count === 1 ? 'file' : 'files'}` : 'Folder'}</td>
							<td/>
						</tr>
					))}
					{visibleFiles.map(file => (
						<tr key={file.path} className={`clickable${selected?.path === file.path ? ' selected' : ''}`} onClick={() => setSelected(selected?.path === file.path ? null : file)}>
							<td><span className="entry-name"><span className="entry-icon"><Icon name="file" size={16}/></span><span className="name break">{file.path.substring(prefix.length)}</span></span></td>
							<td className="num">{formatBytes(file.size)}</td>
							<td className="muted nowrap" title={formatDate(file.createdAt)}>{file.createdBy ? `${file.createdBy} · ` : ''}{formatRelative(file.createdAt)}</td>
							<td className="actions">
								<Button small variant="ghost" className="btn-icon" title="Download" aria-label={`Download ${file.path}`} onClick={event => {
									event.stopPropagation();
									download(file);
								}}>
									<Icon name="download" size={16}/>
								</Button>
								{canDelete && !files && (
									<Button small variant="ghost" className="btn-icon danger-text" title="Delete" aria-label={`Delete ${file.path}`} onClick={event => {
										event.stopPropagation();
										setDeleting(file);
									}}>
										<Icon name="trash" size={16}/>
									</Button>
								)}
							</td>
						</tr>
					))}
					</tbody>
				</table>
				<Pagination paging={paging} noun="files in this folder"/>
			</div>
			{selected && (
				<Card title={<span className="name break">{fileName(selected.path)}</span>} actions={<button type="button" className="icon-btn" onClick={() => setSelected(null)} aria-label="Close details"><Icon name="close"/></button>}>
					<dl className="details details-stacked">
						<dt>Path</dt>
						<dd className="mono break small">{selected.path}</dd>
						<dt>Content type</dt>
						<dd>{selected.contentType}</dd>
						<dt>Size</dt>
						<dd>{formatBytes(selected.size)} ({formatNumber(selected.size)} bytes)</dd>
						{selected.packageName && <>
							<dt>Package</dt>
							<dd>{selected.packageName} {selected.version && shortDigest(selected.version)}</dd>
						</>}
						<dt>Uploaded</dt>
						<dd>{formatDate(selected.createdAt)}{selected.createdBy ? ` by ${selected.createdBy}` : ''}</dd>
						<dt>SHA-256</dt>
						<dd className="hash"><code>{selected.sha256}</code><CopyButton value={selected.sha256} iconOnly label="Copy SHA-256"/></dd>
						<dt>SHA-1</dt>
						<dd className="hash"><code>{selected.sha1}</code><CopyButton value={selected.sha1} iconOnly label="Copy SHA-1"/></dd>
						<dt>MD5</dt>
						<dd className="hash"><code>{selected.md5}</code><CopyButton value={selected.md5} iconOnly label="Copy MD5"/></dd>
					</dl>
					<Button variant="primary" onClick={() => download(selected)}><Icon name="download" size={16}/>Download</Button>
				</Card>
			)}
			{deleting && (
				<ConfirmDialog
					title={repository.kind === 'REMOTE' ? 'Delete cached file' : 'Delete file'}
					message={repository.kind === 'REMOTE'
						? <>Delete the cached copy of <strong>{deleting.path}</strong>? It is fetched from the upstream again on the next download.</>
						: <>Delete <strong>{deleting.path}</strong> from <strong>{repository.name}</strong>? This can not be undone.</>}
					onConfirm={async () => {
						await deleteFile(repository, deleting);
						notify(`Deleted ${fileName(deleting.path)}`);
						if (selected?.path === deleting.path) {
							setSelected(null);
						}
						setReload(value => value + 1);
						onChanged?.();
					}}
					onClose={() => setDeleting(null)}
				/>
			)}
		</div>
	);
}

/**
 * Empty state of a repository without content, shows the client setup right away instead of only linking to it.
 */
function EmptyWithSetup({repository, title, text}: { repository: Repository; title: string; text: string }) {
	const {principal} = useSession();
	return (
		<div className="empty-setup">
			<div className="empty-setup-head">
				<span className="empty-setup-icon"><Icon name={repository.type === 'generic' ? 'folder' : 'package'} size={22}/></span>
				<div>
					<strong>{title}</strong>
					<p className="muted">{text} Replace <code>&lt;token&gt;</code> with an <a className="link" href={`${href('tokens')}?new=1`}>access token</a>.</p>
				</div>
			</div>
			<SnippetTabs key={repository.name} snippets={setupSnippets(repository, principal?.username ?? 'user')}/>
		</div>
	);
}

function Setup({repository}: { repository: Repository }) {
	const {principal} = useSession();
	return (
		<div className="setup">
			<p className="muted">
				Replace <code>&lt;token&gt;</code> with an <a className="link" href={href('tokens')}>access token</a>.
				{repository.publicRead && ' Downloads work without credentials because the repository is public.'}
			</p>
			<SnippetTabs key={repository.name} snippets={setupSnippets(repository, principal?.username ?? 'user')}/>
		</div>
	);
}

function Settings({repository, onSaved}: { repository: Repository; onSaved: () => void }) {
	const {notify} = useSession();
	const [description, setDescription] = useState(repository.description ?? '');
	const [publicRead, setPublicRead] = useState(repository.publicRead);
	const [allowRedeploy, setAllowRedeploy] = useState(repository.allowRedeploy);
	const [busy, setBusy] = useState(false);
	const [error, setError] = useState<string>();
	const [deleting, setDeleting] = useState(false);
	const dirty = description !== (repository.description ?? '') || publicRead !== repository.publicRead || allowRedeploy !== repository.allowRedeploy;
	
	const save = async (event: FormEvent) => {
		event.preventDefault();
		setBusy(true);
		setError(undefined);
		try {
			await api.updateRepository(repository.name, {description, publicRead, allowRedeploy: allowRedeploy && !IMMUTABLE_TYPES.includes(repository.type)});
			notify('Repository settings saved');
			onSaved();
		} catch (e) {
			setError(errorText(e));
		} finally {
			setBusy(false);
		}
	};
	
	return (
		<div className="stack">
			{repository.kind === 'REMOTE' && repository.remote && <RemoteSettings repository={repository} remote={repository.remote} onSaved={onSaved}/>}
			{repository.kind === 'VIRTUAL' && <MemberSettings repository={repository} onSaved={onSaved}/>}
			<Card title="General">
				<form className="form" onSubmit={save}>
					<Field label="Name"><input value={repository.name} disabled/></Field>
					<Field label="Description"><input value={description} onChange={event => setDescription(event.target.value)}/></Field>
					<Toggle checked={publicRead} onChange={setPublicRead} label="Public read" description="Anyone can download without credentials"/>
					{!IMMUTABLE_TYPES.includes(repository.type) && <Toggle checked={allowRedeploy} onChange={setAllowRedeploy} label="Allow redeploy" description="Released versions can be overwritten"/>}
					{error && <ErrorBox error={error}/>}
					<div><Button variant="primary" type="submit" disabled={busy || !dirty}>{busy ? 'Saving…' : 'Save changes'}</Button></div>
				</form>
			</Card>
			<Card title="Danger zone" className="danger-card">
				<div className="setting-row">
					<div>
						<strong>Delete this repository</strong>
						<div className="muted small">All packages, files and permissions of the repository are removed. This can not be undone.</div>
					</div>
					<Button variant="danger" onClick={() => setDeleting(true)}>Delete repository</Button>
				</div>
			</Card>
			{deleting && (
				<ConfirmDialog
					title="Delete repository"
					message={<>Delete <strong>{repository.name}</strong> with all its content?</>}
					onConfirm={async () => {
						await api.deleteRepository(repository.name);
						notify(`Repository ${repository.name} deleted`);
						navigate(href('repositories'));
					}}
					onClose={() => setDeleting(false)}
				/>
			)}
		</div>
	);
}

function RemoteSettings({repository, remote, onSaved}: { repository: Repository; remote: NonNullable<Repository['remote']>; onSaved: () => void }) {
	const {notify} = useSession();
	const [url, setUrl] = useState(remote.url);
	const [cacheEnabled, setCacheEnabled] = useState(remote.cacheEnabled);
	const [username, setUsername] = useState(remote.username ?? '');
	const [password, setPassword] = useState('');
	const [busy, setBusy] = useState(false);
	const [error, setError] = useState<string>();
	const dirty = url !== remote.url || cacheEnabled !== remote.cacheEnabled || username !== (remote.username ?? '') || password !== '';
	
	const save = async (event: FormEvent) => {
		event.preventDefault();
		setBusy(true);
		setError(undefined);
		try {
			// An empty password field keeps the stored password, an empty user removes the upstream credentials
			await api.updateRepository(repository.name, {remote: {url: url.trim(), cacheEnabled, username: username.trim(), password: password || undefined}});
			notify('Upstream settings saved');
			setPassword('');
			onSaved();
		} catch (e) {
			setError(errorText(e));
		} finally {
			setBusy(false);
		}
	};
	
	return (
		<Card title="Remote (proxy)">
			<form className="form" onSubmit={save}>
				<p className="muted small">This repository is read-only, artifacts are fetched from the upstream on demand.</p>
				<Field label="Upstream URL"><input value={url} onChange={event => setUrl(event.target.value)} required maxLength={1024}/></Field>
				<Field label="Upstream username" hint="Leave empty for an anonymous upstream"><input value={username} onChange={event => setUsername(event.target.value)} autoComplete="off"/></Field>
				<Field label="Upstream password" hint={remote.hasPassword ? 'A password is stored, leave empty to keep it' : 'Optional'}>
					<input type="password" value={password} onChange={event => setPassword(event.target.value)} autoComplete="new-password" disabled={!username.trim()}/>
				</Field>
				<Toggle checked={cacheEnabled} onChange={setCacheEnabled} label="Cache artifacts" description="Store fetched artifacts locally on first download"/>
				{error && <ErrorBox error={error}/>}
				<div><Button variant="primary" type="submit" disabled={busy || !dirty || !url.trim()}>{busy ? 'Saving…' : 'Save upstream'}</Button></div>
			</form>
		</Card>
	);
}

function MemberSettings({repository, onSaved}: { repository: Repository; onSaved: () => void }) {
	const {notify} = useSession();
	const repositories = useAsync(() => api.repositories(), []);
	const [members, setMembers] = useState(repository.members);
	const [busy, setBusy] = useState(false);
	const [error, setError] = useState<string>();
	const candidates = (repositories.data ?? []).filter(entry => entry.type === repository.type && entry.kind !== 'VIRTUAL' && !members.includes(entry.name));
	const dirty = members.join('\n') !== repository.members.join('\n');
	const move = (index: number, delta: number) => setMembers(current => {
		const next = [...current];
		[next[index], next[index + delta]] = [next[index + delta], next[index]];
		return next;
	});
	
	const save = async () => {
		setBusy(true);
		setError(undefined);
		try {
			await api.updateRepository(repository.name, {members});
			notify('Members saved');
			onSaved();
		} catch (e) {
			setError(errorText(e));
		} finally {
			setBusy(false);
		}
	};
	
	return (
		<Card title="Virtual (group)">
			<div className="form">
				<p className="muted small">Reads are resolved across these members in order, the first match wins. Members the reader can not read are skipped. This repository is read-only.</p>
				{members.length === 0 ? <span className="muted">No members</span> : (
					<table className="table table-compact">
						<tbody>
						{members.map((member, index) => (
							<tr key={member}>
								<td className="shrink muted">{index + 1}</td>
								<td><a className="link" href={href('repositories', member)}>{member}</a></td>
								<td className="actions">
									<Button small variant="ghost" className="btn-icon" title="Move up" aria-label={`Move ${member} up`} disabled={index === 0} onClick={() => move(index, -1)}><Icon name="arrow-up" size={15}/></Button>
									<Button small variant="ghost" className="btn-icon" title="Move down" aria-label={`Move ${member} down`} disabled={index === members.length - 1} onClick={() => move(index, 1)}><Icon name="arrow-down"
									                                                                                                                                                                                     size={15}/></Button>
									<Button small variant="ghost" className="danger-text" onClick={() => setMembers(current => current.filter(entry => entry !== member))}>Remove</Button>
								</td>
							</tr>
						))}
						</tbody>
					</table>
				)}
				{candidates.length > 0 && (
					<Select label="Add member" placeholder="Add a member…" value="" onChange={value => value && setMembers(current => [...current, value])}
							options={candidates.map(entry => ({value: entry.name, label: entry.name, hint: entry.kind === 'REMOTE' ? 'remote' : undefined}))}/>
				)}
				{error && <ErrorBox error={error}/>}
				<div><Button variant="primary" onClick={save} disabled={busy || !dirty || members.length === 0}>{busy ? 'Saving…' : 'Save members'}</Button></div>
			</div>
		</Card>
	);
}

function RepositoryAccess({repository}: { repository: Repository }) {
	const {notify} = useSession();
	const permissions = useAsync(() => Promise.all([api.permissions({repository: repository.name}), api.permissions({repository: '*'})]), [repository.name]);
	const users = useAsync(() => api.users(), []);
	const [username, setUsername] = useState('');
	const [level, setLevel] = useState<AccessLevel>('READ');
	const [busy, setBusy] = useState(false);
	
	const grant = async (event: FormEvent) => {
		event.preventDefault();
		setBusy(true);
		try {
			await api.setPermission({repository: repository.name, username, level});
			notify(`Granted ${level} to ${username}`);
			setUsername('');
			permissions.reload();
		} catch (e) {
			notify(errorText(e), 'error');
		} finally {
			setBusy(false);
		}
	};
	const revoke = async (user: string) => {
		try {
			await api.deletePermission(repository.name, user);
			notify(`Revoked access of ${user}`);
			permissions.reload();
		} catch (e) {
			notify(errorText(e), 'error');
		}
	};
	
	const [direct, global] = permissions.data ?? [[], []];
	const admins = (users.data ?? []).filter(user => user.admin);
	
	return (
		<div className="stack">
			<Card title="Grant access">
				<form className="inline-form" onSubmit={grant}>
					<Select label="User" placeholder="Select user…" value={username} onChange={setUsername}
							options={(users.data ?? []).filter(user => !user.admin).map(user => ({value: user.username, label: user.username}))}/>
					<Select<AccessLevel> label="Level" value={level} onChange={setLevel} options={ACCESS_LEVELS.map(entry => ({value: entry, label: entry}))}/>
					<Button variant="primary" type="submit" disabled={busy || !username}>Grant</Button>
				</form>
				<p className="muted small">READ downloads, WRITE publishes, tags and yanks, DELETE removes artifacts. Each level includes the lower ones.</p>
			</Card>
			<Card title="Who has access">
				{permissions.error && <ErrorBox error={permissions.error} onRetry={permissions.reload}/>}
				{!permissions.data ? <Spinner/> : (
					<table className="table table-compact">
						<thead>
						<tr>
							<th>User</th>
							<th>Level</th>
							<th>Source</th>
							<th/>
						</tr>
						</thead>
						<tbody>
						{repository.publicRead && <tr>
							<td className="muted">Anonymous</td>
							<td><Badge tone="success">READ</Badge></td>
							<td className="muted">Public repository</td>
							<td/>
						</tr>}
						{admins.map(user => <tr key={`admin-${user.username}`}>
							<td>{user.username}</td>
							<td><Badge tone="accent">ALL</Badge></td>
							<td className="muted">Administrator</td>
							<td/>
						</tr>)}
						{global.map(permission => <tr key={`global-${permission.username}`}>
							<td>{permission.username}</td>
							<td><Badge>{permission.level}</Badge></td>
							<td className="muted">All repositories (*)</td>
							<td/>
						</tr>)}
						{direct.map(permission => (
							<tr key={permission.username}>
								<td>{permission.username}</td>
								<td><Badge>{permission.level}</Badge></td>
								<td className="muted">This repository</td>
								<td className="actions"><Button small variant="ghost" className="danger-text" onClick={() => revoke(permission.username)}>Revoke</Button></td>
							</tr>
						))}
						</tbody>
					</table>
				)}
			</Card>
		</div>
	);
}
