import {useEffect, useMemo, useRef, useState} from 'react';
import {api} from '../api';
import {NAV_GROUPS} from '../nav';
import {href, navigate} from '../router';
import {useSession} from '../session';
import type {Package, Repository} from '../types';
import {Icon, type IconName} from './icons';
import {TypeBadge} from './ui';

interface Entry {
	key: string;
	label: string;
	detail?: string;
	target: string;
	icon?: IconName;
	repository?: Repository;
	group: string;
}

interface Index {
	repositories: Repository[];
	packages: { repository: Repository; pkg: Package }[];
}

let cached: { at: number; index: Promise<Index> } | null = null;

/**
 * Loads repositories and their packages for the search, kept for a minute so reopening the palette is instant.
 */
function loadIndex(): Promise<Index> {
	if (!cached || Date.now() - cached.at > 60_000) {
		const index = api.repositories().then(async repositories => {
			const packages = await Promise.all(repositories.map(repository => api.packages(repository.name).then(
				list => list.map(pkg => ({repository, pkg})),
				() => [],
			)));
			return {repositories, packages: packages.flat()};
		});
		cached = {at: Date.now(), index};
		index.catch(() => (cached = null));
	}
	return cached.index;
}

const LIMIT = 8;

export function CommandPalette({onClose}: { onClose: () => void }) {
	const {principal} = useSession();
	const dialog = useRef<HTMLDialogElement>(null);
	const list = useRef<HTMLUListElement>(null);
	const [query, setQuery] = useState('');
	const [active, setActive] = useState(0);
	const [index, setIndex] = useState<Index | null>(null);
	const [error, setError] = useState(false);

	useEffect(() => {
		dialog.current?.showModal();
		loadIndex().then(setIndex, () => setError(true));
	}, []);

	const entries = useMemo(() => {
		const needle = query.trim().toLowerCase();
		const matches = (...values: (string | null | undefined)[]) => !needle || values.some(value => value?.toLowerCase().includes(needle));
		const pages: Entry[] = NAV_GROUPS.flatMap(group => group.items)
			.filter(item => !item.admin || principal?.admin)
			.filter(item => matches(item.label))
			.map(item => ({key: `page:${item.id}`, label: item.label, target: href(...(item.id ? [item.id] : [])), icon: item.icon, group: 'Pages'}));
		const repositories: Entry[] = (index?.repositories ?? [])
			.filter(repository => matches(repository.name, repository.description, repository.type))
			.slice(0, LIMIT)
			.map(repository => ({key: `repo:${repository.name}`, label: repository.name, detail: repository.description ?? undefined, target: href('repositories', repository.name), repository, group: 'Repositories'}));
		const packages: Entry[] = needle ? (index?.packages ?? [])
			.filter(entry => matches(entry.pkg.name))
			.slice(0, LIMIT)
			.map(({repository, pkg}) => ({
				key: `pkg:${repository.name}/${pkg.name}`,
				label: pkg.name,
				detail: repository.name,
				target: href('repositories', repository.name, 'packages', pkg.name),
				repository,
				group: 'Packages',
			})) : [];
		return [...(needle ? [...packages, ...repositories] : repositories), ...pages];
	}, [query, index, principal?.admin]);

	useEffect(() => setActive(0), [query]);
	useEffect(() => {
		list.current?.querySelector('[aria-selected="true"]')?.scrollIntoView({block: 'nearest'});
	}, [active]);

	const open = (entry: Entry | undefined) => {
		if (entry) {
			onClose();
			navigate(entry.target);
		}
	};

	return (
		<dialog ref={dialog} className="modal palette" onCancel={event => {
			event.preventDefault();
			onClose();
		}} onClick={event => event.target === dialog.current && onClose()}>
			<div className="palette-input">
				<Icon name="search"/>
				<input
					autoFocus
					value={query}
					placeholder="Search repositories, packages and pages…"
					aria-label="Search"
					onChange={event => setQuery(event.target.value)}
					onKeyDown={event => {
						if (event.key === 'ArrowDown') {
							event.preventDefault();
							setActive(value => Math.min(entries.length - 1, value + 1));
						} else if (event.key === 'ArrowUp') {
							event.preventDefault();
							setActive(value => Math.max(0, value - 1));
						} else if (event.key === 'Enter') {
							event.preventDefault();
							open(entries[active]);
						}
					}}
				/>
				<kbd>Esc</kbd>
			</div>
			<ul ref={list} className="palette-list" role="listbox">
				{entries.length === 0 && <li className="palette-empty muted">{error ? 'Could not load repositories' : index ? 'No results' : 'Loading…'}</li>}
				{entries.map((entry, position) => (
					<li key={entry.key}>
						{(position === 0 || entries[position - 1].group !== entry.group) && <div className="palette-group">{entry.group}</div>}
						<button type="button" role="option" aria-selected={position === active} className="palette-item" onMouseMove={() => setActive(position)} onClick={() => open(entry)}>
							{entry.repository ? <TypeBadge type={entry.repository.type}/> : entry.icon && <Icon name={entry.icon}/>}
							<span className="palette-label">{entry.label}</span>
							{entry.detail && <span className="palette-detail muted">{entry.detail}</span>}
						</button>
					</li>
				))}
			</ul>
		</dialog>
	);
}
