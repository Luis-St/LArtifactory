export function formatBytes(bytes: number): string {
	if (bytes < 1024) {
		return `${bytes} B`;
	}
	const units = ['KB', 'MB', 'GB', 'TB'];
	let value = bytes / 1024;
	let unit = 0;
	while (value >= 1024 && unit < units.length - 1) {
		value /= 1024;
		unit++;
	}
	return `${value.toFixed(value < 10 ? 1 : 0)} ${units[unit]}`;
}

export function formatNumber(value: number): string {
	return value.toLocaleString();
}

export function formatDate(value: string | null | undefined): string {
	if (!value) {
		return '—';
	}
	return new Date(value).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

export function formatRelative(value: string | null | undefined): string {
	if (!value) {
		return '—';
	}
	const seconds = (Date.now() - new Date(value).getTime()) / 1000;
	const steps: [number, Intl.RelativeTimeFormatUnit][] = [[60, 'second'], [60, 'minute'], [24, 'hour'], [30, 'day'], [12, 'month'], [Infinity, 'year']];
	let amount = seconds;
	for (const [size, unit] of steps) {
		if (Math.abs(amount) < size) {
			return new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' }).format(-Math.round(amount), unit);
		}
		amount /= size;
	}
	return formatDate(value);
}

/**
 * Compares versions segment wise, numeric segments are compared as numbers.
 */
export function compareVersions(first: string, second: string): number {
	const a = first.split(/[.\-+]/);
	const b = second.split(/[.\-+]/);
	for (let i = 0; i < Math.max(a.length, b.length); i++) {
		const x = a[i] ?? '';
		const y = b[i] ?? '';
		const nx = Number(x);
		const ny = Number(y);
		const result = !isNaN(nx) && !isNaN(ny) && x !== '' && y !== '' ? nx - ny : x.localeCompare(y);
		if (result !== 0) {
			return result;
		}
	}
	return 0;
}
