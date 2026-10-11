import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { api, downloadFile } from '../api';
import {
	Badge, Button, Card, CodeBlock, ConfirmDialog, CopyButton, Empty, ErrorBox, Field, PageHeader, Spinner, Stat, Tabs, Toggle, TypeBadge, TYPE_INFO, errorText,
} from '../components/ui';
import { compareVersions, formatBytes, formatDate, formatNumber, formatRelative } from '../format';
import { useAsync } from '../hooks';
import { latestVersion } from '../inventory';
import { href, navigate } from '../router';
import { useSession } from '../session';
import { setupSnippets } from '../setup';
import { ACCESS_LEVELS, type AccessLevel, type FileEntry, type Package, type Repository } from '../types';

type Tab = 'packages' | 'files' | 'setup' | 'settings' | 'access';

export function RepositoryDetail({ name, params }: { name: string; params: URLSearchParams }) {
	const { principal } = useSession();
	const repository = useAsync(() => api.repository(name), [name]);
	const packages = useAsync(() => api.packages(name), [name]);
	const files = useAsync(() => api.files(name), [name]);

	const isGeneric = repository.data?.type === 'generic';
	const requested = params.get('tab') as Tab | null;
	const tab: Tab = requested ?? (isGeneric ? 'files' : 'packages');
	const setTab = (id: Tab) => navigate(`${href('repositories', name)}?tab=${id}`);

	if (repository.loading && !repository.data) {
		return <Spinner />;
	}
	if (repository.error || !repository.data) {
		return (
			<>
				<PageHeader title={name} />
				<ErrorBox error={repository.error ?? 'Repository not found'} onRetry={repository.reload} />
			</>
		);
	}
	const repo = repository.data;
	const totalSize = (files.data ?? []).reduce((sum, file) => sum + file.size, 0);
	const versionCount = (packages.data ?? []).reduce((sum, pkg) => sum + pkg.versions.length, 0);

	const tabs: { id: Tab; label: string }[] = [
		...(isGeneric ? [] : [{ id: 'packages' as Tab, label: 'Packages' }]),
		{ id: 'files', label: 'Files' },
		{ id: 'setup', label: 'Client setup' },
		...(principal?.admin ? [{ id: 'settings' as Tab, label: 'Settings' }, { id: 'access' as Tab, label: 'Access' }] : []),
	];

	return (
		<>
			<a className="link small back" href={href('repositories')}>← Repositories</a>
			<PageHeader
				title={<span className="cell-inline">{repo.name} <TypeBadge type={repo.type} /></span>}
				subtitle={repo.description || `${TYPE_INFO[repo.type].label} repository`}
				actions={
					<div className="url-chip">
						<code>{repo.type === 'cargo' ? `sparse+${repo.url}/index/` : repo.type === 'nuget' ? `${repo.url}/v3/index.json` : repo.url}</code>
						<CopyButton value={repo.type === 'cargo' ? `sparse+${repo.url}/index/` : repo.type === 'nuget' ? `${repo.url}/v3/index.json` : repo.url} />
					</div>
				}
			/>

			<div className="stats-row">
				{!isGeneric && <Stat label="Packages" value={packages.data ? formatNumber(packages.data.length) : '…'} />}
				{!isGeneric && <Stat label="Versions" value={packages.data ? formatNumber(versionCount) : '…'} />}
				<Stat label="Files" value={files.data ? formatNumber(files.data.length) : '…'} />
				<Stat label="Storage" value={files.data ? formatBytes(totalSize) : '…'} />
				<Stat label="Access" value={repo.publicRead ? 'Public' : 'Private'} hint={repo.allowRedeploy ? 'Redeploy allowed' : 'Releases immutable'} />
			</div>

			<Tabs tabs={tabs} active={tab} onChange={setTab} />

			{tab === 'packages' && (
				packages.error ? <ErrorBox error={packages.error} onRetry={packages.reload} />
					: !packages.data ? <Spinner />
					: <PackageList repository={repo} packages={packages.data} files={files.data ?? []} selected={params.get('package')} onChanged={() => { packages.reload(); files.reload(); }} />
			)}
			{tab === 'files' && (
				files.error ? <ErrorBox error={files.error} onRetry={files.reload} />
					: !files.data ? <Spinner />
					: <FileBrowser repository={repo} files={files.data} />
			)}
			{tab === 'setup' && <Setup repository={repo} />}
			{tab === 'settings' && principal?.admin && <Settings repository={repo} onSaved={repository.reload} />}
			{tab === 'access' && principal?.admin && <RepositoryAccess repository={repo} />}
		</>
	);
}

