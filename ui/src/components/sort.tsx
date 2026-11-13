import {type ReactNode, useMemo, useState} from 'react';
import {Icon} from './icons';

export type SortDirection = 'asc' | 'desc';
export type SortState<K extends string> = { key: K; direction: SortDirection } | null;
type SortValue = string | number | null | undefined;

const collator = new Intl.Collator(undefined, {numeric: true, sensitivity: 'base'});

function compareValues(a: SortValue, b: SortValue): number {
	// Missing values always go last, whatever the direction
	if (a == null || a === '') {
		return b == null || b === '' ? 0 : 1;
	}
	if (b == null || b === '') {
		return -1;
	}
	return typeof a === 'number' && typeof b === 'number' ? a - b : collator.compare(String(a), String(b));
}

/**
 * Sorts rows by a clicked column, each click cycles default (the order the rows come in) -> ascending -> descending -> default.
 * A comparator can be given instead of a value for columns that need a custom order, e.g. versions.
 */
export function useSort<T, K extends string>(rows: T[], columns: Record<K, (row: T) => SortValue>, comparators: Partial<Record<K, (a: T, b: T) => number>> = {}) {
	const [sort, setSort] = useState<SortState<K>>(null);
	const sorted = useMemo(() => {
		if (!sort) {
			return rows;
		}
		const comparator = comparators[sort.key];
		const value = columns[sort.key];
		const factor = sort.direction === 'asc' ? 1 : -1;
		return [...rows].sort((a, b) => {
			if (comparator) {
				return comparator(a, b) * factor;
			}
			const x = value(a);
			const y = value(b);
			// Keep missing values last in both directions
			if (x == null || x === '' || y == null || y === '') {
				return compareValues(x, y);
			}
			return compareValues(x, y) * factor;
		});
		// The column accessors are recreated each render, the rows and the sort decide the order
	}, [rows, sort]);
	const toggle = (key: K) => setSort(current => current?.key !== key ? {key, direction: 'asc'} : current.direction === 'asc' ? {key, direction: 'desc'} : null);
	return {sorted, sort, toggle};
}

/**
 * A table header cell that sorts its column on click, shows the current direction with an arrow.
 */
export function SortHeader<K extends string>({id, sort, onSort, children, className}: { id: K; sort: SortState<K>; onSort: (key: K) => void; children: ReactNode; className?: string }) {
	const active = sort?.key === id ? sort.direction : null;
	return (
		<th className={className} aria-sort={active === 'asc' ? 'ascending' : active === 'desc' ? 'descending' : 'none'}>
			<button type="button" className={`th-sort${active ? ' th-sort-active' : ''}`} onClick={() => onSort(id)}
					title={active === 'asc' ? 'Sorted ascending, click to sort descending' : active === 'desc' ? 'Sorted descending, click to reset' : 'Click to sort ascending'}>
				<span>{children}</span>
				<Icon name={active === 'desc' ? 'arrow-down' : active === 'asc' ? 'arrow-up' : 'sort'} size={13}/>
			</button>
		</th>
	);
}
