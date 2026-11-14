import {useEffect, useState} from 'react';
import {formatNumber} from '../format';
import {Icon} from './icons';
import {Select} from './Select';

const STORAGE_KEY = 'lartifactory.pageSize';
const PAGE_SIZES = ['25', '50', '100', '250'] as const;
const listeners = new Set<(size: number) => void>();

function readPageSize(): number {
	try {
		const value = Number(localStorage.getItem(STORAGE_KEY));
		return PAGE_SIZES.includes(String(value) as (typeof PAGE_SIZES)[number]) ? value : 50;
	} catch {
		return 50;
	}
}

let pageSize = readPageSize();

function setPageSize(size: number): void {
	pageSize = size;
	try {
		localStorage.setItem(STORAGE_KEY, String(size));
	} catch {
		// Storage unavailable, the size only applies to this page
	}
	listeners.forEach(listener => listener(size));
}

export interface Paging {
	page: number;
	pageCount: number;
	size: number;
	total: number;
	setPage: (page: number) => void;
}

/**
 * Splits rows into pages, the rows per page are one setting shared by every table and remembered in the browser.
 * The page goes back to the first one whenever the number of rows changes (filters) or the page size does.
 */
export function usePaging<T>(rows: T[]): { visible: T[]; paging: Paging } {
	const [size, setSize] = useState(pageSize);
	const [page, setPage] = useState(0);
	useEffect(() => {
		listeners.add(setSize);
		return () => {
			listeners.delete(setSize);
		};
	}, []);
	useEffect(() => setPage(0), [rows.length, size]);
	const pageCount = Math.max(1, Math.ceil(rows.length / size));
	const current = Math.min(page, pageCount - 1);
	return {
		visible: rows.slice(current * size, (current + 1) * size),
		paging: {page: current, pageCount, size, total: rows.length, setPage},
	};
}

/**
 * The page numbers shown around the current page, gaps are marked with null.
 */
function pageNumbers(page: number, count: number): (number | null)[] {
	const pages = new Set([0, count - 1, page - 1, page, page + 1].filter(value => value >= 0 && value < count));
	const sorted = [...pages].sort((a, b) => a - b);
	return sorted.flatMap((value, index) => (index > 0 && value - sorted[index - 1] > 1 ? [null, value] : [value]));
}

/**
 * Footer below a table with the shown range, page buttons and the rows per page, hidden while everything fits on the smallest page.
 */
export function Pagination({paging, noun = 'rows'}: { paging: Paging; noun?: string }) {
	const {page, pageCount, size, total, setPage} = paging;
	if (total <= Number(PAGE_SIZES[0])) {
		return null;
	}
	return (
		<div className="table-footer pagination">
			<span>{formatNumber(page * size + 1)}–{formatNumber(Math.min(total, (page + 1) * size))} of {formatNumber(total)} {noun}</span>
			<div className="pagination-pages">
				<button type="button" className="page-btn" onClick={() => setPage(page - 1)} disabled={page === 0} aria-label="Previous page"><Icon name="chevron-left" size={15}/></button>
				{pageNumbers(page, pageCount).map((value, index) => value === null
					? <span key={`gap-${index}`} className="faint">…</span>
					: <button key={value} type="button" className={`page-btn${value === page ? ' page-btn-active' : ''}`} onClick={() => setPage(value)} aria-current={value === page ? 'page' : undefined}>{value + 1}</button>)}
				<button type="button" className="page-btn" onClick={() => setPage(page + 1)} disabled={page >= pageCount - 1} aria-label="Next page"><Icon name="chevron-right" size={15}/></button>
			</div>
			<span className="pagination-size">
				<span>Rows per page</span>
				<Select label="Rows per page" className="select-compact" value={String(size)} onChange={value => setPageSize(Number(value))} options={PAGE_SIZES.map(value => ({value, label: value}))}/>
			</span>
		</div>
	);
}

/**
 * Paging of a list the server pages, the page goes back to the first one whenever one of the reset keys (filters, sorting) or the page size changes.
 */
export function useServerPaging(resetKeys: unknown[]): { offset: number; limit: number; paging: (total: number) => Paging } {
	const [size, setSize] = useState(pageSize);
	const [page, setPage] = useState(0);
	useEffect(() => {
		listeners.add(setSize);
		return () => {
			listeners.delete(setSize);
		};
	}, []);
	useEffect(() => setPage(0), [...resetKeys, size]);
	return {
		offset: page * size,
		limit: size,
		paging: total => ({page: Math.min(page, Math.max(0, Math.ceil(total / size) - 1)), pageCount: Math.max(1, Math.ceil(total / size)), size, total, setPage}),
	};
}