function PackageList({ repository, packages, files, selected, onChanged }: { repository: Repository; packages: Package[]; files: FileEntry[]; selected: string | null; onChanged: () => void }) {
	const [search, setSearch] = useState('');
	const [expanded, setExpanded] = useState<string | null>(selected);
	useEffect(() => setExpanded(selected), [selected]);

	const query = search.trim().toLowerCase();
	const rows = packages.filter(pkg => !query || pkg.name.toLowerCase().includes(query)).sort((a, b) => a.name.localeCompare(b.name));

	if (packages.length === 0) {
		return <Empty title="No packages yet">See <a className="link" href={`${href('repositories', repository.name)}?tab=setup`}>client setup</a> to publish the first package.</Empty>;
	}
	return (
		<>
			<div className="toolbar">
				<input className="search" type="search" placeholder="Filter packages…" value={search} onChange={event => setSearch(event.target.value)} />
			</div>
			<div className="package-list">
				{rows.map(pkg => (
					<PackageRow
						key={pkg.name}
						repository={repository}
						pkg={pkg}
						files={files.filter(file => file.packageName === pkg.name)}
						open={expanded === pkg.name}
						onToggle={() => setExpanded(expanded === pkg.name ? null : pkg.name)}
						onChanged={onChanged}
					/>
				))}
			</div>
		</>
	);
}

function PackageRow({ repository, pkg, files, open, onToggle, onChanged }: { repository: Repository; pkg: Package; files: FileEntry[]; open: boolean; onToggle: () => void; onChanged: () => void }) {
	const { notify } = useSession();
	const [deleting, setDeleting] = useState<{ version?: string } | null>(null);
	const versions = useMemo(() => [...pkg.versions].sort((a, b) => compareVersions(b.version, a.version)), [pkg.versions]);
	const latest = latestVersion(pkg);
	const size = files.reduce((sum, file) => sum + file.size, 0);
	const tagsByVersion = Object.entries(pkg.tags).reduce<Record<string, string[]>>((result, [tag, version]) => {
		(result[version] ??= []).push(tag);
		return result;
	}, {});
	const yankedLabel = repository.type === 'nuget' ? 'unlisted' : 'yanked';

	return (
		<div className={`package${open ? ' open' : ''}`}>
			<button type="button" className="package-head" onClick={onToggle} aria-expanded={open}>
				<span className="chevron" aria-hidden>{open ? '▾' : '▸'}</span>
				<span className="mono strong">{pkg.name}</span>
				{latest && <Badge tone="accent">{latest}</Badge>}
				<span className="package-meta muted small">
					{pkg.versions.length} {pkg.versions.length === 1 ? 'version' : 'versions'} · {formatBytes(size)}
				</span>
			</button>
			{open && (
				<div className="package-body">
					<table className="table table-compact">
						<thead>
							<tr>
								<th>Version</th>
								<th>Published</th>
								<th>By</th>
								<th>Files</th>
								<th />
							</tr>
						</thead>
						<tbody>
							{versions.map(version => {
								const versionFiles = files.filter(file => file.version === version.version);
								return (
									<tr key={version.version}>
										<td>
											<span className="cell-inline">
												<span className={`mono${version.yanked ? ' strike' : ''}`}>{version.version}</span>
												{version.yanked && <Badge tone="warning">{yankedLabel}</Badge>}
												{(tagsByVersion[version.version] ?? []).map(tag => <Badge key={tag} tone="accent">{tag}</Badge>)}
											</span>
										</td>
										<td className="muted" title={formatDate(version.createdAt)}>{formatRelative(version.createdAt)}</td>
										<td className="muted">{version.createdBy ?? '—'}</td>
										<td>
											<div className="file-chips">
												{versionFiles.map(file => (
													<button key={file.path} type="button" className="file-chip" title={`${file.path}\nsha256 ${file.sha256}`} onClick={() => downloadFile(repository, file).catch(error => notify(error.message, 'error'))}>
														{file.path.substring(file.path.lastIndexOf('/') + 1)} <span className="muted">{formatBytes(file.size)}</span>
													</button>
												))}
												{versionFiles.length === 0 && <span className="muted">—</span>}
											</div>
										</td>
										<td className="actions">
											<Button small variant="ghost" className="danger-text" onClick={() => setDeleting({ version: version.version })}>Delete</Button>
										</td>
									</tr>
								);
							})}
						</tbody>
					</table>
					<div className="package-footer">
						<Button small variant="danger" onClick={() => setDeleting({})}>Delete package</Button>
					</div>
				</div>
			)}
			{deleting && (
				<ConfirmDialog
					title={deleting.version ? 'Delete version' : 'Delete package'}
					message={deleting.version
						? <>Delete <strong className="mono">{pkg.name} {deleting.version}</strong> and its files from <strong>{repository.name}</strong>? Clients depending on it will break.</>
						: <>Delete <strong className="mono">{pkg.name}</strong> with all {pkg.versions.length} versions from <strong>{repository.name}</strong>?</>}
					onConfirm={async () => {
						await api.deletePackage(repository.name, pkg.name, deleting.version);
						notify(deleting.version ? `Deleted ${pkg.name} ${deleting.version}` : `Deleted ${pkg.name}`);
						onChanged();
					}}
					onClose={() => setDeleting(null)}
				/>
			)}
		</div>
	);
}

