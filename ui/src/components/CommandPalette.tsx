import {useEffect, useMemo, useRef, useState} from 'react';
import {NAV_GROUPS, visibleTo} from '../nav';
import {href, navigate} from '../router';
import {useSession} from '../session';
import {api} from '../api';
import type {Repository, RepositoryType, SearchResult} from '../types';
import {Icon, type IconName} from './icons';
import {TypeBadge} from './ui';

interface Entry {
	key: string;
	label: string;
	detail?: string;
	target: string;
	icon?: IconName;
	type?: RepositoryType;
	group: string;
}

const LIMIT = 8;

export function CommandPalette({onClose}: { onClose: () => void }) {
	const {principal} = useSession();
	const dialog = useRef<HTMLDialogElement>(null);
	const list = useRef<HTMLUListElement>(null);
	const [query, setQuery] = useState('');
	const [active, setActive] = useState(0);
	const [repositoryList, setRepositoryList] = useState<Repository[] | null>(null);
	const [packageHits, setPackageHits] = useState<SearchResult[]>([]);
	const [error, setError] = useState(false);

	useEffect(() => {
		dialog.current?.showModal();
		api.repositories().then(setRepositoryList, () => setError(true));
	}, []);
	
	// Packages are searched on the server, debounced so typing does not send a request per key
	useEffect(() => {
		const needle = query.trim();
		if (!needle) {
			setPackageHits([]);
			return;
		}
		let active = true;
		const timer = setTimeout(() => api.search(needle, LIMIT).then(hits => active && setPackageHits(hits), () => active && setPackageHits([])), 150);
		return () => {
			active = false;
			clearTimeout(timer);
		};
	}, [query]);

	const entries = useMemo(() => {
		const needle = query.trim().toLowerCase();
		const matches = (...values: (string | null | undefined)[]) => !needle || values.some(value => value?.toLowerCase().includes(needle));
		const pages: Entry[] = NAV_GROUPS.flatMap(group => group.items)
			.filter(item => visibleTo(item, principal))
			.filter(item => matches(item.label))
			.map(item => ({key: `page:${item.id}`, label: item.label, target: href(...(item.id ? [item.id] : [])), icon: item.icon, group: 'Pages'}));
		const repositories: Entry[] = (repositoryList ?? [])
			.filter(repository => matches(repository.name, repository.description, repository.type))
			.slice(0, LIMIT)
			.map(repository => ({key: `repo:${repository.name}`, label: repository.name, detail: repository.description ?? undefined, target: href('repositories', repository.name), type: repository.type, group: 'Repositories'}));
		const packages: Entry[] = needle ? packageHits.map(hit => ({
			key: `pkg:${hit.repository}/${hit.name}`,
			label: hit.name,
			detail: hit.repository,
			target: href('repositories', hit.repository, 'packages', hit.name),
			type: hit.type,
			group: 'Packages',
		})) : [];
		return [...(needle ? [...packages, ...repositories] : repositories), ...pages];
	}, [query, repositoryList, packageHits, principal]);

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
				{entries.length === 0 && <li className="palette-empty muted">{error ? 'Could not load repositories' : repositoryList ? 'No results' : 'Loading…'}</li>}
				{entries.map((entry, position) => (
					<li key={entry.key}>
						{(position === 0 || entries[position - 1].group !== entry.group) && <div className="palette-group">{entry.group}</div>}
						<button type="button" role="option" aria-selected={position === active} className="palette-item" onMouseMove={() => setActive(position)} onClick={() => open(entry)}>
							{entry.type ? <TypeBadge type={entry.type}/> : entry.icon && <Icon name={entry.icon}/>}
							<span className="palette-label">{entry.label}</span>
							{entry.detail && <span className="palette-detail muted">{entry.detail}</span>}
						</button>
					</li>
				))}
			</ul>
		</dialog>
	);
}
