import {useMemo, useState} from 'react';
import {api, downloadFile} from '../api';
import {Icon} from '../components/icons';
import {Select} from '../components/Select';
import {SortHeader, useSort} from '../components/sort';
import {Badge, Button, Card, ConfirmDialog, CopyButton, Empty, ErrorBox, PageHeader, SnippetTabs, Spinner, Stat, Stats, TypeBadge} from '../components/ui';
import {compareVersions, fileName, formatBytes, formatDate, formatNumber, formatRelative, isDigest, shortDigest} from '../format';
import {useAsync} from '../hooks';
import {latestLabel, latestVersion} from '../inventory';
import {href, navigate} from '../router';
import {useSession} from '../session';
import {installSnippets} from '../setup';
import type {FileEntry, PackageVersion} from '../types';
import {Pagination, usePaging} from '../components/paging';

type Column = 'version' | 'published' | 'by' | 'files' | 'size';

/**
 * One package of a repository: how to install it, its versions and the files of the selected version.
 */
export function PackageDetail({repositoryName, packageName, params}: { repositoryName: string; packageName: string; params: URLSearchParams }) {
	const {notify} = useSession();
	const repository = useAsync(() => api.repository(repositoryName), [repositoryName]);
	const packages = useAsync(() => api.packages(repositoryName), [repositoryName]);
	const files = useAsync(() => api.files(repositoryName), [repositoryName]);
	const [deleting, setDeleting] = useState<{ version?: string } | null>(null);

	const repo = repository.data;
	const pkg = packages.data?.find(entry => entry.name === packageName);
	const oci = repo?.type === 'oci';
	const packageFiles = useMemo(() => (files.data ?? []).filter(file => file.packageName === packageName), [files.data, packageName]);
	const filesByVersion = useMemo(() => packageFiles.reduce<Record<string, FileEntry[]>>((result, file) => {
		(result[file.version ?? ''] ??= []).push(file);
		return result;
	}, {}), [packageFiles]);
	const tagsByVersion = useMemo(() => Object.entries(pkg?.tags ?? {}).reduce<Record<string, string[]>>((result, [tag, version]) => {
		(result[version] ??= []).push(tag);
		return result;
	}, {}), [pkg]);
	// Default order: newest version first, OCI versions are digests so they are ordered by publication
	const versions = useMemo(() => [...(pkg?.versions ?? [])].sort((a, b) => oci ? b.createdAt.localeCompare(a.createdAt) : compareVersions(b.version, a.version)), [pkg, oci]);
	const sizeOf = (version: string) => (filesByVersion[version] ?? []).reduce((sum, file) => sum + file.size, 0);
	const {sorted, sort, toggle} = useSort<PackageVersion, Column>(versions, {
		version: version => version.version,
		published: version => version.createdAt,
		by: version => version.createdBy,
		files: version => filesByVersion[version.version]?.length ?? 0,
		size: version => sizeOf(version.version),
	}, {version: (a, b) => oci ? a.createdAt.localeCompare(b.createdAt) : compareVersions(a.version, b.version)});
	const {visible, paging} = usePaging(sorted);

	if ((repository.loading && !repo) || (packages.loading && !packages.data)) {
		return <Spinner/>;
	}
	const back = {label: repositoryName, href: href('repositories', repositoryName)};
	const crumbs = [{label: 'Repositories', href: href('repositories')}, back, {label: packageName}];
	if (repository.error || packages.error || !repo) {
		return (
			<>
				<PageHeader crumbs={crumbs} title={packageName}/>
				<ErrorBox error={repository.error ?? packages.error ?? 'Repository not found'} onRetry={() => {
					repository.reload();
					packages.reload();
				}}/>
			</>
		);
	}
	if (!pkg) {
		return (
			<>
				<PageHeader crumbs={crumbs} title={packageName}/>
				<Empty title="Package not found">There is no package {packageName} in {repositoryName}. <a className="link" href={back.href}>Back to the repository</a></Empty>
			</>
		);
	}

	const requested = params.get('version');
	// OCI versions are digests, the manifest the newest tag points at is the latest one
	const latestTag = oci ? latestLabel(repo, pkg) : undefined;
	const latest = (latestTag && pkg.tags[latestTag]) || latestVersion(pkg);
	const selected = versions.find(version => version.version === requested) ?? versions.find(version => version.version === latest) ?? versions[0];
	const selectVersion = (version: string) => navigate(`${href('repositories', repositoryName, 'packages', packageName)}?version=${encodeURIComponent(version)}`);
	const versionLabel = (version: string) => isDigest(version) ? shortDigest(version) : version;
	const selectedFiles = selected ? filesByVersion[selected.version] ?? [] : [];
	const selectedTags = selected ? tagsByVersion[selected.version] ?? [] : [];
	const snippets = selected ? installSnippets(repo, pkg.name, selected.version, selectedTags[0]) : [];
	const totalSize = packageFiles.reduce((sum, file) => sum + file.size, 0);
	const lastPublished = versions.reduce<string | undefined>((result, version) => (!result || version.createdAt > result ? version.createdAt : result), undefined);
	const yankedLabel = repo.type === 'nuget' ? 'unlisted' : 'yanked';
	const download = (file: FileEntry) => downloadFile(repo, file).catch(error => notify(error.message, 'error'));

	return (
		<>
			<PageHeader
				crumbs={crumbs}
				title={<span className="cell-inline package-title">{pkg.name} <TypeBadge type={repo.type}/></span>}
				subtitle={<>Package in <a className="link" href={back.href}>{repositoryName}</a>{repo.description ? ` · ${repo.description}` : ''}</>}
				actions={<Button variant="ghost" className="danger-text" onClick={() => setDeleting({})}><Icon name="trash" size={15}/>Delete package</Button>}
			/>

			<Stats>
				<Stat label={oci ? 'Latest tag' : 'Latest version'} value={latestLabel(repo, pkg) ?? '—'}/>
				<Stat label="Versions" value={formatNumber(pkg.versions.length)}/>
				<Stat label="Files" value={files.data ? formatNumber(packageFiles.length) : '…'}/>
				<Stat label="Storage" value={files.data ? formatBytes(totalSize) : '…'}/>
				<Stat label="Last published" value={formatRelative(lastPublished)} hint={formatDate(lastPublished)}/>
			</Stats>

			<div className="detail-grid">
				<div className="stack">
					{selected && snippets.length > 0 && (
						<Card title="Install" actions={versions.length > 1 && (
							<Select label="Version" value={selected.version} onChange={selectVersion} className="select-compact"
									options={versions.map(version => ({value: version.version, label: versionLabel(version.version), hint: (tagsByVersion[version.version] ?? []).join(', ') || undefined}))}/>
						)}>
							<p className="muted small card-intro">
								Requires the repository to be configured, see <a className="link" href={`${back.href}?tab=setup`}>client setup</a>.
							</p>
							<SnippetTabs key={`${selected.version}`} snippets={snippets}/>
						</Card>
					)}

					<Card title={<>Versions <span className="tab-count">{versions.length}</span></>} className="card-flush">
						<div className="table-scroll">
							<table className="table">
								<thead>
								<tr>
									<SortHeader id="version" sort={sort} onSort={toggle}>{oci ? 'Manifest' : 'Version'}</SortHeader>
									<SortHeader id="published" sort={sort} onSort={toggle}>Published</SortHeader>
									<SortHeader id="by" sort={sort} onSort={toggle}>By</SortHeader>
									<SortHeader id="files" sort={sort} onSort={toggle} className="num">Files</SortHeader>
									<SortHeader id="size" sort={sort} onSort={toggle} className="num">Size</SortHeader>
									<th/>
								</tr>
								</thead>
								<tbody>
								{visible.map(version => (
									<tr key={version.version} className={`clickable${version.version === selected?.version ? ' selected' : ''}`} onClick={() => selectVersion(version.version)}>
										<td>
											<span className="cell-inline">
												<span className={`name${version.yanked ? ' strike' : ''}`} title={version.version}>{versionLabel(version.version)}</span>
												{version.version === latest && !oci && <Badge tone="accent">latest</Badge>}
												{(tagsByVersion[version.version] ?? []).filter(tag => oci || tag !== 'latest').map(tag => <Badge key={tag} tone="accent">{tag}</Badge>)}
												{version.yanked && <Badge tone="warning">{yankedLabel}</Badge>}
											</span>
										</td>
										<td className="muted nowrap" title={formatDate(version.createdAt)}>{formatRelative(version.createdAt)}</td>
										<td className="muted">{version.createdBy ?? '—'}</td>
										<td className="num">{filesByVersion[version.version]?.length ?? 0}</td>
										<td className="num">{formatBytes(sizeOf(version.version))}</td>
										<td className="actions">
											<Button small variant="ghost" className="btn-icon danger-text" title={`Delete ${oci ? 'manifest' : 'version'}`} aria-label={`Delete ${version.version}`}
													onClick={event => {
														event.stopPropagation();
														setDeleting({version: version.version});
													}}>
												<Icon name="trash" size={16}/>
											</Button>
										</td>
									</tr>
								))}
								</tbody>
							</table>
							<Pagination paging={paging} noun="versions"/>
						</div>
					</Card>
				</div>

				{selected && (
					<Card title={<span className="cell-inline">{oci ? 'Manifest' : 'Version'} <span className="name">{versionLabel(selected.version)}</span></span>} className="detail-side">
						<dl className="details">
							<dt>Published</dt>
							<dd>{formatDate(selected.createdAt)}</dd>
							<dt>By</dt>
							<dd>{selected.createdBy ?? '—'}</dd>
							{selectedTags.length > 0 && <>
								<dt>Tags</dt>
								<dd className="cell-inline">{selectedTags.map(tag => <Badge key={tag} tone="accent">{tag}</Badge>)}</dd>
							</>}
							{oci && <>
								<dt>Digest</dt>
								<dd className="hash"><code>{selected.version}</code><CopyButton value={selected.version} iconOnly label="Copy digest"/></dd>
							</>}
							{selected.yanked && <>
								<dt>Status</dt>
								<dd><Badge tone="warning">{yankedLabel}</Badge></dd>
							</>}
						</dl>
						<h3 className="subsection-title">Files</h3>
						{selectedFiles.length === 0 ? <p className="muted small">No files.</p> : (
							<ul className="file-list">
								{selectedFiles.sort((a, b) => a.path.localeCompare(b.path)).map(file => (
									<li key={file.path}>
										<span className="entry-icon"><Icon name="file" size={15}/></span>
										<span className="file-list-main">
											<span className="name ellipsis" title={file.path}>{fileName(file.path)}</span>
											<span className="muted small">{formatBytes(file.size)} · {file.contentType}</span>
										</span>
										<CopyButton value={file.sha256} iconOnly label="Copy SHA-256"/>
										<Button small variant="ghost" className="btn-icon" title="Download" aria-label={`Download ${file.path}`} onClick={() => download(file)}>
											<Icon name="download" size={16}/>
										</Button>
									</li>
								))}
							</ul>
						)}
					</Card>
				)}
			</div>

			{deleting && (
				<ConfirmDialog
					title={deleting.version ? `Delete ${oci ? 'manifest' : 'version'}` : 'Delete package'}
					message={deleting.version
						? <>Delete <strong>{pkg.name} {versionLabel(deleting.version)}</strong> and its files from <strong>{repositoryName}</strong>? Clients depending on it will break.</>
						: <>Delete <strong>{pkg.name}</strong> with all {pkg.versions.length} versions from <strong>{repositoryName}</strong>?</>}
					onConfirm={async () => {
						await api.deletePackage(repositoryName, pkg.name, deleting.version);
						notify(deleting.version ? `Deleted ${pkg.name} ${versionLabel(deleting.version)}` : `Deleted ${pkg.name}`);
						if (!deleting.version || pkg.versions.length === 1) {
							navigate(back.href);
						} else {
							packages.reload();
							files.reload();
						}
					}}
					onClose={() => setDeleting(null)}
				/>
			)}
		</>
	);
}