function FileBrowser({ repository, files }: { repository: Repository; files: FileEntry[] }) {
	const { notify } = useSession();
	const [prefix, setPrefix] = useState('');
	const [selected, setSelected] = useState<FileEntry | null>(null);

	// Folders and files directly below the current prefix
	const { folders, entries } = useMemo(() => {
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
				const stats = folderStats.get(folder) ?? { count: 0, size: 0 };
				stats.count++;
				stats.size += file.size;
				folderStats.set(folder, stats);
			}
		}
		return {
			folders: [...folderStats.entries()].sort(([a], [b]) => a.localeCompare(b)),
			entries: direct.sort((a, b) => a.path.localeCompare(b.path)),
		};
	}, [files, prefix]);

	const crumbs = prefix.split('/').filter(Boolean);

	if (files.length === 0) {
		return <Empty title="No files yet" />;
	}
	return (
		<div className="browser">
			<div className="breadcrumbs">
				<button type="button" className="crumb" onClick={() => setPrefix('')}>{repository.name}</button>
				{crumbs.map((crumb, index) => (
					<span key={index}>
						<span className="muted"> / </span>
						<button type="button" className="crumb" onClick={() => setPrefix(crumbs.slice(0, index + 1).join('/') + '/')}>{crumb}</button>
					</span>
				))}
			</div>
			<div className="table-wrap">
				<table className="table table-compact">
					<thead>
						<tr>
							<th>Name</th>
							<th className="num">Size</th>
							<th>Uploaded</th>
							<th />
						</tr>
					</thead>
					<tbody>
						{prefix && (
							<tr className="clickable" onClick={() => setPrefix(crumbs.slice(0, -1).join('/') + (crumbs.length > 1 ? '/' : ''))}>
								<td colSpan={4} className="muted">↰ ..</td>
							</tr>
						)}
						{folders.map(([folder, stats]) => (
							<tr key={folder} className="clickable" onClick={() => setPrefix(prefix + folder + '/')}>
								<td><span className="folder">📁</span> <span className="mono">{folder}/</span></td>
								<td className="num muted">{formatBytes(stats.size)}</td>
								<td className="muted">{stats.count} {stats.count === 1 ? 'file' : 'files'}</td>
								<td />
							</tr>
						))}
						{entries.map(file => (
							<tr key={file.path} className={selected?.path === file.path ? 'selected' : ''}>
								<td><button type="button" className="link mono" onClick={() => setSelected(selected?.path === file.path ? null : file)}>{file.path.substring(prefix.length)}</button></td>
								<td className="num">{formatBytes(file.size)}</td>
								<td className="muted" title={formatDate(file.createdAt)}>{file.createdBy ? `${file.createdBy} · ` : ''}{formatRelative(file.createdAt)}</td>
								<td className="actions"><Button small variant="ghost" onClick={() => downloadFile(repository, file).catch(error => notify(error.message, 'error'))}>Download</Button></td>
							</tr>
						))}
					</tbody>
				</table>
			</div>
			{selected && (
				<Card title={<span className="mono">{selected.path}</span>} actions={<Button small variant="ghost" onClick={() => setSelected(null)}>Close</Button>}>
					<dl className="details">
						<dt>Content type</dt><dd>{selected.contentType}</dd>
						<dt>Size</dt><dd>{formatBytes(selected.size)} ({formatNumber(selected.size)} bytes)</dd>
						{selected.packageName && <><dt>Package</dt><dd className="mono">{selected.packageName} {selected.version}</dd></>}
						<dt>Uploaded</dt><dd>{formatDate(selected.createdAt)}{selected.createdBy ? ` by ${selected.createdBy}` : ''}</dd>
						<dt>SHA-256</dt><dd className="mono break">{selected.sha256} <CopyButton value={selected.sha256} /></dd>
						<dt>SHA-1</dt><dd className="mono break">{selected.sha1}</dd>
						<dt>MD5</dt><dd className="mono break">{selected.md5}</dd>
					</dl>
				</Card>
			)}
		</div>
	);
}

