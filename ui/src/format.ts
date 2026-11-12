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
	return new Date(value).toLocaleString(undefined, {dateStyle: 'medium', timeStyle: 'short'});
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
			return new Intl.RelativeTimeFormat(undefined, {numeric: 'auto'}).format(-Math.round(amount), unit);
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

const DIGEST = /^(sha256|sha512):([a-f0-9]{16,})$/;

/**
 * Shortens content digests (OCI manifest versions) to a readable prefix, other values are returned unchanged.
 */
export function shortDigest(value: string, length = 12): string {
	const match = DIGEST.exec(value);
	return match ? `${match[1]}:${match[2].substring(0, length)}` : value;
}

export function isDigest(value: string): boolean {
	return DIGEST.test(value);
}

/**
 * Shortens the last segment of a file path, blobs and manifests of OCI repositories are named by their full digest.
 */
export function fileName(path: string): string {
	const name = path.substring(path.lastIndexOf('/') + 1);
	return /^[a-f0-9]{64}$/.test(name) ? `${name.substring(0, 12)}…` : name;
}

/**
 * Formats an ip address for display, java renders IPv6 addresses uncompressed and in brackets.
 */
export function formatIp(value: string | null | undefined): string {
	if (!value) {
		return '—';
	}
	const ip = value.replace(/^\[|]$/g, '');
	if (!ip.includes(':')) {
		return ip;
	}
	const groups = ip.split(':').map(group => group.replace(/^0+(?=.)/, ''));
	// Replace the longest run of zero groups with ::
	let best = {start: -1, length: 0};
	for (let i = 0; i < groups.length;) {
		if (groups[i] !== '0') {
			i++;
			continue;
		}
		let j = i;
		while (j < groups.length && groups[j] === '0') {
			j++;
		}
		if (j - i > best.length) {
			best = {start: i, length: j - i};
		}
		i = j;
	}
	if (best.length < 2) {
		return groups.join(':');
	}
	const head = groups.slice(0, best.start).join(':');
	const tail = groups.slice(best.start + best.length).join(':');
	return `${head}::${tail}`;
}
