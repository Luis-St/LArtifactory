import {useMemo, useState} from 'react';
import {api, ApiError} from '../api';
import {Icon} from '../components/icons';
import {Select} from '../components/Select';
import {Badge, Button, Card, ConfirmDialog, CopyButton, Empty, ErrorBox, PageHeader, SnippetTabs, Spinner, Stat, Stats, TypeBadge} from '../components/ui';
import {compareVersions, formatBytes, formatDate, formatNumber, formatRelative, isDigest, shortDigest} from '../format';
import {useAsync} from '../hooks';
import {latestLabel, latestVersion} from '../inventory';
import {href, navigate, versionHref} from '../router';
import {useSession} from '../session';
import {installSnippets} from '../setup';
import {type FileEntry, includesLevel} from '../types';
import {FileBrowser} from './RepositoryDetail';

/**
 * The deepest folder all files share, the file browser of a version starts there instead of at the repository root.
 */
function commonFolder(files: FileEntry[]): string {
	if (files.length === 0) {
		return '';
	}
	const split = files.map(file => file.path.split('/').slice(0, -1));
	const shared: string[] = [];
	for (let index = 0; index < split[0].length; index++) {
		const segment = split[0][index];
		if (!split.every(parts => parts[index] === segment)) {
			break;
		}
		shared.push(segment);
	}
	return shared.length > 0 ? `${shared.join('/')}/` : '';
}

/**
 * One version of a package: how to install exactly this version, its details and its files.
 */