function Setup({ repository }: { repository: Repository }) {
	const { principal } = useSession();
	return (
		<div className="setup">
			<p className="muted">
				Replace <code>&lt;token&gt;</code> with an <a className="link" href={href('tokens')}>access token</a>.
				{repository.publicRead && ' Downloads work without credentials because the repository is public.'}
			</p>
			<div className="snippet-grid">
				{setupSnippets(repository, principal?.username ?? 'user').map(snippet => <CodeBlock key={snippet.title} title={snippet.title} code={snippet.code} />)}
			</div>
		</div>
	);
}

function Settings({ repository, onSaved }: { repository: Repository; onSaved: () => void }) {
	const { notify } = useSession();
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
			await api.updateRepository(repository.name, { description, publicRead, allowRedeploy });
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
			<Card title="General">
				<form className="form" onSubmit={save}>
					<Field label="Name"><input value={repository.name} disabled /></Field>
					<Field label="Description"><input value={description} onChange={event => setDescription(event.target.value)} /></Field>
					<Toggle checked={publicRead} onChange={setPublicRead} label="Public read" description="Anyone can download without credentials" />
					<Toggle checked={allowRedeploy} onChange={setAllowRedeploy} label="Allow redeploy" description="Released versions can be overwritten" />
					{error && <ErrorBox error={error} />}
					<div><Button variant="primary" type="submit" disabled={busy || !dirty}>{busy ? 'Saving…' : 'Save changes'}</Button></div>
				</form>
			</Card>
			<Card title="Danger zone" className="danger-card">
				<div className="danger-row">
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

function RepositoryAccess({ repository }: { repository: Repository }) {
	const { notify } = useSession();
	const permissions = useAsync(() => Promise.all([api.permissions({ repository: repository.name }), api.permissions({ repository: '*' })]), [repository.name]);
	const users = useAsync(() => api.users(), []);
	const [username, setUsername] = useState('');
	const [level, setLevel] = useState<AccessLevel>('READ');
	const [busy, setBusy] = useState(false);

	const grant = async (event: FormEvent) => {
		event.preventDefault();
		setBusy(true);
		try {
			await api.setPermission({ repository: repository.name, username, level });
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
					<select value={username} onChange={event => setUsername(event.target.value)} required aria-label="User">
						<option value="">Select user…</option>
						{(users.data ?? []).filter(user => !user.admin).map(user => <option key={user.username} value={user.username}>{user.username}</option>)}
					</select>
					<select value={level} onChange={event => setLevel(event.target.value as AccessLevel)} aria-label="Level">
						{ACCESS_LEVELS.map(entry => <option key={entry} value={entry}>{entry}</option>)}
					</select>
					<Button variant="primary" type="submit" disabled={busy || !username}>Grant</Button>
				</form>
				<p className="muted small">READ downloads, WRITE publishes, tags and yanks, DELETE removes artifacts. Each level includes the lower ones.</p>
			</Card>
			<Card title="Who has access">
				{permissions.error && <ErrorBox error={permissions.error} onRetry={permissions.reload} />}
				{!permissions.data ? <Spinner /> : (
					<table className="table table-compact">
						<thead><tr><th>User</th><th>Level</th><th>Source</th><th /></tr></thead>
						<tbody>
							{repository.publicRead && <tr><td className="muted">Anonymous</td><td><Badge tone="success">READ</Badge></td><td className="muted">Public repository</td><td /></tr>}
							{admins.map(user => <tr key={`admin-${user.username}`}><td>{user.username}</td><td><Badge tone="accent">ALL</Badge></td><td className="muted">Administrator</td><td /></tr>)}
							{global.map(permission => <tr key={`global-${permission.username}`}><td>{permission.username}</td><td><Badge>{permission.level}</Badge></td><td className="muted">All repositories (*)</td><td /></tr>)}
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