export function VersionDetail({repositoryName, packageName, version}: { repositoryName: string; packageName: string; version: string }) {
	const {notify} = useSession();
	const repository = useAsync(() => api.repository(repositoryName), [repositoryName]);
	// Only this package is loaded, with its versions, tags and the files of all versions
	const packages = useAsync(() => api.pkg(repositoryName, packageName).catch(error => {
		if (error instanceof ApiError && error.status === 404) {
			return null;
		}
		throw error;
	}), [repositoryName, packageName]);
	const files = {data: packages.data?.files, error: packages.error, reload: packages.reload};
	const [deleting, setDeleting] = useState(false);

	const repo = repository.data;
	const pkg = packages.data ?? undefined;
	const oci = repo?.type === 'oci';
	const entry = pkg?.versions.find(candidate => candidate.version === version);
	const versionFiles = useMemo(() => (files.data ?? []).filter(file => file.version === version), [files.data, version]);
	const canDelete = includesLevel(repo?.access, 'DELETE') && repo?.kind === 'LOCAL';
	const versions = useMemo(() => [...(pkg?.versions ?? [])].sort((a, b) => oci ? b.createdAt.localeCompare(a.createdAt) : compareVersions(b.version, a.version)), [pkg, oci]);

	if ((repository.loading && !repo) || (packages.loading && packages.data === undefined)) {
		return <Spinner/>;
	}
	const versionLabel = (value: string) => isDigest(value) ? shortDigest(value) : value;
	const packageHref = href('repositories', repositoryName, 'packages', packageName);
	const crumbs = [
		{label: 'Repositories', href: href('repositories')},
		{label: repositoryName, href: href('repositories', repositoryName)},
		{label: packageName, href: packageHref},
		{label: versionLabel(version)},
	];
	if (repository.error || packages.error || !repo) {
		return (
			<>
				<PageHeader crumbs={crumbs} title={versionLabel(version)}/>
				<ErrorBox error={repository.error ?? packages.error ?? 'Repository not found'} onRetry={() => {
					repository.reload();
					packages.reload();
				}}/>
			</>
		);
	}
	if (!pkg || !entry) {
		return (
			<>
				<PageHeader crumbs={crumbs} title={versionLabel(version)}/>
				<Empty title="Version not found">{packageName} has no version {versionLabel(version)} in {repositoryName}. <a className="link" href={packageHref}>Back to the package</a></Empty>
			</>
		);
	}

	const tags = Object.entries(pkg.tags).filter(([, target]) => target === version).map(([tag]) => tag);
	const latestTag = oci ? latestLabel(repo.type, pkg) : undefined;
	const latest = (latestTag && pkg.tags[latestTag]) || latestVersion(pkg);
	const isLatest = latest === version;
	const size = versionFiles.reduce((sum, file) => sum + file.size, 0);
	const yankedLabel = repo.type === 'nuget' ? 'Unlisted' : 'Yanked';
	const snippets = installSnippets(repo, pkg.name, version, tags[0]);
	const index = versions.findIndex(candidate => candidate.version === version);
	const newer = index > 0 ? versions[index - 1] : undefined;
	const older = index < versions.length - 1 ? versions[index + 1] : undefined;
	const root = commonFolder(versionFiles);

	return (
		<>
			<PageHeader
				crumbs={crumbs}
				title={<span className="cell-inline package-title">{pkg.name} <Badge tone="accent">{versionLabel(version)}</Badge> <TypeBadge type={repo.type}/></span>}
				subtitle={<>{oci ? 'Manifest' : 'Version'} of <a className="link" href={packageHref}>{pkg.name}</a> in <a className="link" href={href('repositories', repositoryName)}>{repositoryName}</a></>}
				actions={
					<>
						{versions.length > 1 && (
							<Select label="Switch version" value={version} onChange={value => navigate(versionHref(repositoryName, packageName, value))}
									options={versions.map(candidate => ({
										value: candidate.version,
										label: versionLabel(candidate.version),
										hint: candidate.version === latest ? 'latest' : Object.entries(pkg.tags).filter(([, target]) => target === candidate.version).map(([tag]) => tag).join(', ') || undefined,
									}))}/>
						)}
						{canDelete && <Button variant="ghost" className="danger-text" onClick={() => setDeleting(true)}><Icon name="trash" size={15}/>Delete {oci ? 'manifest' : 'version'}</Button>}
					</>
				}
			/>

			<Stats>
				<Stat label="Published" value={formatRelative(entry.createdAt)} hint={formatDate(entry.createdAt)}/>
				<Stat label="Published by" value={entry.createdBy ?? '—'}/>
				<Stat label="Files" value={files.data ? formatNumber(versionFiles.length) : '…'}/>
				<Stat label="Size" value={files.data ? formatBytes(size) : '…'}/>
				<Stat label="Status" value={entry.yanked ? yankedLabel : isLatest ? 'Latest' : 'Available'}
					  hint={entry.yanked ? 'Not resolved for new installs' : isLatest ? (pkg.versions.length === 1 ? 'Only version' : `Newest of ${pkg.versions.length} versions`) : `${index} newer ${index === 1 ? 'version' : 'versions'}`}/>
			</Stats>

			<div className="detail-grid">
				<div className="stack">
					{snippets.length > 0 && (
						<Card title="Install">
							<p className="muted small card-intro">
								Requires the repository to be configured, see <a className="link" href={`${href('repositories', repositoryName)}?tab=setup`}>client setup</a>.
							</p>
							<SnippetTabs snippets={snippets}/>
						</Card>
					)}
					<Card title={<>Files <span className="tab-count">{versionFiles.length}</span></>} className="card-flush card-browser">
						{files.error ? <ErrorBox error={files.error} onRetry={files.reload}/>
							: !files.data ? <Spinner/>
								: <FileBrowser key={version} repository={repo} files={versionFiles} root={root} rootLabel={versionLabel(version)}/>}
					</Card>
				</div>

				<Card title="Details" className="detail-side">
					<dl className="details">
						<dt>{oci ? 'Digest' : 'Version'}</dt>
						<dd className={oci ? 'hash' : undefined}>{oci ? <><code>{version}</code><CopyButton value={version} iconOnly label="Copy digest"/></> : <span className="name">{version}</span>}</dd>
						<dt>Package</dt>
						<dd><a className="link" href={packageHref}>{pkg.name}</a></dd>
						<dt>Repository</dt>
						<dd><a className="link" href={href('repositories', repositoryName)}>{repositoryName}</a></dd>
						<dt>Published</dt>
						<dd>{formatDate(entry.createdAt)}</dd>
						<dt>By</dt>
						<dd>{entry.createdBy ?? '—'}</dd>
						{tags.length > 0 && <>
							<dt>Tags</dt>
							<dd className="cell-inline">{tags.map(tag => <Badge key={tag} tone="accent">{tag}</Badge>)}</dd>
						</>}
						<dt>Status</dt>
						<dd>{entry.yanked ? <Badge tone="warning">{yankedLabel.toLowerCase()}</Badge> : isLatest ? <Badge tone="accent">latest</Badge> : <Badge>available</Badge>}</dd>
						{root && <>
							<dt>Folder</dt>
							<dd className="mono break small">{root}</dd>
						</>}
					</dl>
					{(newer || older) && (
						<div className="version-nav">
							{older ? <a className="btn btn-secondary btn-small" href={versionHref(repositoryName, packageName, older.version)}><Icon name="chevron-left" size={14}/>{versionLabel(older.version)}</a> : <span/>}
							{newer && <a className="btn btn-secondary btn-small" href={versionHref(repositoryName, packageName, newer.version)}>{versionLabel(newer.version)}<Icon name="chevron-right" size={14}/></a>}
						</div>
					)}
				</Card>
			</div>

			{deleting && (
				<ConfirmDialog
					title={`Delete ${oci ? 'manifest' : 'version'}`}
					message={<>Delete <strong>{pkg.name} {versionLabel(version)}</strong> and its files from <strong>{repositoryName}</strong>? Clients depending on it will break.</>}
					onConfirm={async () => {
						await api.deletePackage(repositoryName, pkg.name, version);
						notify(`Deleted ${pkg.name} ${versionLabel(version)}`);
						navigate(pkg.versions.length === 1 ? href('repositories', repositoryName) : packageHref);
					}}
					onClose={() => setDeleting(false)}
				/>
			)}
		</>
	);
}
